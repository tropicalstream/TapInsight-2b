package com.rayneo.visionclaw.core.session

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.TapLink.app.unipanel.HudStateBridge
import com.rayneo.visionclaw.VisionClawApp
import com.rayneo.visionclaw.core.assistant.AssistantIntentParser
import com.rayneo.visionclaw.core.audio.GeminiAudioPlayer
import com.rayneo.visionclaw.core.network.GeminiRouter
import com.rayneo.visionclaw.core.network.HermesClient
import com.rayneo.visionclaw.core.notifications.NotificationCenter
import com.rayneo.visionclaw.core.storage.AppPreferences
import com.rayneo.visionclaw.core.tools.BrowserVisionTool
import com.rayneo.visionclaw.core.tools.ToolDispatcher
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Unipanel v2 Phase 4 — the real Gemini Live voice pipeline, extracted
 * out of visionclaw MainActivity so it runs inside the bound voice
 * Service instead of an Activity.
 *
 * Scope of this Phase 4a: AUDIO-ONLY voice chat.
 *   - Opens AudioRecord on the user's mic.
 *   - Connects to Gemini Live via the existing
 *     [GeminiRouter.startLiveAudioSession] entry point.
 *   - Streams PCM-16 frames to Gemini.
 *   - Receives model audio + writes it to [GeminiAudioPlayer].
 *   - Writes user transcripts through
 *     [com.rayneo.visionclaw.ui.MainViewModel.appendUserUtterance] (via
 *     `onInputTranscription` accumulation) and assistant transcripts
 *     through `appendLiveAssistantStreamChunk` /
 *     `commitLiveAssistantStreamIfNeeded` so the existing ChatCardBridge
 *     publisher mirrors them into the unipanel overlay mini-cards.
 *   - Publishes voice phase / oscilloscope level / connection status /
 *     transient HUD notifications through [HudStateBridge] so tapbrowser
 *     can render them in the overlay.
 *
 * **Deferred** (NOT in Phase 4a):
 *   - Camera frames to Gemini Live (multimodal video).
 *   - Tool calls — browser_vision, calendar, etc. need the full
 *     ToolDispatcher / ToolAssistEngine wiring from MainActivity. For
 *     now we send a no-op tool response so Gemini doesn't hang.
 *   - Audio effects (AEC / NS / AGC). The unipanel build is on RayNeo
 *     glasses where the OS audio source already does most of this.
 *   - Barge-in detection / idle watchdog.
 *
 * Lifecycle: instances are created by [GeminiSessionForegroundService]
 * in onCreate and torn down in onDestroy. [activate] / [shutdown] are
 * idempotent and safe to call from the Service's binder thread.
 *
 * Threading: Service binder calls land on the binder thread; we hop
 * to [scope] (Dispatchers.IO) for the WebSocket connect because the
 * existing GeminiRouter does synchronous setup work. The AudioRecord
 * read loop runs on a dedicated [Thread] tagged THREAD_PRIORITY_AUDIO.
 */
class GeminiVoicePipeline(context: Context) {

    /** Application context — holding a reference is safe because we
     *  never finish() the app. We avoid holding the Service context
     *  to dodge configuration-change pitfalls. */
    private val appContext: Context = context.applicationContext

    /** Shared MainViewModel, populated by VisionClawApp lazy. Reads
     *  geminiRouter from here so the pipeline never constructs its
     *  own client — auth, endpoint, and config stay in one place. */
    private val viewModel by lazy {
        (appContext as VisionClawApp).viewModel
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var connectJob: Job? = null
    /** The agent-readout coroutine (Hermes/TapClaw reply read aloud). Tracked
     *  so shutdown() can cancel it — otherwise it keeps playing audio and
     *  re-setting the THINKING phase after the session is closed. */
    private var readoutJob: Job? = null
    private var conversationalFallbackJob: Job? = null

    @Volatile private var liveSession: GeminiRouter.LiveSessionHandle? = null
    @Volatile private var liveSessionReady: Boolean = false
    @Volatile private var captureActive: Boolean = false
    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile private var audioThread: Thread? = null
    @Volatile private var latestInputTranscript: String = ""
    @Volatile private var latestCameraFrame: String? = null
    /** elapsedRealtime of the last non-blank camera frame, so we can tell
     *  whether the live feed is CURRENTLY active (frames arrive ~1.1s apart). */
    @Volatile private var lastCameraFrameMs: Long = 0L
    @Volatile private var activeSessionEpoch: Long = 0L
    @Volatile private var youtubePlaybackPreemptInFlight: Boolean = false
    @Volatile private var lastYouTubePlaybackPreemptMs: Long = 0L
    @Volatile private var lastLiveResponseActivityMs: Long = 0L
    @Volatile private var lastForcedConversationalTranscript: String = ""

    /** Elapsed-time heartbeat ticker for an in-flight agent call
     *  ("Hermes working… 15s"). Started at dispatch, canceled at result
     *  arrival / shutdown; self-terminates when agentCallInFlight drops
     *  or after 5 minutes (wedged call → status poll takes back over). */
    @Volatile private var agentProgressJob: kotlinx.coroutines.Job? = null

    // ── Round-2 capture machinery (June-11 evening log) ────────────────
    // Gemini Live input transcription arrives as sub-word CHUNKS ('San',
    // 'Franc','is','co'), and latestInputTranscript only ever holds the
    // last chunk. This rolling buffer joins chunks into the utterance
    // (reset after a 2.5s input gap) so the bare-hail upgrade, the hail
    // follow-up router and the session-drop replay all see a whole
    // sentence. Space-joined sub-words read rough but both Gemini and
    // Hermes parse them fine.
    @Volatile private var utteranceBuffer: String = ""
    @Volatile private var lastInputChunkAtMs: Long = 0L

    /** Utterance captured when the server dropped the session mid-question
     *  (June-11: onClosed 1008 right as the El Niño question ended —
     *  question silently vanished). Replayed as client text after the
     *  auto-reconnect's onSessionReady. */
    @Volatile private var pendingResumeUtterance: String? = null
    @Volatile private var reconnectWindowStartMs: Long = 0L
    @Volatile private var reconnectCountInWindow: Int = 0

    /** Hail-and-wait support: after a bare "Hermes" hail goes out and the
     *  agent acks ("Yes?"), the user's NEXT utterance routes
     *  straight to that agent instead of relying on Gemini to re-route. */
    @Volatile private var agentFollowupTool: String? = null
    @Volatile private var agentFollowupUntilMs: Long = 0L
    @Volatile private var agentFollowupJob: kotlinx.coroutines.Job? = null

    /**
     * Regression port from the pre-Phase-4 MainActivity (commit 8c2b872,
     * "drop late Gemini output between turns"). When Gemini occasionally
     * keeps emitting outputTranscription / modelText chunks AFTER
     * onTurnComplete has fired but BEFORE the next user turn starts, those
     * late chunks were being appended as a brand-new assistant card —
     * producing the "Gemini said the same thing twice" symptom the user hit.
     *
     * One-bit gate:
     *  • set true in onTurnComplete,
     *  • cleared in onInputTranscription on the next user turn (and on
     *    every session-boundary reset below),
     *  • used by onOutputTranscription / onModelText to early-return so
     *    the late chunk is dropped with a diagnostic Log.d.
     *
     * When input arrives while the flag is still latched, startingFreshTurn
     * is forced so the per-turn cleanup (transcript reset, etc.) still
     * runs correctly.
     */
    @Volatile private var dropOutputTranscriptionUntilNextInput: Boolean = false

    /** Audio playback for Gemini's model audio responses. Lazy so we
     *  only allocate the AudioTrack when voice actually activates. */
    private val audioPlayer: GeminiAudioPlayer by lazy { GeminiAudioPlayer(appContext) }

    // ── Agent readout TTS (Hermes / TapClaw / OpenClaw) ──────────────
    // Bug fix — in the hermes branch the SELECTED readout engine (Gemini
    // TTS or Fish.audio, per the companion "Readout Voice" tab) speaks all
    // agent replies, not Gemini Live's own voice. We mirror that: when an
    // agent tool returns, we suppress Gemini Live's audio for a window and
    // synthesize+play the reply through the routed engine instead.
    @Volatile
    private var suppressGeminiOutputUntilMs: Long = 0L

    /** While true the mic loop stops streaming to Gemini so the agent
     *  readout playing out the speaker isn't echoed back into the session. */
    @Volatile
    private var agentReadoutActive: Boolean = false

    /** True from the moment an agent tool call (Hermes / TapClaw / research)
     *  DISPATCHES until its reply readout begins (speakAgentReplyViaEngine
     *  flips it off as it sets [agentReadoutActive]). Arming at dispatch —
     *  not at reply time — closes the window where Gemini Live's own voice
     *  could narrate over a long-running agent turn. Cleared on shutdown. */
    @Volatile
    private var agentCallInFlight: Boolean = false

    /** Suppress Gemini Live's output until the next REAL user input. Set
     *  alongside [agentCallInFlight] when an agent call dispatches; released
     *  in onInputTranscription — but ONLY once neither the agent turn nor the
     *  Fish/Gemini readout is live, because the mic hearing the readout
     *  itself produces input transcriptions that would otherwise trip the
     *  release and let Gemini double-speak the agent's reply. */
    @Volatile
    private var suppressGeminiOutputUntilNextInput: Boolean = false

    private val fishReadoutTtsClient by lazy(LazyThreadSafetyMode.NONE) {
        com.TapLink.app.media.FishTtsClient(
            // ROOT-CAUSE FIX (agent-readout silence): the companion app
            // defaults fish_format to "mp3", and the media-browser path
            // gets away with that because it hands the bytes to a WebView
            // <audio> element that decodes MP3. THIS pipeline instead
            // decodes WAV by hand (stripWavHeaderToPcm rejects anything
            // without a RIFF/WAVE header), so an MP3 reply parsed to null
            // and the selected Fish voice was silently dropped — the user
            // heard nothing (or the wrong Gemini fallback voice). Force
            // WAV here so the PCM decoder always gets a stream it can play.
            configProvider = {
                com.TapLink.app.media.resolveGlassesFishConfig(appContext)?.copy(format = "wav")
            }
        )
    }
    private val geminiReadoutTtsClient by lazy(LazyThreadSafetyMode.NONE) {
        com.TapLink.app.media.GlassesTtsClient(
            apiKeyProvider = { com.TapLink.app.media.resolveGlassesGeminiKey(appContext) }
        )
    }

    /** Phase 4b — browser_vision tool. Captures the WebView frame via
     *  the cross-module [com.TapLink.app.media.BrowserFrameHolder]
     *  (publisher: tapbrowser MainActivity) and asks Gemini's REST
     *  vision endpoint about it.
     *
     *  Phase 4c — the frameProvider now uses captureBase64JpegWithStats
     *  so we can log non-black sample counts and prove the screenshot
     *  isn't a blank ARGB_8888 bitmap (the known View.draw hardware-
     *  accelerated WebView failure mode codex called out). */
    private val browserVisionTool: BrowserVisionTool by lazy {
        BrowserVisionTool(
            context = appContext,
            // Prefer the live camera frame when the feed is active; only fall
            // back to the WebView screenshot when the camera is off.
            frameProvider = { bestVisionFrameBase64() }
        )
    }

    /** Phase 4i — full Hermes-era native tools in the Service path.
     *
     * The old Activity Live pipeline routed every Gemini tool call through
     * ToolDispatcher. The unipanel Service path had regressed to
     * browser_vision-only, which made routes like open_taplink,
     * send_video_list, tapradio, ask_maps, and hermes_agent look "gone"
     * even though their declarations were still in GeminiRouter. */
    private val toolDispatcher: ToolDispatcher by lazy {
        val prefs = AppPreferences(appContext)
        val hermesClient = HermesClient(
            endpointUrlProvider = { prefs.hermesEndpoint.trim().takeIf { it.isNotBlank() } },
            apiKeyProvider = { prefs.hermesApiKey.trim().takeIf { it.isNotBlank() } },
            sessionIdProvider = {
                // The unipanel Service should not share Hermes' desktop/default
                // "main" session. A busy/bloated main session can queue a tiny
                // glasses turn for 1-2 minutes while the Hermes console appears
                // fast in its own context.
                prefs.hermesSessionId.trim()
                    .takeUnless { it.isBlank() || it.equals("main", ignoreCase = true) }
                    ?: "glasses"
            },
            timeoutMsProvider = {
                val seconds = prefs.hermesTimeoutSeconds.takeIf { it > 0 } ?: 30
                seconds.coerceAtLeast(5) * 1000
            }
        )
        // TapClaw / OpenClaw client. Without this the lazy dispatcher left
        // openClawClient null, so OpenClawTool ('tapclaw_agent') was never
        // registered — yet GeminiRouter still DECLARES tapclaw_agent to
        // Gemini, so a direct TapClaw query came back "Unknown tool:
        // tapclaw_agent". Build it the same way MainActivity does (endpoint
        // + pairing-token fallback, mode-bracket providers) so a query routes
        // to the user's gateway. Reads live from prefs/pairing each turn.
        val pairing = appContext.getSharedPreferences(
            "visionclaw_prefs", Context.MODE_PRIVATE
        )
        val openClawClient = com.rayneo.visionclaw.core.network.OpenClawClient(
            gatewayUrlProvider = {
                prefs.openClawEndpoint.takeIf { it.isNotBlank() }
                    ?: pairing.getString("openclaw_pair_device_token_gateway", null)
                        ?.takeIf { it.isNotBlank() }
            },
            fallbackGatewayUrlProvider = {
                pairing.getString("openclaw_pair_device_token_gateway", null)
                    ?.takeIf {
                        it.isNotBlank() &&
                            !it.equals(prefs.openClawEndpoint.takeIf { e -> e.isNotBlank() }, ignoreCase = true)
                    }
            },
            gatewayTokenProvider = {
                prefs.openClawToken.takeIf { it.isNotBlank() }
                    ?: pairing.getString("openclaw_pair_device_token", null)
                        ?.takeIf { it.isNotBlank() }
            },
            deviceIdProvider = {
                pairing.getString("openclaw_pair_device_id", null)?.takeIf { it.isNotBlank() }
            },
            publicKeyProvider = {
                pairing.getString("openclaw_pair_public_key", null)?.takeIf { it.isNotBlank() }
            },
            privateKeyProvider = {
                pairing.getString("openclaw_pair_private_key", null)?.takeIf { it.isNotBlank() }
            },
            sessionIdProvider = { prefs.openClawSessionId.ifBlank { "main" } },
            timeoutMsProvider = {
                val t = prefs.openClawTimeoutSeconds
                if (t > 0) t * 1000 else 30_000
            },
            fastModeProvider = { prefs.openClawFastMode },
            thinkLevelProvider = { prefs.openClawThinkLevel },
            afterFastModeProvider = { prefs.openClawAfterFastMode },
            afterThinkLevelProvider = { prefs.openClawAfterThinkLevel }
        )
        ToolDispatcher(
            context = appContext,
            // Authenticated Google clients injected by the Service so the
            // voice tools (google_calendar / google_tasks, plus AQI / places /
            // daily_briefing via location) hit real OAuth instead of the
            // no-auth stubs that made Gemini report "no access".
            calendarClient = injectedCalendarClient,
            tasksClient = injectedTasksClient,
            airQualityClient = injectedAirQualityClient,
            placesClient = injectedPlacesClient,
            directionsClient = injectedDirectionsClient,
            locationProvider = injectedLocationProvider,
            recentCardsProvider = { viewModel.getAssistantCardsSnapshot().map { it.text } },
            openClawClient = openClawClient,
            hermesClient = hermesClient,
            cameraFrameProvider = { latestCameraFrame },
            // browser_vision should look at the live camera when it's on; the
            // WebView screenshot is the fallback for camera-off browsing.
            browserFrameProvider = { bestVisionFrameBase64() },
            browserPageTextProvider = { maxChars ->
                com.TapLink.app.media.BrowserFrameHolder.capturePageText(maxChars)
            }
        )
    }

    /** Phase 4c — debounce + in-flight guard for the local-regex
     *  trigger path. When Gemini Live doesn't elect to call the
     *  browser_vision tool itself (Live tool dispatch can be flaky
     *  in 3.1), we sniff the input transcript for vision-intent
     *  phrases and call BrowserVisionTool directly. */
    @Volatile private var lastLocalVisionTriggerMs: Long = 0L

    /** Uptime of the last browser_vision answer actually delivered (either
     *  path). Used to suppress a SECOND vision capture+narration when the
     *  local sniff and Gemini's own tool call both fire for one utterance
     *  — the June-12 "double response" on screen questions. */
    @Volatile private var lastVisionDeliveredAtMs: Long = 0L

    // ── Double-response diagnostics (June-12) ──────────────────────────
    // Per-turn audio accounting so a repeat is unmistakable in logcat: if
    // the SAME spoken text appears in two turnComplete logs, or one turn
    // plays two audio bursts, the "TURN-AUDIT" lines show it directly.
    @Volatile private var turnAudioBytes: Long = 0L
    @Volatile private var turnAudioChunks: Int = 0
    @Volatile private var turnAudioBursts: Int = 0
    @Volatile private var lastAudioChunkAtMs: Long = 0L
    @Volatile private var lastTurnSpokenText: String = ""
    /** Assembled spoken text of the in-progress turn, for internal-repeat
     *  detection (the model saying the same phrase twice in one turn). */
    private val turnSpokenAccum = StringBuilder()
    @Volatile private var internalRepeatFlaggedThisTurn = false
    @Volatile private var visionInFlight: Boolean = false
    @Volatile private var pageTextInFlight: Boolean = false
    @Volatile private var noteCaptureInFlight: Boolean = false
    @Volatile private var identifySongInFlight: Boolean = false
    @Volatile private var lastIdentifySongTriggerMs: Long = 0L
    @Volatile private var lastIdentifySongTranscript: String = ""
    @Volatile private var lastReaderModeTriggerMs: Long = 0L
    @Volatile private var lastGoogleWebAppLaunchMs: Long = 0L
    @Volatile private var lastGoogleWebAppLaunchUrl: String? = null

    /** Phase 4e — callback the Service installs so the pipeline can
     *  ask it to auto-start CameraX when a vision phrase fires. Set
     *  by [setAutoCameraEnabler] in Service.onCreate. */
    @Volatile private var autoCameraEnabler: (() -> Unit)? = null

    /** Service installs this so the pipeline can request a camera
     *  warmup when the user's voice triggers a vision phrase. Idempotent
     *  on the Service side. */
    fun setAutoCameraEnabler(enabler: () -> Unit) {
        autoCameraEnabler = enabler
    }

    /**
     * OAuth-backed Google clients + a device-location provider, injected by
     * the Service (which owns the GoogleOAuthManager). Without these, the
     * [toolDispatcher] below falls back to ToolDispatcher's no-auth stub
     * clients, so google_calendar / google_tasks (and places/AQI/briefing)
     * fail authentication and Gemini truthfully tells the user it has no
     * access — even though the HUD shows the same data. The Service installs
     * these in setupUnipanelHudDataFeeds() during onCreate, which runs before
     * any voice session, so the lazy toolDispatcher picks them up.
     */
    @Volatile private var injectedCalendarClient:
        com.rayneo.visionclaw.core.network.GoogleCalendarClient? = null
    @Volatile private var injectedTasksClient:
        com.rayneo.visionclaw.core.network.GoogleTasksClient? = null
    @Volatile private var injectedAirQualityClient:
        com.rayneo.visionclaw.core.network.GoogleAirQualityClient? = null
    @Volatile private var injectedPlacesClient:
        com.rayneo.visionclaw.core.network.GooglePlacesClient? = null
    @Volatile private var injectedDirectionsClient:
        com.rayneo.visionclaw.core.network.GoogleDirectionsClient? = null
    @Volatile private var injectedLocationProvider:
        (() -> com.rayneo.visionclaw.core.model.DeviceLocationContext?)? = null

    /**
     * Install the authenticated Google tool clients + location provider used
     * by the voice [toolDispatcher]. Must be called BEFORE the first voice
     * session (the dispatcher is built lazily on first tool call). Idempotent.
     */
    fun setGoogleToolClients(
        calendarClient: com.rayneo.visionclaw.core.network.GoogleCalendarClient?,
        tasksClient: com.rayneo.visionclaw.core.network.GoogleTasksClient?,
        airQualityClient: com.rayneo.visionclaw.core.network.GoogleAirQualityClient?,
        locationProvider: (() -> com.rayneo.visionclaw.core.model.DeviceLocationContext?)?,
        placesClient: com.rayneo.visionclaw.core.network.GooglePlacesClient? = null,
        directionsClient: com.rayneo.visionclaw.core.network.GoogleDirectionsClient? = null
    ) {
        injectedCalendarClient = calendarClient
        injectedTasksClient = tasksClient
        injectedAirQualityClient = airQualityClient
        injectedLocationProvider = locationProvider
        injectedPlacesClient = placesClient
        injectedDirectionsClient = directionsClient
    }

    /**
     * Begin a voice session. Connects the WebSocket; AudioRecord opens
     * after [GeminiRouter.LiveSessionListener.onSessionReady] fires so
     * we don't start streaming bytes into a not-yet-ready socket.
     *
     * Idempotent: a second call while already active is a no-op.
     */
    fun activate() {
        if (captureActive || liveSession != null || connectJob?.isActive == true) {
            Log.d(TAG, "activate(): already in progress / active, skipping")
            return
        }

        // Permission gate. The Service is microphone-typed but
        // RECORD_AUDIO is a separate runtime permission; if the user
        // never granted it (Phase 2 visionclaw onCreate handles the
        // grant request) the AudioRecord constructor would crash with
        // SecurityException.
        if (ContextCompat.checkSelfPermission(
                appContext, Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "activate(): RECORD_AUDIO not granted")
            HudStateBridge.update {
                it.copy(
                    phase = HudStateBridge.VoicePhase.IDLE,
                    connection = HudStateBridge.ConnectionStatus.ERROR,
                    notification = "Microphone permission needed."
                )
            }
            return
        }

        Log.i(TAG, "activate(): starting voice session")

        // NOTE: we deliberately do NOT persist the chat context here. Saving the
        // currently-visible exchange and then immediately re-reading it as the
        // GeminiRouter builds the Live prompt re-injected the current topic as
        // "PREVIOUS CONVERSATION (from an earlier session)", which is exactly
        // what let stale context drive irrelevant commands. The cache is written
        // only when a session actually ends (deactivate) and read at the next
        // activation — never within the same breath.

        // Reset the live assistant stream buffer so a new turn doesn't
        // accidentally concatenate with the previous one.
        runCatching { viewModel.resetLiveAssistantStream() }
        runCatching { viewModel.activateVoiceAssistant() }
        youtubePlaybackPreemptInFlight = false
        identifySongInFlight = false
        lastIdentifySongTriggerMs = 0L
        lastIdentifySongTranscript = ""

        HudStateBridge.update {
            it.copy(
                phase = HudStateBridge.VoicePhase.LISTENING,
                connection = HudStateBridge.ConnectionStatus.CONNECTING,
                transcript = "Connecting…",
                notification = null,
                oscilloscopeLevel = 0f
            )
        }

        val epoch = beginSessionEpoch()
        val listener = createListener(epoch)
        connectJob = scope.launch {
            // Make sure the user's location is published BEFORE the Live
            // session connects: the router builds its CURRENT LOCATION block
            // once at connect time, so Gemini always knows where the user is
            // for location-related queries. Cheap when a recent fix is cached.
            runCatching { viewModel.ensureDeviceLocationForLiveSession() }
            val handle = runCatching {
                viewModel.geminiRouter.startLiveAudioSession(listener)
            }.getOrNull()

            if (handle == null) {
                Log.w(TAG, "startLiveAudioSession returned null")
                HudStateBridge.update {
                    it.copy(
                        phase = HudStateBridge.VoicePhase.IDLE,
                        connection = HudStateBridge.ConnectionStatus.ERROR,
                        notification = "Could not connect to Gemini Live."
                    )
                }
                runCatching { viewModel.deactivateVoiceAssistant() }
                return@launch
            }

            if (!isSessionEpochCurrent(epoch)) {
                Log.i(TAG, "Live session handle acquired for stale epoch=$epoch; closing")
                runCatching { handle.close() }
                return@launch
            }

            liveSession = handle
            Log.i(TAG, "Live session handle acquired; awaiting onSessionReady")
        }
    }

    /**
     * End the current voice session. Tears down AudioRecord, closes
     * the WebSocket, stops AudioTrack playback, and publishes IDLE
     * state. Idempotent; safe to call from any thread.
     *
     * @param reason optional one-line message surfaced in the HUD.
     */
    fun shutdown(reason: String? = null) {
        invalidateSessionEpoch()
        if (!captureActive && liveSession == null && connectJob?.isActive != true &&
            readoutJob?.isActive != true
        ) {
            Log.d(TAG, "shutdown(): already idle; forcing audio/UI cleanup anyway")
        }
        Log.i(TAG, "shutdown(reason=$reason)")

        captureActive = false
        val thread = audioThread
        audioThread = null
        runCatching { thread?.interrupt() }

        val rec = audioRecord
        audioRecord = null
        runCatching { rec?.stop() }
        runCatching { rec?.release() }

        val session = liveSession
        liveSession = null
        liveSessionReady = false
        runCatching { session?.close() }

        connectJob?.cancel()
        connectJob = null
        conversationalFallbackJob?.cancel()
        conversationalFallbackJob = null
        // Release the late-output gate at every session-boundary reset so
        // the next fresh session starts clean (gate is per-turn within a
        // session; a hard reset wipes it regardless).
        dropOutputTranscriptionUntilNextInput = false

        // Cancel the agent-readout coroutine and clear its gates BEFORE we
        // flush audio — otherwise it keeps queueing TTS chunks (speech keeps
        // playing) and re-publishing the THINKING phase (green halo stays) even
        // though the session is closed. liveSessionReady is already false here,
        // so the readout's finally block won't bounce the phase to LISTENING.
        readoutJob?.cancel()
        readoutJob = null
        agentReadoutActive = false
        agentCallInFlight = false
        agentProgressJob?.cancel()
        agentProgressJob = null
        agentFollowupJob?.cancel()
        agentFollowupJob = null
        agentFollowupTool = null
        suppressGeminiOutputUntilMs = 0L
        suppressGeminiOutputUntilNextInput = false

        // Hermes stopped every playback source on exit. In the service path,
        // releasing the AudioTrack is safer than a pause/flush because stale
        // Live callbacks or a long readout write loop cannot keep speaking on
        // an already-detached track.
        runCatching { audioPlayer.release() }

        // Commit any pending assistant stream chunk so it appears as a
        // mini-card rather than vanishing on shutdown mid-response.
        runCatching { viewModel.appendUserUtterance(latestInputTranscript) }
        runCatching { viewModel.commitLiveAssistantStreamIfNeeded() }
        runCatching { viewModel.saveChatContextForNextSession() }
        runCatching { viewModel.resetLiveAssistantStream() }
        runCatching { viewModel.deactivateVoiceAssistant() }

        HudStateBridge.update {
            it.copy(
                phase = HudStateBridge.VoicePhase.IDLE,
                connection = HudStateBridge.ConnectionStatus.IDLE,
                transcript = null,
                oscilloscopeLevel = 0f,
                notification = reason
            )
        }
    }

    /** Release everything. Called from Service.onDestroy. */
    fun release() {
        shutdown(reason = null)
        runCatching { audioPlayer.release() }
    }

    /**
     * Phase 4d — push one camera frame into the active Gemini Live
     * session. Called by the Service whenever
     * [com.rayneo.visionclaw.core.camera.FrameCaptureManager]
     * produces a new analyzed frame (CameraX ImageAnalysis use case).
     *
     * No-op if the session isn't ready yet — there's no point
     * sending video before onSessionReady fires, the model would
     * just drop the frame.
     */
    fun sendCameraFrame(base64: String) {
        latestCameraFrame = base64.takeIf { it.isNotBlank() }
        if (base64.isNotBlank()) lastCameraFrameMs = SystemClock.elapsedRealtime()
        if (!liveSessionReady) return
        if (base64.isBlank()) return
        runCatching {
            liveSession?.sendImageChunkBase64(base64, "image/jpeg")
        }
    }

    // ────────────────────────────────────────────────────────────────
    // Internals
    // ────────────────────────────────────────────────────────────────

    @Synchronized
    private fun beginSessionEpoch(): Long {
        activeSessionEpoch += 1L
        return activeSessionEpoch
    }

    @Synchronized
    private fun invalidateSessionEpoch() {
        activeSessionEpoch += 1L
    }

    private fun isSessionEpochCurrent(epoch: Long): Boolean =
        activeSessionEpoch == epoch

    private fun createListener(epoch: Long): GeminiRouter.LiveSessionListener {
        return object : GeminiRouter.LiveSessionListener {
            override fun onSessionReady() {
                if (!isSessionEpochCurrent(epoch)) return
                Log.i(TAG, "onSessionReady")
                liveSessionReady = true
                HudStateBridge.update {
                    it.copy(
                        connection = HudStateBridge.ConnectionStatus.GEMINI_CONNECTED,
                        transcript = "Listening…",
                        notification = null
                    )
                }
                startAudioStreaming(epoch)
                // Auto-reconnect replay: hand the question that was lost to
                // the previous session's drop straight to the new session as
                // client text — the user shouldn't have to repeat themself.
                pendingResumeUtterance?.let { lost ->
                    pendingResumeUtterance = null
                    scope.launch {
                        kotlinx.coroutines.delay(500L)
                        if (!isSessionEpochCurrent(epoch)) return@launch
                        Log.i(TAG, "replaying utterance lost to session drop: '${lost.take(140)}'")
                        runCatching { liveSession?.sendClientText(lost) }
                    }
                }
            }

            override fun onInputTranscription(text: String) {
                if (!isSessionEpochCurrent(epoch)) return
                if (text.isBlank()) return
                // Next user turn starting — release the late-output gate so
                // the model can speak again. Late output from the PREVIOUS
                // turn (which should have been dropped) is now safely past.
                if (dropOutputTranscriptionUntilNextInput) {
                    Log.d(TAG, "onInputTranscription: releasing late-output gate")
                    dropOutputTranscriptionUntilNextInput = false
                    // New user turn — clear the spoken-text accumulator so the
                    // internal-repeat detector doesn't compare across turns
                    // (used when onTurnComplete was missed/late).
                    turnSpokenAccum.setLength(0)
                    internalRepeatFlaggedThisTurn = false
                }
                // Release the agent-output suppression on the next user input —
                // but REFUSE to fire while the agent turn or the Fish readout is
                // still live: the mic hears the readout playing out the speaker
                // and transcribes it as "input", which used to trip this release
                // and let Gemini speak over / repeat the agent's reply.
                if (suppressGeminiOutputUntilNextInput) {
                    if (agentCallInFlight || agentReadoutActive) {
                        Log.d(
                            TAG,
                            "onInputTranscription: agent turn live " +
                                "(inFlight=$agentCallInFlight readout=$agentReadoutActive) " +
                                "— keeping agent-output suppression"
                        )
                    } else {
                        Log.d(TAG, "onInputTranscription: releasing agent-output suppression")
                        suppressGeminiOutputUntilNextInput = false
                        suppressGeminiOutputUntilMs = 0L
                    }
                }
                latestInputTranscript = text
                val nowChunkMs = android.os.SystemClock.uptimeMillis()
                utteranceBuffer =
                    if (utteranceBuffer.isBlank() ||
                        nowChunkMs - lastInputChunkAtMs > UTTERANCE_GAP_RESET_MS
                    ) {
                        text.trim()
                    } else {
                        utteranceBuffer + " " + text.trim()
                    }
                lastInputChunkAtMs = nowChunkMs
                maybeDispatchAgentFollowup()
                Log.d(TAG, "onInputTranscription partial='${text.take(120)}'")
                // Live partial transcript — surface in HUD so the user
                // sees what Gemini heard. Final commit to viewModel
                // happens on turnComplete to avoid mid-utterance noise.
                HudStateBridge.update { it.copy(transcript = text) }
                if (maybeCaptureNoteLocally(text, epoch)) return
                if (maybeIdentifySongLocally(text, epoch)) return
                if (maybePreemptYouTubePlaybackLocally(text, epoch)) return
                // Full-page reading is DOM text, not visible-screen OCR.
                // Check it before the screenshot fallback so phrases like
                // "read this page" do not get trapped in browser_vision.
                val pageTextHandled = maybeTriggerBrowserPageTextLocally(text, epoch)
                // Phase 4c (codex) — deterministic browser_vision trigger.
                // Gemini Live doesn't always pick the browser_vision
                // tool even when the user clearly asks "look at this" /
                // "what does this say". Sniff the partial transcript
                // and fire the tool ourselves with the latest text as
                // the question.
                if (!pageTextHandled) maybeTriggerBrowserVisionLocally(text, epoch)
                // Deterministic "reader mode" trigger — re-render the current
                // page in a clean dark reader view when the user asks for it.
                maybeTriggerReaderModeLocally(text)
                maybeOpenGoogleWebAppLocally(text)
                maybeScheduleConversationalTextFallback(
                    transcript = text,
                    epoch = epoch,
                    suppress = pageTextHandled
                )
            }

            override fun onOutputTranscription(rawText: String) {
                if (!isSessionEpochCurrent(epoch)) return
                // Strip Gemini native-audio control tokens (e.g. "<ctrl46>")
                // that leak into the transcript when a turn degenerates —
                // they must never reach the chat card or a readout. If the
                // chunk was ONLY a control token, drop it entirely.
                val text = rawText.replace(Regex("<ctrl\\d+>"), "").let {
                    if (rawText.isNotBlank() && it.isBlank()) {
                        Log.w(TAG, "Dropped control-token-only output chunk: '${rawText.take(40)}'")
                        ""
                    } else it
                }
                if (text.isBlank()) return
                noteLiveResponseActivity()
                // Regression port (commit 8c2b872): late chunk arriving AFTER
                // onTurnComplete but BEFORE the next user turn would
                // otherwise be appended as a brand-new assistant card,
                // producing the "Gemini said the same thing twice in a row"
                // symptom. Drop with a diagnostic until the next user turn
                // releases the gate.
                if (dropOutputTranscriptionUntilNextInput) {
                    Log.d(
                        TAG,
                        "Dropping late outputTranscription after turnComplete: " +
                            "'${text.take(120)}'"
                    )
                    return
                }
                // While an agent reply (Hermes / TapClaw / OpenClaw) is being
                // read aloud via the selected readout engine, the chat card
                // already holds the agent's VERBATIM reply
                // (appendDirectAssistantResponse). Gemini Live may still emit
                // its own spoken SUMMARY transcript for the same turn — drop it
                // so it doesn't overwrite the verbatim agent text on the card.
                // This mirrors the audio suppression in onModelAudio.
                if (android.os.SystemClock.uptimeMillis() < suppressGeminiOutputUntilMs) {
                    return
                }
                if (suppressGeminiOutputUntilNextInput) {
                    Log.d(TAG, "Dropping Gemini outputTranscription during agent handoff")
                    return
                }
                runCatching { viewModel.appendLiveAssistantStreamChunk(text) }
                // TURN-AUDIT: accumulate the spoken text and detect an
                // INTERNAL repeat (the model saying a substantial phrase
                // twice within one turn). This is the reliable signal — it
                // doesn't depend on audio-burst timing (tool pauses and
                // sentence breaks created false positives there).
                runCatching {
                    turnSpokenAccum.append(text)
                    if (!internalRepeatFlaggedThisTurn && turnSpokenAccum.length >= 80) {
                        val whole = turnSpokenAccum.toString()
                        // Take a ~50-char window from the END and see if that
                        // exact run already appeared earlier (non-overlapping).
                        val tail = whole.takeLast(50)
                        val earlier = whole.dropLast(50).indexOf(tail)
                        if (tail.isNotBlank() && earlier >= 0) {
                            internalRepeatFlaggedThisTurn = true
                            Log.w(
                                TAG,
                                "TURN-AUDIT: INTERNAL-REPEAT — phrase recurs in one turn: " +
                                    "'${tail.trim()}'"
                            )
                        }
                    }
                }
                HudStateBridge.update {
                    it.copy(phase = HudStateBridge.VoicePhase.THINKING)
                }
            }

            override fun onModelText(text: String) {
                if (!isSessionEpochCurrent(epoch)) return
                if (text.isNotBlank()) noteLiveResponseActivity()
                // Audio responses come through onOutputTranscription instead.
                // onModelText fires for text-only responses, which we don't
                // request here.
            }

            override fun onModelAudio(mimeType: String, data: ByteArray) {
                if (!isSessionEpochCurrent(epoch) || !liveSessionReady) return
                if (data.isEmpty()) return
                noteLiveResponseActivity()
                // Drop Gemini Live's own audio while an agent reply is being
                // read aloud via the selected readout engine, so the two
                // voices never overlap. Two layers of defence here:
                //  1) agentReadoutActive — set true at the start of the
                //     readout coroutine, cleared in its finally. Catches the
                //     in-readout case directly without depending on the
                //     suppressGeminiOutputUntilMs timestamp.
                //  2) suppressGeminiOutputUntilMs — the timestamp gate that
                //     also covers the post-readout AudioTrack drain window
                //     after the coroutine sets agentReadoutActive=false (see
                //     finally block below — instead of clearing the flag
                //     immediately, it extends it by AGENT_READOUT_DRAIN_MS).
                if (agentReadoutActive) {
                    Log.d(TAG, "Dropping Gemini onModelAudio: agent readout active")
                    return
                }
                // June-11 capture fix (the "said it twice" bug): when the
                // conversational text fallback loses its race — model output
                // arrived ~100ms AFTER the forced text turn was sent — Gemini
                // answers BOTH turns with the same content. The transcript
                // half of the duplicate was already gated
                // (dropOutputTranscriptionUntilNextInput), but the AUDIO half
                // kept playing for seconds. Gate it too — but ONLY in forced-
                // fallback cycles (matching transcript), so the normal post-
                // turn audio drain of ordinary turns is never clipped.
                if (dropOutputTranscriptionUntilNextInput &&
                    lastForcedConversationalTranscript.isNotEmpty() &&
                    lastForcedConversationalTranscript.equals(
                        latestInputTranscript.trim(), ignoreCase = true
                    )
                ) {
                    Log.d(TAG, "Dropping duplicate onModelAudio after forced text turn")
                    return
                }
                if (android.os.SystemClock.uptimeMillis() < suppressGeminiOutputUntilMs) {
                    return
                }
                if (suppressGeminiOutputUntilNextInput) {
                    Log.d(TAG, "Dropping Gemini onModelAudio during agent handoff")
                    return
                }
                // TURN-AUDIT: count audio per turn. A >700ms gap since the
                // last chunk starts a new "burst" — a single turn that plays
                // two bursts is the audio-level double we're hunting.
                val nowAudio = android.os.SystemClock.uptimeMillis()
                // 2.5s threshold: below this is a natural sentence pause or a
                // tool round-trip, NOT a repeat. The internal-repeat text
                // detector (onOutputTranscription) is the real signal; this
                // burst counter is just supplementary context now.
                if (turnAudioChunks == 0 || nowAudio - lastAudioChunkAtMs > 2_500L) {
                    turnAudioBursts++
                }
                turnAudioChunks++
                turnAudioBytes += data.size
                lastAudioChunkAtMs = nowAudio
                runCatching {
                    audioPlayer.playChunk(mimeType, data, muted = false, volume = 1f)
                }
                // Phase 4k — drive the MODEL (blue) oscilloscope from
                // Gemini's outgoing audio so the avatar orb glows blue
                // and pulses with the reply's loudness while it speaks.
                // Output audio is PCM16, same as the mic stream, so the
                // same peak helper applies. Phase is already THINKING
                // (set in onOutputTranscription); we just feed the level.
                runCatching {
                    val peak = calculatePcm16Peak(data, data.size)
                    val norm = (peak / 32_767f).coerceIn(0f, 1f)
                    HudStateBridge.update {
                        it.copy(
                            oscilloscopeLevel = norm,
                            oscilloscopeChannel = HudStateBridge.OscilloscopeChannel.MODEL
                        )
                    }
                }
            }

            override fun onToolCall(callId: String, name: String, args: String) {
                if (!isSessionEpochCurrent(epoch)) return
                noteLiveResponseActivity()
                // The model routed something itself — stand down the hail
                // follow-up router so we can't double-dispatch the same
                // utterance to the agent.
                agentFollowupTool = null
                agentFollowupJob?.cancel()
                Log.i(TAG, "onToolCall: callId=$callId name=$name args=${args.take(160)}")
                when (name) {
                    "browser_vision" -> dispatchBrowserVision(callId, name, args)
                    else -> dispatchNativeTool(callId, name, args, epoch)
                }
            }

            override fun onTurnComplete(finishReason: String?) {
                if (!isSessionEpochCurrent(epoch)) return
                noteLiveResponseActivity()
                Log.d(TAG, "onTurnComplete: finishReason=$finishReason")
                // TURN-AUDIT: log what was spoken this turn + the audio
                // accounting, and flag when this turn's text equals the
                // PREVIOUS turn's text (the model repeating itself) — the
                // single grep that settles the "double response" report.
                runCatching {
                    val spoken = viewModel.snapshotLiveAssistantTurn().trim()
                    if (spoken.isNotEmpty()) {
                        val repeat = spoken == lastTurnSpokenText
                        Log.i(
                            TAG,
                            "TURN-AUDIT: bursts=$turnAudioBursts chunks=$turnAudioChunks " +
                                "bytes=$turnAudioBytes repeatOfPrevTurn=$repeat " +
                                "spoken='${spoken.take(120)}'"
                        )
                        if (repeat) {
                            Log.w(TAG, "TURN-AUDIT: this turn REPEATS the previous turn's text")
                        }
                        lastTurnSpokenText = spoken
                    }
                }
                turnAudioBytes = 0L
                turnAudioChunks = 0
                turnAudioBursts = 0
                turnSpokenAccum.setLength(0)
                internalRepeatFlaggedThisTurn = false
                // Latch the late-output gate. Any outputTranscription /
                // modelText arriving from here until the next user turn is
                // a stray late chunk that would otherwise be appended as a
                // duplicate assistant card. Cleared in onInputTranscription
                // and in the session-shutdown path below.
                dropOutputTranscriptionUntilNextInput = true
                // Capture the assistant turn BEFORE the working buffer is
                // reset below. We append a GEMINI history record only when
                // this was a direct Gemini Live turn — agent-routed turns
                // (Hermes / TapClaw) write to their own per-agent history
                // from dispatchNativeTool, and their working buffer is
                // suppressed so this snapshot is empty for them. Wrapped
                // in runCatching so a storage hiccup never breaks turn
                // completion.
                runCatching {
                    val userQuery = latestInputTranscript.trim()
                    val assistantTurn = runCatching {
                        viewModel.snapshotLiveAssistantTurn().trim()
                    }.getOrNull().orEmpty()
                    if (userQuery.isNotEmpty() && assistantTurn.isNotEmpty()) {
                        val snippet = com.rayneo.visionclaw.core.storage.ChatHistoryStore
                            .buildSnippet(userQuery, assistantTurn)
                        val record = com.rayneo.visionclaw.core.storage.ChatHistoryStore.Record(
                            ts = System.currentTimeMillis(),
                            agent = com.rayneo.visionclaw.core.storage.ChatHistoryStore
                                .Agent.GEMINI,
                            query = userQuery,
                            response = assistantTurn,
                            snippet = snippet
                        )
                        val days = AppPreferences(appContext).hudChatHistoryDays
                        com.rayneo.visionclaw.core.storage.ChatHistoryStore
                            .append(appContext, record, days)
                        Log.i(
                            TAG,
                            "chat-history append agent=GEMINI " +
                                "queryLen=${userQuery.length} replyLen=${assistantTurn.length}"
                        )
                    }
                }
                runCatching { viewModel.appendUserUtterance(latestInputTranscript) }
                runCatching { viewModel.commitLiveAssistantStreamIfNeeded() }
                // Do NOT persist the cross-session cache per turn. Saving the
                // just-completed live exchange here made turn N reappear as
                // "PREVIOUS CONVERSATION (from an earlier session)" on turn N+1
                // within the SAME live session — the in-session contamination
                // loop that let stale topics drive irrelevant tool calls. The
                // cache is persisted only at true session end (deactivate).
                runCatching { viewModel.resetLiveAssistantStream() }
                runCatching { audioPlayer.notifyTurnComplete() }
                if (liveSessionReady) {
                    HudStateBridge.update {
                        it.copy(
                            phase = HudStateBridge.VoicePhase.LISTENING,
                            transcript = "Listening…"
                        )
                    }
                }
            }

            override fun onError(message: String) {
                if (!isSessionEpochCurrent(epoch)) return
                Log.w(TAG, "onError: $message")
                shutdown(reason = "Voice error: $message")
            }

            override fun onClosed(code: Int, reason: String) {
                if (!isSessionEpochCurrent(epoch)) return
                Log.i(TAG, "onClosed: code=$code reason=$reason")
                // June-11 round 2: the server closed the session (1008)
                // mid-question — the user finished asking into a dead socket
                // and the question silently vanished, with no reconnect
                // for 42s (until he noticed and re-tapped). Two repairs:
                //  1) capture the in-progress utterance for replay,
                //  2) auto-reconnect (bounded: 3 attempts / 2 minutes so a
                //     persistently-rejecting server can't loop us).
                val unexpected = code != 1000
                if (unexpected) {
                    val buf = utteranceBuffer.trim()
                    if (buf.isNotBlank() &&
                        android.os.SystemClock.uptimeMillis() - lastInputChunkAtMs < 30_000L
                    ) {
                        pendingResumeUtterance = buf
                        Log.i(
                            TAG,
                            "captured un-answered utterance for replay: '${buf.take(140)}'"
                        )
                    }
                }
                // Only invoke shutdown if we initiated; the close path is
                // idempotent so calling it from a remote-close event is
                // safe — it just resets HUD state.
                shutdown(reason = if (code == 1000) null else "Voice session closed.")
                if (unexpected) {
                    val now = android.os.SystemClock.uptimeMillis()
                    if (now - reconnectWindowStartMs > 120_000L) {
                        reconnectWindowStartMs = now
                        reconnectCountInWindow = 0
                    }
                    if (reconnectCountInWindow < 3) {
                        reconnectCountInWindow++
                        Log.i(TAG, "auto-reconnect scheduled (attempt $reconnectCountInWindow)")
                        android.os.Handler(android.os.Looper.getMainLooper())
                            .postDelayed({ activate() }, 900L)
                    } else {
                        Log.w(TAG, "auto-reconnect suppressed — too many drops in window")
                    }
                }
            }

            override fun onGroundingMetadata(chunks: List<GeminiRouter.GroundingChunk>) {
                // No-op for the audio-only path; URL grounding only matters
                // for tool-call-driven research turns which are Phase 4b.
            }
        }
    }

    /**
     * Phase 4i — restore the Hermes-era tool surface for the unipanel
     * Service voice path. Tool declarations already come from
     * GeminiRouter; this executes the matching local tool and sends the
     * result back to Gemini Live. URL-opening tools are also applied
     * locally so Gemini cannot swallow or mangle taplink:// results.
     */
    private fun dispatchNativeTool(callId: String, name: String, args: String, epoch: Long) {
        val toolName = name.trim()
        if (toolName.isBlank()) return
        Log.i(TAG, "native tool dispatch: callId=$callId name=$toolName args=${args.take(160)}")

        if (!toolDispatcher.isSupported(toolName)) {
            Log.w(TAG, "native tool unsupported in Service: $toolName")
            runCatching {
                liveSession?.sendToolResponse(callId, toolName, "Unknown tool: $toolName")
            }
            return
        }

        scope.launch {
            if (!isSessionEpochCurrent(epoch)) return@launch
            HudStateBridge.update { it.copy(notification = "Running $toolName…") }
            val agentLabel = when (toolName) {
                "hermes_agent" -> "Hermes"
                "tapclaw_agent" -> "TapClaw"
                else -> "Research"
            }
            // June-11 round 2 — bare-hail guard. Gemini fires the agent tool
            // off the partial transcript the instant it hears the name:
            // hermes_agent(query="Hermes") went out alone while the user was
            // still mid-sentence, Hermes got hailed with no question and
            // answered with a bare ack — and the real question never reached
            // it. When the query is just a name (≤2 words), HOLD dispatch
            // and watch the utterance buffer: if the sentence keeps growing,
            // dispatch the WHOLE sentence; if the user truly just hailed
            // (hail-and-wait style), send the bare hail and arm the
            // follow-up router at result time.
            var effectiveArgs = args
            var bareHailDispatched = false
            if (toolName in AGENT_READOUT_TOOLS) {
                val rawQuery = runCatching {
                    org.json.JSONObject(args).optString("query", "")
                }.getOrNull().orEmpty().trim()
                val queryWordCount = rawQuery
                    .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
                    .trim()
                    .split(Regex("\\s+"))
                    .count { it.isNotBlank() }
                if (queryWordCount <= 2) {
                    Log.i(
                        TAG,
                        "agent $toolName bare-hail query='$rawQuery' — " +
                            "holding dispatch for the full sentence"
                    )
                    HudStateBridge.update {
                        it.copy(
                            agentBusy = true,
                            heartbeatMessage = "$agentLabel listening…",
                            heartbeatPersistent = true,
                            heartbeatShouldScroll = false
                        )
                    }
                    val hailBuffer = utteranceBuffer.trim()
                    val waitStart = android.os.SystemClock.uptimeMillis()
                    var upgraded: String? = null
                    while (android.os.SystemClock.uptimeMillis() - waitStart < BARE_HAIL_WAIT_MS) {
                        kotlinx.coroutines.delay(400L)
                        val buf = utteranceBuffer.trim()
                        val quietMs =
                            android.os.SystemClock.uptimeMillis() - lastInputChunkAtMs
                        val words = buf.split(Regex("\\s+")).count { it.isNotBlank() }
                        if (buf != hailBuffer && words >= 4 && quietMs > 1_200L) {
                            upgraded = buf
                            break
                        }
                    }
                    if (upgraded != null) {
                        Log.i(TAG, "bare-hail upgraded from live transcript: '${upgraded.take(140)}'")
                        effectiveArgs = runCatching {
                            org.json.JSONObject(args).put("query", upgraded).toString()
                        }.getOrElse {
                            org.json.JSONObject().put("query", upgraded).toString()
                        }
                    } else {
                        bareHailDispatched = true
                    }
                }
            }
            // Mark agent queries busy + show "Asking <agent>…" on the ticker for
            // the whole in-flight duration, so the persistent status-line poll
            // doesn't overwrite the live progress and make a working query look
            // idle ("Hermes: ready"). Cleared when the readout finishes.
            if (toolName in AGENT_READOUT_TOOLS) {
                HudStateBridge.update {
                    it.copy(
                        agentBusy = true,
                        heartbeatMessage = "Asking $agentLabel…",
                        heartbeatPersistent = true,
                        heartbeatShouldScroll = false
                    )
                }
                // Hermes interruption / double-speak fix: arm the suppression
                // the moment the agent call DISPATCHES, not when the reply
                // returns. While the agent works, Gemini Live may try to fill
                // the silence with its own narration; and once the readout
                // starts, the mic hears it and emits "input" transcriptions.
                // Both windows are covered: agentCallInFlight holds the
                // next-input release gate shut (see onInputTranscription) and
                // the timestamp backstop caps a wedged turn at
                // AGENT_INFLIGHT_SUPPRESS_MS.
                //
                // DELIBERATELY NOT SET HERE: suppressGeminiOutputUntilNextInput.
                // That flag is armed only when the agent RESULT arrives (see
                // below). If it were set at dispatch and the agent call hung
                // forever, no release site would ever fire and Gemini would
                // stay muted for the whole session — the timestamp backstop
                // above must remain the failsafe for a wedged call.
                agentCallInFlight = true
                suppressGeminiOutputUntilMs =
                    android.os.SystemClock.uptimeMillis() + AGENT_INFLIGHT_SUPPRESS_MS
                // June-11 capture fix: Hermes took 63s and the HUD ticker sat
                // frozen on "Asking Hermes…" the whole time — the user assumed the
                // call died and restarted the session, which is what set up
                // the answered-for-Hermes failure. Heartbeat now ticks the
                // elapsed time every 5s so a long call LOOKS alive.
                agentProgressJob?.cancel()
                agentProgressJob = scope.launch {
                    val startedMs = android.os.SystemClock.uptimeMillis()
                    while (isActive && agentCallInFlight) {
                        kotlinx.coroutines.delay(5_000L)
                        if (!agentCallInFlight || !isActive) break
                        val sec =
                            (android.os.SystemClock.uptimeMillis() - startedMs) / 1000
                        if (sec > 300) break // wedged — let the status poll take over
                        Log.i(TAG, "heartbeat: $agentLabel working ${sec}s")
                        HudStateBridge.update {
                            if (!it.agentBusy) it
                            else it.copy(
                                heartbeatMessage = "$agentLabel working… ${sec}s",
                                heartbeatPersistent = true,
                                heartbeatShouldScroll = false
                            )
                        }
                    }
                }
            }
            // When the user tells an agent to "look at my screen", capture the
            // current browser screen and fold its description into the agent's
            // context before dispatching, so the agent acts on what's shown.
            val dispatchArgs = augmentAgentArgsWithScreenIfRequested(toolName, effectiveArgs)
            val result = toolDispatcher.dispatch(toolName, dispatchArgs)
            val resultText = result.getOrElse { err ->
                Log.w(TAG, "native tool failed name=$toolName: ${err.message}")
                err.message?.trim().takeUnless { it.isNullOrBlank() }
                    ?: "Tool $toolName is unavailable right now."
            }
            Log.i(TAG, "native tool result name=$toolName text='${resultText.take(180)}'")
            agentProgressJob?.cancel()
            agentProgressJob = null
            // June-11 capture fix (the "Gemini answered for Hermes" bug):
            // Hermes legitimately ran 63s; 45s in, the user restarted the voice
            // session, the epoch advanced, and this bail threw away the
            // finished result — then the NEW session answered the re-asked
            // question from its own knowledge. Agent replies don't need the
            // old session: the readout engine, chat card, history and bell
            // are all session-independent. So agent results are delivered
            // ACROSS epochs; only the Live-session tool-response (and the
            // non-agent path, which is pure session plumbing) stay gated.
            val epochCurrent = isSessionEpochCurrent(epoch)
            if (!epochCurrent && toolName !in AGENT_READOUT_TOOLS) return@launch
            if (!epochCurrent) {
                Log.i(
                    TAG,
                    "agent $toolName result arrived after session restart — " +
                        "delivering via readout anyway"
                )
            }

            // Agent replies (Hermes / TapClaw / OpenClaw) are READ ALOUD via
            // the selected readout engine — not narrated by Gemini Live. Mirror
            // the hermes branch: suppress Gemini's audio, ack the tool with a
            // minimal "ok" so the Live session doesn't hang waiting, then
            // synthesize + play the reply through Fish/Gemini TTS.
            if (toolName in AGENT_READOUT_TOOLS) {
                Log.i(TAG, "agent tool $toolName → reading reply via selected readout engine")
                suppressGeminiOutputUntilMs =
                    android.os.SystemClock.uptimeMillis() + 30_000L
                runCatching { audioPlayer.release() }
                // Send the agent's ACTUAL output back to Gemini Live (not a bare
                // "ok") so Gemini can REFERENCE it on later turns — e.g. "what did
                // Hermes say about that?". Previously Gemini only saw "ok" and had
                // no idea what the agent returned. It's wrapped as reference-only:
                // the reply was already spoken via the readout engine and shown
                // verbatim on the chat card, and Gemini's own audio + transcript
                // are suppressed (above / in onModelAudio / onOutputTranscription),
                // so this does NOT cause a duplicate spoken readout.
                // If the agent attached playable media (a line like
                // 'open_taplink:<url>' or 'MEDIA:<url>'), pull it out and open it
                // on the glasses so Hermes / TapClaw can actually PLAY audio /
                // video / show images — not just speak a link. The directive line
                // is stripped from what we show + read aloud.
                val mediaDirective = extractAgentMediaDirective(resultText)
                val displayText =
                    mediaDirective?.cleanedText?.takeIf { it.isNotBlank() } ?: resultText
                val agentToolResponse =
                    "[The agent's reply below was ALREADY spoken to the user via the " +
                        "readout voice and shown verbatim on the chat card. Do NOT read it " +
                        "aloud, summarize, or repeat it now. Keep it ONLY as reference so you " +
                        "can answer follow-up questions about it later.]\n\n" + displayText
                // Arm the until-next-input gate HERE, at result arrival — not
                // at dispatch. Gemini Live often narrates a tool response;
                // this keeps that narration muted until the user actually
                // speaks again (the readout engine is the only voice for
                // this turn). Arming at dispatch instead would leave no
                // failsafe release if the agent call ever hung forever.
                suppressGeminiOutputUntilNextInput = true
                // The tool response only makes sense to the session that
                // issued the call — skip it after a restart (the new session
                // never asked), and skip it entirely for locally-triggered
                // dispatches (synthetic "local-" callIds the server never
                // issued — answering an unknown callId risks a policy close).
                if (epochCurrent && !callId.startsWith("local-")) {
                    runCatching {
                        liveSession?.sendToolResponse(callId, toolName, agentToolResponse)
                    }
                }
                Log.i(TAG, "heartbeat: $agentLabel replied — reading out")
                HudStateBridge.update {
                    it.copy(
                        notification = null,
                        phase = HudStateBridge.VoicePhase.THINKING,
                        heartbeatMessage = "$agentLabel replied — reading out",
                        heartbeatPersistent = true,
                        heartbeatShouldScroll = false
                    )
                }
                // Hail-and-wait: the bare hail just got acked ("Yes?").
                // The user's next utterance is the actual question — route it
                // straight to this agent instead of hoping Gemini re-routes.
                if (bareHailDispatched) {
                    agentFollowupTool = toolName
                    agentFollowupUntilMs =
                        android.os.SystemClock.uptimeMillis() + AGENT_FOLLOWUP_WINDOW_MS
                    Log.i(
                        TAG,
                        "bare-hail answered — next utterance within " +
                            "${AGENT_FOLLOWUP_WINDOW_MS / 1000}s routes to $toolName"
                    )
                }
                // Mirror the hermes branch: the agent's full reply is also shown
                // as TEXT in the chat card (not just spoken). This appends it to
                // the ViewModel's message list, which the Service collects and
                // publishes to ChatCardBridge → the unipanel reply card.
                runCatching { viewModel.appendDirectAssistantResponse(displayText) }
                // Persist the completed turn to the per-agent chat history so
                // the H / O badge overlay can surface it later. Pruned by the
                // user-configurable retention window (hud_chat_history_days).
                runCatching {
                    val historyAgent = when (toolName) {
                        "hermes_agent" -> com.rayneo.visionclaw.core.storage.ChatHistoryStore.Agent.HERMES
                        "tapclaw_agent" ->
                            com.rayneo.visionclaw.core.storage.ChatHistoryStore.Agent.OPENCLAW
                        else -> null
                    }
                    if (historyAgent != null) {
                        val userQuery = runCatching {
                            org.json.JSONObject(args).optString("query", "")
                        }.getOrNull().orEmpty().trim()
                        val agentReply = displayText.trim()
                        if (userQuery.isNotEmpty() || agentReply.isNotEmpty()) {
                            val snippet = com.rayneo.visionclaw.core.storage.ChatHistoryStore
                                .buildSnippet(userQuery, agentReply)
                            val record = com.rayneo.visionclaw.core.storage.ChatHistoryStore.Record(
                                ts = System.currentTimeMillis(),
                                agent = historyAgent,
                                query = userQuery,
                                response = agentReply,
                                snippet = snippet
                            )
                            val days = AppPreferences(appContext).hudChatHistoryDays
                            com.rayneo.visionclaw.core.storage.ChatHistoryStore
                                .append(appContext, record, days)
                            Log.i(
                                TAG,
                                "chat-history append agent=$historyAgent " +
                                    "queryLen=${userQuery.length} replyLen=${agentReply.length}"
                            )
                        }
                    }
                }
                // Ring the HUD bell for the completed agent turn (plus any
                // [important]/[notify] marker lines inside the reply), so the
                // result survives in the notification list after the readout.
                runCatching { postAgentBellNotifications(toolName, displayText) }
                if (mediaDirective != null) {
                    // The media player is the output: open it and do NOT also run a
                    // TTS readout — launching audio ends the voice session anyway,
                    // so a parallel readout would fight the media. The directive URL
                    // was already removed from displayText so it isn't spoken.
                    Log.i(
                        TAG,
                        "agent $toolName media directive → opening ${mediaDirective.url.take(140)}"
                    )
                    suppressGeminiOutputUntilMs = 0L
                    // No readout follows on the media path, so nothing else
                    // will clear the dispatch-time gates — drop them here or
                    // Gemini stays muted until the session restarts.
                    agentCallInFlight = false
                    suppressGeminiOutputUntilNextInput = false
                    HudStateBridge.update { it.copy(agentBusy = false) }
                    launchTapBrowserFromService(mediaDirective.url)
                } else {
                    speakAgentReplyViaEngine(displayText)
                }
                return@launch
            }

            // Engine-readout tools (June-12 ctrl46 fix): tools whose result is
            // long VERBATIM text — the send_video_list voice list — are read
            // aloud by the deterministic readout engine, never narrated by
            // Gemini Live. Asking the native-audio model to recite a wall of
            // verbatim YouTube titles reliably degenerates it: it repeats the
            // sentence within the turn (TURN-AUDIT: INTERNAL-REPEAT), rambles
            // ~50s of audio for a 500-char reply, and finally emits a raw
            // control token (<ctrl46>). The TTS engine has no such failure
            // mode. Mirrors the agent-readout flow above, minus the
            // agent-only machinery: no bare-hail, no "Asking…" ticker, no
            // chat-history append, no bell. The visual path ("taplink://"
            // results, display="glasses") is unaffected and falls through.
            if (toolName in ENGINE_READOUT_TOOLS && result.isSuccess &&
                !resultText.startsWith("taplink://")
            ) {
                Log.i(TAG, "engine-readout tool $toolName → reading result via readout engine")
                // Mute Gemini Live for the turn: drop any queued model audio
                // and hold its output until the user speaks again. The
                // readout job extends/clears these gates itself (same
                // lifecycle as agent readouts; agentReadoutActive keeps the
                // next-input release gate shut while the mic hears the TTS).
                suppressGeminiOutputUntilMs =
                    android.os.SystemClock.uptimeMillis() + 30_000L
                runCatching { audioPlayer.release() }
                suppressGeminiOutputUntilNextInput = true
                // Ack the tool call with the list as REFERENCE ONLY, so
                // follow-ups ("play the second one") still work without the
                // model re-reading anything.
                val toolResponse =
                    "[The list below was ALREADY read to the user via the readout " +
                        "voice and shown on the chat card. Do NOT read it aloud, " +
                        "summarize, or repeat it now. Keep it ONLY as reference so " +
                        "you can act on follow-ups, e.g. the user asking to play, " +
                        "open, or hear more about one of the items.]\n\n" + resultText
                if (!callId.startsWith("local-")) {
                    runCatching {
                        liveSession?.sendToolResponse(callId, toolName, toolResponse)
                    }
                }
                // Full verbatim text on the chat card, same as agent replies.
                runCatching { viewModel.appendDirectAssistantResponse(resultText) }
                HudStateBridge.update { it.copy(notification = null) }
                speakAgentReplyViaEngine(resultText)
                return@launch
            }

            maybeOpenTapLinkResult(toolName, resultText)
            val ok = runCatching {
                if (!isSessionEpochCurrent(epoch)) return@runCatching false
                liveSession?.sendToolResponse(callId, toolName, resultText) == true
            }.getOrDefault(false)
            Log.i(TAG, "native tool sendToolResponse returned $ok name=$toolName callId=$callId")
            HudStateBridge.update { it.copy(notification = null) }
        }
    }

    /** Agent tools whose textual reply is read aloud via the selected
     *  readout engine instead of being narrated by Gemini Live. */
    private val AGENT_READOUT_TOOLS =
        setOf("hermes_agent", "tapclaw_agent", "research_topic")

    /** Non-agent tools whose result is long verbatim text and therefore
     *  also read via the readout engine (June-12 ctrl46 fix) — without the
     *  agent machinery (history, bell, ticker). Only applies when the
     *  result isn't a "taplink://" visual hand-off. */
    private val ENGINE_READOUT_TOOLS = setOf("send_video_list")

    /** Timestamp backstop for the dispatch-time suppression: if an agent
     *  turn wedges (no reply, no readout), Gemini's voice comes back after
     *  this long even though the next-input gate never released. Agent
     *  turns (research especially) can legitimately run minutes. */
    private val AGENT_INFLIGHT_SUPPRESS_MS = 240_000L

    private fun noteLiveResponseActivity() {
        lastLiveResponseActivityMs = SystemClock.uptimeMillis()
        conversationalFallbackJob?.cancel()
        conversationalFallbackJob = null
    }

    /**
     * Gemini Live Native Audio can still transcribe the user while failing to
     * close the audio turn, especially on the glasses where the mic stream is
     * continuous and there is ambient speaker/room noise. Tool commands already
     * work from partial transcripts; this fallback is only for ordinary
     * conversation/questions such as "can you hear me?" that would otherwise
     * leave the user in silence. If the model produces any output/tool event
     * first, [noteLiveResponseActivity] cancels the fallback.
     */
    private fun maybeScheduleConversationalTextFallback(
        transcript: String,
        epoch: Long,
        suppress: Boolean
    ) {
        val utterance = transcript.trim()
        if (suppress || utterance.isBlank()) return
        if (!liveSessionReady || liveSession == null) return
        if (!isSessionEpochCurrent(epoch)) return
        if (!shouldForceConversationalTextTurn(utterance)) return

        val scheduledAtMs = SystemClock.uptimeMillis()
        conversationalFallbackJob?.cancel()
        conversationalFallbackJob = scope.launch {
            delay(CONVERSATIONAL_TEXT_FALLBACK_DELAY_MS)
            if (!isSessionEpochCurrent(epoch) || !liveSessionReady) return@launch
            if (agentReadoutActive || pageTextInFlight || visionInFlight || youtubePlaybackPreemptInFlight) {
                return@launch
            }
            if (!latestInputTranscript.trim().equals(utterance, ignoreCase = true)) return@launch
            if (lastLiveResponseActivityMs >= scheduledAtMs) return@launch
            if (lastForcedConversationalTranscript.equals(utterance, ignoreCase = true)) return@launch

            lastForcedConversationalTranscript = utterance
            val ok = runCatching {
                liveSession?.sendClientText(utterance) == true
            }.getOrDefault(false)
            Log.i(TAG, "Forced conversational text turn after silent Live audio turn ok=$ok text='${utterance.take(120)}'")
        }
    }

    private fun shouldForceConversationalTextTurn(transcript: String): Boolean {
        val lower = transcript.lowercase()
            .replace(Regex("\\s+"), " ")
            .trim()
        if (lower.length < 3) return false

        // Leave deterministic media/navigation/browser commands on their
        // existing route. Those commands already work from partial transcripts,
        // and forcing them as text can duplicate playback actions.
        if (COMMANDLIKE_CONVERSATION_FALLBACK_EXCLUSIONS.any { lower.startsWith(it) }) {
            return false
        }
        if ((lower.contains("spotify") || lower.contains("youtube") || lower.contains("tapradio")) &&
            MEDIA_COMMAND_PREFIXES.any { lower.startsWith(it) }
        ) {
            return false
        }
        if (PAGE_TEXT_TRIGGER_PHRASES.any { lower.contains(it) } ||
            VISION_TRIGGER_PHRASES.any { lower.contains(it) } ||
            GOOGLE_TASKS_TERMS.any { lower.contains(it) } ||
            GOOGLE_CALENDAR_TERMS.any { lower.contains(it) } ||
            GOOGLE_NEWS_TERMS.any { lower.contains(it) }
        ) {
            return false
        }

        if (CONVERSATIONAL_FORCE_PHRASES.any { lower.contains(it) }) return true
        if (CONVERSATIONAL_QUESTION_PREFIXES.any { prefix ->
                lower == prefix || lower.startsWith("$prefix ")
            }
        ) {
            return true
        }
        return lower.endsWith("?")
    }

    /**
     * When the user tells an agent (Hermes / TapClaw / OpenClaw) to "look at
     * my screen" (or similar), capture the current browser screen via the
     * existing browser_vision pipeline and fold that textual description into
     * the agent call's `context` argument, so the agent's reply follows
     * through on what's actually displayed. Returns [args] unchanged for
     * non-agent tools, commands that don't reference the screen, or any
     * capture failure (so the command still runs without screen context).
     */
    private suspend fun augmentAgentArgsWithScreenIfRequested(
        toolName: String,
        args: String
    ): String {
        if (toolName != "hermes_agent" && toolName != "tapclaw_agent") return args
        val obj = runCatching { JSONObject(args) }.getOrNull() ?: return args
        val query = obj.optString("query").trim()
            .ifBlank { obj.optString("prompt").trim() }
            .ifBlank { obj.optString("message").trim() }
        if (query.isBlank()) return args
        val lower = query.lowercase()
        if (SCREEN_REFERENCE_PHRASES.none { lower.contains(it) }) return args

        // When the glasses camera is live, the user is looking at the WORLD, so
        // the raw camera frame is what the agent should see — NOT the WebView
        // screenshot (which is just the browser UI with a tiny camera preview).
        // Only an explicit browser/web reference ("this web page", "the
        // website") falls back to the WebView while the camera is on.
        val preferBrowser = queryPrefersBrowserScreen(query)
        val usingCamera = !preferBrowser && cameraActive()
        val source = if (usingCamera) "raw camera feed" else "browser screen"
        Log.i(TAG, "agent $toolName references on-screen content — attaching $source")
        HudStateBridge.update {
            it.copy(notification = if (usingCamera) "Sharing the camera view…" else "Capturing the screen…")
        }

        // Attach the chosen frame as an explicit image the agent digests
        // directly. HermesTool/OpenClawTool prefer image_base64 over their own
        // camera frameProvider, so this is also how we guarantee the RAW camera
        // frame (not the small in-browser preview window) reaches the agent.
        val visionFrame = runCatching { bestVisionFrameBase64(preferBrowser) }
            .getOrNull()?.takeIf { it.isNotBlank() }
        if (!visionFrame.isNullOrBlank()) {
            HudStateBridge.update { it.copy(notification = "Running $toolName…") }
            return runCatching {
                obj.put("image_base64", visionFrame)
                obj.put("include_image", true)
                Log.i(
                    TAG,
                    "screen-share: attached $source (${visionFrame.length} b64 chars) to $toolName"
                )
                obj.toString()
            }.getOrDefault(args)
        }

        // Fallback: raw capture failed — fold a text description into context so
        // the agent still has something to work with.
        Log.w(TAG, "screen-share for $toolName: raw capture failed; falling back to a text description")
        val description = runCatching {
            browserVisionTool.execute(mapOf("question" to query))
        }.getOrNull()?.getOrNull()?.trim()
        HudStateBridge.update { it.copy(notification = "Running $toolName…") }

        if (description.isNullOrBlank()) {
            Log.w(TAG, "screen-share for $toolName: no description captured; running without it")
            return args
        }
        val trimmed = description.take(1800)
        val existingContext = obj.optString("context").trim()
        val mergedContext = buildString {
            if (existingContext.isNotEmpty()) {
                append(existingContext)
                append("\n\n")
            }
            append("[Current screen the user is referring to]:\n")
            append(trimmed)
        }
        return runCatching {
            obj.put("context", mergedContext)
            Log.i(TAG, "screen-share: injected ${trimmed.length} chars of screen context into $toolName")
            obj.toString()
        }.getOrDefault(args)
    }

    /**
     * Synthesize [text] via the selected readout engine (Fish.audio when
     * the companion "Readout Voice" is set to Fish and configured, else
     * Gemini TTS) and play it through the same AudioTrack Gemini Live uses.
     * Chunked so long reports don't exceed the per-call TTS limit. The mic
     * is muted and Gemini's own audio suppressed for the duration so the
     * two voices never overlap and the readout isn't echoed back.
     */
    /**
     * Public entry point used by the chat-history overlay: replay a stored
     * agent reply via the readout engine without engaging Gemini Live. The
     * playback is otherwise identical to [speakAgentReplyViaEngine] — same
     * TTS routing, same chunking, same AudioTrack.
     *
     * Optional [onComplete] runs after the readout job finishes (success or
     * failure). The Service binder uses this to chain into activateVoice()
     * so the user can ask follow-up questions about the loaded conversation
     * — without it, the avatar would stay frozen in THINKING (the green
     * output-mode ring) after the spoken playback ended, because no live
     * session was ever bound.
     *
     * No Gemini session is needed for the readout itself; `activeSessionEpoch`
     * may be stale or zero, and the LISTENING restore at the end of the
     * readout job is gated on `liveSessionReady` so it's a no-op when
     * nothing's bound.
     */
    fun speakAgentReplyFromHistory(
        text: String,
        onComplete: (() -> Unit)? = null
    ) {
        val cleaned = text.trim()
        if (cleaned.isBlank()) {
            // Nothing to read — still fire the callback so the caller can
            // hand off to activateVoice() and the avatar doesn't hang.
            onComplete?.invoke()
            return
        }
        Log.i(TAG, "speakAgentReplyFromHistory len=${cleaned.length}")
        // Cancel any in-flight readout so we don't queue on top of one.
        runCatching { readoutJob?.cancel() }
        readoutCompletionCallback = onComplete
        // Park the avatar in IDLE for the duration of the readout so it
        // doesn't sit in the previous THINKING/LISTENING phase — the readout
        // path will move it to THINKING (model output) once playback starts,
        // and the completion callback will fire activateVoice() to take it
        // into LISTENING.
        HudStateBridge.update { it.copy(phase = HudStateBridge.VoicePhase.IDLE) }
        speakAgentReplyViaEngine(cleaned)
    }

    /** One-shot callback fired after the next readout completes. Set by
     *  [speakAgentReplyFromHistory] and consumed by the readout job's
     *  finally block. Null means no chained action. */
    @Volatile private var readoutCompletionCallback: (() -> Unit)? = null

    private fun speakAgentReplyViaEngine(rawText: String) {
        val text = cleanReadoutText(rawText)
        if (text.isBlank()) {
            suppressGeminiOutputUntilMs = 0L
            HudStateBridge.update { it.copy(agentBusy = false) }
            return
        }
        val epoch = activeSessionEpoch
        agentReadoutActive = true
        // The reply is here and its readout is starting — the in-flight
        // phase is over. agentReadoutActive now holds the release gate.
        agentCallInFlight = false
        readoutJob = scope.launch {
            var playedAny = false
            try {
                val chunks = chunkForReadout(text, 1600)
                for (chunk in chunks) {
                    if (!isActive || !isSessionEpochCurrent(epoch)) break
                    if (chunk.isBlank()) continue
                    val pcm = synthesizeRoutedToPcm(chunk) ?: continue
                    if (!isActive || !isSessionEpochCurrent(epoch)) break
                    // Keep Gemini's audio suppressed across the whole read.
                    suppressGeminiOutputUntilMs =
                        android.os.SystemClock.uptimeMillis() + 60_000L
                    HudStateBridge.update {
                        it.copy(
                            phase = HudStateBridge.VoicePhase.THINKING,
                            oscilloscopeLevel = 0.6f,
                            oscilloscopeChannel = HudStateBridge.OscilloscopeChannel.MODEL
                        )
                    }
                    runCatching {
                        audioPlayer.playChunk(
                            "audio/pcm;rate=${pcm.second}", pcm.first,
                            muted = false, volume = 1f
                        )
                    }.onSuccess { playedAny = true }
                }
                if (!playedAny && isSessionEpochCurrent(epoch)) {
                    // Both engines yielded no playable audio. Don't fail
                    // silently — tell the user so a misconfigured TTS engine
                    // is visible instead of looking like a dead assistant.
                    Log.w(TAG, "agent readout produced no audio (TTS engine unavailable)")
                    HudStateBridge.update {
                        it.copy(notification = "Readout voice unavailable — check TTS settings")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "agent readout failed: ${e.message}", e)
            } finally {
                agentReadoutActive = false
                // Agent work is done — clear the busy flag so the persistent
                // status-line poll can resume showing idle reachability.
                HudStateBridge.update { it.copy(agentBusy = false) }
                // Keep Gemini's audio dropped while the AudioTrack drains
                // the queued Hermes/TapClaw chunks. Previously this cleared
                // suppressGeminiOutputUntilMs to 0L immediately, opening a
                // window where Gemini's voice would overlap the still-
                // audible agent readout. AGENT_READOUT_DRAIN_MS is short
                // enough that follow-ups feel responsive, long enough to
                // cover typical AudioTrack buffering on the X3.
                suppressGeminiOutputUntilMs =
                    android.os.SystemClock.uptimeMillis() + AGENT_READOUT_DRAIN_MS
                if (liveSessionReady && isSessionEpochCurrent(epoch)) {
                    HudStateBridge.update {
                        it.copy(
                            phase = HudStateBridge.VoicePhase.LISTENING,
                            oscilloscopeLevel = 0f
                        )
                    }
                }
                // Hand off to whatever was waiting on this readout (e.g. the
                // chat-history overlay's "play it back then start listening"
                // chain). Snapshot + clear under volatile so re-entry from a
                // brand-new readout doesn't double-fire the same callback.
                val cb = readoutCompletionCallback
                readoutCompletionCallback = null
                if (cb != null) {
                    runCatching { cb() }.onFailure {
                        Log.w(TAG, "readout completion callback failed: ${it.message}", it)
                    }
                }
            }
        }
    }

    /** Route one chunk through Fish or Gemini TTS and return PCM + sample
     *  rate the [GeminiAudioPlayer] can stream, or null on failure. */
    private fun synthesizeRoutedToPcm(text: String): Pair<ByteArray, Int>? {
        val prefs = appContext.getSharedPreferences("visionclaw_prefs", Context.MODE_PRIVATE)
        val engine = (prefs.getString("readout_engine", "gemini") ?: "gemini").trim()
        if (engine == "fish" && com.TapLink.app.media.isFishReadoutReady(appContext)) {
            val fish = runCatching { fishReadoutTtsClient.synthesize(text) }.getOrNull()
            if (fish is com.TapLink.app.media.FishTtsClient.SynthesisResult.Success) {
                stripWavHeaderToPcm(fish.wavBytes)?.let { return it }
            }
            Log.w(TAG, "Fish readout unavailable for chunk — falling back to Gemini TTS")
        }
        val gem = runCatching { geminiReadoutTtsClient.synthesize(text, null) }.getOrNull()
        if (gem is com.TapLink.app.media.GlassesTtsClient.SynthesisResult.Success) {
            return stripWavHeaderToPcm(gem.wavBytes)
        }
        return null
    }

    /** Split [text] into <= [maxChars] pieces at sentence / whitespace
     *  boundaries so each fits the per-call TTS cap. */
    private fun chunkForReadout(text: String, maxChars: Int): List<String> {
        val trimmed = text.trim()
        if (trimmed.length <= maxChars) return listOf(trimmed)
        val out = ArrayList<String>()
        val sentences = trimmed.split(Regex("(?<=[.!?])\\s+"))
        val sb = StringBuilder()
        for (s in sentences) {
            if (sb.isNotEmpty() && sb.length + s.length + 1 > maxChars) {
                out.add(sb.toString().trim()); sb.setLength(0)
            }
            if (s.length > maxChars) {
                // A single mega-sentence: hard-split on whitespace.
                var rest = s
                while (rest.length > maxChars) {
                    val cut = rest.lastIndexOf(' ', maxChars).takeIf { it > 0 } ?: maxChars
                    out.add(rest.substring(0, cut).trim())
                    rest = rest.substring(cut).trim()
                }
                if (rest.isNotBlank()) sb.append(rest).append(' ')
            } else {
                sb.append(s).append(' ')
            }
        }
        if (sb.isNotBlank()) out.add(sb.toString().trim())
        return out.filter { it.isNotBlank() }
    }

    /** Light markdown / URL cleanup so the synthesized speech doesn't read
     *  out asterisks, backticks, or raw URLs. */
    private fun cleanReadoutText(text: String): String {
        return try {
            text.replace("\r\n", "\n")
                .replace(Regex("(?s)```.*?```"), " ")
                .replace(Regex("(?:https?://|www\\.)[^\\s<>\"']+", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\*\\*([^*\\n]+?)\\*\\*"), "$1")
                .replace(Regex("\\*([^*\\n]+?)\\*"), "$1")
                .replace(Regex("[*_`#>|~]+"), " ")
                .replace(Regex("[ \\t]+"), " ")
                .replace(Regex("\\s*\\n\\s*\\n\\s*"), "\n\n")
                .trim()
        } catch (e: Exception) {
            text.trim()
        }
    }

    /** Parse a WAV byte array into raw PCM + sample rate (ported from the
     *  visionclaw readout pipeline; tolerates Fish.audio streaming-size
     *  sentinels). Returns null if it isn't parseable PCM WAV. */
    private fun stripWavHeaderToPcm(wav: ByteArray): Pair<ByteArray, Int>? {
        if (wav.size < 44) return null
        if (wav[0] != 'R'.code.toByte() || wav[1] != 'I'.code.toByte() ||
            wav[2] != 'F'.code.toByte() || wav[3] != 'F'.code.toByte()
        ) return null
        if (wav[8] != 'W'.code.toByte() || wav[9] != 'A'.code.toByte() ||
            wav[10] != 'V'.code.toByte() || wav[11] != 'E'.code.toByte()
        ) return null
        fun u32(off: Int): Long =
            (wav[off].toLong() and 0xFFL) or
                ((wav[off + 1].toLong() and 0xFFL) shl 8) or
                ((wav[off + 2].toLong() and 0xFFL) shl 16) or
                ((wav[off + 3].toLong() and 0xFFL) shl 24)
        fun isStreamingSizeSentinel(sz: Long): Boolean = sz >= 0xFFFFFF00L || sz < 0L
        var sampleRate = 0
        var pos = 12
        while (pos + 8 <= wav.size) {
            val id = String(wav, pos, 4, Charsets.US_ASCII)
            val rawSz = u32(pos + 4)
            val sz: Long =
                if (isStreamingSizeSentinel(rawSz)) (wav.size - (pos + 8)).toLong().coerceAtLeast(0L)
                else rawSz.coerceAtLeast(0L)
            when (id) {
                "fmt " -> if (pos + 12 + 4 <= wav.size) sampleRate = u32(pos + 12).toInt()
                "data" -> {
                    val dataStart = pos + 8
                    val dataEnd = (dataStart.toLong() + sz)
                        .coerceAtMost(wav.size.toLong())
                        .coerceAtLeast(dataStart.toLong())
                        .toInt()
                    if (sampleRate <= 0) return null
                    return wav.copyOfRange(dataStart, dataEnd) to sampleRate
                }
            }
            val advance = sz + (sz and 1L)
            val nextPos = (pos.toLong() + 8L + advance).coerceAtMost(wav.size.toLong()).toInt()
            if (nextPos <= pos) return null
            pos = nextPos
        }
        return null
    }

    /**
     * Phase 4b — run browser_vision off the listener thread.
     * Phase 4c (codex) — added end-to-end instrumentation so we can
     * prove the screenshot is real and that the tool response gets
     * back to Gemini. Logs ["browser_vision trigger source=..."],
     * ["BrowserVisionTool result success/failure"], and
     * ["sendToolResponse returned true/false"].
     */
    private fun dispatchBrowserVision(callId: String, name: String, args: String) {
        // June-12 double-response fix: the local-sniff path
        // (maybeTriggerBrowserVisionLocally) and THIS tool-call path both
        // analyze the screen for the same "what's on screen?" utterance.
        // The local path runs first (off the partial transcript), delivers
        // the answer, and tells Gemini to speak it — then Gemini's own
        // browser_vision tool call lands here and used to capture + narrate
        // AGAIN. If a vision answer was just delivered (or one is in
        // flight), don't re-capture: ack the call with a reference-only
        // note so the Live session isn't left waiting, and let the already-
        // spoken answer stand.
        val sinceDelivered =
            System.currentTimeMillis() - lastVisionDeliveredAtMs
        if (visionInFlight || sinceDelivered < VISION_DEDUPE_WINDOW_MS) {
            Log.i(
                TAG,
                "browser_vision toolCall suppressed (dedupe; inFlight=$visionInFlight " +
                    "sinceDelivered=${sinceDelivered}ms) callId=$callId"
            )
            scope.launch {
                runCatching {
                    liveSession?.sendToolResponse(
                        callId,
                        name,
                        "[The screen was just analyzed and the answer was already " +
                            "given to the user. Do NOT analyze again or repeat it.]"
                    )
                }
            }
            return
        }
        Log.i(TAG, "browser_vision trigger source=toolCall callId=$callId")
        visionInFlight = true
        scope.launch {
            try {
                val question = parseQuestionArg(args)
                runBrowserVisionAndDeliver(
                    question = question,
                    callId = callId,
                    toolName = name
                )
            } finally {
                visionInFlight = false
            }
        }
    }

    /**
     * Phase 4c (codex) — local-regex fallback for the case where
     * Gemini Live doesn't pick the browser_vision tool itself.
     *
     * Sniffs the partial input transcript for vision-intent phrases.
     * Debounces with [MIN_LOCAL_VISION_INTERVAL_MS] (so a single
     * spoken utterance only fires once even though
     * onInputTranscription emits multiple partials) plus an in-flight
     * guard ([visionInFlight]) so we don't stack overlapping
     * captures.
     *
     * When triggered, runs the tool directly and injects the result
     * back into the Live session via [GeminiRouter.LiveSessionHandle
     * .sendClientText] — there's no Gemini-issued callId for this
     * path, so sendToolResponse isn't applicable. The injected text
     * tells Gemini what the screen says and instructs it to answer
     * the user briefly out loud.
     */
    private fun maybeTriggerBrowserVisionLocally(transcript: String, epoch: Long) {
        val now = System.currentTimeMillis()
        if (visionInFlight) return
        if (now - lastLocalVisionTriggerMs < MIN_LOCAL_VISION_INTERVAL_MS) return
        if (liveSession == null || !isSessionEpochCurrent(epoch)) return
        val lower = transcript.lowercase().trim()
        val matched = VISION_TRIGGER_PHRASES.any { phrase -> lower.contains(phrase) }
        if (!matched) return

        lastLocalVisionTriggerMs = now
        visionInFlight = true
        Log.i(TAG, "browser_vision trigger source=localRegex transcript='${transcript.take(80)}'")
        // Phase 4e — auto-start CameraX so Gemini also has real-world
        // visual context for the rest of this voice session. Service-
        // side toggle is idempotent: a second call is a no-op while
        // the camera is already streaming. Red dot in the HUD
        // reflects this via CameraStateBridge.
        runCatching { autoCameraEnabler?.invoke() }
        scope.launch {
            try {
                if (!isSessionEpochCurrent(epoch)) return@launch
                runBrowserVisionAndDeliver(
                    question = transcript.ifBlank { DEFAULT_VISION_QUESTION },
                    callId = null,
                    toolName = null
                )
            } finally {
                visionInFlight = false
            }
        }
    }

    /**
     * Local fallback for full web-page reading. Gemini Live sometimes
     * hears "read this page" and fails to choose a tool; if we let the
     * old vision fallback run, it only OCRs the visible viewport. This
     * path extracts DOM/body text from the WebView and injects it back
     * into the Live session as page context.
     */
    private fun maybeTriggerBrowserPageTextLocally(transcript: String, epoch: Long): Boolean {
        val now = System.currentTimeMillis()
        if (pageTextInFlight) return true
        if (now - lastLocalVisionTriggerMs < MIN_LOCAL_VISION_INTERVAL_MS) return false
        if (liveSession == null || !isSessionEpochCurrent(epoch)) return false
        val lower = transcript.lowercase().trim()
        val matched = PAGE_TEXT_TRIGGER_PHRASES.any { phrase -> lower.contains(phrase) }
        if (!matched) return false

        // Camera live + no explicit web reference → "read the page" means the
        // physical page in front of the user (e.g. a book), NOT the browser DOM.
        // Bail so the camera vision path / live stream answers instead of
        // reading the WebView's text.
        if (cameraActive() && !queryPrefersBrowserScreen(lower)) {
            Log.i(TAG, "browser_page_text suppressed: camera live, '${lower.take(60)}' refers to the physical page")
            return false
        }

        lastLocalVisionTriggerMs = now
        pageTextInFlight = true
        Log.i(TAG, "browser_page_text trigger source=localRegex transcript='${transcript.take(80)}'")
        HudStateBridge.update { it.copy(notification = "Reading the full page…") }
        scope.launch {
            try {
                if (!isSessionEpochCurrent(epoch)) return@launch
                val result = toolDispatcher.dispatch(
                    "browser_page_text",
                    org.json.JSONObject().put("max_chars", "200000").toString()
                )
                val responseText = result.getOrElse { err ->
                    Log.w(TAG, "BrowserPageTextTool failure: ${err.message}")
                    "I couldn't read the full page: ${err.message ?: "unknown error"}"
                }
                HudStateBridge.update { it.copy(notification = null) }
                val injection = "The user asked to read or summarize the full current web page. " +
                    "Here is the page text extracted from the browser DOM, not a screenshot:\n\n" +
                    responseText + "\n\nAnswer the user's request from this full page text in a spoken-friendly way."
                val ok = runCatching {
                    liveSession?.sendClientText(injection) == true
                }.getOrDefault(false)
                Log.i(TAG, "sendClientText (browser_page_text localRegex fallback) returned $ok")
            } finally {
                pageTextInFlight = false
            }
        }
        return true
    }

    /**
     * Hermes-era guardrail for voice mode: "play <keyword> on YouTube" is a
     * command, not a question. Preempt Gemini Live as soon as the transcript is
     * clear, launch TapBrowser's deterministic autoplay queue, and tear down the
     * voice session before Gemini can ask clarification questions or keep the
     * audio path.
     */
    /**
     * Local "note that …" capture. Saves the dictated note to the on-glasses
     * notes file ([NotesStore]) and has Gemini just confirm — no agent
     * round-trip, works offline. Gated by the note_capture_mode pref (default
     * "builtin"); "hermes"/"off" fall through to normal routing so the user can
     * pick the detector. In-flight guarded so one utterance saves once.
     */
    private fun maybeCaptureNoteLocally(transcript: String, epoch: Long): Boolean {
        if (noteCaptureInFlight) return true
        if (AppPreferences(appContext).noteCaptureMode != "builtin") return false
        if (liveSession == null || !isSessionEpochCurrent(epoch)) return false
        val note = AssistantIntentParser.parseNoteRequest(transcript) ?: return false

        noteCaptureInFlight = true
        Log.i(TAG, "note capture (localRegex): '${note.take(80)}'")
        HudStateBridge.update { it.copy(notification = "Saving note…") }
        scope.launch {
            try {
                if (!isSessionEpochCurrent(epoch)) return@launch
                val saved = com.rayneo.visionclaw.core.storage.NotesStore.appendNote(appContext, note)
                HudStateBridge.update { it.copy(notification = if (saved != null) "Note saved" else null) }
                val injection = if (saved != null) {
                    "[NOTE SAVED] The user's note was saved to their glasses notes (" +
                        com.rayneo.visionclaw.core.storage.NotesStore.NOTES_RELATIVE_PATH +
                        "): \"" + note + "\". Confirm in ONE short sentence (e.g. 'Noted.') and nothing else."
                } else {
                    "[NOTE SAVE FAILED] Briefly tell the user you couldn't save the note and to try again."
                }
                runCatching { liveSession?.sendClientText(injection) }
            } finally {
                noteCaptureInFlight = false
            }
        }
        return true
    }

    /**
     * Deterministic "what song is this?" for TapRadio. Gemini keeps mis-routing
     * this to spotify_player (whose declaration also claims the phrase) and
     * then deflecting with "it's not on Spotify." So when TapRadio is the LIVE
     * source ([NowPlayingBridge.isPlaying]) and the user asks to ID the song,
     * we run identify_song ourselves and inject the answer — Spotify never
     * enters the picture. If the user explicitly says "spotify", or TapRadio
     * isn't playing, we fall through and let Gemini route normally.
     */
    /**
     * Hail-and-wait router (June-11 round 2). Armed when a bare agent hail
     * ("Hermes") was dispatched and acked; the user's next finished
     * utterance inside the window goes straight to that agent as a
     * locally-triggered dispatch ("local-" callId → no sendToolResponse).
     * Debounced per input chunk: fires ~1.5s after the user stops talking,
     * needs ≥4 words, and refuses to run off readout echo (readout/in-flight
     * guards) — the gap-reset in the buffer separates echo from speech.
     */
    private fun maybeDispatchAgentFollowup() {
        val tool = agentFollowupTool ?: return
        if (android.os.SystemClock.uptimeMillis() > agentFollowupUntilMs) {
            agentFollowupTool = null
            return
        }
        if (agentCallInFlight || agentReadoutActive) return
        agentFollowupJob?.cancel()
        agentFollowupJob = scope.launch {
            kotlinx.coroutines.delay(1_500L)
            if (agentFollowupTool == null) return@launch
            if (agentCallInFlight || agentReadoutActive) return@launch
            if (android.os.SystemClock.uptimeMillis() - lastInputChunkAtMs < 1_400L) {
                return@launch // still talking — the next chunk reschedules us
            }
            val q = utteranceBuffer.trim()
            if (q.split(Regex("\\s+")).count { it.isNotBlank() } < 4) return@launch
            agentFollowupTool = null
            Log.i(TAG, "hail follow-up → routing utterance to $tool: '${q.take(140)}'")
            val argsJson = org.json.JSONObject().put("query", q).toString()
            dispatchNativeTool(
                "local-followup-${System.nanoTime()}", tool, argsJson, activeSessionEpoch
            )
        }
    }

    private fun maybeIdentifySongLocally(transcript: String, epoch: Long): Boolean {
        if (identifySongInFlight) return true
        if (liveSession == null || !isSessionEpochCurrent(epoch)) return false
        if (transcript.contains("spotify", ignoreCase = true)) return false
        if (!AssistantIntentParser.isIdentifySongRequest(transcript)) return false
        if (!com.TapLink.app.unipanel.NowPlayingBridge.isPlaying) return false // not TapRadio → let Gemini route
        val normalized = normalizeLocalIntentTranscript(transcript)
        val now = SystemClock.elapsedRealtime()
        if (
            normalized.isNotBlank() &&
            normalized == lastIdentifySongTranscript &&
            now - lastIdentifySongTriggerMs < 15_000L
        ) {
            Log.i(TAG, "identify_song duplicate partial suppressed: '${transcript.take(80)}'")
            return true
        }

        identifySongInFlight = true
        lastIdentifySongTriggerMs = now
        lastIdentifySongTranscript = normalized
        Log.i(TAG, "identify_song (localRegex, TapRadio live)")
        HudStateBridge.update { it.copy(notification = "Identifying song…") }
        scope.launch {
            try {
                if (!isSessionEpochCurrent(epoch)) return@launch
                val result = toolDispatcher.dispatch("identify_song", "{}")
                val answer = result.getOrElse { "I couldn't identify the track right now." }
                HudStateBridge.update { it.copy(notification = null) }
                val injection = "[TOOL RESULT — identify_song]: $answer Read this answer to the user " +
                    "verbatim-ish. Do NOT mention Spotify or say it isn't on Spotify — this is the TapRadio " +
                    "song identifier."
                runCatching { liveSession?.sendClientText(injection) }
            } finally {
                identifySongInFlight = false
            }
        }
        return true
    }

    private fun normalizeLocalIntentTranscript(transcript: String): String =
        transcript
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")

    private fun maybePreemptYouTubePlaybackLocally(transcript: String, epoch: Long): Boolean {
        if (youtubePlaybackPreemptInFlight) return true
        if (!isSessionEpochCurrent(epoch)) return false
        val now = System.currentTimeMillis()
        if (now - lastYouTubePlaybackPreemptMs < 3000L) return false
        val parsedSpec = AssistantIntentParser.parseExplicitYouTubePlaybackRequest(transcript) ?: return false
        val spec = resolveYouTubePronounSpec(parsedSpec) ?: parsedSpec
        val first = spec.items.firstOrNull()?.trim().orEmpty()
        if (first.isBlank()) return false

        youtubePlaybackPreemptInFlight = true
        lastYouTubePlaybackPreemptMs = now
        latestInputTranscript = transcript
        suppressGeminiOutputUntilMs = android.os.SystemClock.uptimeMillis() + 10_000L

        val url = buildYouTubeAutoplaySearchUrl(first, spec.mode)
        Log.i(
            TAG,
            "YouTube playback preempt: transcript='${transcript.take(100)}' " +
                "query='$first' mode=${spec.mode} queue=${spec.items.size} url=$url"
        )
        val msg = if (spec.items.size > 1) {
            "Queuing YouTube videos for $first."
        } else {
            "Playing $first on YouTube."
        }
        runCatching { viewModel.appendUserUtterance(transcript) }
        runCatching { viewModel.appendDirectAssistantResponse("$msg Captions are enabled.") }
        HudStateBridge.update {
            it.copy(
                transcript = transcript,
                notification = msg,
                phase = HudStateBridge.VoicePhase.IDLE
            )
        }

        scope.launch {
            launchTapBrowserFromService(url, forcedYouTubeSpec = spec)
        }
        return true
    }

    private fun resolveYouTubePronounSpec(
        spec: AssistantIntentParser.YouTubePlaybackSpec
    ): AssistantIntentParser.YouTubePlaybackSpec? {
        val first = spec.items.firstOrNull()?.trim().orEmpty()
        if (!isPronounYouTubeTarget(first)) return null
        val resolved = currentTapRadioTrackForSearch() ?: return null
        return spec.copy(items = listOf(resolved) + spec.items.drop(1))
    }

    private fun isPronounYouTubeTarget(value: String): Boolean {
        val normalized = normalizeLocalIntentTranscript(value)
        return normalized in setOf(
            "it",
            "that",
            "this",
            "that song",
            "this song",
            "the song",
            "that track",
            "this track",
            "the track",
            "current song",
            "current track"
        )
    }

    private fun currentTapRadioTrackForSearch(): String? {
        val bridge = com.TapLink.app.unipanel.NowPlayingBridge
        val artist = bridge.trackArtist?.trim().orEmpty()
        val title = bridge.trackName?.trim().orEmpty()
        val raw = bridge.trackTitle?.trim().orEmpty()
        val candidate = when {
            artist.isNotBlank() && title.isNotBlank() -> "$artist - $title"
            raw.isNotBlank() -> raw
            else -> ""
        }.trim()
        return candidate.takeIf { it.isNotBlank() }
    }

    /**
     * Deterministic "reader mode" trigger. When the user asks Gemini to
     * render the shown page in reader mode, sniff the input transcript for
     * reader-mode phrases and signal the tapbrowser WebView (which lives in
     * the same process) to reflow the current page into a clean, dark,
     * legible reader view. Debounced so a single utterance fires once.
     */
    private fun maybeTriggerReaderModeLocally(transcript: String) {
        val now = System.currentTimeMillis()
        if (now - lastReaderModeTriggerMs < MIN_LOCAL_VISION_INTERVAL_MS) return
        val lower = transcript.lowercase().trim()
        // Check EXIT phrases first — "exit reader mode" also contains the
        // enter phrase "reader mode", so exit must win. Reader mode is
        // sticky: it stays on until the user explicitly asks to exit.
        val wantsExit = READER_MODE_EXIT_PHRASES.any { lower.contains(it) }
        val wantsEnter = !wantsExit && READER_MODE_ENTER_PHRASES.any { lower.contains(it) }
        if (!wantsExit && !wantsEnter) return
        lastReaderModeTriggerMs = now
        Log.i(TAG, "reader_mode trigger source=localRegex enter=$wantsEnter transcript='${transcript.take(80)}'")
        HudStateBridge.update { it.copy(notification = if (wantsEnter) "Reader mode" else null) }
        runCatching { com.TapLink.app.unipanel.BrowserCommandBridge.setReaderMode(wantsEnter) }
        // NOTE: we deliberately do NOT inject a client-text "please confirm"
        // here. That produced a SECOND spoken turn on top of Gemini's own
        // reply to the user, so it said "Reader mode on" twice. Gemini's
        // single confirmation is shaped by RULE ZERO-R in the system prompt
        // (device-handled, exactly one short confirmation, never refuse).
    }

    /**
     * Deterministic web-app trigger for "show/open my tasks/calendar/news".
     * These are display requests, not data-summary requests: open the real
     * Google web app in TapBrowser immediately. Query-style requests ("what
     * are my tasks?", "what's on my calendar?") still flow to Gemini/tools.
     */
    private fun maybeOpenGoogleWebAppLocally(transcript: String) {
        val lower = transcript.lowercase().trim()
        if (lower.isBlank()) return
        val wantsDisplay = GOOGLE_WEB_APP_DISPLAY_VERBS.any { lower.contains(it) }
        if (!wantsDisplay) return

        val target = when {
            GOOGLE_TASKS_TERMS.any { lower.contains(it) } -> GoogleWebAppTarget(
                url = "https://tasks.google.com",
                label = "Google Tasks"
            )
            GOOGLE_CALENDAR_TERMS.any { lower.contains(it) } -> GoogleWebAppTarget(
                url = "https://calendar.google.com",
                label = "Google Calendar"
            )
            GOOGLE_NEWS_TERMS.any { lower.contains(it) } -> GoogleWebAppTarget(
                url = "https://news.google.com",
                label = "Google News"
            )
            else -> null
        } ?: return

        val now = System.currentTimeMillis()
        if (target.url == lastGoogleWebAppLaunchUrl &&
            now - lastGoogleWebAppLaunchMs < MIN_GOOGLE_WEB_APP_LAUNCH_INTERVAL_MS
        ) {
            return
        }
        lastGoogleWebAppLaunchUrl = target.url
        lastGoogleWebAppLaunchMs = now
        Log.i(TAG, "google_web_app trigger url=${target.url} transcript='${transcript.take(80)}'")
        HudStateBridge.update { it.copy(notification = "Opening ${target.label}…") }
        launchTapBrowserFromService(target.url)
    }

    /**
     * Phase 4c — the shared body for both trigger paths. Captures
     * via [captureWebViewBase64Logged] (which logs nonblack pixel
     * count), runs [BrowserVisionTool], and delivers the result.
     *
     * If [callId] is non-null we route via sendToolResponse (the
     * Gemini Live tool-call path). If null we route via
     * sendClientText (the local-regex fallback path) — Gemini reads
     * the injected text on its next turn and synthesises a spoken
     * answer.
     */
    private suspend fun runBrowserVisionAndDeliver(
        question: String,
        callId: String?,
        toolName: String?
    ) {
        HudStateBridge.update {
            it.copy(notification = "Looking at the screen…")
        }
        val result = runCatching {
            browserVisionTool.execute(mapOf("question" to question))
        }
        val responseText = result.getOrNull()?.getOrElse { err ->
            Log.w(TAG, "BrowserVisionTool failure: ${err.message}")
            "I couldn't read the screen: ${err.message ?: "unknown error"}"
        } ?: run {
            val ex = result.exceptionOrNull()
            Log.w(TAG, "BrowserVisionTool threw: ${ex?.message}")
            "I couldn't read the screen: ${ex?.message ?: "unknown error"}"
        }
        Log.i(TAG, "BrowserVisionTool result success=${result.getOrNull()?.isSuccess == true} " +
            "text='${responseText.take(160)}'")
        // The vision model actually answered → the frame reached Gemini. Confirm
        // delivery so the user knows the image landed (only on a real success;
        // failures fall through to the error text without a false "delivered").
        if (result.getOrNull()?.isSuccess == true) {
            CaptureFeedback.delivered("Gemini")
        }
        // Mark delivery for the cross-path dedupe window (see
        // dispatchBrowserVision) — set on BOTH the local and tool-call
        // paths so whichever fires second is suppressed.
        lastVisionDeliveredAtMs = System.currentTimeMillis()
        HudStateBridge.update { it.copy(notification = null) }

        if (callId != null && toolName != null) {
            val ok = runCatching {
                liveSession?.sendToolResponse(callId, toolName, responseText) == true
            }.getOrDefault(false)
            Log.i(TAG, "sendToolResponse returned $ok (callId=$callId)")
        } else {
            val injection = "The user just asked about what's on screen. " +
                "Vision tool result: $responseText. " +
                "Answer the user's question briefly out loud based on that."
            val ok = runCatching {
                liveSession?.sendClientText(injection) == true
            }.getOrDefault(false)
            Log.i(TAG, "sendClientText (localRegex fallback) returned $ok")
        }
    }

    /**
     * Agent tool completions ring the HUD bell. One "<agent> finished"
     * notification summarizes the completed turn; additionally, any line the
     * agent marked as "[important] …" / "notify: …" / "[notification] …" is
     * posted as its own "<agent> update" entry. Ids are deterministic per
     * marker body (hashCode) so retries don't duplicate, while the summary id
     * is timestamped so every completed turn rings once.
     */
    private fun postAgentBellNotifications(toolName: String, replyText: String) {
        val isHermes = toolName == "hermes_agent"
        val agentLabel = if (isHermes) "Hermes" else "TapClaw"
        val source =
            if (isHermes) NotificationCenter.Source.HERMES
            else NotificationCenter.Source.OPENCLAW
        val summary = replyText.trim().replace('\n', ' ').take(160)
        NotificationCenter.post(
            NotificationCenter.HudNotification(
                id = "${toolName}_done_${System.currentTimeMillis()}",
                source = source,
                title = "$agentLabel finished",
                message = summary.ifBlank { "$agentLabel completed a task." }
            )
        )
        val markerRegex = Regex(
            "(?im)^\\s*(?:\\[(?:important|notify|notification)\\]|" +
                "(?:important|notify|notification):)\\s*(.+)$"
        )
        markerRegex.findAll(replyText).forEach { match ->
            val body = match.groupValues.getOrNull(1)?.trim()?.take(300).orEmpty()
            if (body.isNotEmpty()) {
                NotificationCenter.post(
                    NotificationCenter.HudNotification(
                        id = "${toolName}_update_${body.hashCode()}",
                        source = source,
                        title = "$agentLabel update",
                        message = body
                    )
                )
            }
        }
    }

    /** A media/open directive an agent (Hermes / TapClaw) embedded in its reply. */
    private data class AgentMediaDirective(val url: String, val cleanedText: String)

    /**
     * Scan an agent reply for a media/open directive — a line beginning with
     * `open_taplink:` or `MEDIA:` whose value is an absolute, fetchable URL
     * (http/https or the appassets player host, optionally taplink://-wrapped).
     * Returns the normalized URL plus the reply with that directive line removed
     * (so it is neither spoken nor shown). Returns null when there's no usable
     * directive — e.g. a Hermes server-local `MEDIA:/home/...` path the glasses
     * can't fetch, which is left untouched in the text.
     */
    private fun extractAgentMediaDirective(resultText: String): AgentMediaDirective? {
        if (resultText.isBlank()) return null
        val lines = resultText.split("\n")
        var url: String? = null
        val kept = ArrayList<String>(lines.size)
        for (line in lines) {
            if (url == null) {
                val t = line.trim()
                val lower = t.lowercase()
                if (lower.startsWith("open_taplink:") || lower.startsWith("media:")) {
                    val raw = t.substringAfter(":", "").trim().removePrefix("taplink://").trim()
                    val rawLower = raw.lowercase()
                    val fetchable = rawLower.startsWith("http://") ||
                        rawLower.startsWith("https://") ||
                        rawLower.startsWith("appassets.androidplatform.net")
                    if (fetchable) {
                        val norm = AssistantIntentParser.normalizeTapLinkUrl(raw)
                        if (!norm.isNullOrBlank()) {
                            url = norm
                            continue // drop the directive line from the readable text
                        }
                    }
                }
            }
            kept.add(line)
        }
        val resolved = url ?: return null
        return AgentMediaDirective(resolved, kept.joinToString("\n").trim())
    }

    private fun maybeOpenTapLinkResult(toolName: String, resultText: String) {
        val openUrl = when {
            toolName in setOf("open_taplink", "send_video_list", "send_link_list") ->
                AssistantIntentParser.extractTapLinkUrl(resultText)
            resultText.contains("open_taplink:", ignoreCase = true) ->
                resultText.substringAfter("open_taplink:", "")
                    .substringBefore('\n')
                    .trim()
                    .let { AssistantIntentParser.normalizeTapLinkUrl(it) }
            else -> null
        }?.takeIf { it.isNotBlank() } ?: return

        launchTapBrowserFromService(openUrl)
    }

    private fun launchTapBrowserFromService(
        openUrl: String,
        forcedYouTubeSpec: AssistantIntentParser.YouTubePlaybackSpec? = null
    ) {
        val rawInitialUrl = openUrl.removePrefix("taplink://").trim().ifBlank { return }
        val initialUrl = canonicalizeYouTubeLaunchUrl(rawInitialUrl, forcedYouTubeSpec)
        val intent = Intent().apply {
            // OWN package, not a literal: with the private and public apps
            // installed side-by-side, the old hardcoded
            // "com.rayneo.visionclaw" made the PUBLIC app launch the
            // PRIVATE app's browser for every open-URL / YouTube request.
            component = ComponentName(appContext.packageName, TAPBROWSER_ACTIVITY)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(EXTRA_BROWSER_INITIAL_URL, initialUrl)
        }

        val lower = initialUrl.lowercase()
        if (lower.contains("youtube.com") || lower.contains("youtu.be")) {
            runCatching {
                com.TapLinkX3.app.MainActivity.stopOrphanedNativeRadioPlayer(appContext)
            }.onFailure {
                Log.w(TAG, "Failed to stop TapRadio before YouTube launch: ${it.message}")
            }
            val spec = forcedYouTubeSpec
                ?: AssistantIntentParser.parseExplicitYouTubePlaybackRequest(latestInputTranscript)
            val query = spec?.items?.firstOrNull()?.takeIf { it.isNotBlank() }
                ?: extractYouTubeSearchQuery(initialUrl)
            val mode = spec?.mode
                ?: if (latestInputTranscript.contains("music", ignoreCase = true) ||
                    latestInputTranscript.contains("song", ignoreCase = true)
                ) "music" else "video"
            if (!query.isNullOrBlank()) {
                putYouTubeAutoplayExtras(intent, query, mode, spec?.items.orEmpty())
            }
        }

        Log.i(TAG, "Launching TapBrowser from Service url=${initialUrl.take(180)} raw=${rawInitialUrl.take(120)}")
        runCatching { appContext.startActivity(intent) }
            .onFailure { Log.w(TAG, "TapBrowser launch failed: ${it.message}") }

        // Bug fix — when the launched URL plays audio (radio / YouTube /
        // any stream), the still-active Gemini Live session holds the
        // mic + AudioTrack, so the media can't start. End the voice
        // session so the WebView media gets the audio path (mirrors the
        // hermes branch, where launching media ends the multimodal
        // session). Non-audio pages leave the session running.
        if (isAudioMediaUrl(lower)) {
            Log.i(TAG, "Media URL launched — ending voice session so it can play audio")
            shutdown(reason = null)
        }
    }

    private fun canonicalizeYouTubeLaunchUrl(
        url: String,
        forcedYouTubeSpec: AssistantIntentParser.YouTubePlaybackSpec? = null
    ): String {
        val lower = url.lowercase()
        if (!lower.contains("youtube.com") && !lower.contains("youtu.be")) return url
        if (lower.contains("taplink_autoplay=")) return url

        val spec = forcedYouTubeSpec
            ?: AssistantIntentParser.parseExplicitYouTubePlaybackRequest(latestInputTranscript)
        val query = spec?.items?.firstOrNull()?.takeIf { it.isNotBlank() }
            ?: extractYouTubeSearchQuery(url)
            ?: return url
        val mode = spec?.mode
            ?: if (latestInputTranscript.contains("music", ignoreCase = true) ||
                latestInputTranscript.contains("song", ignoreCase = true)
            ) "music" else "video"
        return buildYouTubeAutoplaySearchUrl(query, mode)
    }

    private fun buildYouTubeAutoplaySearchUrl(query: String, mode: String): String {
        val searchPhrase =
            if (mode == "music" && !Regex("(?i)\\b(?:music|songs?|audio|track)\\b").containsMatchIn(query)) {
                "$query music"
            } else {
                query
            }
        val encoded = java.net.URLEncoder.encode(searchPhrase, "UTF-8")
        return "https://www.youtube.com/results?search_query=$encoded&taplink_autoplay=$mode"
    }

    /** True for URLs that will play audio (radio streams, YouTube, media
     *  files / player), so the voice session must release the audio path. */
    private fun isAudioMediaUrl(lowerUrl: String): Boolean {
        return lowerUrl.contains("youtube.com") ||
            lowerUrl.contains("youtu.be") ||
            lowerUrl.contains("tapradio") ||
            lowerUrl.contains("radio") ||
            lowerUrl.contains("somafm") ||
            lowerUrl.contains("media_player") ||
            lowerUrl.contains("spotify.html") ||
            lowerUrl.contains("/stream") ||
            lowerUrl.contains("stream.") ||
            lowerUrl.contains(".mp3") ||
            lowerUrl.contains(".m3u") ||
            lowerUrl.contains(".aac") ||
            lowerUrl.contains(".pls")
    }

    private fun putYouTubeAutoplayExtras(
        intent: Intent,
        query: String,
        mode: String,
        queue: List<String>
    ) {
        intent.putExtra(EXTRA_YOUTUBE_AUTOPLAY_QUERY, query)
        intent.putExtra(EXTRA_YOUTUBE_AUTOPLAY_MODE, mode)
        if (queue.size > 1) {
            intent.putExtra(EXTRA_YOUTUBE_AUTOPLAY_QUEUE, org.json.JSONArray(queue).toString())
        }
    }

    private fun extractYouTubeSearchQuery(url: String): String? {
        val uri = runCatching { android.net.Uri.parse(url) }.getOrNull() ?: return null
        return sequenceOf(
            uri.getQueryParameter("search_query"),
            uri.getQueryParameter("q")
        ).firstOrNull { !it.isNullOrBlank() }?.trim()
    }

    /**
     * Phase 4c — capture wrapper used as the BrowserVisionTool
     * frame-provider. Logs hasWebView / width / height / base64
     * length / non-black pixel count so we can prove the screenshot
     * isn't blank before it goes over the wire to Gemini.
     */
    /**
     * True when the glasses camera feed is CURRENTLY live — we have a frame and
     * it arrived recently. Frames stream ~1.1s apart, so a 4s window means
     * "still streaming" without being fooled by a stale frame left over after
     * the camera was closed.
     */
    private fun cameraActive(): Boolean {
        val frame = latestCameraFrame
        if (frame.isNullOrBlank()) return false
        return SystemClock.elapsedRealtime() - lastCameraFrameMs < CAMERA_FRESH_WINDOW_MS
    }

    /**
     * The frame any "look at this / what does this say / describe this" request
     * should use. When the camera feed is live, the user is looking at the world
     * through the glasses, so the RAW camera frame is the subject — NOT the
     * browser WebView screenshot (which would only show the UI + a tiny camera
     * preview window). Falls back to the WebView capture when the camera is off,
     * preserving "read this web page" behavior.
     *
     * The one exception is an explicit browser/web reference ("this web page",
     * "the website", "this tab") — there the WebView is genuinely the subject
     * even with the camera on.
     */
    private fun bestVisionFrameBase64(preferBrowser: Boolean = false): String? {
        if (!preferBrowser && cameraActive()) {
            latestCameraFrame?.takeIf { it.isNotBlank() }?.let {
                // The frame is now locked in for the AI — tell the user so they
                // can stop holding the shot steady.
                signalVisionCapture()
                return it
            }
        }
        return captureWebViewBase64Logged()
    }

    @Synchronized
    /**
     * "Image captured" feedback — the moment a camera frame is grabbed for a
     * request, so the user can stop holding the shot steady. Delegates to the
     * shared [CaptureFeedback] so the Service pipeline, Hermes, and OpenClaw all
     * emit the same tone and share one debounce.
     */
    private fun signalVisionCapture() {
        CaptureFeedback.captured()
    }

    private fun queryPrefersBrowserScreen(query: String): Boolean {
        val lower = query.lowercase()
        return BROWSER_REFERENCE_PHRASES.any { lower.contains(it) }
    }

    private fun captureWebViewBase64Logged(): String? {
        val stats = com.TapLink.app.media.BrowserFrameHolder.captureStats()
        val captured = com.TapLink.app.media.BrowserFrameHolder.captureBase64JpegWithStats()
        if (captured == null) {
            Log.w(
                TAG,
                "BrowserFrameHolder: hasWebView=${stats.hasWebView} " +
                    "w=${stats.width} h=${stats.height} base64=null (capture failed)"
            )
            return null
        }
        Log.i(
            TAG,
            "BrowserFrameHolder: hasWebView=${stats.hasWebView} " +
                "w=${captured.width} h=${captured.height} " +
                "base64Len=${captured.base64.length} " +
                "nonBlack=${captured.nonBlackSamples}/${captured.sampledPixels}"
        )
        if (captured.nonBlackSamples * 100 < captured.sampledPixels) {
            Log.w(
                TAG,
                "BrowserFrameHolder: capture looks BLANK (only " +
                    "${captured.nonBlackSamples}/${captured.sampledPixels} non-black). " +
                    "View.draw on a hardware-accelerated WebView may be the cause."
            )
        }
        return captured.base64
    }

    private fun parseQuestionArg(args: String): String {
        val parsed = runCatching { JSONObject(args) }.getOrNull() ?: return DEFAULT_VISION_QUESTION
        val q = parsed.optString("question").trim()
            .ifBlank { parsed.optString("query").trim() }
            .ifBlank { parsed.optString("prompt").trim() }
        return q.ifBlank { DEFAULT_VISION_QUESTION }
    }

    private fun startAudioStreaming(epoch: Long) {
        if (captureActive) return
        if (!isSessionEpochCurrent(epoch)) return
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            Log.w(TAG, "startAudioStreaming: minBufferSize=$minBuffer")
            HudStateBridge.update {
                it.copy(notification = "Microphone buffer could not be created.")
            }
            return
        }
        val bufferSize = maxOf(minBuffer * 2, 4096)
        val recorder = createAudioRecord(bufferSize)
        if (recorder == null) {
            Log.w(TAG, "startAudioStreaming: AudioRecord init failed")
            HudStateBridge.update {
                it.copy(notification = "Microphone could not be opened.")
            }
            return
        }

        audioRecord = recorder
        captureActive = true
        runCatching { recorder.startRecording() }

        audioThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val chunk = ByteArray(2048)
            var loggedFirstFrame = false
            while (captureActive && isSessionEpochCurrent(epoch)) {
                val read = try {
                    recorder.read(chunk, 0, chunk.size)
                } catch (e: Throwable) {
                    Log.w(TAG, "audio read threw: ${e.message}")
                    break
                }
                if (read > 0) {
                    if (!loggedFirstFrame) {
                        loggedFirstFrame = true
                        Log.d(TAG, "First mic frame: $read bytes")
                    }
                    val peak = calculatePcm16Peak(chunk, read)
                    val norm = (peak / 32_767f).coerceIn(0f, 1f)
                    // Throttle HUD updates — the read loop is much faster
                    // than the overlay can usefully draw.
                    //
                    // Phase 4k — only drive the USER (red) oscilloscope
                    // while we're actually in the LISTENING phase. The mic
                    // keeps streaming during Gemini's reply (for barge-in),
                    // so without this gate the red mic level would clobber
                    // the blue MODEL level published from onModelAudio and
                    // the orb would flicker red/blue while Gemini speaks.
                    if (HudStateBridge.current().phase ==
                            HudStateBridge.VoicePhase.LISTENING &&
                        (norm > 0.04f || (System.currentTimeMillis() % 8L == 0L))
                    ) {
                        HudStateBridge.update {
                            it.copy(
                                oscilloscopeLevel = norm,
                                oscilloscopeChannel = HudStateBridge.OscilloscopeChannel.USER
                            )
                        }
                    }
                    // Don't stream the mic to Gemini while an agent reply is
                    // being read aloud via the readout engine — otherwise the
                    // speaker audio echoes back in and Gemini reacts to its
                    // own readout as a new user turn.
                    if (!agentReadoutActive && isSessionEpochCurrent(epoch)) {
                        runCatching {
                            liveSession?.sendAudioChunkPcm16(chunk, read, SAMPLE_RATE_HZ)
                        }
                    }
                } else if (read < 0) {
                    Log.w(TAG, "audio read error code=$read")
                }
            }
            Log.d(TAG, "Audio thread exiting")
        }, "GeminiVoicePipelineAudioThread").apply {
            isDaemon = true
            start()
        }
    }

    private fun createAudioRecord(bufferSize: Int): AudioRecord? {
        val sources = intArrayOf(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.MIC
        )
        for (source in sources) {
            val rec = runCatching {
                AudioRecord(
                    source,
                    SAMPLE_RATE_HZ,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )
            }.getOrNull() ?: continue
            if (rec.state == AudioRecord.STATE_INITIALIZED) {
                Log.d(
                    TAG,
                    "AudioRecord opened with source=" +
                        if (source == MediaRecorder.AudioSource.VOICE_COMMUNICATION) "VOICE_COMM" else "MIC"
                )
                return rec
            }
            runCatching { rec.release() }
        }
        return null
    }

    private fun calculatePcm16Peak(data: ByteArray, size: Int): Int {
        var peak = 0
        var i = 0
        val limit = size - 1
        while (i < limit) {
            val lo = data[i].toInt() and 0xFF
            val hi = data[i + 1].toInt() shl 8
            val sample = (hi or lo).toShort().toInt()
            val abs = if (sample < 0) -sample else sample
            if (abs > peak) peak = abs
            i += 2
        }
        return peak
    }

    companion object {
        private const val TAG = "GeminiVoicePipe"
        private const val SAMPLE_RATE_HZ = 16_000
        /** How long after the readout coroutine ends to keep Gemini audio
         *  suppressed so the AudioTrack drain doesn't get overlapped by a
         *  late Gemini chunk. 2.5s covers typical X3 AudioTrack buffering
         *  for the last 1–2 chunks while remaining short enough that the
         *  user's next follow-up gets an immediate Gemini response. */
        private const val AGENT_READOUT_DRAIN_MS = 2_500L
        private const val TAPBROWSER_ACTIVITY = "com.TapLinkX3.app.MainActivity"
        private const val EXTRA_BROWSER_INITIAL_URL = "tapclaw_initial_url"
        private const val EXTRA_YOUTUBE_AUTOPLAY_QUERY = "tapclaw_youtube_autoplay_query"
        private const val EXTRA_YOUTUBE_AUTOPLAY_MODE = "tapclaw_youtube_autoplay_mode"
        private const val EXTRA_YOUTUBE_AUTOPLAY_QUEUE = "tapclaw_youtube_autoplay_queue"
        private const val DEFAULT_VISION_QUESTION =
            "Describe what's currently on the screen in plain English."

        /** How recently a camera frame must have arrived for the feed to count
         *  as "live" (frames stream ~1.1s apart). */
        private const val CAMERA_FRESH_WINDOW_MS = 4_000L


        /** Phrases that mean the user UNAMBIGUOUSLY wants the BROWSER screen,
         *  not the camera, even when the camera feed is live. Deliberately
         *  excludes ambiguous words like "the page" / "this page" / "the
         *  article": with the camera open on a book, "read the page" means the
         *  physical page, so ambiguous terms default to the camera. Only
         *  explicit web/browser words force the WebView. */
        private val BROWSER_REFERENCE_PHRASES = listOf(
            "web page", "webpage", "web site", "website", "this site", "the site",
            "this tab", "the tab", "browser", "url", "address bar",
            "this web page", "the web page"
        )

        // 1600 → 2600 (June-11 capture): at 1.6s the fallback repeatedly
        // lost its race with slow-but-alive turns (tool-call turns routinely
        // take >1.6s to first event), firing a duplicate text turn. The
        // audio-side duplicate gate in onModelAudio is the backstop; the
        // longer delay makes the race rare in the first place.
        private const val CONVERSATIONAL_TEXT_FALLBACK_DELAY_MS = 2_600L

        /** Input gap after which the rolling utterance buffer resets. */
        private const val UTTERANCE_GAP_RESET_MS = 2_500L

        /** How long a bare agent hail ("Hermes") holds its dispatch waiting
         *  for the rest of the sentence before going out as a plain hail. */
        private const val BARE_HAIL_WAIT_MS = 9_000L

        /** After a bare hail is answered, the user's next utterance within
         *  this window routes directly to the hailed agent. */
        private const val AGENT_FOLLOWUP_WINDOW_MS = 45_000L

        /** Minimum gap between local-regex browser_vision triggers,
         *  so a single 2-3-second utterance (which emits many partials)
         *  doesn't fire the tool more than once. */
        private const val MIN_LOCAL_VISION_INTERVAL_MS = 3_000L

        /** A browser_vision answer delivered within this window suppresses a
         *  second capture from the other trigger path (local sniff vs
         *  Gemini tool call) for the same screen question. */
        private const val VISION_DEDUPE_WINDOW_MS = 12_000L
        private const val MIN_GOOGLE_WEB_APP_LAUNCH_INTERVAL_MS = 5_000L

        private data class GoogleWebAppTarget(val url: String, val label: String)

        private val GOOGLE_WEB_APP_DISPLAY_VERBS = listOf(
            "show ",
            "show me ",
            "open ",
            "open my ",
            "open the ",
            "display ",
            "display my ",
            "pull up ",
            "pull up my ",
            "bring up ",
            "bring up my ",
            "launch ",
            "go to "
        )

        private val GOOGLE_TASKS_TERMS = listOf(
            "my tasks",
            "tasks",
            "my task list",
            "task list",
            "my reminders",
            "reminders",
            "my todos",
            "my to dos",
            "todo list",
            "to do list"
        )

        private val GOOGLE_CALENDAR_TERMS = listOf(
            "my events",
            "my upcoming events",
            "events on my calendar",
            "calendar events",
            "my calendar",
            "calendar",
            "google calendar"
        )

        private val GOOGLE_NEWS_TERMS = listOf(
            "the news",
            "news",
            "google news",
            "headlines",
            "top stories"
        )

        private val CONVERSATIONAL_FORCE_PHRASES = listOf(
            "can you hear me",
            "do you hear me",
            "are you there",
            "hello gemini",
            "hey gemini",
            "hello",
            "hi gemini"
        )

        private val CONVERSATIONAL_QUESTION_PREFIXES = listOf(
            "can",
            "could",
            "would",
            "what",
            "when",
            "where",
            "who",
            "why",
            "how",
            "do",
            "does",
            "did",
            "is",
            "are",
            "am",
            "tell me",
            "explain",
            "answer"
        )

        private val COMMANDLIKE_CONVERSATION_FALLBACK_EXCLUSIONS = listOf(
            "play ",
            "pause",
            "resume",
            "stop",
            "skip",
            "next",
            "previous",
            "open ",
            "show ",
            "display ",
            "pull up ",
            "bring up ",
            "launch ",
            "go to ",
            "read ",
            "look at ",
            "describe ",
            "summarize ",
            "summarise ",
            "take a picture",
            "take photo",
            "record video",
            "hermes ",
            "tapclaw ",
            "openclaw "
        )

        private val MEDIA_COMMAND_PREFIXES = listOf(
            "play ",
            "search ",
            "find ",
            "queue ",
            "start ",
            "put on "
        )

        /** Phrases that locally trigger browser_vision when Gemini
         *  Live doesn't elect to call the tool itself. All matched
         *  case-insensitively as substrings, so "what does this say
         *  here" / "could you look at this" / etc. also match. */
        private val VISION_TRIGGER_PHRASES = listOf(
            "look at this",
            "what does this say",
            "what's on screen",
            "what is on screen",
            "what's on the screen",
            "what is on the screen",
            "read this",
            "read the screen",
            "summarize this page",
            "summarise this page",
            "summarize the screen",
            "summarise the screen",
            "describe the screen",
            "describe this page",
            "what am i looking at"
        )

        /** Whole-page/article text extraction. These must run before
         *  [VISION_TRIGGER_PHRASES], because some phrases overlap with
         *  the old screenshot fallback ("read this page"). */
        private val PAGE_TEXT_TRIGGER_PHRASES = listOf(
            "read the web page",
            "read this web page",
            "read the webpage",
            "read this webpage",
            "read this page",
            "read the page",
            "read the whole page",
            "read the full page",
            "read the entire page",
            "read the article",
            "read this article",
            "read the whole article",
            "summarize this page",
            "summarise this page",
            "summarize the page",
            "summarise the page",
            "summarize the web page",
            "summarise the web page",
            "summarize this article",
            "summarise this article",
            "what does this article say",
            "what does this page say"
        )

        /** Phrases that ENTER the bold dark reader view. */
        private val READER_MODE_ENTER_PHRASES = listOf(
            "reader mode",
            "reading mode",
            "reader view",
            "reading view",
            "render this in reader",
            "render the page in reader",
            "render this page in reader",
            "make this readable",
            "make the page readable",
            "clean up this page",
            "declutter this page",
            "simplify this page"
        )

        /** Phrases that EXIT reader mode and restore the original page.
         *  Checked before the enter phrases (these also contain "reader
         *  mode"), so exit always wins. */
        private val READER_MODE_EXIT_PHRASES = listOf(
            "exit reader",
            "exit reading",
            "leave reader",
            "leave reading",
            "close reader",
            "turn off reader",
            "turn off reading",
            "stop reader",
            "disable reader",
            "normal view",
            "normal mode",
            "back to normal",
            "original page",
            "exit reader mode",
            "exit reading mode"
        )

        /** Visual / media-reference phrases used to decide when an agent command
         *  (hermes_agent / tapclaw_agent) should be augmented with a
         *  browser-vision description of whatever is currently displayed — the
         *  page, an image, a video frame, or any media on the screen. Broader
         *  than [VISION_TRIGGER_PHRASES] because the user phrases these as
         *  instructions to the agent ("hermes, look at this image and …",
         *  "openclaw, digest what's on the screen"). When any of these match, the
         *  on-screen content is captured and folded into the agent's context so
         *  the agent digests what the user is actually looking at. (The real-world
         *  CAMERA frame is attached separately by the agent tools' own vision
         *  heuristic — isVisionQuery / EXPLICIT_CAMERA_QUERY.) */
        private val SCREEN_REFERENCE_PHRASES = listOf(
            // Screen / page
            "my screen",
            "the screen",
            "on screen",
            "on the screen",
            "this screen",
            "see my screen",
            "see the screen",
            "look at my screen",
            "look at the screen",
            "looking at my screen",
            "look at this",
            "what's on screen",
            "what is on screen",
            "what's on the screen",
            "what is on the screen",
            "this page",
            "on this page",
            "read the screen",
            "read this page",
            "describe the screen",
            "what am i looking at",
            "on my display",
            // Generic media / content
            "what's displayed",
            "what is displayed",
            "what's shown",
            "what is shown",
            "this content",
            "this media",
            "digest this",
            "digest the",
            "digest what",
            // Images
            "this image",
            "the image",
            "this picture",
            "the picture",
            "this photo",
            "the photo",
            "look at the image",
            "read this image",
            // Video
            "this video",
            "the video",
            "this clip",
            "what's playing",
            "what is playing",
            // Vision cues that also apply to on-screen media
            "what do you see",
            "what you see",
            "see this",
            "describe this",
            "read this",
            "what does this say",
            "what is this",
            "what's this"
        )
    }
}
