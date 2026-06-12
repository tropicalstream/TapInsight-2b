package com.rayneo.visionclaw

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
// android.graphics.Bitmap import removed – no longer needed
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.speech.SpeechRecognizer
import android.util.DisplayMetrics
import android.util.Log
import android.util.Patterns
// Choreographer removed – no longer needed for mirror frame callback
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
// PixelCopy / SurfaceView mirroring removed – BinocularSbsLayout handles SBS
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewpager2.widget.ViewPager2
import com.TapLink.app.media.MediaLibraryService
import com.rayneo.visionclaw.core.assistant.AssistantIntent
import com.rayneo.visionclaw.core.assistant.AssistantIntentParser
import com.rayneo.visionclaw.core.learn.LearnLmMemoryStore
import com.rayneo.visionclaw.core.network.GeminiRouter
import com.rayneo.visionclaw.core.network.LearnLmRouter
import com.rayneo.visionclaw.core.network.GeminiTtsClient
import com.rayneo.visionclaw.core.audio.GeminiAudioPlayer
import com.rayneo.visionclaw.core.audio.TtsController
import com.rayneo.visionclaw.core.camera.FrameCaptureManager
import com.rayneo.visionclaw.core.input.RayNeoArdkTrackpadBridge
import com.rayneo.visionclaw.core.input.SpeechInputController
import com.rayneo.visionclaw.core.input.TrackpadGestureEngine
import com.rayneo.visionclaw.core.location.DeviceLocationResolver
import com.rayneo.visionclaw.core.model.DeviceLocationContext
import com.rayneo.visionclaw.core.model.OpenClawStatusService
import com.rayneo.visionclaw.core.network.ActiveNetworkHttp
import com.rayneo.visionclaw.core.network.RelayUrlHelper
import com.rayneo.visionclaw.core.notifications.NotificationCenter
import com.rayneo.visionclaw.core.notifications.RelayNotifyInbox
import com.rayneo.visionclaw.core.storage.AppPreferences
import com.rayneo.visionclaw.core.storage.ReadableArtifactStore
import com.rayneo.visionclaw.core.tools.ToolDispatcher
import com.rayneo.visionclaw.ui.MainPagerAdapter
import com.rayneo.visionclaw.ui.MainViewModel
import com.rayneo.visionclaw.ui.CustomKeyboardView
import com.rayneo.visionclaw.ui.VoiceOscilloscopeView
import com.rayneo.visionclaw.ui.panels.TrackpadPanel
import com.rayneo.visionclaw.ui.panels.chat.ChatPanelFragment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.max
import java.security.Security
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * MainActivity – entry point for TapInsight on RayNeo X3 Pro.
 *
 * Handles: • XR session / origin null-safety checks • Trackpad gesture engine wiring (short /
 * double tap + swipe) • Edge-zone panel switching (5% left/right edges with 20px center movement) •
 * Speech input & TTS audio output integration • Frame capture for vision-based queries • HUD panel
 * anchoring for the 6 000-nit MicroLED binocular display • API-key-required notification overlay •
 * ViewPager2 hosting Chat HUD (browser launches to original TAPLINKX3 activity)
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "TapInsight"
        private const val HUD_NOTIFICATION_DURATION_MS = 3_000L
        private const val HEARTBEAT_UI_INTERVAL_MS = 20_000L
        private const val OPENCLAW_PROGRESS_UI_MIN_INTERVAL_MS = 1_000L
        private const val OPENCLAW_PROGRESS_TICKER_DISPLAY_MS = 9_000L
        private const val CHAT_HUD_IDLE_TIMEOUT_MS = 60_000L
        /** Minimum center-movement distance (px) to trigger edge-zone panel switch. */
        private const val EDGE_CENTER_MOVEMENT_THRESHOLD_PX = 48f
        /** Screen edge zone percentage (5% from left or right edge). */
        private const val EDGE_ZONE_PERCENTAGE = 0.05f
        private const val TRACKPAD_EDGE_DEADZONE_PX = 32f
        private const val VOICE_ACTIVATE_BEEP_MS = 90
        private const val VOICE_TIMEOUT_BEEP_MS = 180
        private const val VOICE_LISTEN_START_DELAY_MS = 220L
        private const val VOICE_ACTIVATION_DEBOUNCE_MS = 400L
        private const val GEMINI_LIVE_IDLE_TIMEOUT_MS = 120_000L
        private const val LEARNLM_IDLE_TIMEOUT_MS = 30_000L
        private const val GEMINI_LIVE_ACTIVITY_HEARTBEAT_MS = 250L
        private const val GEMINI_FOLLOW_UP_SPEECH_HOLD_MS = 350L
        private const val GEMINI_FOLLOW_UP_SPEECH_GAP_MS = 250L
        private const val GEMINI_FOLLOW_UP_SPEECH_RECHECK_MS = 1_500L
        private const val GEMINI_LIVE_CONNECT_TIMEOUT_MS = 15_000L
        private const val GEMINI_AUDIO_SAMPLE_RATE = 16_000
        private const val GEMINI_AUDIO_NON_SILENT_THRESHOLD = 600
        private const val GEMINI_BARGE_IN_MIN_MIC_LEVEL = 0.15f
        private const val GEMINI_BARGE_IN_OUTPUT_MARGIN = 0.13f
        private const val GEMINI_BARGE_IN_OUTPUT_RATIO = 2.10f
        private const val GEMINI_BARGE_IN_HOLD_MS = 420L
        private const val GEMINI_BARGE_IN_COOLDOWN_MS = 1_200L
        private const val GEMINI_BARGE_IN_SUPPRESS_MS = 1_500L
        private const val GEMINI_BARGE_IN_GRACE_AFTER_OUTPUT_MS = 2_500L
        private const val MULTIMODAL_FRAME_INTERVAL_MS = 2_000L
        private const val CAMERA_IDLE_TIMEOUT_MS = 5_000L
        // TapInsight: always use Gemini Live directly for continuous camera + voice.
        // Native STT would kill the camera after each utterance.
        private const val USE_NATIVE_STT = false
        private const val OSCILLOSCOPE_USER_COLOR = 0xFFFF4B52.toInt()
        private const val OSCILLOSCOPE_MODEL_COLOR = 0xFF4AA6FF.toInt()
        private const val OSCILLOSCOPE_UI_THROTTLE_MS = 45L
        // MIRROR_FRAME_INTERVAL_NS removed – BinocularSbsLayout handles SBS
        private const val TAP_BROWSER_ACTIVITY_CLASS = "com.TapLinkX3.app.MainActivity"
        private const val EXTRA_BROWSER_INITIAL_URL = "tapclaw_initial_url"
        private const val EXTRA_RETURN_TO_CHAT_ON_DOUBLE_TAP = "tapclaw_return_to_chat_double_tap"
        /**
         * Tells tapbrowser MainActivity that it's being launched purely
         * to warm up the WebView so the visionclaw-side browser_vision
         * tool always has something to screenshot. tapbrowser onCreates
         * fully (so the WebView is attached to BrowserFrameHolder), then
         * pushes itself behind via moveTaskToBack(true) so visionclaw
         * stays foreground. Without this flag, the existing launch path
         * promotes tapbrowser to the front, which would steal focus from
         * the chat panel on startup.
         */
        const val EXTRA_BROWSER_WARM_START = "tapclaw_warm_start"
        /**
         * Unipanel v2 — reverse warm-start direction. tapbrowser is the
         * launcher; on cold launch it kicks visionclaw to onCreate so the
         * mic / Gemini / chat-state stack is ready, then visionclaw
         * cooperatively moveTaskToBack(true)s itself so tapbrowser stays
         * in front. Without this flag visionclaw would keep its launcher-
         * brought-to-front semantics and steal the screen back from the
         * browser canvas. We also set a translucent theme on the warm
         * path so even the brief inflate flash is invisible.
         */
        private const val EXTRA_TAPCLAW_WARM_START = "visionclaw_warm_start"
        private const val EXTRA_YOUTUBE_AUTOPLAY_QUERY = "tapclaw_youtube_autoplay_query"
        private const val EXTRA_YOUTUBE_AUTOPLAY_MODE = "tapclaw_youtube_autoplay_mode"
        private const val EXTRA_YOUTUBE_AUTOPLAY_QUEUE = "tapclaw_youtube_autoplay_queue"
        private const val GENERIC_SCROLL_SCALE = 22f
        private const val LOCATION_MIN_TIME_MS = 2_000L
        private const val LOCATION_MIN_DISTANCE_METERS = 2f
        private const val LOCATION_SNAPSHOT_MAX_AGE_MS = 15 * 60 * 1000L
        private const val LOCATION_SNAPSHOT_TIMEOUT_MS = 5_000L
        private const val LOCATION_SNAPSHOT_REFRESH_DEBOUNCE_MS = 15_000L
        private const val LOCATION_PRECISE_MAX_AGE_MS = 2 * 60 * 1000L
        private const val LOCATION_PRECISE_MAX_ACCURACY_METERS = 250f
        private const val LOCATION_REJECT_LOW_CONFIDENCE_JUMP_METERS = 10_000f
        private const val LIVE_INPUT_SETTLE_MS = 900L
        private const val LOCAL_DIRECT_OUTPUT_SUPPRESS_MS = 4_000L
        // 400 is the effective floor of segmentTranscriptForPlayback
        // (GeminiTtsClient coerces smaller values back up to 400). The first
        // segment's Gemini TTS synthesis is the bottleneck before playback
        // starts — at 1800 chars the TTS endpoint has to generate ~120 s of
        // PCM (~5.7 MB) in one HTTP response, which was taking ~15-18 s
        // on-device (the dominant cause of the original 20-s silent delay).
        // At 400 chars the first-segment synth is ~2-4 s in ideal conditions
        // and ~5-7 s on a slow connection, so playback starts much sooner.
        // If the user still sees double-digit delays after this change, the
        // added timing logs will show whether the bottleneck is
        // synthesizeVerbatim (network/API) or something else.
        private const val READOUT_SEGMENT_MAX_CHARS = 400
        private const val READOUT_DRAIN_TIMEOUT_MS = 5 * 60 * 1000L

        // ── Left-arm tap shortcut (cyttsp6_mt) ───────────────────────────
        // The X3 Pro temple trackpads expose two distinct input devices:
        //   cyttsp5_mt → RIGHT arm (temple trackpad, sends KEYCODE_BUTTON_A
        //                / KEYCODE_DPAD_CENTER through dispatchKeyEvent)
        //   cyttsp6_mt → LEFT arm  (volume pad, emits raw MotionEvents
        //                through dispatchTouchEvent)
        // Gesture mapping during a Gemini Live session:
        //   LEFT-arm SINGLE tap   → toggle camera ON/OFF without ending
        //                           the session (toggleCameraDuringGemini)
        //   RIGHT-arm DOUBLE tap  → end the session (cyclePanelViaDoubleTap)
        // Tap detection mirrors TrackpadGestureEngine's internal
        // thresholds (300 ms / 30 px tolerance).
        private const val LEFT_ARM_DEVICE_NAME = "cyttsp6_mt"
        private const val LEFT_ARM_TAP_MAX_MS = 300L
        private const val LEFT_ARM_TAP_MOVE_TOLERANCE_PX = 30f

        // ── HUD bell notifications ───────────────────────────────────────
        // Cadence of notificationPollRunnable (calendar/tasks due-soon
        // refresh + relay notify-queue drain).
        private const val NOTIFICATION_POLL_INTERVAL_MS = 300_000L
        // Agents can flag a line of their stream as bell-worthy by starting
        // it with "[important]" / "[notify]" / "[notification]" (or the
        // colon forms "important:" etc.). maybePostImportantAgentUpdate
        // scans the rolling stream tail for these markers and posts the
        // marked body to the NotificationCenter.
        private val AGENT_IMPORTANT_MARKER_REGEX = Regex(
            "(?im)^\\s*(?:\\[(?:important|notify|notification)\\]|(?:important|notify|notification):)\\s*(.+)$"
        )
    }

    private enum class GeminiLiveState {
        IDLE,
        LISTENING,
        THINKING,
        FOLLOW_UP
    }

    private enum class ReadoutTarget {
        RESEARCH_REPORT,
        LAST_CHAT_CARD,
        TAPCLAW_RESULT,
        LAST_ARTIFACT
    }

    private enum class ReadoutMode {
        VERBATIM,
        SUMMARY
    }

    private enum class ReadoutAction {
        PLAY,
        RESUME
    }

    private enum class ReadoutPlaybackOutcome {
        COMPLETED,
        UNVERIFIED,
        FAILED
    }

    private enum class GeminiSegmentOutcome {
        VERIFIED,
        UNVERIFIED,
        FAILED
    }

    private data class ReadoutCommand(
        val target: ReadoutTarget,
        val mode: ReadoutMode,
        val topicQuery: String? = null,
        val action: ReadoutAction = ReadoutAction.PLAY
    )

    private data class ResolvedReadoutArtifact(
        val target: ReadoutTarget,
        val title: String,
        val text: String,
        val createdAtMs: Long,
        val artifactId: String? = null,
        val topic: String? = null,
        val unread: Boolean = false
    )

    private data class InterruptedResearchReadout(
        val artifact: ResolvedReadoutArtifact,
        val segments: List<String>,
        val nextSegmentIndex: Int
    ) {
        val hasRemaining: Boolean
            get() = nextSegmentIndex in segments.indices
    }

    // ── ViewModel & gesture engine ───────────────────────────────────────
    //
    // Unipanel v2 Phase 2 — `by viewModels()` keeps working syntactically,
    // but the override below makes it (and every `by activityViewModels()`
    // call inside ChatPanelFragment / SettingsPanelFragment /
    // WebPanelFragment) resolve to the single Application-scoped
    // MainViewModel held by VisionClawApp. This is the prerequisite for
    // the upcoming voice Service: the Service runs outside Activity
    // lifecycle but needs to write into the same `viewModel.messages`
    // StateFlow that ChatCardBridge publishes from.
    //
    // The factory override is the cleanest way to get there without
    // touching the ~40 fragment / activity call sites that use the
    // ViewModelProvider idiom — they all funnel through this factory
    // when resolving MainViewModel.
    private val viewModel: MainViewModel by viewModels()
    private val gestureEngine = TrackpadGestureEngine()

    // androidx.activity 1.8+ exposes defaultViewModelProviderFactory as a
    // Kotlin `val`, not a Java getDefaultViewModelProviderFactory() method.
    // Project is on androidx.activity 1.10.0 so we override the property.
    // Lazy so we don't touch `application` before super.onCreate finishes.
    override val defaultViewModelProviderFactory: ViewModelProvider.Factory by lazy {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return when {
                    modelClass.isAssignableFrom(MainViewModel::class.java) ->
                        (application as VisionClawApp).viewModel as T
                    else -> throw IllegalArgumentException(
                        "Unknown ViewModel class: ${modelClass.name} — " +
                            "add it to MainActivity.defaultViewModelProviderFactory"
                    )
                }
            }
        }
    }

    // ── Fragment instances (retained across config changes via ViewPager2) ─
    private val chatFragment = ChatPanelFragment()

    // ── Views ────────────────────────────────────────────────────────────
    private var viewPager: ViewPager2? = null
    private var hudNotification: TextView? = null
    private var holdProgressBar: ProgressBar? = null
    private var listeningOverlay: FrameLayout? = null
    private var listeningTranscript: TextView? = null
    private var voiceOscilloscope: VoiceOscilloscopeView? = null
    private var customKeyboardView: CustomKeyboardView? = null
    private var activeTextInput: EditText? = null

    // Screen mirroring removed – BinocularSbsLayout handles SBS rendering

    private val uiHandler = Handler(Looper.getMainLooper())
    private val delayedVoiceStartRunnable = Runnable { startVoiceInputSession() }
    private val stopGeminiCaptureRunnable = Runnable { handleGeminiLiveIdleTimeout() }
    private val liveSetupTimeoutRunnable = Runnable {
        if (geminiLiveSession != null && !liveSessionReady) {
            handleGeminiVoiceFailure("Gemini Live connection timed out. Try again.")
        }
    }
    private val settledLiveInputRunnable = Runnable {
        val safe = pendingLiveInputTranscript.trim()
        if (safe.isBlank()) return@Runnable
        if (safe == lastHandledLiveInputTranscript) return@Runnable

        lastHandledLiveInputTranscript = safe
        lastToolAssistTranscript = safe
        // Phase 2 Step 2c.3.1: surface the finalized user turn as
        // an in-memory ChatMessage so the unipanel mini-card stack
        // and any other StateFlow observer can render it.
        viewModel.appendUserUtterance(safe)
        toolAssistRecoveryFired = false
        mediaPlaybackRecoveryFired = false

        // ── LearnLM continuation fast-path ──
        // Intercept before maybeAssist so we never do the slow HTTP call
        if (geminiLiveSession != null && AssistantIntentParser.isExplicitLearnRequest(safe)) {
            val isContinuation = safe.lowercase().let { l ->
                l.contains("continue") || l.contains("last problem") || l.contains("pick up") ||
                    l.contains("where we left off") || l.contains("previous") || l.contains("resume")
            }
            if (isContinuation) {
                Log.d(TAG, "LearnLM continuation fast-path — building context from disk")
                learnLmToolCallActive = true
                keepLearnLmSessionAliveUntilManualClose = true
                armSilenceWatchdog()
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val recentCards = viewModel.getAssistantCardsSnapshot().map { it.text }
                        val ctx = learnLmMemoryStore.buildContext(safe, recentCards)
                        val lesson = ctx.priorLessons.firstOrNull()
                        val contextText = if (lesson != null) {
                            buildString {
                                append("[LEARNLM CONTINUATION — The user wants to continue their previous tutoring session]\n")
                                append("Previous topic: ${lesson.topic}\n")
                                append("Previous question: ${lesson.query}\n")
                                append("Previous lesson summary: ${lesson.summary}\n")
                                lesson.lessonExcerpt?.takeIf { it.isNotBlank() }?.let {
                                    append("Lesson excerpt: ${it.take(500)}\n")
                                }
                                append("\nStart by giving a brief verbal summary of where we left off on this problem, ")
                                append("then ask the user what they'd like to focus on next. Keep the voice conversation going.")
                            }
                        } else {
                            "[LEARNLM CONTINUATION — The user wants to continue a previous tutoring session but no saved lesson was found. " +
                                "Ask them what problem they'd like to work on. Keep the voice conversation going.]"
                        }
                        val sent = geminiLiveSession?.sendClientText(contextText) == true
                        Log.d(TAG, "LearnLM continuation context injected=$sent, topic=${lesson?.topic}")
                    } catch (e: Exception) {
                        Log.e(TAG, "LearnLM continuation fast-path error", e)
                    }
                }
                return@Runnable
            }
        }

        if (maybeRouteLocalIntentDirectly(safe)) {
            return@Runnable
        }

        val engine = toolAssistEngine ?: return@Runnable
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val assist = engine.maybeAssist(safe) ?: return@launch
                Log.d(TAG, "ToolAssist matched [${assist.toolName}]: ${assist.resultText.take(200)}")
                if (assist.toolName == "tapclaw_agent") {
                    cacheTapClawReadableArtifact(assist.resultText)
                }
                if (hasGeminiStartedReplyForCurrentTurn()) {
                    Log.d(TAG, "ToolAssist skipped late injection [${assist.toolName}] because Gemini output already started")
                    if (assist.toolName == "tapclaw_agent") {
                        runOnUiThread {
                            presentTapClawResultLocally(
                                resultText = assist.resultText,
                                autoRead = !assist.resultText.contains("open_taplink:", ignoreCase = true)
                            )
                        }
                    }
                    return@launch
                }

                // LearnLM continuation prefers staying in Gemini Live voice
                if (assist.preferLiveVoice && geminiLiveSession != null) {
                    Log.d(TAG, "ToolAssist routing learn continuation through Gemini Live voice")
                    // Set learnlm flags so 30s timeout applies
                    learnLmToolCallActive = true
                    keepLearnLmSessionAliveUntilManualClose = true
                    armSilenceWatchdog()
                    val sent = geminiLiveSession?.sendClientText(assist.contextPrompt) == true
                    Log.d(TAG, "ToolAssist learn continuation injected=$sent")
                    if (!sent) {
                        // Fallback to local if injection failed
                        runOnUiThread { presentToolAssistLocally(assist.toolName, assist.resultText) }
                    }
                    return@launch
                }

                // If the tool result contains an open_taplink: URL (e.g. ask_maps
                // returning ar_nav.html), extract and open it immediately rather
                // than round-tripping through Gemini which would mangle the URL.
                if (assist.resultText.contains("open_taplink:")) {
                    val tapLinkUrl = assist.resultText
                        .substringAfter("open_taplink:")
                        .substringBefore("\n")
                        .trim()
                    val normalized = AssistantIntentParser.normalizeTapLinkUrl(tapLinkUrl)
                    if (!normalized.isNullOrBlank()) {
                        Log.d(TAG, "ToolAssist [${assist.toolName}] auto-opening URL: $normalized")
                        // file:///android_asset/ URLs must go through TapBrowser
                        // (WebPanelFragment has allowFileAccess=false)
                        runOnUiThread {
                            if (normalized.startsWith("file:///android_asset/")) {
                                launchTapBrowser(initialUrl = normalized)
                            } else {
                                viewModel.openUrl(normalized)
                            }
                        }
                    }
                }

                if (shouldOwnToolAssistLocally(assist.toolName)) {
                    runOnUiThread {
                        presentToolAssistLocally(assist.toolName, assist.resultText)
                    }
                    return@launch
                }
                val sent = geminiLiveSession?.sendClientText(assist.contextPrompt) == true
                Log.d(TAG, "ToolAssist injected clientContent sent=$sent")
                if (sent) {
                    runOnUiThread {
                        showHudNotification(toolAssistHudStatus(assist.toolName, assist.resultText))
                    }
                } else {
                    runOnUiThread {
                        presentToolAssistLocally(assist.toolName, assist.resultText)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "ToolAssist error", e)
            }
        }
    }
    private val cameraIdleTimeoutRunnable = Runnable {
        if (!cameraCaptureActive) return@Runnable
        val recentVoiceActivity =
                (SystemClock.uptimeMillis() - lastUserSpeechActivityMs) < CAMERA_IDLE_TIMEOUT_MS
        if (isGeminiListeningOrThinking() || recentVoiceActivity) {
            scheduleCameraIdleTimeout()
        } else {
            stopCameraCapture()
            showHudNotification("Camera auto-off")
        }
    }
    private val hideHudNotificationRunnable = Runnable {
        hudNotification
                ?.animate()
                ?.alpha(0f)
                ?.setDuration(220)
                ?.withEndAction { hudNotification?.visibility = View.GONE }
                ?.start()
    }
    private val chatHudIdleRunnable = Runnable {
        val currentPanel = viewPager?.currentItem ?: return@Runnable
        if (currentPanel != MainViewModel.PANEL_CHAT) return@Runnable
        if (chatFragment.isHudModeEnabled()) return@Runnable
        if (chatFragment.isBatterySavingDarkMode()) return@Runnable
        if (isGeminiListeningOrThinking()) {
            scheduleChatHudIdleTimer()
            return@Runnable
        }
        chatFragment.setHudModeEnabled(true)
    }
    private val hudStatePushRunnable = object : Runnable {
        override fun run() {
            pushHudStateToChatFragment(force = false)
            uiHandler.postDelayed(this, 2000L)
        }
    }
    // 5-minute notification poll. Forces the calendar / tasks HUD feeds to
    // refresh (their fetch paths post due-soon bells through
    // NotificationCenter) and drains any relay-queued notifications
    // (RelayNotifyInbox) on a background dispatcher — that's the
    // away-from-home path where the Mac can't push to the glasses
    // directly. Armed in onResume, removed in onPause.
    private val notificationPollRunnable = object : Runnable {
        override fun run() {
            viewModel.refreshHudUpcomingCalendar(force = true)
            viewModel.refreshHudTasks(force = true)
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching { RelayNotifyInbox.drainBlocking(applicationContext) }
            }
            uiHandler.postDelayed(this, NOTIFICATION_POLL_INTERVAL_MS)
        }
    }

    // ── Speech & Audio ───────────────────────────────────────────────────
    private var speechController: SpeechInputController? = null
    private var geminiLiveSession:
            com.rayneo.visionclaw.core.network.GeminiRouter.LiveSessionHandle? =
            null
    private var geminiAudioRecord: AudioRecord? = null
    private var geminiAudioThread: Thread? = null
    @Volatile private var geminiCaptureActive = false
    @Volatile private var liveSessionReady = false
    @Volatile private var liveSessionClosingByApp = false
    @Volatile private var liveState = GeminiLiveState.IDLE
    @Volatile private var awaitingServerTurnComplete = false
    private var latestLiveTranscript = ""
    private var latestLiveOutputTranscript = ""
    private val readableArtifactStore by lazy(LazyThreadSafetyMode.NONE) {
        ReadableArtifactStore(this)
    }
    private val geminiReadoutTtsClient by lazy(LazyThreadSafetyMode.NONE) {
        GeminiTtsClient(
            apiKeyProvider = { resolveGeminiReadoutApiKey() },
            fallbackApiKeyProvider = {
                BuildConfig.GEMINI_API_KEY.trim().takeIf { it.isNotBlank() }
            },
            modelProvider = {
                viewModel.preferences.researchTtsModel.trim().takeIf { it.isNotBlank() }
            },
            voiceNameProvider = {
                viewModel.preferences.researchTtsVoiceName.trim().takeIf { it.isNotBlank() }
                    ?: viewModel.preferences.ttsVoiceName.trim().takeIf { it.isNotBlank() }
                    ?: viewModel.preferences.liveVoiceName.trim().takeIf { it.isNotBlank() }
            },
            languageCodeProvider = {
                viewModel.preferences.researchTtsLanguage.trim().takeIf { it.isNotBlank() }
                    ?: viewModel.preferences.liveLanguageCode.trim().takeIf { it.isNotBlank() }
            },
            directorNotesProvider = {
                viewModel.preferences.researchTtsDirectorNotes.trim().takeIf { it.isNotBlank() }
            },
            timeoutSecondsProvider = {
                viewModel.preferences.timeoutResearchSeconds
            }
        )
    }
    /**
     * Lazy Fish.audio TTS client used for the verbatim readout path
     * (research reports, chat-card readouts, "read TapClaw result
     * verbatim"). The companion app's Readout Voice tabs let the user
     * pick Gemini or Fish; whichever is chosen, [synthesizeVerbatimRouted]
     * routes through that engine. This client requests `format=wav`
     * (overriding the user's MP3 default) so we can strip the WAV
     * header to raw PCM and feed it to the existing
     * [GeminiAudioPlayer]-based AudioTrack pipeline — keeping all the
     * stop/flush/drain/HUD logic shared between engines.
     */
    private val fishReadoutTtsClient by lazy(LazyThreadSafetyMode.NONE) {
        com.TapLink.app.media.FishTtsClient(
            configProvider = {
                val base = com.TapLink.app.media.resolveGlassesFishConfig(
                    this@MainActivity, nullIfNotActiveEngine = false
                )
                // Force WAV regardless of the user's mp3/wav/opus
                // preference — we need the embedded sample rate header
                // and raw PCM payload for the AudioTrack player. The
                // user's choice still applies to the on-glasses Media
                // Library text reader, which has its own bridge path.
                base?.copy(format = "wav")
            }
        )
    }

    private var geminiReadoutAudioPlayer: GeminiAudioPlayer? = null
    private var lastResolvedReadoutArtifact: ResolvedReadoutArtifact? = null
    private var speechStopJob: Job? = null
    private var activeReadoutJob: Job? = null
    @Volatile private var interruptedResearchReadout: InterruptedResearchReadout? = null
    private var geminiSessionInitJob: Job? = null
    @Volatile private var pendingLiveInputTranscript = ""
    @Volatile private var lastHandledLiveInputTranscript = ""
    @Volatile private var lastLiveActivityHeartbeatMs = 0L
    @Volatile private var lastMultimodalFrameSentMs = 0L
    @Volatile private var lastUserSpeechActivityMs = 0L
    @Volatile private var lastGeminiOutputActivityMs = 0L
    @Volatile private var currentGeminiOutputTurnStartedMs = 0L
    @Volatile private var dropOutputTranscriptionUntilNextInput = false
    @Volatile private var forceDirectGeminiLive = true
    @Volatile private var lastVoiceActivationMs = 0L
    /** Monotonically increasing counter to detect stale WebSocket callbacks from old sessions. */
    @Volatile private var geminiSessionEpoch = 0L
    @Volatile private var suppressGeminiOutputUntilMs = 0L
    @Volatile private var keepLearnLmSessionAliveUntilManualClose = false
    @Volatile private var learnLmToolCallActive = false
    @Volatile private var nativeSttFallbackTriggered = false
    private lateinit var toolDispatcher: ToolDispatcher
    private var toolAssistEngine: com.rayneo.visionclaw.core.tools.ToolAssistEngine? = null
    private lateinit var learnLmMemoryStore: LearnLmMemoryStore
    private var companionServer: com.rayneo.visionclaw.core.config.CompanionServer? = null
    private lateinit var oauthManager: com.rayneo.visionclaw.core.network.GoogleOAuthManager
    private var livePlacesClient: com.rayneo.visionclaw.core.network.GooglePlacesClient? = null

    /** True when dark mode activated battery saver (vs. it already being on). */
    private var darkModeActivatedBatterySaver = false

    private var lastKnownAiConnectionStatus = ChatPanelFragment.ConnectionStatus.IDLE
    private var sawNonSilentGeminiAudio = false
    private var loggedGeminiAudioProbe = false
    private var rayNeoMicRouteActive = false
    private var geminiAcousticEchoCanceler: AcousticEchoCanceler? = null
    private var geminiNoiseSuppressor: NoiseSuppressor? = null
    private var geminiAutomaticGainControl: AutomaticGainControl? = null
    @Volatile private var geminiBargeInCandidateSinceMs = 0L
    @Volatile private var geminiBargeInLastTriggerMs = 0L
    @Volatile private var geminiFollowUpSpeechCandidateSinceMs = 0L
    @Volatile private var geminiFollowUpSpeechLastPeakMs = 0L
    @Volatile private var geminiFollowUpSpeechEvidenceMs = 0L
    private var frameCapture: FrameCaptureManager? = null
    private var geminiAudioPlayer: GeminiAudioPlayer? = null
    private var ttsController: TtsController? = null
    private var toneGenerator: ToneGenerator? = null
    private var latestFrame: String? = null
    private var cameraCaptureActive = false
    private var coreEyeSurfaceReady = false
    private var pendingCameraStart = false
    @Volatile private var assistantSessionStartsAudioOnly = false
    @Volatile private var lastOscilloscopeUiUpdateMs = 0L
    private var lastPushedCalendarSummary = ""
    private var lastPushedTasksSummary = ""
    private var lastPushedNewsSummary = ""
    private var lastPushedAqiText: String? = null
    private var lastPushedAqiValue: Int? = null
    private var lastPushedRadioName: String? = null
    private var lastPushedRadioPlaying = false
    private var pendingFocusNewChatOnResume = false

    // ── Edge-zone tracking ───────────────────────────────────────────────
    private var swipeStartX = 0f
    private var swipeStartY = 0f
    private var isTrackingSwipe = false
    private var edgeZoneLeft = 0f
    private var edgeZoneRight = 0f
    private var initialPageSnapDone = false

    // ── Left-arm (cyttsp6_mt) tap shortcut state ──────────────────────────
    // Mirrors the right-arm double-tap → cyclePanelViaDoubleTap() path
    // using a SINGLE tap on the left arm pad. Tracked here (not inside
    // TrackpadGestureEngine) because the engine is device-agnostic and
    // we only want this shortcut when the touch comes from the left
    // arm hardware specifically. State is updated on every left-arm
    // MotionEvent in dispatchTouchEvent → consumedByLeftArmTap().
    private var leftArmTapDownTimeMs = 0L
    private var leftArmTapDownX = 0f
    private var leftArmTapDownY = 0f
    private var leftArmTapTracking = false
    private var leftArmTapMovedTooFar = false

    // ── Runtime permissions ───────────────────────────────────────────────
    private var micPermissionGranted = false
    private var cameraPermissionGranted = false
    private var locationPermissionGranted = false
    private var locationManager: LocationManager? = null
    private var locationTrackingActive = false
    private lateinit var deviceLocationResolver: DeviceLocationResolver
    @Volatile private var lastLocationSnapshotRefreshElapsedMs = 0L
    private val locationListener =
            LocationListener { location -> publishDeviceLocationContext(location) }

    private val permissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants
                ->
                micPermissionGranted =
                        grants[Manifest.permission.RECORD_AUDIO]
                                ?: (ContextCompat.checkSelfPermission(
                                        this,
                                        Manifest.permission.RECORD_AUDIO
                                ) == PackageManager.PERMISSION_GRANTED)
                cameraPermissionGranted =
                        grants[Manifest.permission.CAMERA]
                                ?: (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                                        PackageManager.PERMISSION_GRANTED)
                val fineGranted =
                        grants[Manifest.permission.ACCESS_FINE_LOCATION]
                                ?: (ContextCompat.checkSelfPermission(
                                        this,
                                        Manifest.permission.ACCESS_FINE_LOCATION
                                ) == PackageManager.PERMISSION_GRANTED)
                val coarseGranted =
                        grants[Manifest.permission.ACCESS_COARSE_LOCATION]
                                ?: (ContextCompat.checkSelfPermission(
                                        this,
                                        Manifest.permission.ACCESS_COARSE_LOCATION
                                ) == PackageManager.PERMISSION_GRANTED)
                locationPermissionGranted = fineGranted || coarseGranted
                Log.i(
                        TAG,
                        "Permissions — mic=$micPermissionGranted camera=$cameraPermissionGranted location=$locationPermissionGranted"
                )
                syncCameraToGeminiState(viewModel.voiceAssistantActive.value == true)
                if (locationPermissionGranted) {
                    startLocationTracking()
                    refreshLocationSnapshot(force = true)
                } else {
                    stopLocationTracking()
                    viewModel.clearDeviceLocationContext()
                }
            }

    override fun attachBaseContext(newBase: Context) {
        val config = Configuration(newBase.resources.configuration).apply {
            densityDpi = DisplayMetrics.DENSITY_MEDIUM
        }
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    // ══════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ══════════════════════════════════════════════════════════════════════

    private fun configureDnsCaching() {
        runCatching {
            Security.setProperty("networkaddress.cache.ttl", "60")
            Security.setProperty("networkaddress.cache.negative.ttl", "0")
            System.setProperty("networkaddress.cache.ttl", "60")
            System.setProperty("networkaddress.cache.negative.ttl", "0")
            Log.d(TAG, "Configured DNS cache policy: ttl=60 negativeTtl=0")
        }.onFailure {
            Log.w(TAG, "Failed configuring DNS cache policy: ${it.message}")
        }
    }

    private fun bindProcessToValidatedWifi() {
        runCatching {
            val connectivityManager = getSystemService(ConnectivityManager::class.java) ?: return
            val activeNetwork = connectivityManager.activeNetwork
            val capabilities = activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }
            val unbound = connectivityManager.bindProcessToNetwork(null)
            Log.d(
                TAG,
                "Cleared process network binding unbound=$unbound activeNetwork=$activeNetwork validated=${
                    capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
                } wifi=${capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true}"
            )
        }.onFailure {
            Log.w(TAG, "Failed clearing process network binding: ${it.message}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Initialize Mercury SDK for binocular (both lenses) display — must be before super.
        runCatching { com.ffalcon.mercury.android.sdk.MercurySDK.init(application) }

        // Unipanel v2: warm-start path. tapbrowser is the launcher, and on
        // cold launch it kicks visionclaw onCreate so the chat / Gemini /
        // mic stack is alive in the background. We don't want the user to
        // see a flash of visionclaw's chat UI before moveTaskToBack lands,
        // so the theme is swapped to a translucent variant for this one
        // lifecycle. Must come BEFORE super.onCreate so the theme actually
        // applies to the inflate.
        //
        // IMPORTANT — the theme MUST descend from Theme.VisionClawRayNeo
        // (which extends MaterialComponents/AppCompat). The v1 attempt
        // used android.R.style.Theme_Translucent_NoTitleBar which is a
        // bare-framework theme; AppCompatActivity hard-crashes mid-onCreate
        // with "You need to use a Theme.AppCompat theme (or descendant)"
        // when the active theme lacks AppCompat's required attributes.
        // Theme.VisionClawRayNeo.Headless inherits everything from the
        // main theme and only adds windowIsTranslucent + null window
        // animations, so AppCompat is satisfied and the window is invisible.
        val isUnipanelWarmStart = intent?.getBooleanExtra(EXTRA_TAPCLAW_WARM_START, false) == true
        if (isUnipanelWarmStart) {
            setTheme(R.style.Theme_VisionClawRayNeo_Headless)
        }

        super.onCreate(savedInstanceState)
        configureDnsCaching()
        bindProcessToValidatedWifi()

        // Restore the HUD bell's persisted state (visible list, unread
        // badge count AND the dedupe memory) before any poller or push
        // path can post. Without the dedupe restore, the 5-minute pollers
        // would re-post already-seen events after every restart and
        // falsely re-light the badge. Idempotent — safe even if the
        // foreground service initialized it first.
        NotificationCenter.init(applicationContext)

        // ── Immersive full-screen for AR HUD ─────────────────────────
        configureImmersiveDisplay()
        window.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING or
                        WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
        )

        setContentView(R.layout.activity_main)

        // Unipanel v2: if we were warm-started by tapbrowser, pin the
        // entire activity_main hierarchy to INVISIBLE the instant it's
        // inflated. Even though the translucent theme makes the window
        // transparent, individual views (BinocularSbsLayout backgrounds,
        // the chat ViewPager, etc.) still paint themselves and produce a
        // visible flash before moveTaskToBack lands. Pinning android.R.id
        // .content to INVISIBLE here short-circuits the entire draw pass
        // — the system's layout / measure still runs (so fragments attach
        // normally and lifecycle proceeds), but nothing reaches the
        // screen. The user perceives only tapbrowser drawing during launch.
        //
        // moveTaskToBack happens on the decorView's first post so it
        // arrives after onAttachedToWindow, which is when Android is
        // actually willing to background the task. Setting it from
        // inside onCreate too early gets silently ignored.
        if (isUnipanelWarmStart) {
            findViewById<View>(android.R.id.content)?.visibility = View.INVISIBLE
            window.decorView.post {
                runCatching {
                    moveTaskToBack(true)
                    Log.d(TAG, "Unipanel v2: visionclaw warm-started; sent task to back")
                }.onFailure { e ->
                    Log.w(TAG, "Unipanel v2: moveTaskToBack failed: ${e.message}")
                }
            }
        }

        // ── Bind views (null-safe) ───────────────────────────────────
        viewPager = findViewById(R.id.view_pager)
        hudNotification = findViewById(R.id.hud_notification)
        holdProgressBar = findViewById(R.id.hold_progress)
        listeningOverlay = findViewById(R.id.listening_overlay)
        listeningTranscript = findViewById(R.id.listening_transcript)
        voiceOscilloscope = findViewById(R.id.voice_oscilloscope)
        customKeyboardView = findViewById(R.id.custom_keyboard_view)

        setupCustomKeyboard()

        // ── Initialize OAuth manager and API clients ─────────────────────
        val prefs = viewModel.preferences
        // Clear stale TapRadio "now playing" state from previous session on cold start
        // The radio isn't actually playing when the app restarts
        getSharedPreferences("visionclaw_prefs", MODE_PRIVATE).edit()
            .putBoolean("tapradio_now_playing_active", false)
            .remove("tapradio_now_playing_name")
            .remove("tapradio_now_playing_genre")
            .apply()

        oauthManager = com.rayneo.visionclaw.core.network.GoogleOAuthManager(prefs, this)
        deviceLocationResolver = DeviceLocationResolver(this)

        val calendarClient = com.rayneo.visionclaw.core.network.GoogleCalendarClient(
            apiKeyProvider = { prefs.calendarApiKey },
            accessTokenProvider = {
                kotlinx.coroutines.runBlocking { oauthManager.getValidAccessToken() }
            },
            context = this
        )
        viewModel.setCalendarClient(calendarClient)

        val directionsClient = com.rayneo.visionclaw.core.network.GoogleDirectionsClient(
            apiKeyProvider = { prefs.googleMapsApiKey },
            context = this
        )

        val tasksClient = com.rayneo.visionclaw.core.network.GoogleTasksClient(
            accessTokenProvider = {
                kotlinx.coroutines.runBlocking { oauthManager.getValidAccessToken() }
            },
            context = this
        )
        viewModel.setTasksClient(tasksClient)

        val placesClient = com.rayneo.visionclaw.core.network.GooglePlacesClient(
            apiKeyProvider = { prefs.googleMapsApiKey },
            context = this
        )
        livePlacesClient = placesClient

        val airQualityClient = com.rayneo.visionclaw.core.network.GoogleAirQualityClient(
            apiKeyProvider = { prefs.googleMapsApiKey },
            context = this
        )
        viewModel.setAirQualityClient(airQualityClient)
        val weatherClient = com.rayneo.visionclaw.core.network.OpenMeteoWeatherClient(
            context = this
        )

        val deviceLocationLambda: () -> DeviceLocationContext? = {
            getToolReadyLocationContext()
        }

        // OpenClaw/TapClaw: use companion UI settings first, fall back to
        // device token + endpoint stored during QR/node pairing.
        val pairingPrefs = getSharedPreferences("visionclaw_prefs", MODE_PRIVATE)
        val openClawClient = com.rayneo.visionclaw.core.network.OpenClawClient(
            gatewayUrlProvider = {
                prefs.openClawEndpoint.takeIf { it.isNotBlank() }
                    ?: pairingPrefs.getString("openclaw_pair_device_token_gateway", null)
                        ?.takeIf { it.isNotBlank() }
            },
            fallbackGatewayUrlProvider = {
                pairingPrefs.getString("openclaw_pair_device_token_gateway", null)
                    ?.takeIf {
                        it.isNotBlank() &&
                            !it.equals(prefs.openClawEndpoint.takeIf { endpoint -> endpoint.isNotBlank() }, ignoreCase = true)
                    }
            },
            gatewayTokenProvider = {
                prefs.openClawToken.takeIf { it.isNotBlank() }
                    ?: pairingPrefs.getString("openclaw_pair_device_token", null)
                        ?.takeIf { it.isNotBlank() }
            },
            deviceIdProvider = {
                pairingPrefs.getString("openclaw_pair_device_id", null)
                    ?.takeIf { it.isNotBlank() }
            },
            publicKeyProvider = {
                pairingPrefs.getString("openclaw_pair_public_key", null)
                    ?.takeIf { it.isNotBlank() }
            },
            privateKeyProvider = {
                pairingPrefs.getString("openclaw_pair_private_key", null)
                    ?.takeIf { it.isNotBlank() }
            },
            sessionIdProvider = { prefs.openClawSessionId.ifBlank { "main" } },
            timeoutMsProvider = {
                val t = prefs.openClawTimeoutSeconds
                if (t > 0) t * 1000 else 30_000
            },
            // ── Mode bracket providers ──────────────────────────────
            // These read live from AppPreferences each time, so the
            // user's choice in the companion app takes effect on the
            // next turn without a session reset (and explicitly via
            // resetModePrefixForSession when the dropdown changes).
            fastModeProvider = { prefs.openClawFastMode },
            thinkLevelProvider = { prefs.openClawThinkLevel },
            afterFastModeProvider = { prefs.openClawAfterFastMode },
            afterThinkLevelProvider = { prefs.openClawAfterThinkLevel }
        )
        // Store client reference for periodic ping and start idle status ticker.
        openClawClientField = openClawClient
        startOpenClawPing()
        // Hermes equivalent: store reference and start the parallel ping
        // loop so the HUD status icon turns red/green based on Hermes
        // server reachability. Constructed below in this same scope —
        // assign after construction to avoid forward-reference confusion.

        // Hermes Agent client — separate HTTP/SSE client that talks to
        // the user's hermes-agent API server. Constructed alongside
        // OpenClaw so both can be active simultaneously (different
        // voice keyword routes to each tool). Reads endpoint + API key
        // from AppPreferences — settings configured in the Hermes
        // section of the companion app.
        val hermesClient = com.rayneo.visionclaw.core.network.HermesClient(
            endpointUrlProvider = { prefs.hermesEndpoint.takeIf { it.isNotBlank() } },
            apiKeyProvider = { prefs.hermesApiKey.takeIf { it.isNotBlank() } },
            sessionIdProvider = { prefs.hermesSessionId.ifBlank { "main" } },
            timeoutMsProvider = {
                val t = prefs.hermesTimeoutSeconds
                if (t > 0) t * 1000 else 30_000
            }
        )
        // Wire HUD ticker / heartbeat for Hermes turns into the SAME
        // callbacks as OpenClaw so the existing UI (ticker scrolling,
        // stream-active indicator, etc.) Just Works™ for Hermes too.
        // The accumulator + reset logic from OpenClaw's handler is
        // duplicated here against the Hermes client's callbacks.
        hermesClient.onProgressUpdate = { deltaText ->
            val now = android.os.SystemClock.uptimeMillis()
            synchronized(heartbeatStreamBuffer) {
                // Hermes already sends the rolling tail via takeLast(200)
                // from inside HermesClient — but to keep the same UI
                // behavior as OpenClaw, we treat each callback as an
                // already-accumulated snapshot. Replace, don't append.
                heartbeatStreamBuffer.setLength(0)
                heartbeatStreamBuffer.append(deltaText)
            }
            val heartbeatText = deltaText.take(200).replace('\n', ' ')
            lastTapClawHeartbeat = heartbeatText
            lastOpenClawTaskLabel = "Hermes working"
            lastOpenClawActivityMs = now
            lastOpenClawGatewayHealthy = true
            OpenClawStatusService.updateHeartbeat(
                heartbeat = heartbeatText,
                taskLabel = "Hermes working",
                gatewayHealthy = true,
                activityUptimeMs = now
            )
            if (now - lastHeartbeatUiUpdateMs >= OPENCLAW_PROGRESS_UI_MIN_INTERVAL_MS) {
                lastHeartbeatUiUpdateMs = now
                runOnUiThread {
                    renderOpenClawTicker("Hermes working", gatewayHealthy = true, transient = true)
                    chatFragment.setStreamActiveIndicator(true)
                }
            }
            maybePostImportantAgentUpdate(deltaText, NotificationCenter.Source.HERMES)
        }
        hermesClient.onProgressComplete = { success ->
            val now = android.os.SystemClock.uptimeMillis()
            // Ring the HUD bell for every finished Hermes run — the user is
            // often mid-task elsewhere when a long agent turn lands. Posted
            // BEFORE the buffer reset so the message can quote the last
            // heartbeat tail. Timestamped id: every completion is a fresh
            // event, never deduped away.
            NotificationCenter.post(
                NotificationCenter.HudNotification(
                    id = "hermes_done_$now",
                    source = NotificationCenter.Source.HERMES,
                    title = if (success) "Hermes finished" else "Hermes failed",
                    message = lastTapClawHeartbeat?.takeIf { it.isNotBlank() }?.take(160)
                        ?: if (success) "Hermes completed a task." else "Hermes run failed."
                )
            )
            synchronized(heartbeatStreamBuffer) { heartbeatStreamBuffer.setLength(0) }
            lastOpenClawTaskLabel = if (success) "Hermes done" else "Hermes failed"
            lastOpenClawActivityMs = now
            lastHeartbeatUiUpdateMs = now
            OpenClawStatusService.updateHeartbeat(
                heartbeat = lastTapClawHeartbeat,
                taskLabel = lastOpenClawTaskLabel,
                gatewayHealthy = lastOpenClawGatewayHealthy || success,
                activityUptimeMs = now
            )
            runOnUiThread {
                renderOpenClawTicker(lastOpenClawTaskLabel, gatewayHealthy = success, transient = false)
                chatFragment.setStreamActiveIndicator(false)
            }
        }

        // Show OpenClaw streaming progress as a persistent HUD ticker under
        // the clock, with frequent updates during active gateway work.
        openClawClient.onProgressUpdate = { deltaText ->
            val now = android.os.SystemClock.uptimeMillis()
            // Accumulate streaming deltas so the ticker scrolls readable
            // text instead of flashing one token at a time. Hermes' SSE
            // stream delivers content token-by-token; the old OpenClaw
            // gateway delivered short stage labels. We append, then
            // display the trailing 200 chars so the user sees the most
            // recent context. heartbeatStreamBuffer is reset in
            // onProgressComplete so each turn starts fresh.
            synchronized(heartbeatStreamBuffer) {
                heartbeatStreamBuffer.append(deltaText)
                // Cap the buffer at ~4 KB so a runaway long response
                // doesn't grow it without bound. We only show 200 chars
                // anyway.
                if (heartbeatStreamBuffer.length > 4096) {
                    val drop = heartbeatStreamBuffer.length - 2048
                    heartbeatStreamBuffer.delete(0, drop)
                }
            }
            val heartbeatText = heartbeatStreamBuffer.toString()
                .takeLast(200)
                .replace('\n', ' ')
            // Scan the raw (un-flattened) stream tail for [important]/[notify]
            // markers — agents flag bell-worthy lines that way. Wider window
            // than the 200-char ticker so a marker that scrolled off the
            // ticker still rings the bell exactly once (content-hash id).
            val rawStreamTail = synchronized(heartbeatStreamBuffer) {
                heartbeatStreamBuffer.toString().takeLast(1024)
            }
            maybePostImportantAgentUpdate(rawStreamTail, NotificationCenter.Source.OPENCLAW)
            val hudLabel = openClawProgressLabel(deltaText)
            val labelChanged = hudLabel != lastOpenClawTaskLabel

            lastTapClawHeartbeat = heartbeatText
            lastOpenClawTaskLabel = hudLabel
            lastOpenClawActivityMs = now
            lastOpenClawGatewayHealthy = true
            if (lastOpenClawConnectionLabel == "OpenClaw checking...") {
                lastOpenClawConnectionLabel = "OpenClaw connected"
            }
            // Mirror to the process-wide status service so the
            // status_briefing tool can report current state.
            OpenClawStatusService.updateHeartbeat(
                heartbeat = heartbeatText,
                taskLabel = hudLabel,
                gatewayHealthy = true,
                activityUptimeMs = now
            )
            OpenClawStatusService.updateConnection(lastOpenClawConnectionLabel, healthy = true)

            if (labelChanged || now - lastHeartbeatUiUpdateMs >= OPENCLAW_PROGRESS_UI_MIN_INTERVAL_MS) {
                lastHeartbeatUiUpdateMs = now
                runOnUiThread {
                    renderOpenClawTicker(hudLabel, gatewayHealthy = true, transient = true)
                    chatFragment.setStreamActiveIndicator(true)
                    // Speak the heartbeat out loud as soon as it arrives so the
                    // user isn't left in silence while OpenClaw is working.
                    // Only speak on label CHANGE (not every throttle tick) to
                    // avoid repeating the same line. force=false so we never
                    // pre-empt Gemini's current utterance.
                    if (labelChanged && !hudLabel.isNullOrBlank()) {
                        ttsController?.speak(hudLabel)
                    }
                }
            }
        }
        // Explicit completion signal: fired exactly once per sendMessage call
        // by OpenClawClient when the agent run reaches a terminal state.
        // Purpose: let the user know TapClaw is done so they aren't left
        // wondering whether the gateway is still working or has silently
        // hung. This complements the delta heartbeat above — the deltas
        // show *what* TapClaw is doing, this event fires when TapClaw is
        // *done* doing it.
        openClawClient.onProgressComplete = { success ->
            val hadActiveTask = lastOpenClawTaskLabel != null
            val finalLabel = if (success) "Task complete" else "Task failed"
            val now = android.os.SystemClock.uptimeMillis()
            // Reset the streaming-text accumulator so the next turn's
            // ticker starts fresh instead of continuing to show the
            // tail of the previous response.
            synchronized(heartbeatStreamBuffer) { heartbeatStreamBuffer.setLength(0) }
            lastOpenClawTaskLabel = finalLabel
            lastOpenClawActivityMs = now
            lastHeartbeatUiUpdateMs = now
            // Mirror terminal status to the process-wide service.
            OpenClawStatusService.updateHeartbeat(
                heartbeat = lastTapClawHeartbeat,
                taskLabel = finalLabel,
                gatewayHealthy = lastOpenClawGatewayHealthy || success,
                activityUptimeMs = now
            )
            runOnUiThread {
                // Keep the terminal action visible as the stationary ticker
                // until the next user-requested function completes. Live
                // heartbeat deltas still appear transiently and scroll over it.
                recordHudFunctionTicker(finalLabel, gatewayHealthy = lastOpenClawGatewayHealthy || success)
                chatFragment.setStreamActiveIndicator(false)
                // Announce completion verbally ONLY if the user actually saw
                // this run start (i.e. a heartbeat label was already shown).
                // Avoids random "TapClaw finished" calls when a trivial /
                // instant call returns before any delta fired.
                if (hadActiveTask) {
                    val spoken = if (success) "TapClaw finished." else "TapClaw ran into a problem."
                    ttsController?.speak(spoken)
                }
            }
            // After-turn mode restore (fire-and-forget). Only fires on
            // success — failed turns leave whatever profile TapClaw is
            // in alone so the user can debug. The method internally
            // checks whether any after-turn slash commands are
            // configured and is a no-op when both prefs are off / "".
            if (success) {
                lifecycleScope.launch(Dispatchers.IO) {
                    runCatching { openClawClient.fireAfterTurnRestoreIfConfigured() }
                        .onFailure { Log.w(TAG, "After-turn restore failed", it) }
                }
            }
        }
        // Store + start the parallel Hermes ping so the HUD status icon
        // (next to the clock) turns green when Hermes is reachable. The
        // hermesClient itself was constructed (and its onProgressUpdate /
        // onProgressComplete wired) earlier in this same scope.
        hermesClientField = hermesClient
        startHermesPing()

        toolDispatcher = ToolDispatcher(
            this, calendarClient, directionsClient, tasksClient,
            placesClient = placesClient,
            airQualityClient = airQualityClient,
            weatherClient = weatherClient,
            learnLmRouter = viewModel.learnLmRouter,
            recentCardsProvider = { viewModel.getAssistantCardsSnapshot().map { it.text } },
            locationProvider = deviceLocationLambda,
            openClawClient = openClawClient,
            hermesClient = hermesClient,
            cameraFrameProvider = { latestFrame },
            // Pull WebView screenshots on demand from the tapbrowser
            // module via the cross-Activity holder. Returns null when
            // the browser isn't currently up.
            browserFrameProvider = { com.TapLink.app.media.BrowserFrameHolder.captureBase64Jpeg() },
            browserPageTextProvider = { maxChars ->
                com.TapLink.app.media.BrowserFrameHolder.capturePageText(maxChars)
            },
            batteryLevelProvider = { getBatteryLevel() },
            isChargingProvider = { isBatteryCharging() },
            toggleBatterySaver = { enabled -> onBatterySaverToggled(enabled) }
        )

        // ToolAssistEngine: client-side tool execution for Live model
        // which may have unreliable function calling.
        toolAssistEngine = com.rayneo.visionclaw.core.tools.ToolAssistEngine(
            toolDispatcher = toolDispatcher,
            locationProvider = deviceLocationLambda
        )

        learnLmMemoryStore = LearnLmMemoryStore(this)

        viewModel.setMultimodalCameraEnabled(false)
        viewModel.setMultimodalTextureReady(false)

        // Start companion config server so phone can configure TapInsight via WiFi
        val serverPort = viewModel.appConfig.debugServerSettings.port
        companionServer = com.rayneo.visionclaw.core.config.CompanionServer(
            this, serverPort, oauthManager,
            locationProvider = deviceLocationLambda,
            calendarSummaryProvider = { viewModel.calendarSummary.value },
            tasksSummaryProvider = { viewModel.tasksSummary.value },
            newsSummaryProvider = { viewModel.newsSummary.value },
            airQualityTextProvider = { viewModel.airQualitySummary.value?.text },
            airQualityValueProvider = { viewModel.airQualitySummary.value?.aqi },
            phoneLocationConsumer = { context ->
                runOnUiThread {
                    if (context != null) {
                        publishDeviceLocationContext(context)
                    } else if (viewModel.getDeviceLocationContext()?.provider == "companion_phone") {
                        viewModel.clearDeviceLocationContext()
                        pushHudStateToChatFragment(force = true)
                    }
                }
            },
            cameraFrameProvider = {
                // Convert the base64 latestFrame to raw JPEG bytes for the HTTP endpoint.
                // Returns null if no frame is available (camera not active).
                latestFrame?.let { base64 ->
                    try {
                        android.util.Base64.decode(base64, android.util.Base64.NO_WRAP)
                    } catch (e: Exception) {
                        null
                    }
                }
            }
        )
        companionServer?.startServer()
        Log.d(TAG, "Companion config server available at http://<glasses-ip>:$serverPort")

        // ── Calculate edge zones + gesture side awareness ─────────────────
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        edgeZoneLeft = screenWidth * EDGE_ZONE_PERCENTAGE
        edgeZoneRight = screenWidth * (1f - EDGE_ZONE_PERCENTAGE)
        gestureEngine.setScreenSize(screenWidth, screenHeight)

        // ── XR Origin / session safety checks ────────────────────────
        initXrOriginSafe()

        // ── ViewPager setup ──────────────────────────────────────────
        setupViewPager()
        // HUD bell: activating a notification (tap / trackpad select in the
        // bell list) drops it into the chat as an assistant card and reads
        // it aloud, so the user never has to squint at the one-line list.
        chatFragment.setNotificationActionListener(
                object : ChatPanelFragment.NotificationActionListener {
                    override fun onNotificationActivated(
                        notification: NotificationCenter.HudNotification
                    ) {
                        viewModel.appendDirectAssistantResponse(
                            "${notification.title} — ${notification.message}"
                        )
                        ttsController?.speak(notification.spokenText(), force = true)
                    }
                }
        )
        chatFragment.setCoreEyeSurfaceListener(
                object : ChatPanelFragment.CoreEyeSurfaceListener {
                    override fun onSurfaceAvailable() {
                        coreEyeSurfaceReady = true
                        viewModel.setMultimodalTextureReady(true)
                        syncCameraToGeminiState(viewModel.voiceAssistantActive.value == true)
                    }

                    override fun onSurfaceDestroyed() {
                        coreEyeSurfaceReady = false
                        viewModel.setMultimodalTextureReady(false)
                        if (cameraCaptureActive) {
                            stopCameraCapture()
                        }
                        pendingCameraStart =
                                cameraPermissionGranted && viewModel.voiceAssistantActive.value == true
                    }
                }
        )
        chatFragment.setCardActionListener(
                object : ChatPanelFragment.CardActionListener {
                    override fun onAssistantRequested() {
                        runOnUiThread { activateChatVoiceAssistant() }
                    }
                }
        )

        // Bridge dark mode display toggle → battery saver backend optimizations
        chatFragment.setDarkModeListener(
                object : ChatPanelFragment.DarkModeListener {
                    override fun onDarkModeChanged(enabled: Boolean) {
                        onDarkModeBatterySaverBridge(enabled)
                    }
                }
        )

        // ── Trackpad gesture engine ──────────────────────────────────
        setupGestureEngine()

        // ── Speech input controller ──────────────────────────────────
        if (USE_NATIVE_STT) {
            speechController =
                    SpeechInputController(
                            this,
                            object : SpeechInputController.Listener {
                                override fun onSpeechResult(text: String) {
                                    handleSpeechResult(text)
                                }

                                override fun onSpeechPartial(text: String) {
                                    handleSpeechPartial(text)
                                }

                                override fun onSpeechStatus(status: String) {
                                    runOnUiThread { updateListeningTranscript(status) }
                                }

                                override fun onSpeechError(errorCode: Int) {
                                    handleSpeechError(errorCode)
                                }
                            }
                    )
        }

        // ── Frame capture manager ────────────────────────────────────
        frameCapture = FrameCaptureManager(this)

        // ── TTS controller (triggered via voice command, not double-tap) ─
        geminiAudioPlayer = GeminiAudioPlayer(this).also { player ->
            player.onDrainComplete = {
                // Called on a background thread once all buffered audio has
                // been played.  NOW arm the silence watchdog so the idle
                // timeout only begins after the user has heard the full reply.
                Log.d(TAG, "GeminiAudioPlayer drain complete — arming silence watchdog")
                runOnUiThread { armSilenceWatchdog() }
            }
        }
        geminiReadoutAudioPlayer = GeminiAudioPlayer(this)
        if (viewModel.preferences.ttsVolume <= 0f) {
            viewModel.preferences.ttsVolume = 0.80f
        }
        ttsController = TtsController(this, viewModel.preferences)
        toneGenerator =
                runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 85) }.getOrNull()

        // ── Wire trackpad scroll → active panel onTrackpadScroll ─────
        gestureEngine.onScroll = { deltaX, deltaY ->
            runOnUiThread {
                if (customKeyboardView?.visibility == View.VISIBLE) {
                    customKeyboardView?.handleTrackpadSwipe(deltaX, deltaY)
                } else {
                    currentTrackpadPanel()?.onTrackpadPan(deltaX, deltaY)
                }
            }
        }

        // ── Observe ViewModel events ─────────────────────────────────
        observeViewModel()
        viewModel.refreshHudUpcomingCalendar(force = true)
        pushHudStateToChatFragment(force = true)
        uiHandler.postDelayed({ pushHudStateToChatFragment(force = true) }, 1500L)
        uiHandler.postDelayed({ pushHudStateToChatFragment(force = true) }, 5000L)
        uiHandler.postDelayed({ pushHudStateToChatFragment(force = true) }, 9000L)
        applyInitialPageSelection()
        viewPager?.post { syncCameraToGeminiState(viewModel.voiceAssistantActive.value == true) }

        // ── Request runtime permissions for mic + camera ─────────────
        requestRequiredPermissions()

        Log.i(TAG, "TapInsight MainActivity created successfully")

        // Step 1 warm-start disabled per user request — the brief
        // tapbrowser → moveTaskToBack flash on cold launch was
        // disruptive. The browser_vision tool will populate
        // BrowserFrameHolder lazily on the user's first explicit
        // browser visit (double-tap → tapbrowser onCreate attaches
        // the WebView). The trade-off: asking "look at this" before
        // ever visiting the browser returns "I can't see the
        // browser right now" instead of a screenshot.
        //
        // To re-enable, uncomment:
        //   uiHandler.postDelayed({ warmStartTapBrowserIfNeeded() }, 2500L)

        // Phase 2 Step 2c.3: feed the chat conversation state into
        // the tapbrowser overlay so the mini chat-card stack over
        // the WebView reflects the real chat. visionclaw is the
        // source of truth — _messages is the StateFlow the chat
        // panel itself renders from. We translate each ChatMessage
        // into a transport-friendly ChatCardBridge.Card so the
        // tapbrowser module doesn't need to depend on visionclaw's
        // package layout. Subscribers in tapbrowser pick up the
        // publish synchronously and hop to their own UI thread.
        lifecycleScope.launch {
            viewModel.messages.collect { messages ->
                val cards = messages.map {
                    com.TapLink.app.unipanel.ChatCardBridge.Card(
                        text = it.text,
                        fromUser = it.fromUser,
                        timestampMs = it.timestampMs
                    )
                }
                com.TapLink.app.unipanel.ChatCardBridge.publish(cards)
            }
        }
    }

    /** One-shot guard so the warm-start fires once per Activity, even
     *  if onResume / configuration-change paths call back into here. */
    private var tapBrowserWarmStartAttempted = false

    /**
     * Best-effort launch of tapbrowser MainActivity so its WebView is
     * attached to [com.TapLink.app.media.BrowserFrameHolder] before the
     * user ever asks "what does this say". Tapbrowser cooperatively
     * sends itself to the back of its task via moveTaskToBack so this
     * doesn't interrupt the chat panel.
     *
     * Skipped when the holder already has a WebView (e.g. tapbrowser
     * was already running from a previous launch). Also skipped if
     * tapbrowser was warm-started this Activity lifetime — we don't
     * need to keep relaunching it on every onResume.
     */
    private fun warmStartTapBrowserIfNeeded() {
        if (tapBrowserWarmStartAttempted) return
        tapBrowserWarmStartAttempted = true
        try {
            if (com.TapLink.app.media.BrowserFrameHolder.hasWebView()) {
                Log.d(TAG, "warmStartTapBrowser: WebView already attached, skipping launch")
                return
            }
            val intent = Intent().setClassName(this, TAP_BROWSER_ACTIVITY_CLASS).apply {
                // NEW_TASK + MULTIPLE_TASK so tapbrowser gets its own
                // task. NO_ANIMATION suppresses the visible transition
                // animation. Tapbrowser onCreate will detect the warm
                // flag and immediately moveTaskToBack(true) once setup
                // is far enough along to have attached the WebView.
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
                putExtra(EXTRA_BROWSER_WARM_START, true)
            }
            Log.i(TAG, "warmStartTapBrowser: launching tapbrowser for background WebView attach")
            startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "warmStartTapBrowser failed: ${e.message}")
            // Reset the guard so a subsequent visionclaw resume can retry.
            tapBrowserWarmStartAttempted = false
        }
    }

    override fun onResume() {
        super.onResume()
        bindProcessToValidatedWifi()
        // ── Silence any TapRadio that slipped past the exit handoff ──
        // The chat / HUD screen must never have a radio station playing in
        // the background. This is a safety net for paths that don't flow
        // through TapBrowser.performDoubleTapBackNavigation (e.g. an orphan
        // ExoPlayer that outlived a prior tapbrowser Activity instance, a
        // crash/recreate cycle, or a cold start with `BrowserPrefs.last_url`
        // still pointing at radio.html?playUrl=…). The upgraded
        // stopOrphanedNativeRadioPlayer(context) also:
        //   - releases the live tapbrowser instance's player + metadata
        //   - navigates any radio.html / podcasts.html / spotify.html WebView
        //     to about:blank so no <audio> element or pending JS timer can
        //     resurrect playback
        //   - sanitizes `BrowserPrefs.last_url` + wipes `webview_state` so
        //     the next launch doesn't auto-replay the stream
        //   - clears all `tapradio_now_playing_*` HUD prefs
        try {
            com.TapLinkX3.app.MainActivity.stopOrphanedNativeRadioPlayer(this)
        } catch (t: Throwable) {
            Log.w(TAG, "stopOrphanedNativeRadioPlayer failed: ${t.message}")
        }
        if (!initialPageSnapDone) {
            initialPageSnapDone = true
            applyInitialPageSelection()
        }
        viewModel.setMultimodalTextureReady(
                coreEyeSurfaceReady && chatFragment.isCoreEyeSurfaceReady()
        )
        syncCameraToGeminiState(viewModel.voiceAssistantActive.value == true)
        handlePanelChanged(viewPager?.currentItem ?: MainViewModel.PANEL_CHAT)
        refreshResearchReadyIndicator()
        refreshTapClawResultReadyIndicator()
        uiHandler.removeCallbacks(hudStatePushRunnable)
        uiHandler.post(hudStatePushRunnable)
        uiHandler.removeCallbacks(notificationPollRunnable)
        uiHandler.post(notificationPollRunnable)
        if (locationPermissionGranted) {
            startLocationTracking()
            refreshLocationSnapshot(force = false)
        }
        viewModel.refreshHudUpcomingCalendar(force = false)
        exitTextInputMode()
        if (pendingFocusNewChatOnResume) {
            chatFragment.view?.post {
                chatFragment.focusNewChatCard(animate = false)
                pendingFocusNewChatOnResume = false
            }
        }
    }

    override fun onPause() {
        uiHandler.removeCallbacks(delayedVoiceStartRunnable)
        uiHandler.removeCallbacks(stopGeminiCaptureRunnable)
        uiHandler.removeCallbacks(cameraIdleTimeoutRunnable)
        uiHandler.removeCallbacks(chatHudIdleRunnable)
        uiHandler.removeCallbacks(hudStatePushRunnable)
        uiHandler.removeCallbacks(notificationPollRunnable)
        pendingCameraStart = false
        assistantSessionStartsAudioOnly = false
        releaseGeminiAudioCapture(cancelOnly = true)
        stopAllSpeechPlayback()
        hideCustomKeyboard(clearFocus = true)
        stopCameraCapture()
        stopLocationTracking()
        // Persist current chat so next session can reference it
        viewModel.saveChatContextForNextSession()
        super.onPause()
    }

    override fun onDestroy() {
        companionServer?.stopServer()
        companionServer = null
        gestureEngine.release()
        chatFragment.setCoreEyeSurfaceListener(null)
        chatFragment.setCardActionListener(null)
        speechController?.destroy()
        assistantSessionStartsAudioOnly = false
        activeReadoutJob?.cancel()
        activeReadoutJob = null
        speechStopJob?.cancel()
        speechStopJob = null
        releaseGeminiAudioCapture(cancelOnly = true)
        viewModel.setMultimodalTextureReady(false)
        viewModel.setMultimodalCameraEnabled(false)
        frameCapture?.shutdown()
        geminiAudioPlayer?.release()
        geminiAudioPlayer = null
        geminiReadoutAudioPlayer?.release()
        geminiReadoutAudioPlayer = null
        ttsController?.shutdown()
        toneGenerator?.release()
        toneGenerator = null
        viewPager = null
        hudNotification = null
        holdProgressBar = null
        listeningOverlay = null
        listeningTranscript = null
        voiceOscilloscope = null
        customKeyboardView = null
        activeTextInput = null
        uiHandler.removeCallbacksAndMessages(null)
        uiHandler.removeCallbacks(cameraIdleTimeoutRunnable)
        stopLocationTracking()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        configureImmersiveDisplay()
        // Recalculate edge zones + gesture side awareness on config change
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        edgeZoneLeft = screenWidth * EDGE_ZONE_PERCENTAGE
        edgeZoneRight = screenWidth * (1f - EDGE_ZONE_PERCENTAGE)
        gestureEngine.setScreenSize(screenWidth, screenHeight)
    }

    // ══════════════════════════════════════════════════════════════════════
    // Runtime Permissions
    // ══════════════════════════════════════════════════════════════════════

    private fun requestRequiredPermissions() {
        val needed = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED
        ) {
            micPermissionGranted = true
        } else {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                        PackageManager.PERMISSION_GRANTED
        ) {
            cameraPermissionGranted = true
        } else {
            needed.add(Manifest.permission.CAMERA)
        }

        val fineGranted =
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                        PackageManager.PERMISSION_GRANTED
        val coarseGranted =
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                        PackageManager.PERMISSION_GRANTED
        locationPermissionGranted = fineGranted || coarseGranted
        if (!locationPermissionGranted) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
            needed.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        // Unipanel v2: POST_NOTIFICATIONS is the gatekeeper for the
        // foreground-service notification that keeps the AudioRecord
        // alive on Android 11+. Without an actually-posted notification
        // the FGS counts as silently-killed, the OS yanks our mic
        // privilege, and Gemini sits forever in "listening" with no
        // audio reaching it. Runtime grant is required on Android 13+
        // (TIRAMISU); older builds get the permission at install time
        // via the manifest entry.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                needed.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        } else {
            syncCameraToGeminiState(viewModel.voiceAssistantActive.value == true)
            startLocationTracking()
            refreshLocationSnapshot(force = true)
        }
    }

    private fun startLocationTracking() {
        if (locationTrackingActive || !locationPermissionGranted) return

        val manager =
                locationManager
                        ?: getSystemService(LocationManager::class.java)?.also {
                            locationManager = it
                        }
                        ?: return

        publishBestLastKnownLocation(manager)

        var requested = false
        val hasFine =
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                        PackageManager.PERMISSION_GRANTED
        val hasCoarse =
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                        PackageManager.PERMISSION_GRANTED

        if (hasFine && manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            runCatching {
                        manager.requestLocationUpdates(
                                LocationManager.GPS_PROVIDER,
                                LOCATION_MIN_TIME_MS,
                                LOCATION_MIN_DISTANCE_METERS,
                                locationListener,
                                Looper.getMainLooper()
                        )
                    }
                    .onSuccess { requested = true }
                    .onFailure { Log.w(TAG, "Failed to request GPS updates: ${it.message}") }
        }

        if ((hasFine || hasCoarse) && manager.isProviderEnabled(LocationManager.FUSED_PROVIDER)) {
            runCatching {
                        manager.requestLocationUpdates(
                                LocationManager.FUSED_PROVIDER,
                                LOCATION_MIN_TIME_MS,
                                LOCATION_MIN_DISTANCE_METERS,
                                locationListener,
                                Looper.getMainLooper()
                        )
                    }
                    .onSuccess { requested = true }
                    .onFailure { Log.w(TAG, "Failed to request fused location updates: ${it.message}") }
        }

        if (hasCoarse && manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            runCatching {
                        manager.requestLocationUpdates(
                                LocationManager.NETWORK_PROVIDER,
                                LOCATION_MIN_TIME_MS,
                                LOCATION_MIN_DISTANCE_METERS,
                                locationListener,
                                Looper.getMainLooper()
                        )
                    }
                    .onSuccess { requested = true }
                    .onFailure { Log.w(TAG, "Failed to request network location updates: ${it.message}") }
        }

        if (hasCoarse && manager.isProviderEnabled(LocationManager.PASSIVE_PROVIDER)) {
            runCatching {
                        manager.requestLocationUpdates(
                                LocationManager.PASSIVE_PROVIDER,
                                LOCATION_MIN_TIME_MS,
                                LOCATION_MIN_DISTANCE_METERS,
                                locationListener,
                                Looper.getMainLooper()
                        )
                    }
                    .onSuccess { requested = true }
                    .onFailure { Log.w(TAG, "Failed to request passive location updates: ${it.message}") }
        }

        locationTrackingActive = requested
        if (requested) {
            Log.i(TAG, "Location tracking enabled")
            refreshLocationSnapshot(force = false)
        } else {
            Log.w(TAG, "Location tracking unavailable; no providers registered")
            refreshLocationSnapshot(force = true)
        }
    }

    private fun stopLocationTracking() {
        if (!locationTrackingActive) return
        runCatching { locationManager?.removeUpdates(locationListener) }
                .onFailure { Log.w(TAG, "Failed to remove location updates: ${it.message}") }
        locationTrackingActive = false
    }

    private fun publishBestLastKnownLocation(manager: LocationManager) {
        val hasFine =
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                        PackageManager.PERMISSION_GRANTED
        val hasCoarse =
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                        PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) return

        val providers = mutableListOf<String>()
        if (hasFine) {
            providers += LocationManager.GPS_PROVIDER
        }
        if (hasFine || hasCoarse) {
            providers += LocationManager.FUSED_PROVIDER
        }
        if (hasCoarse) {
            providers += LocationManager.NETWORK_PROVIDER
            providers += LocationManager.PASSIVE_PROVIDER
        }

        var best: Location? = null
        providers.forEach { provider ->
            val candidate = runCatching { manager.getLastKnownLocation(provider) }.getOrNull() ?: return@forEach
            best = selectBetterLocation(current = best, candidate = candidate)
        }
        best?.takeIf {
            val ageMs = System.currentTimeMillis() - (it.time.takeIf { ts -> ts > 0L } ?: System.currentTimeMillis())
            val accuracy = if (it.hasAccuracy()) it.accuracy else Float.MAX_VALUE
            ageMs <= LOCATION_SNAPSHOT_MAX_AGE_MS && accuracy <= 500f
        }?.let { publishDeviceLocationContext(it) }
    }

    private fun selectBetterLocation(current: Location?, candidate: Location): Location {
        if (current == null) return candidate
        val candidateTime = candidate.time
        val currentTime = current.time
        val candidateAccuracy = if (candidate.hasAccuracy()) candidate.accuracy else Float.MAX_VALUE
        val currentAccuracy = if (current.hasAccuracy()) current.accuracy else Float.MAX_VALUE
        val timeDelta = candidateTime - currentTime

        return when {
            timeDelta > 120_000L -> candidate
            timeDelta < -120_000L -> current
            candidateAccuracy + 10f < currentAccuracy -> candidate
            timeDelta > 0L && candidateAccuracy <= currentAccuracy + 50f -> candidate
            else -> current
        }
    }

    private fun publishDeviceLocationContext(location: Location) {
        publishDeviceLocationContext(
            DeviceLocationContext(
                latitude = location.latitude,
                longitude = location.longitude,
                accuracyMeters = if (location.hasAccuracy()) location.accuracy else null,
                altitudeMeters = if (location.hasAltitude()) location.altitude else null,
                speedMps = if (location.hasSpeed()) location.speed else null,
                bearingDeg = if (location.hasBearing()) location.bearing else null,
                provider = location.provider,
                timestampMs = location.time.takeIf { it > 0L } ?: System.currentTimeMillis()
            )
        )
    }

    private fun publishDeviceLocationContext(context: DeviceLocationContext) {
        val current = viewModel.getDeviceLocationContext()
        if (!shouldAcceptLocationUpdate(current, context)) {
            Log.d(
                TAG,
                "Ignoring lower-quality location update provider=${context.provider} lat=${context.latitude} lon=${context.longitude} acc=${context.accuracyMeters}"
            )
            return
        }
        viewModel.updateDeviceLocationContext(context)
        Log.d(
                TAG,
                "Location update provider=${context.provider} lat=${context.latitude} lon=${context.longitude} acc=${context.accuracyMeters}"
        )
        viewModel.refreshHudUpcomingCalendar(force = false)
        runOnUiThread {
            pushHudStateToChatFragment(force = true)
        }
    }

    private fun getToolReadyLocationContext(): DeviceLocationContext? {
        viewModel.getDeviceLocationContext()?.takeIf(::isPreciseLocationContext)?.let { return it }
        deviceLocationResolver.peekCached(
            maxAgeMs = LOCATION_PRECISE_MAX_AGE_MS,
            maxAccuracyMeters = LOCATION_PRECISE_MAX_ACCURACY_METERS,
            allowApproximate = false
        )?.let { cached ->
            publishDeviceLocationContext(cached)
            return viewModel.getDeviceLocationContext()?.takeIf(::isPreciseLocationContext) ?: cached
        }
        val resolved = deviceLocationResolver.resolveNavigationBlocking()
        if (resolved != null) {
            publishDeviceLocationContext(resolved)
        }
        return viewModel.getDeviceLocationContext()?.takeIf(::isPreciseLocationContext) ?: resolved
    }

    private fun ensureLiveSessionLocationContext(): DeviceLocationContext? {
        val current = viewModel.getDeviceLocationContext()
        if (current != null &&
            current.provider != "ip_geolocation" &&
            System.currentTimeMillis() - current.timestampMs <= LOCATION_SNAPSHOT_MAX_AGE_MS
        ) {
            return current
        }
        deviceLocationResolver.peekCached(
            maxAgeMs = LOCATION_SNAPSHOT_MAX_AGE_MS,
            maxAccuracyMeters = 1_000f,
            allowApproximate = false
        )?.let { cached ->
            publishDeviceLocationContext(cached)
            return cached
        }
        val fallback = deviceLocationResolver.resolveBlocking(
            maxAgeMs = LOCATION_SNAPSHOT_MAX_AGE_MS,
            timeoutMs = 1_200L,
            requirePrecise = false,
            allowApproximateFallback = true
        )
        if (fallback != null) {
            publishDeviceLocationContext(fallback)
        }
        return viewModel.getDeviceLocationContext() ?: fallback
    }

    private suspend fun prefetchLiveNearbyPlaceSnapshot(location: DeviceLocationContext) {
        val placesClient = livePlacesClient ?: return
        if (viewModel.preferences.googleMapsApiKey.isBlank()) {
            viewModel.clearNearbyPlaceSnapshot()
            return
        }

        val result = withTimeoutOrNull(1_800L) {
            placesClient.searchText(
                textQuery = "points of interest",
                latitude = location.latitude,
                longitude = location.longitude,
                radiusMeters = 1_500.0,
                pageSize = 5
            )
        }

        when (result) {
            is com.rayneo.visionclaw.core.network.GooglePlacesClient.PlacesResult.Success -> {
                val summary = formatNearbyPlaceSnapshot(result.places)
                if (summary.isNotBlank()) {
                    viewModel.updateNearbyPlaceSnapshot(
                        latitude = location.latitude,
                        longitude = location.longitude,
                        summary = summary
                    )
                    Log.d(TAG, "Prepared nearby place snapshot: $summary")
                }
            }
            is com.rayneo.visionclaw.core.network.GooglePlacesClient.PlacesResult.ApiKeyMissing -> {
                viewModel.clearNearbyPlaceSnapshot()
            }
            is com.rayneo.visionclaw.core.network.GooglePlacesClient.PlacesResult.Error -> {
                Log.w(TAG, "Nearby place snapshot failed: ${result.message}")
            }
            null -> {
                Log.d(TAG, "Nearby place snapshot timed out during Gemini Live startup")
            }
        }
    }

    private fun formatNearbyPlaceSnapshot(
        places: List<com.rayneo.visionclaw.core.network.GooglePlacesClient.NearbyPlace>
    ): String {
        return places
            .asSequence()
            .filter { it.name.isNotBlank() }
            .distinctBy { "${it.name.lowercase(Locale.US)}|${it.shortAddress.lowercase(Locale.US)}" }
            .take(4)
            .joinToString(" | ") { place ->
                buildString {
                    append(place.name)
                    place.shortAddress.takeIf { it.isNotBlank() }?.let {
                        append(" — ")
                        append(it)
                    }
                    place.types.firstOrNull()
                        ?.replace('_', ' ')
                        ?.takeIf { it.isNotBlank() }
                        ?.let {
                            append(" — ")
                            append(it)
                        }
                    when (place.isOpen) {
                        true -> append(" — open")
                        false -> append(" — closed")
                        null -> Unit
                    }
                }
            }
    }

    private fun isPreciseLocationContext(context: DeviceLocationContext): Boolean {
        val ageMs = System.currentTimeMillis() - context.timestampMs
        val accuracy = context.accuracyMeters ?: Float.MAX_VALUE
        return ageMs <= LOCATION_PRECISE_MAX_AGE_MS &&
            accuracy <= LOCATION_PRECISE_MAX_ACCURACY_METERS &&
            context.provider != "ip_geolocation"
    }

    private fun isLowConfidenceLocationContext(context: DeviceLocationContext): Boolean {
        val accuracy = context.accuracyMeters ?: Float.MAX_VALUE
        return context.provider == "ip_geolocation" || accuracy > 1_000f
    }

    private fun distanceMeters(a: DeviceLocationContext, b: DeviceLocationContext): Float {
        val results = FloatArray(1)
        Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, results)
        return results.firstOrNull() ?: Float.MAX_VALUE
    }

    private fun shouldAcceptLocationUpdate(
        current: DeviceLocationContext?,
        candidate: DeviceLocationContext
    ): Boolean {
        if (current == null) return candidate.provider != "ip_geolocation" || candidate.accuracyMeters != null
        val candidateAgeMs = System.currentTimeMillis() - candidate.timestampMs
        if (candidateAgeMs > LOCATION_SNAPSHOT_MAX_AGE_MS) return false

        val timeDelta = candidate.timestampMs - current.timestampMs
        val candidateAccuracy = candidate.accuracyMeters ?: Float.MAX_VALUE
        val currentAccuracy = current.accuracyMeters ?: Float.MAX_VALUE
        val currentApproximate = current.provider == "ip_geolocation"
        val candidateApproximate = candidate.provider == "ip_geolocation"
        val candidateTriangulated =
            candidate.provider == "wifi_geolocation" || candidate.provider == "network_geolocation"
        val currentIsPhoneBridge = current.provider == "companion_phone"
        val candidateIsPhoneBridge = candidate.provider == "companion_phone"
        val phoneBridgeEnabled = viewModel.preferences.phoneLocationBridgeEnabled
        val currentDistanceToCandidate = distanceMeters(current, candidate)

        if (phoneBridgeEnabled) {
            if (candidateIsPhoneBridge) return true
            if (currentIsPhoneBridge) {
                val currentPhoneAgeMs = System.currentTimeMillis() - current.timestampMs
                if (currentPhoneAgeMs <= 60_000L) return false
            }
        }

        if (candidateApproximate && !currentApproximate) return false
        if (isLowConfidenceLocationContext(candidate) &&
            !isLowConfidenceLocationContext(current) &&
            currentDistanceToCandidate > LOCATION_REJECT_LOW_CONFIDENCE_JUMP_METERS
        ) {
            Log.w(
                TAG,
                "Rejecting low-confidence far jump provider=${candidate.provider} distance=${currentDistanceToCandidate.toInt()}m acc=${candidate.accuracyMeters}"
            )
            return false
        }

        return when {
            currentApproximate && candidate.provider != "ip_geolocation" -> true
            timeDelta > 120_000L -> true
            timeDelta < -120_000L -> false
            candidate.provider == LocationManager.GPS_PROVIDER && current.provider != LocationManager.GPS_PROVIDER &&
                candidateAccuracy <= currentAccuracy + 25f -> true
            candidate.provider == LocationManager.FUSED_PROVIDER && current.provider == LocationManager.NETWORK_PROVIDER &&
                candidateAccuracy <= currentAccuracy + 25f -> true
            candidateAccuracy + 25f < currentAccuracy -> true
            candidateTriangulated && currentAccuracy > 1_000f && candidateAccuracy <= 250f -> true
            timeDelta > 0L && candidateAccuracy <= currentAccuracy + 50f -> true
            else -> false
        }
    }

    private fun pushHudStateToChatFragment(force: Boolean) {
        syncTapRadioHudStateFromPrefs()
        val calendarSummary = viewModel.calendarSummary.value
        val tasksSummary = viewModel.tasksSummary.value
        val newsSummary = viewModel.newsSummary.value
        val airQualityState = viewModel.airQualitySummary.value
        val radioState = viewModel.radioSummary.value
        val changed = force ||
            calendarSummary != lastPushedCalendarSummary ||
            tasksSummary != lastPushedTasksSummary ||
            newsSummary != lastPushedNewsSummary ||
            airQualityState?.text != lastPushedAqiText ||
            airQualityState?.aqi != lastPushedAqiValue ||
            radioState?.stationName != lastPushedRadioName ||
            (radioState?.playing == true) != lastPushedRadioPlaying
        if (!changed) return
        lastPushedCalendarSummary = calendarSummary
        lastPushedTasksSummary = tasksSummary
        lastPushedNewsSummary = newsSummary
        lastPushedAqiText = airQualityState?.text
        lastPushedAqiValue = airQualityState?.aqi
        lastPushedRadioName = radioState?.stationName
        lastPushedRadioPlaying = radioState?.playing == true
        chatFragment.syncHudSnapshot(
            calendarSummary = calendarSummary,
            tasksSummary = tasksSummary,
            newsSummary = newsSummary,
            airQualityState = airQualityState,
            radioState = radioState
        )
        publishUnipanelHudSnapshot(
            calendarSummary = calendarSummary,
            tasksSummary = tasksSummary,
            newsSummary = newsSummary,
            airQualityState = airQualityState,
            radioState = radioState
        )
    }

    private fun publishUnipanelHudSnapshot(
        calendarSummary: String,
        tasksSummary: String,
        newsSummary: String,
        airQualityState: MainViewModel.AirQualityHudState?,
        radioState: MainViewModel.RadioHudState? = null
    ) {
        com.TapLink.app.unipanel.HudStateBridge.update { state ->
            state.copy(
                calendarSummary = calendarSummary,
                tasksSummary = tasksSummary,
                newsSummary = newsSummary,
                airQualityText = airQualityState?.text,
                airQualityValue = airQualityState?.aqi,
                radioStation = radioState?.stationName,
                radioPlaying = radioState?.playing == true
            )
        }
    }

    private fun publishUnipanelGatewayStatus(
        hermes: Boolean,
        status: ChatPanelFragment.OpenClawGatewayStatus
    ) {
        val bridgeStatus = when (status) {
            ChatPanelFragment.OpenClawGatewayStatus.HIDDEN ->
                com.TapLink.app.unipanel.HudStateBridge.GatewayStatus.HIDDEN
            ChatPanelFragment.OpenClawGatewayStatus.GOOD ->
                com.TapLink.app.unipanel.HudStateBridge.GatewayStatus.GOOD
            ChatPanelFragment.OpenClawGatewayStatus.BAD ->
                com.TapLink.app.unipanel.HudStateBridge.GatewayStatus.BAD
        }
        com.TapLink.app.unipanel.HudStateBridge.update { state ->
            if (hermes) state.copy(hermesStatus = bridgeStatus)
            else state.copy(openClawStatus = bridgeStatus)
        }
    }

    private fun publishUnipanelHeartbeat(
        label: String?,
        persistent: Boolean,
        scroll: Boolean
    ) {
        com.TapLink.app.unipanel.HudStateBridge.update { state ->
            state.copy(
                heartbeatMessage = label,
                heartbeatPersistent = persistent,
                heartbeatShouldScroll = scroll
            )
        }
    }

    private fun syncTapRadioHudStateFromPrefs() {
        // The chat / HUD screen intentionally never surfaces the TapRadio
        // "now playing" station — when the user is looking at the HUD, no
        // radio should be playing at all (onResume enforces that, and the
        // double-tap exit handler in tapbrowser clears the player). This
        // function used to read the now-playing prefs and mirror them to
        // the HUD; it now just pushes an empty state unconditionally so
        // the HUD radio line is always hidden.
        viewModel.updateRadioHudState(stationName = null, genre = null, playing = false)
    }

    private fun refreshLocationSnapshot(force: Boolean) {
        if (!locationPermissionGranted) return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastLocationSnapshotRefreshElapsedMs < LOCATION_SNAPSHOT_REFRESH_DEBOUNCE_MS) {
            return
        }
        lastLocationSnapshotRefreshElapsedMs = now
        lifecycleScope.launch(Dispatchers.IO) {
            val snapshot =
                deviceLocationResolver.resolve(
                    maxAgeMs = LOCATION_SNAPSHOT_MAX_AGE_MS,
                    timeoutMs = LOCATION_SNAPSHOT_TIMEOUT_MS
                ) ?: return@launch
            publishDeviceLocationContext(snapshot)
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // XR Origin Safety
    // ══════════════════════════════════════════════════════════════════════

    private fun initXrOriginSafe() {
        try {
            val xrDisplayManager = getSystemService("xr_display")
            if (xrDisplayManager == null) {
                Log.w(TAG, "XR display subsystem is null — running in fallback 2D mode")
                return
            }
            Log.d(TAG, "XR display subsystem initialised: $xrDisplayManager")

            // Attach vendor trackpad bridge if ARDK is available on-device
            val bridge = RayNeoArdkTrackpadBridge()
            val attached = bridge.attachIfAvailable(this)
            Log.d(TAG, "Vendor trackpad bridge attached: $attached")

            try {
                val cameraManager = getSystemService(CAMERA_SERVICE)
                if (cameraManager == null) {
                    Log.w(TAG, "Camera service is null — photo features disabled")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Camera subsystem init failed", e)
            }
        } catch (e: Exception) {
            Log.e(TAG, "XR Origin init failed — running in 2D fallback mode", e)
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Immersive Display (6 000-nit MicroLED optimised)
    // ══════════════════════════════════════════════════════════════════════

    @SuppressLint("WrongConstant")
    private fun configureImmersiveDisplay() {
        WindowCompat.setDecorFitsSystemWindows(window, false)

        window.apply {
            addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            applyTransparentSystemBarColors()

            val lp = attributes
            // Honour the user's saved brightness preference. The default
            // (DEFAULT_SCREEN_BRIGHTNESS = 1.0f) still maxes the panel out,
            // matching the legacy BRIGHTNESS_OVERRIDE_FULL behaviour for
            // anyone who never touches the slider.
            lp.screenBrightness = runCatching {
                viewModel.preferences.screenBrightness
            }.getOrDefault(com.rayneo.visionclaw.core.storage.AppPreferences.DEFAULT_SCREEN_BRIGHTNESS)
            attributes = lp
        }

        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        window.decorView.setBackgroundColor(Color.BLACK)
    }

    @Suppress("DEPRECATION")
    private fun applyTransparentSystemBarColors() {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
    }

    /**
     * Applies the given brightness to this window immediately and persists
     * it to preferences so subsequent launches start at the same level.
     * The SettingsPanelFragment slider funnels through here.
     *
     * @param value 0.05f–1.0f. Values outside the range are clamped.
     */
    fun setScreenBrightness(value: Float) {
        val clamped = value.coerceIn(0.05f, 1f)
        viewModel.preferences.screenBrightness = clamped
        window.attributes = window.attributes.apply {
            screenBrightness = clamped
        }
    }

    fun enterTextInputMode() {
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING or
                        WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
        )
        hideSystemIme()
    }

    fun exitTextInputMode() {
        hideCustomKeyboard(clearFocus = true)
        configureImmersiveDisplay()
        window.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING or
                        WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
        )
    }

    fun showCustomKeyboardFor(target: EditText) {
        activeTextInput = target
        target.showSoftInputOnFocus = false
        target.requestFocus()
        target.requestFocusFromTouch()
        target.isCursorVisible = true
        target.setSelection(target.text?.length ?: 0)
        enterTextInputMode()
        customKeyboardView?.visibility = View.VISIBLE
        customKeyboardView?.bringToFront()
        customKeyboardView?.post { customKeyboardView?.focusHideButton() }
    }

    fun hideCustomKeyboard(clearFocus: Boolean = false) {
        hideSystemIme()
        customKeyboardView?.visibility = View.GONE
        if (clearFocus) {
            activeTextInput?.clearFocus()
            currentFocus?.clearFocus()
            activeTextInput = null
        }
    }

    private fun hideSystemIme() {
        WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.ime())
        val imm = getSystemService(InputMethodManager::class.java)
        val token = currentFocus?.windowToken ?: window.decorView.windowToken
        imm?.hideSoftInputFromWindow(token, 0)
    }

    private fun setupCustomKeyboard() {
        customKeyboardView?.setOnKeyboardActionListener(
                object : CustomKeyboardView.OnKeyboardActionListener {
                    override fun onKeyPressed(key: String) {
                        withActiveInput { target ->
                            val start = target.selectionStart.coerceAtLeast(0)
                            val end = target.selectionEnd.coerceAtLeast(0)
                            val min = minOf(start, end)
                            val max = maxOf(start, end)
                            target.text?.replace(min, max, key)
                            target.setSelection(min + key.length)
                        }
                    }

                    override fun onBackspacePressed() {
                        withActiveInput { target ->
                            val start = target.selectionStart.coerceAtLeast(0)
                            val end = target.selectionEnd.coerceAtLeast(0)
                            val min = minOf(start, end)
                            val max = maxOf(start, end)
                            when {
                                min != max -> {
                                    target.text?.delete(min, max)
                                    target.setSelection(min)
                                }
                                min > 0 -> {
                                    target.text?.delete(min - 1, min)
                                    target.setSelection(min - 1)
                                }
                            }
                        }
                    }

                    override fun onEnterPressed() {
                        withActiveInput { target ->
                            val handled =
                                    target.dispatchKeyEvent(
                                            KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)
                                    ) ||
                                            target.dispatchKeyEvent(
                                                    KeyEvent(
                                                            KeyEvent.ACTION_UP,
                                                            KeyEvent.KEYCODE_ENTER
                                                    )
                                            )
                            if (!handled) {
                                target.text?.append("\n")
                                target.setSelection(target.text?.length ?: 0)
                            }
                        }
                    }

                    override fun onHideKeyboard() {
                        hideCustomKeyboard(clearFocus = true)
                    }

                    override fun onClearPressed() {
                        withActiveInput { target ->
                            target.text?.clear()
                            target.setSelection(0)
                        }
                    }

                    override fun onMoveCursorLeft() {
                        withActiveInput { target ->
                            val pos = target.selectionStart.coerceAtLeast(0)
                            target.setSelection((pos - 1).coerceAtLeast(0))
                        }
                    }

                    override fun onMoveCursorRight() {
                        withActiveInput { target ->
                            val pos = target.selectionStart.coerceAtLeast(0)
                            val max = target.text?.length ?: 0
                            target.setSelection((pos + 1).coerceAtMost(max))
                        }
                    }

                }
        )
        customKeyboardView?.visibility = View.GONE
    }

    private inline fun withActiveInput(action: (EditText) -> Unit) {
        val cached = activeTextInput?.takeIf { it.isAttachedToWindow }
        val target = cached ?: (currentFocus as? EditText) ?: return
        activeTextInput = target
        target.showSoftInputOnFocus = false
        hideSystemIme()
        action(target)
    }

    // ══════════════════════════════════════════════════════════════════════
    // ViewPager (Chat HUD host)
    // ══════════════════════════════════════════════════════════════════════

    private fun setupViewPager() {
        val pager =
                viewPager
                        ?: run {
                            Log.e(TAG, "ViewPager is null — cannot set up panels")
                            return
                        }

        pager.adapter = MainPagerAdapter(this, chatFragment)
        pager.offscreenPageLimit = 1
        pager.isUserInputEnabled = false
        pager.isSaveEnabled = false

        pager.registerOnPageChangeCallback(
                object : ViewPager2.OnPageChangeCallback() {
                    override fun onPageSelected(position: Int) {
                        viewModel.setActivePanel(position)
                        handlePanelChanged(position)
                    }
                }
        )
    }

    private fun applyInitialPageSelection() {
        val pager = viewPager ?: return
        pager.setCurrentItem(MainViewModel.PANEL_CHAT, false)
        viewModel.setActivePanel(MainViewModel.PANEL_CHAT)
        pager.post {
            pager.setCurrentItem(MainViewModel.PANEL_CHAT, false)
            viewModel.setActivePanel(MainViewModel.PANEL_CHAT)
            handlePanelChanged(MainViewModel.PANEL_CHAT)
        }
    }

    private fun handlePanelChanged(position: Int) {
        if (position != MainViewModel.PANEL_CHAT) return
        chatFragment.setHudModeEnabled(false)
        if (pendingFocusNewChatOnResume) {
            chatFragment.view?.post {
                chatFragment.focusNewChatCard(animate = false)
                pendingFocusNewChatOnResume = false
            }
        }
        scheduleChatHudIdleTimer()
    }

    private fun scheduleChatHudIdleTimer() {
        uiHandler.removeCallbacks(chatHudIdleRunnable)
        if (viewPager?.currentItem != MainViewModel.PANEL_CHAT) return
        if (chatFragment.isHudModeEnabled()) return
        uiHandler.postDelayed(chatHudIdleRunnable, CHAT_HUD_IDLE_TIMEOUT_MS)
    }

    private fun markTrackpadActivity() {
        if (viewPager?.currentItem != MainViewModel.PANEL_CHAT) return
        if (chatFragment.isHudModeEnabled()) return
        scheduleChatHudIdleTimer()
    }

    /**
     * Right-arm double-tap behaviour:
     *   1. Reader-expanded card  → exit reader mode
     *   2. Gemini active         → end the Gemini session (regardless of
     *                              whether the camera is on)
     *   3. Gemini NOT active     → launch TapBrowser
     *
     * Camera mode is no longer toggled from the right-arm double-tap —
     * left-arm single-tap now owns camera on/off, see
     * [toggleCameraDuringGemini]. The right-arm double-tap is purely a
     * "close conversation" gesture during Gemini sessions.
     */
    private fun cyclePanelViaDoubleTap() {
        uiHandler.removeCallbacks(chatHudIdleRunnable)

        // ── If the chat panel is showing a reader-expanded card, double-tap
        //    closes it back to the normal chat carousel. ──
        val currentPanel = viewPager?.currentItem ?: MainViewModel.PANEL_CHAT
        if (currentPanel == MainViewModel.PANEL_CHAT && chatFragment.isReaderModeActive()) {
            chatFragment.exitReaderModeFromOutside()
            return
        }

        val geminiActive = viewModel.voiceAssistantActive.value == true

        if (geminiActive) {
            // Gemini active → end the session. Camera state doesn't matter;
            // shutdownMultimodalSession will tear down the frame pipeline
            // along with the WebSocket. (Left-arm single-tap is the
            // camera-toggle gesture; right-arm double-tap is the close
            // gesture.)
            shutdownMultimodalSession("Session ended.")
            return
        }

        // Not active → launch TapBrowser
        launchTapBrowser()
    }

    /**
     * Left-arm (cyttsp6_mt) single-tap behaviour during a Gemini Live
     * session: toggle the camera between ON (multimodal/video) and OFF
     * (audio-only) WITHOUT ending the session.
     *
     * If no Gemini session is active, this is a no-op — the left arm is
     * left alone outside Gemini so its normal scroll/swipe behavior is
     * preserved (the consumedByLeftArmTap() filter in dispatchTouchEvent
     * already gates this method on viewModel.voiceAssistantActive, but
     * we re-check here defensively).
     */
    private fun toggleCameraDuringGemini() {
        uiHandler.removeCallbacks(chatHudIdleRunnable)

        val geminiActive = viewModel.voiceAssistantActive.value == true
        if (!geminiActive) {
            // Defensive: caller should have already gated on this.
            return
        }

        if (cameraCaptureActive) {
            // Camera ON → switch back to audio-only WITHOUT ending the
            // Gemini session. stopCameraCapture() shuts down the frame
            // pipeline only; the Gemini Live socket stays open.
            stopCameraCapture()
            assistantSessionStartsAudioOnly = true
            showHudNotification("Audio only")
            return
        }

        // Camera OFF → switch to camera/video mode.
        if (!cameraPermissionGranted) {
            // No permission — surface the issue but DO NOT end the
            // session (right-arm double-tap is the close gesture, not
            // this one). The user can still talk to Gemini in audio mode.
            showHudNotification("Camera permission required")
            return
        }
        assistantSessionStartsAudioOnly = false
        startCameraCapture()
        showHudNotification("Camera mode")
    }

    private fun launchTapBrowser(
        initialUrl: String? = null,
        youtubeAutoplayQuery: String? = null,
        youtubeAutoplayMode: String? = null,
        youtubeAutoplayQueue: List<String>? = null
    ) {
        // URLPipe diagnostic: log every TapBrowser launch with the URL EXACTLY
        // as it arrives at the activity boundary. Pair with `URLPipe/extract`,
        // `URLPipe/tap`, `URLPipe/open` to walk the full chain when a URL
        // arrives malformed in the browser.
        Log.d(
            TAG,
            "URLPipe/browser initialUrl='${initialUrl ?: ""}' " +
                "ytQuery='${youtubeAutoplayQuery ?: ""}' " +
                "ytQueueSize=${youtubeAutoplayQueue?.size ?: 0}"
        )
        // Inject saved cookies from companion app into WebView CookieManager
        // before launching TapBrowser (same APK = shared CookieManager).
        injectSavedBrowserCookies()
        pendingFocusNewChatOnResume = true

        // Record the URL we are about to open as "ground truth" so tools
        // downstream (tapclaw_agent email/share flows) can substitute it in
        // for any hallucinated URL Gemini might otherwise invent. Pair with
        // the autoplay query so "email me this video" has a readable topic.
        runCatching {
            val urlToRecord = initialUrl?.trim().orEmpty()
            if (urlToRecord.isNotBlank()) {
                com.rayneo.visionclaw.core.storage.LastUrlStore(this@MainActivity).record(
                    url = urlToRecord,
                    title = youtubeAutoplayQuery?.takeIf { it.isNotBlank() },
                    topic = youtubeAutoplayQuery?.takeIf { it.isNotBlank() }
                )
            }
        }.onFailure { Log.w(TAG, "LastUrlStore.record failed: ${it.message}") }

        val cleanYouTubeQueue = youtubeAutoplayQueue
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.distinctBy { it.lowercase(Locale.US) }
            .orEmpty()
        cacheRecentYouTubePlaybackContext(
            query = youtubeAutoplayQuery,
            mode = youtubeAutoplayMode,
            queue = cleanYouTubeQueue
        )

        if (!youtubeAutoplayQuery.isNullOrBlank() || cleanYouTubeQueue.isNotEmpty()) {
            runCatching {
                val browserClass = Class.forName(TAP_BROWSER_ACTIVITY_CLASS)
                val method = browserClass.getMethod("prepareForIncomingYouTubeAutoplay")
                method.invoke(null)
                Log.d("VisionClaw", "Prepared TapBrowser for incoming YouTube autoplay handoff")
            }
        }

        val intent =
                Intent().setClassName(this, TAP_BROWSER_ACTIVITY_CLASS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra(EXTRA_RETURN_TO_CHAT_ON_DOUBLE_TAP, true)
                    initialUrl
                            ?.trim()
                            ?.takeIf { it.isNotBlank() }
                            ?.let { putExtra(EXTRA_BROWSER_INITIAL_URL, it) }
                    youtubeAutoplayQuery
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                        ?.let { putExtra(EXTRA_YOUTUBE_AUTOPLAY_QUERY, it) }
                    youtubeAutoplayMode
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                        ?.let { putExtra(EXTRA_YOUTUBE_AUTOPLAY_MODE, it) }
                    cleanYouTubeQueue
                        .takeIf { it.size > 1 }
                        ?.let { putExtra(EXTRA_YOUTUBE_AUTOPLAY_QUEUE, org.json.JSONArray(it).toString()) }
                }

        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            showHudNotification("TapBrowser module is unavailable")
        } finally {
            viewModel.setActivePanel(MainViewModel.PANEL_CHAT)
            viewPager?.setCurrentItem(MainViewModel.PANEL_CHAT, false)
            chatFragment.setHudModeEnabled(false)
            scheduleChatHudIdleTimer()
        }
    }

    private fun cacheRecentYouTubePlaybackContext(
        query: String?,
        mode: String?,
        queue: List<String>
    ) {
        val playbackMode = mode?.trim()?.lowercase(Locale.US).orEmpty()
        if (playbackMode in setOf("history", "subscriptions")) return

        val items = (queue.takeIf { it.size > 1 } ?: listOfNotNull(query))
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase(Locale.US) }
            .take(12)
        if (items.isEmpty()) return

        runCatching {
            val videos = JSONArray()
            items.forEach { item ->
                videos.put(
                    JSONObject()
                        .put("title", item)
                        .put("creator", "")
                        .put("reason", "recent playback")
                )
            }
            val topic = query?.trim().orEmpty().ifBlank { items.first() }
            val payload = JSONObject()
                .put("title", "Recent YouTube playback")
                .put("topic", topic)
                .put("videos", videos)
                .toString()
            val now = System.currentTimeMillis()
            getSharedPreferences("visionclaw_prefs", MODE_PRIVATE)
                .edit()
                .putString("recent_youtube_suggestions_json", payload)
                .putLong("recent_youtube_suggestions_ms", now)
                .putString("recent_media_surface", "youtube")
                .putLong("recent_media_surface_ms", now)
                .putString("recent_media_surface_function", "local_youtube_playback")
                .putString("recent_media_surface_transcript", topic.take(1000))
                .apply()
            Log.d(TAG, "Cached recent YouTube playback context count=${items.size} topic=${topic.take(120)}")
        }.onFailure { error ->
            Log.w(TAG, "Failed to cache YouTube playback context: ${error.message}")
        }
    }

    /**
     * Reads browser_cookies from SharedPreferences (set by companion app)
     * and injects them into Android's CookieManager so TapBrowser can use them.
     * Format: JSON array of { domain, cookies, label } objects.
     */
    private fun injectSavedBrowserCookies() {
        try {
            val raw = viewModel.preferences.let {
                val prefs = getSharedPreferences("visionclaw_prefs", MODE_PRIVATE)
                prefs.getString("browser_cookies", null)
            }
            if (raw.isNullOrBlank()) return

            val cookieManager = android.webkit.CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)

            val arr = org.json.JSONArray(raw)
            for (i in 0 until arr.length()) {
                val entry = arr.getJSONObject(i)
                val domain = entry.optString("domain", "").trim()
                val cookieStr = entry.optString("cookies", "").trim()
                if (domain.isBlank() || cookieStr.isBlank()) continue

                // The cookie string from document.cookie is semicolon-separated.
                // CookieManager.setCookie expects one cookie at a time.
                val url = if (domain.startsWith(".")) "https://${domain.substring(1)}" else "https://$domain"
                for (cookie in cookieStr.split(";")) {
                    val trimmed = cookie.trim()
                    if (trimmed.isNotBlank()) {
                        cookieManager.setCookie(url, trimmed)
                    }
                }
            }
            cookieManager.flush()
            Log.d(TAG, "Injected saved browser cookies for ${arr.length()} domain(s)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to inject browser cookies: ${e.message}")
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Trackpad Gesture Engine
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Scan an agent stream tail for [AGENT_IMPORTANT_MARKER_REGEX] markers
     * and post each marked line to the HUD bell. The id is derived from the
     * marker body's hash, so the same marker re-seen on the next delta (the
     * tail is a rolling window) is deduped by NotificationCenter.
     */
    private fun maybePostImportantAgentUpdate(
        streamText: String,
        source: NotificationCenter.Source
    ) {
        if (streamText.isBlank()) return
        AGENT_IMPORTANT_MARKER_REGEX.findAll(streamText).forEach { match ->
            val body = match.groupValues.getOrNull(1)?.trim()?.take(300).orEmpty()
            if (body.isEmpty()) return@forEach
            val agentName =
                if (source == NotificationCenter.Source.HERMES) "Hermes" else "OpenClaw"
            NotificationCenter.post(
                NotificationCenter.HudNotification(
                    id = "${agentName.lowercase(Locale.US)}_update_${body.hashCode()}",
                    source = source,
                    title = "$agentName update",
                    message = body
                )
            )
        }
    }

    private fun setupGestureEngine() {
        gestureEngine.onShortTap = {
            Log.d(TAG, "Short tap")
            runOnUiThread {
                if (customKeyboardView?.visibility == View.VISIBLE) {
                    customKeyboardView?.performFocusedTap()
                    return@runOnUiThread
                }
                val currentPanel = viewPager?.currentItem ?: MainViewModel.PANEL_CHAT
                if (currentPanel == MainViewModel.PANEL_CHAT) {
                    // Bell list open → tap activates the focused notification.
                    if (chatFragment.isNotificationListOpen()) {
                        chatFragment.activateFocusedNotification()
                        return@runOnUiThread
                    }
                    if (chatFragment.isHudModeEnabled()) {
                        chatFragment.setHudModeEnabled(false)
                        scheduleChatHudIdleTimer()
                        return@runOnUiThread
                    }
                    when (chatFragment.handleFocusedCardTap()) {
                        ChatPanelFragment.FocusedTapResult.OPENED_URL -> return@runOnUiThread
                        ChatPanelFragment.FocusedTapResult.IGNORED -> return@runOnUiThread
                        ChatPanelFragment.FocusedTapResult.ACTIVATE_ASSISTANT ->
                                activateChatVoiceAssistant()
                    }
                    return@runOnUiThread
                }
                // Let the active panel handle explicit trackpad selection first.
                val consumed = currentTrackpadPanel()?.onTrackpadSelect() ?: false
                if (!consumed) {
                    // Fallback click if panel does not implement trackpad-select.
                    viewPager?.focusedChild?.performClick()
                }
            }
        }

        gestureEngine.onDoubleTap = {
            Log.d(TAG, "Double tap → cycle panel/session")
            runOnUiThread {
                cyclePanelViaDoubleTap()
            }
        }

        gestureEngine.onLongTap = {
            Log.d(TAG, "Long tap → toggle notification list")
            runOnUiThread {
                if (customKeyboardView?.visibility == View.VISIBLE) {
                    return@runOnUiThread
                }
                val currentPanel = viewPager?.currentItem ?: MainViewModel.PANEL_CHAT
                if (currentPanel == MainViewModel.PANEL_CHAT &&
                    !chatFragment.isBatterySavingDarkMode()
                ) {
                    chatFragment.toggleNotificationList()
                }
            }
        }
    }

    private fun activateChatVoiceAssistant() {
        // ── Debounce rapid taps ──────────────────────────────────────
        val now = SystemClock.elapsedRealtime()
        if (now - lastVoiceActivationMs < VOICE_ACTIVATION_DEBOUNCE_MS) {
            Log.d(TAG, "activateChatVoiceAssistant: debounced (${now - lastVoiceActivationMs}ms)")
            return
        }
        lastVoiceActivationMs = now

        if (!micPermissionGranted) {
            showHudNotification("Microphone permission required")
            playVoiceTimeoutBeep()
            return
        }

        chatFragment.prepareForAssistantLaunch()

        // ── Tear down any existing / stale session first ─────────────
        if (geminiLiveSession != null || geminiCaptureActive || liveState != GeminiLiveState.IDLE) {
            Log.d(TAG, "activateChatVoiceAssistant: cleaning stale session " +
                    "(session=${geminiLiveSession != null}, capture=$geminiCaptureActive, state=$liveState)")
            releaseGeminiAudioCapture(cancelOnly = true)
            viewModel.deactivateVoiceAssistant()
            showListeningOverlay(false)
            // Brief pause to let WebSocket close, then start fresh session
            // automatically — no second tap required.
            uiHandler.postDelayed({
                lastVoiceActivationMs = 0L  // reset debounce so activation proceeds
                activateChatVoiceAssistant()
            }, 300L)
            return
        }

        // ── Save current chat context before starting fresh session ──
        // This persists the conversation so Gemini can reference it if
        // the user asks about "what we discussed" in the next chat.
        // The 6-hour TTL inside MainViewModel.getPreviousChatContext()
        // bounds how stale this can get — anything older drops out
        // automatically so e.g. yesterday's "Amiga 500" session doesn't
        // bleed into today's "Share that list" turn.
        viewModel.saveChatContextForNextSession()

        // ── Halt any speech playback (research readout, TTS) BEFORE
        //    starting the fresh session. If the user taps "New Chat"
        //    during a readout, the Live session is typically idle so
        //    the cleanup branch above doesn't run — without this call
        //    the readout audio would keep playing over the new
        //    listening state, which presents to the user as a frozen
        //    interface (tap seemingly does nothing, audio continues).
        stopAllSpeechPlayback()

        // ── Start fresh session ──────────────────────────────────────
        nativeSttFallbackTriggered = false
        pendingCameraStart = false
        assistantSessionStartsAudioOnly = true

        // Unipanel v2: pin the microphone-typed foreground service
        // BEFORE we touch the audio pipeline. After the launcher swap
        // visionclaw normally runs backgrounded; without an FGS attached
        // to the process Android 11+ revokes our AudioRecord and Gemini
        // sits in "listening" with no frames ever reaching it. Direct
        // call (not via voiceAssistantActive LiveData) because that
        // LiveData is lifecycle-aware and won't fire while visionclaw
        // is STOPPED — which is exactly when we need the service most.
        com.rayneo.visionclaw.core.session.GeminiSessionForegroundService.start(this)

        viewModel.activateVoiceAssistant()
        if (locationPermissionGranted) {
            refreshLocationSnapshot(force = true)
        }
        showListeningOverlay(true)
        updateListeningTranscript("Listening…")
        pushOscilloscopeLevel(0.08f, OSCILLOSCOPE_USER_COLOR, force = true)
        playVoiceActivateBeep()
        uiHandler.removeCallbacks(delayedVoiceStartRunnable)
        uiHandler.postDelayed(delayedVoiceStartRunnable, VOICE_LISTEN_START_DELAY_MS)
    }

    private fun isChatUiReady(): Boolean {
        return chatFragment.isAdded && chatFragment.view != null
    }

    private fun setHudConnectionStatus(status: ChatPanelFragment.ConnectionStatus) {
        when (status) {
            ChatPanelFragment.ConnectionStatus.TOOLS_READY,
            ChatPanelFragment.ConnectionStatus.GEMINI_CONNECTED -> {
                lastKnownAiConnectionStatus = status
            }

            else -> Unit
        }

        val renderStatus =
            if (status == ChatPanelFragment.ConnectionStatus.IDLE &&
                lastKnownAiConnectionStatus != ChatPanelFragment.ConnectionStatus.IDLE
            ) {
                lastKnownAiConnectionStatus
            } else {
                status
            }

        runOnUiThread {
            if (isChatUiReady()) {
                chatFragment.setConnectionStatus(renderStatus)
            }
        }
    }

    /**
     * Previously showed a 📘 badge in the HUD whenever an unread research
     * report was pending. Retired: the badge added UI noise without a clear
     * benefit now that reports are always spoken end-to-end. This stub is
     * kept as a no-op so the many call sites across this Activity don't need
     * to change; any cleanup of those callers can happen incidentally.
     */
    private fun refreshResearchReadyIndicator() {
        // Intentionally empty.
    }

    private fun refreshTapClawResultReadyIndicator() {
        val visible = runCatching {
            readableArtifactStore.hasUnreadLatest(ReadableArtifactStore.ArtifactKind.TAPCLAW_RESULT)
        }.getOrDefault(false)
        runOnUiThread {
            if (isChatUiReady()) {
                chatFragment.setTapClawResultReadyStatus(visible)
            }
        }
    }

    // TapInsight: OpenClaw bridge infrastructure removed.
    // All tool routing now handled by ToolDispatcher via Gemini native tool calls.

    /** Last user transcript from the Live session, used by ToolAssist recovery. */
    @Volatile private var lastToolAssistTranscript = ""

    /**
     * Source citations from the model's googleSearch grounding for the most
     * recent turn that produced any. Populated by the LiveSessionListener
     * onGroundingMetadata callback. Used as the source-of-truth URL list
     * for "send the list / show me the links / send to my glasses" follow-
     * ups, instead of trusting URLs that the model types into a tool call
     * (which it does badly — hallucinated, mismatched, or wrapped redirects).
     *
     * Cleared on a new user turn (in onInputTranscription's fresh-turn
     * detection path) so we never replay stale chunks.
     */
    @Volatile private var pendingGroundingChunks: List<GeminiRouter.GroundingChunk>? = null
    /** Prevents double-firing recovery for the same turn. */
    @Volatile private var toolAssistRecoveryFired = false
    /** Prevents duplicate recovery when Gemini wrongly refuses a play follow-up. */
    @Volatile private var mediaPlaybackRecoveryFired = false

    /**
     * Detect when Gemini says "I can't access the tool" or similar failure
     * responses, and re-inject the tool result via ToolAssist.  This handles
     * the race condition where Gemini's Live model starts responding
     * before our proactive ToolAssist injection arrives.
     */
    private fun maybeRecoverFromGeminiFallback(modelText: String): Boolean {
        // If local turn owner is active, don't attempt any recovery — suppress entirely
        if (isGeminiOutputSuppressed()) return true
        val lower = modelText.lowercase(Locale.US)
        val currentTranscript = listOf(
            pendingLiveInputTranscript,
            latestLiveTranscript,
            lastHandledLiveInputTranscript,
            lastToolAssistTranscript
        ).firstOrNull { it.isNotBlank() }.orEmpty()
        if (!mediaPlaybackRecoveryFired &&
            (isYouTubeDiscussionPlaybackRefusal(lower) || isGeminiVerbalTurnDecline(lower))
        ) {
            resolveRecentYouTubeSuggestionListDisplayRequest(currentTranscript)?.let { listRequest ->
                mediaPlaybackRecoveryFired = true
                Log.d(
                    TAG,
                    "Recovered YouTube suggestion-list display from Gemini verbal refusal for: " +
                        currentTranscript.take(160)
                )
                handleYouTubeSuggestionListDisplayRequest(listRequest)
                return true
            }
            val youtubeRequest = resolveRecentYouTubeFollowUpPlaybackRequest(currentTranscript)
                ?: parseYouTubePlaybackIntent(currentTranscript)
            if (youtubeRequest != null) {
                mediaPlaybackRecoveryFired = true
                Log.d(
                    TAG,
                    "Recovered YouTube playback from discussion-style refusal for: ${currentTranscript.take(160)}"
                )
                runOnUiThread {
                    armLocalDirectResponseHandoff()
                    showHudNotification(youtubeRequest.hudLabel)
                    viewModel.appendDirectAssistantResponse(youtubeRequest.responseText)
                    ttsController?.stop()
                    ttsController?.speak(youtubeRequest.hudLabel)
                    launchTapBrowser(
                        initialUrl = youtubeRequest.searchUrl,
                        youtubeAutoplayQuery = youtubeRequest.query,
                        youtubeAutoplayMode = youtubeRequest.mode,
                        youtubeAutoplayQueue = youtubeRequest.queue
                    )
                }
                return true
            }
            val tapRadioRequest = resolveRecentTapRadioFollowUpPlaybackRequest(currentTranscript)
            if (tapRadioRequest != null) {
                mediaPlaybackRecoveryFired = true
                Log.d(
                    TAG,
                    "Recovered TapRadio playback from Gemini verbal refusal for: ${currentTranscript.take(160)}"
                )
                handleTapRadioPlaybackRequest(tapRadioRequest)
                return true
            }
        }
        val isToolFailure = lower.contains("unable to access") ||
            lower.contains("tool") && (lower.contains("not available") || lower.contains("can't") || lower.contains("cannot")) ||
            lower.contains("don't have access to") ||
            lower.contains("i don't have the ability") ||
            lower.contains("i'm not able to") && (lower.contains("location") || lower.contains("place") || lower.contains("traffic")) ||
            lower.contains("enable location") ||
            lower.contains("i don't know where you are") ||
            lower.contains("don't have your location")

        if (!isToolFailure) return false
        if (toolAssistRecoveryFired) return false

        val transcript = lastToolAssistTranscript
        if (transcript.isBlank()) return false

        val engine = toolAssistEngine ?: return false
        toolAssistRecoveryFired = true
        // localMapTurn removed — places/routes results should flow through Gemini
        // voice to keep the session alive for follow-up questions, not be presented
        // locally which kills the session via armLocalDirectResponseHandoff().

        Log.d(TAG, "ToolAssist RECOVERY triggered for: $transcript")
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val assist = engine.maybeAssist(transcript) ?: return@launch
                Log.d(TAG, "ToolAssist recovery result [${assist.toolName}]: ${assist.resultText.take(200)}")
                // LearnLM continuation prefers voice in recovery path too
                if (assist.preferLiveVoice && geminiLiveSession != null) {
                    learnLmToolCallActive = true
                    keepLearnLmSessionAliveUntilManualClose = true
                    armSilenceWatchdog()
                    geminiLiveSession?.sendClientText(assist.contextPrompt)
                    return@launch
                }
                if (shouldOwnToolAssistLocally(assist.toolName)) {
                    runOnUiThread {
                        presentToolAssistLocally(assist.toolName, assist.resultText)
                    }
                    return@launch
                }
                val sent = geminiLiveSession?.sendClientText(assist.contextPrompt) == true
                Log.d(TAG, "ToolAssist recovery injected=$sent")
                if (sent) {
                    runOnUiThread {
                        showHudNotification(toolAssistHudStatus(assist.toolName, assist.resultText))
                    }
                } else {
                    runOnUiThread {
                        presentToolAssistLocally(assist.toolName, assist.resultText)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "ToolAssist recovery error", e)
            }
        }
        return true
    }

    private fun refreshToolBridgeStatus() {
        // TapInsight: No external bridge. Tools are always local.
        setHudConnectionStatus(ChatPanelFragment.ConnectionStatus.TOOLS_READY)
    }

    private data class SideEffectAuthorization(
        val checked: Boolean,
        val allowed: Boolean,
        val reason: String
    )

    private fun dispatchSkippedToolResponse(callId: String, functionName: String, reason: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val responseId = callId.trim().ifBlank { "tool-${System.currentTimeMillis()}" }
            geminiLiveSession?.sendToolResponse(
                responseId,
                functionName,
                "Tool call skipped: $reason. Cached conversation context may identify the subject, " +
                    "but the current user turn must authorize visible actions or device changes. " +
                    "Answer verbally or ask one concise confirmation before taking action."
            )
        }
    }

    private fun authorizeCurrentTurnSideEffectTool(
        functionName: String,
        argsJson: String
    ): SideEffectAuthorization {
        val args = parseToolArgsForAuthorization(argsJson)
        val action = args?.optString("action").orEmpty().trim().lowercase(Locale.US)
        val display = args?.optString("display").orEmpty().trim().lowercase(Locale.US)
        val transcript = currentTranscriptForSideEffectAuthorization()

        fun allow(reason: String, checked: Boolean = true) =
            SideEffectAuthorization(checked = checked, allowed = true, reason = reason)

        fun block(reason: String) =
            SideEffectAuthorization(checked = true, allowed = false, reason = reason)

        fun requireCurrentTurn(ok: Boolean, reason: String): SideEffectAuthorization =
            if (transcript.isBlank()) {
                block("$functionName was requested without a fresh current-turn transcript")
            } else if (ok) {
                allow(reason)
            } else {
                block("$functionName was requested from context/cache without current-turn permission")
            }

        val mediaAction = hasCurrentTurnMediaActionIntent(transcript)
        val mediaDiscovery = hasCurrentTurnMediaDiscoveryIntent(transcript)
        val mediaConfirmation = confirmsRecentAssistantOffer(
            transcript,
            Regex(
                """(?i)\b(?:play|watch|open|show|display|send|pull\s+up|put\s+on|queue|listen|start|youtube|video|podcast|radio|station|glasses|browser|link|url|spotify|tapradio)\b"""
            )
        )
        val displayRequested = hasCurrentTurnDisplayIntent(transcript)
        val writeRequested = hasCurrentTurnWriteIntent(transcript)

        return when (functionName) {
            "open_taplink", "send_video_list" ->
                requireCurrentTurn(
                    mediaAction || displayRequested || mediaConfirmation,
                    "$functionName authorized by current-turn media/display intent"
                )

            "tapclaw_agent" ->
                requireCurrentTurn(
                    hasCurrentTurnTapClawInvocation(transcript) ||
                        confirmsRecentAssistantOffer(
                            transcript,
                            Regex("""(?i)\b(?:tap\s*claw|tapclaw|open\s*claw|openclaw)\b""")
                        ),
                    "tapclaw_agent authorized by explicit TapClaw/OpenClaw invocation or confirmation"
                )

            "camera_action" ->
                requireCurrentTurn(
                    hasCurrentTurnCameraActionIntent(transcript, action),
                    "camera_action authorized by explicit camera/photo/record/QR intent"
                )

            "tapradio" -> {
                val visualDisplay = display in setOf("glasses", "browser", "display", "show", "visual", "hud")
                val playbackAction = action in setOf("play", "podcast", "stop", "add")
                val discoveryAction = action in setOf(
                    "",
                    "list",
                    "search",
                    "preview",
                    "preview_station",
                    "preview_podcast",
                    "info_station",
                    "top_podcasts"
                )
                when {
                    visualDisplay ->
                        requireCurrentTurn(
                            displayRequested || mediaAction || mediaConfirmation,
                            "tapradio visual display authorized by current-turn display intent"
                        )
                    playbackAction ->
                        requireCurrentTurn(
                            mediaAction || mediaConfirmation,
                            "tapradio playback/control authorized by current-turn media intent"
                        )
                    discoveryAction ->
                        requireCurrentTurn(
                            mediaDiscovery || mediaAction || mediaConfirmation,
                            "tapradio lookup authorized by current-turn media discovery intent"
                        )
                    else -> allow("tapradio non-side-effect action")
                }
            }

            "spotify_player", "sonos_control" -> {
                val playbackAction = functionName == "sonos_control" ||
                    action in setOf("", "play", "pause", "resume", "next", "previous", "save")
                if (playbackAction) {
                    requireCurrentTurn(
                        mediaAction || mediaConfirmation,
                        "$functionName playback/control authorized by current-turn media intent"
                    )
                } else {
                    allow("$functionName read-only/search action", checked = false)
                }
            }

            "ask_maps" -> {
                val opensMap = action in setOf(
                    "navigate",
                    "navigate_3d",
                    "show_3d",
                    "preview_3d",
                    "view_3d",
                    "show_3d_map",
                    "photorealistic_view",
                    "fly_over",
                    "flyover",
                    "orbit",
                    "cinematic_view",
                    "aerial_view"
                )
                if (opensMap) {
                    requireCurrentTurn(
                        hasCurrentTurnMapActionIntent(transcript) ||
                            confirmsRecentAssistantOffer(
                                transcript,
                                Regex("""(?i)\b(?:map|3d|navigation|directions|route|show|open|display)\b""")
                            ),
                        "ask_maps visual/navigation action authorized by current-turn map intent"
                    )
                } else {
                    allow("ask_maps informational action", checked = false)
                }
            }

            "research_topic" ->
                requireCurrentTurn(
                    hasCurrentTurnResearchIntent(transcript) ||
                        confirmsRecentAssistantOffer(
                            transcript,
                            Regex("""(?i)\b(?:research|look\s+up|source|journal|study|paper|article|cite)\b""")
                        ),
                    "research_topic authorized by current-turn research/source intent"
                )

            "google_keep" -> {
                val writeAction = action.isBlank() || action in setOf("create", "append")
                if (writeAction) {
                    requireCurrentTurn(writeRequested, "google_keep write authorized by current-turn note/save intent")
                } else {
                    allow("google_keep read-only action", checked = false)
                }
            }

            "google_calendar" -> {
                val writeAction = action in setOf("create", "add", "schedule")
                if (writeAction) {
                    requireCurrentTurn(writeRequested, "google_calendar write authorized by current-turn schedule intent")
                } else {
                    allow("google_calendar read-only action", checked = false)
                }
            }

            "google_tasks" -> {
                val writeAction = action in setOf("create", "add", "complete", "done", "finish")
                if (writeAction) {
                    requireCurrentTurn(writeRequested, "google_tasks write authorized by current-turn task intent")
                } else {
                    allow("google_tasks read-only action", checked = false)
                }
            }

            "send_message" ->
                requireCurrentTurn(
                    Regex("""(?i)\b(?:send|text|message|sms|email|share)\b""").containsMatchIn(transcript),
                    "send_message authorized by current-turn communication intent"
                )

            "battery_saver" -> {
                val writeAction = action in setOf("enable", "on", "activate", "save", "disable", "off", "deactivate")
                if (writeAction) {
                    requireCurrentTurn(
                        Regex("""(?i)\b(?:battery|power|low\s+power|battery\s+saver)\b""").containsMatchIn(transcript),
                        "battery_saver toggle authorized by current-turn battery intent"
                    )
                } else {
                    allow("battery_saver status action", checked = false)
                }
            }

            "quick_action" -> {
                val listOnly = action in setOf("list", "help")
                if (listOnly) {
                    allow("quick_action list/help action", checked = false)
                } else {
                    requireCurrentTurn(
                        Regex("""(?i)\b(?:quick\s+action|macro|shortcut|good\s+morning|leaving\s+work|heading\s+home|meeting\s+mode|what'?s\s+nearby)\b""")
                            .containsMatchIn(transcript),
                        "quick_action authorized by current-turn macro intent"
                    )
                }
            }

            else -> allow("read-only or already locally guarded tool", checked = false)
        }
    }

    private fun parseToolArgsForAuthorization(argsJson: String): JSONObject? {
        val trimmed = argsJson.trim()
        if (trimmed.isBlank() || trimmed == "{}") return JSONObject()
        return try {
            JSONObject(trimmed)
        } catch (e: Exception) {
            Log.w(TAG, "SideEffectGate could not parse args for authorization: ${e.message}")
            null
        }
    }

    private fun currentTranscriptForSideEffectAuthorization(): String =
        listOf(pendingLiveInputTranscript, latestLiveTranscript)
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()

    private fun hasCurrentTurnTapClawInvocation(transcript: String): Boolean =
        Regex("""(?i)\b(?:tap\s*claw|tapclaw|open\s*claw|openclaw)\b""").containsMatchIn(transcript)

    private fun hasCurrentTurnMediaActionIntent(transcript: String): Boolean {
        if (transcript.isBlank() || isMediaServiceCapabilityQuestion(transcript)) return false
        return Regex(
            """(?i)^\s*(?:please\s+)?(?:(?:can|could|would|will)\s+you\s+)?(?:play|watch|open|show(?:\s+me)?|display|send|pull\s+up|put\s+on|queue(?:\s+up)?|listen\s+to|start|load|go\s+to|bring\s+up|launch)\b"""
        ).containsMatchIn(transcript) ||
            Regex(
                """(?i)\b(?:i\s+(?:want|need|would\s+like)\s+(?:to\s+)?|let'?s\s+|please\s+)(?:play|watch|open|show|display|send|pull\s+up|put\s+on|queue|listen\s+to|start|load|bring\s+up|launch)\b"""
            ).containsMatchIn(transcript)
    }

    private fun hasCurrentTurnDisplayIntent(transcript: String): Boolean {
        if (transcript.isBlank() || isMediaServiceCapabilityQuestion(transcript)) return false
        return Regex(
            """(?i)\b(?:show|display|send|open|pull\s+up|put|load|bring\s+up)\b.*\b(?:glasses|display|browser|web\s*page|website|link|url|list|video|youtube|article)\b"""
        ).containsMatchIn(transcript)
    }

    private fun hasCurrentTurnMediaDiscoveryIntent(transcript: String): Boolean {
        if (transcript.isBlank() || isMediaServiceCapabilityQuestion(transcript)) return false
        val hasMediaType = Regex(
            """(?i)\b(?:podcasts?|radio|stations?|youtube|videos?|songs?|tracks?|music|spotify|tapradio|streams?)\b"""
        ).containsMatchIn(transcript)
        val hasDiscoveryCue = Regex(
            """(?i)\b(?:find|search|look\s+up|list|browse|recommend|suggest|top|best|popular|trending|what\s+are|what'?s|any\s+good|give\s+me|show\s+me)\b"""
        ).containsMatchIn(transcript)
        val explanatoryOnly = Regex(
            """(?i)^\s*(?:tell\s+me\s+about|explain|analyze|what\s+is|what'?s\s+the\s+history|why|how\s+does|help\s+me\s+understand)\b"""
        ).containsMatchIn(transcript)
        return hasMediaType && hasDiscoveryCue && !explanatoryOnly
    }

    private fun hasCurrentTurnCameraActionIntent(transcript: String, action: String): Boolean {
        if (transcript.isBlank()) return false
        return when (action) {
            "read_qr" ->
                Regex("""(?i)\b(?:scan|read)\b.*\b(?:qr|code)\b|\b(?:qr|code)\b.*\b(?:scan|read)\b""")
                    .containsMatchIn(transcript)
            "start_recording", "stop_recording" ->
                Regex("""(?i)\b(?:start|stop|save|record)\b.*\b(?:recording|audio|video)\b|\brecord(?:ing)?\b""")
                    .containsMatchIn(transcript)
            else ->
                Regex("""(?i)\b(?:take|save|capture|snap|shoot)\b.*\b(?:photo|picture|image|camera)\b|\b(?:photo|picture|image)\b.*\b(?:take|save|capture)\b""")
                    .containsMatchIn(transcript)
        }
    }

    private fun hasCurrentTurnMapActionIntent(transcript: String): Boolean {
        if (transcript.isBlank()) return false
        return Regex(
            """(?i)\b(?:navigate|directions?|route|take\s+me|guide\s+me|show|open|display|pull\s+up|3d|map|fly\s+over|aerial)\b"""
        ).containsMatchIn(transcript)
    }

    private fun hasCurrentTurnResearchIntent(transcript: String): Boolean {
        if (transcript.isBlank()) return false
        return Regex(
            """(?i)\b(?:research|deep\s+dive|look\s+up|source|sources|journal|study|paper|article|cite|citation|reference|medical\s+journal|peer\s+reviewed)\b"""
        ).containsMatchIn(transcript)
    }

    private fun hasCurrentTurnWriteIntent(transcript: String): Boolean {
        if (transcript.isBlank()) return false
        return Regex(
            """(?i)\b(?:create|add|save|remember|note|memo|append|write\s+down|schedule|calendar|event|remind|task|todo|complete|mark\s+done)\b"""
        ).containsMatchIn(transcript)
    }

    // (Removed duplicate isMediaServiceCapabilityQuestion — the
    // authoritative definition lives further down in the same file.)

    private fun confirmsRecentAssistantOffer(transcript: String, offerPattern: Regex): Boolean {
        if (!isBareConfirmation(transcript)) return false
        val recentAssistantText = viewModel.getAssistantCardsSnapshot()
            .asReversed()
            .take(3)
            .joinToString("\n") { it.text }
        if (recentAssistantText.isBlank()) return false
        val hasOfferLanguage = Regex(
            """(?i)\b(?:want\s+me\s+to|would\s+you\s+like\s+me\s+to|should\s+i|i\s+can|if\s+you\s+want|if\s+you'd\s+like)\b"""
        ).containsMatchIn(recentAssistantText)
        return hasOfferLanguage && offerPattern.containsMatchIn(recentAssistantText)
    }

    private fun isBareConfirmation(transcript: String): Boolean =
        Regex(
            """(?i)^\s*(?:yes|yeah|yep|yup|sure|ok(?:ay)?|go\s+ahead|please\s+do|do\s+it|that\s+works|sounds\s+good|affirmative)(?:\s+please)?[.!?]*\s*$"""
        ).containsMatchIn(transcript.trim())

    private fun dispatchLiveToolCall(callId: String, name: String, args: String) {
        // Defense-in-depth: reject tool calls that arrive after local handoff claimed the turn
        if (isGeminiOutputSuppressed()) {
            Log.d(TAG, "dispatchLiveToolCall SUPPRESSED: $name (local turn owner active)")
            return
        }
        val requestedFunctionName = name.trim()
        val currentTurnTranscript = listOf(
            pendingLiveInputTranscript,
            lastHandledLiveInputTranscript,
            latestLiveTranscript,
            lastToolAssistTranscript
        )
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
        val rerouteStatusBrief = requestedFunctionName == "battery_saver" &&
            AssistantIntentParser.isStatusBriefingRequest(currentTurnTranscript)
        val functionName = if (rerouteStatusBrief) "status_briefing" else requestedFunctionName
        var effectiveArgs = if (rerouteStatusBrief) "{}" else args
        if (functionName.isBlank()) return
        if (rerouteStatusBrief) {
            Log.w(
                TAG,
                "Rewriting battery_saver tool call to status_briefing for brief transcript: " +
                    currentTurnTranscript.take(160)
            )
        }
        val currentRequestedMediaType = requestedMediaTypeForTranscript(currentTurnTranscript)
        val mediaPlaybackTools = setOf("open_taplink", "send_video_list", "spotify_player", "tapradio")
        val youtubePlaybackTools = setOf("open_taplink", "send_video_list")
        if (functionName in mediaPlaybackTools) {
            mediaServiceCapabilityQuestionResponse(currentTurnTranscript)?.let { response ->
                Log.w(
                    TAG,
                    "Blocking media tool '$functionName' for service capability question: " +
                        currentTurnTranscript.take(160)
                )
                lifecycleScope.launch(Dispatchers.IO) {
                    val responseId = callId.trim().ifBlank { "tool-${System.currentTimeMillis()}" }
                    geminiLiveSession?.sendToolResponse(responseId, functionName, response)
                }
                return
            }
            if (functionName == "send_video_list") {
                resolveRecentYouTubeSuggestionListDisplayRequest(currentTurnTranscript)?.let { listRequest ->
                    Log.w(
                        TAG,
                        "Preempting Gemini send_video_list with cached YouTube suggestion picker: " +
                            currentTurnTranscript.take(160)
                    )
                    handleYouTubeSuggestionListDisplayRequest(listRequest)
                    return
                }
                youtubeSuggestionListWithoutCurrentConsentResponse(currentTurnTranscript)?.let { response ->
                    Log.w(
                        TAG,
                        "Blocking YouTube suggestion list for voice-first media reference turn: " +
                            currentTurnTranscript.take(160)
                    )
                    lifecycleScope.launch(Dispatchers.IO) {
                        val responseId = callId.trim().ifBlank { "tool-${System.currentTimeMillis()}" }
                        geminiLiveSession?.sendToolResponse(responseId, functionName, response)
                    }
                    return
                }
            }
            if (functionName in youtubePlaybackTools) {
                if (currentRequestedMediaType != null && currentRequestedMediaType != RequestedMediaType.YOUTUBE) {
                    Log.d(
                        TAG,
                        "Skipping YouTube follow-up preemption for requested media type=$currentRequestedMediaType " +
                            "tool='$functionName': ${currentTurnTranscript.take(160)}"
                    )
                } else {
                    resolveRecentYouTubeFollowUpPlaybackRequest(currentTurnTranscript)?.let { youtubeRequest ->
                        Log.w(
                            TAG,
                            "Preempting Gemini media tool '$functionName' with deterministic YouTube follow-up route: " +
                                currentTurnTranscript.take(160)
                        )
                        runOnUiThread {
                            armLocalDirectResponseHandoff()
                            showHudNotification(youtubeRequest.hudLabel)
                            viewModel.appendDirectAssistantResponse(youtubeRequest.responseText)
                            recordHudFunctionTicker("Opened YouTube queue", gatewayHealthy = lastOpenClawGatewayHealthy)
                            ttsController?.stop()
                            ttsController?.speak(youtubeRequest.hudLabel)
                            launchTapBrowser(
                                initialUrl = youtubeRequest.searchUrl,
                                youtubeAutoplayQuery = youtubeRequest.query,
                                youtubeAutoplayMode = youtubeRequest.mode,
                                youtubeAutoplayQueue = youtubeRequest.queue
                            )
                        }
                        return
                    }
                }
            } else if (
                looksLikeGenericMediaPickReference(currentTurnTranscript) ||
                looksLikeGenericMediaCollectionReference(currentTurnTranscript)
            ) {
                Log.d(
                    TAG,
                    "Skipping YouTube follow-up preemption for non-YouTube media tool '$functionName': " +
                        currentTurnTranscript.take(160)
                )
            }
        }
        if (functionName in mediaPlaybackTools) {
            redundantMediaLookupAfterSuggestionCacheResponse(currentTurnTranscript, functionName)?.let { response ->
                Log.w(
                    TAG,
                    "Blocking redundant media lookup '$functionName' after cached suggestion list: " +
                        currentTurnTranscript.take(160)
                )
                lifecycleScope.launch(Dispatchers.IO) {
                    val responseId = callId.trim().ifBlank { "tool-${System.currentTimeMillis()}" }
                    geminiLiveSession?.sendToolResponse(responseId, functionName, response)
                }
                return
            }
        }
        if (functionName in mediaPlaybackTools) {
            explicitMediaTypeConflictResponse(currentTurnTranscript, functionName, effectiveArgs)?.let { response ->
                Log.w(
                    TAG,
                    "Rejecting mismatched media tool '$functionName' for requested media type: " +
                        currentTurnTranscript.take(160)
                )
                lifecycleScope.launch(Dispatchers.IO) {
                    val responseId = callId.trim().ifBlank { "tool-${System.currentTimeMillis()}" }
                    geminiLiveSession?.sendToolResponse(responseId, functionName, response)
                }
                return
            }
        }
        if (functionName == "tapradio") {
            tapRadioPodcastActionWithoutCurrentConsentResponse(currentTurnTranscript, effectiveArgs)?.let { response ->
                Log.w(
                    TAG,
                    "Rejecting tapradio podcast action without current podcast consent: " +
                        currentTurnTranscript.take(160)
                )
                lifecycleScope.launch(Dispatchers.IO) {
                    val responseId = callId.trim().ifBlank { "tool-${System.currentTimeMillis()}" }
                    geminiLiveSession?.sendToolResponse(responseId, functionName, response)
                }
                return
            }
            rewriteTapRadioArgsForRequestedMediaType(currentTurnTranscript, effectiveArgs)?.let { reroutedArgs ->
                Log.w(
                    TAG,
                    "Rewriting tapradio args to preserve requested media type: " +
                        currentTurnTranscript.take(160)
                )
                effectiveArgs = reroutedArgs
            }
            forceTapRadioPlaybackArgsIfExplicit(currentTurnTranscript, effectiveArgs)?.let { reroutedArgs ->
                Log.w(
                    TAG,
                    "Rewriting tapradio preview to playback for explicit media directive: " +
                        currentTurnTranscript.take(160)
                )
                effectiveArgs = reroutedArgs
            }
        }
        if (!toolDispatcher.isSupported(functionName)) {
            Log.w(TAG, "Unsupported tool call: $functionName — sending error back to Gemini")
            lifecycleScope.launch(Dispatchers.IO) {
                val responseId = callId.trim().ifBlank { "tool-${System.currentTimeMillis()}" }
                geminiLiveSession?.sendToolResponse(responseId, functionName, "Unknown tool: $functionName")
            }
            return
        }

        if (functionName == "learn_topic") {
            val learnCandidates = listOf(
                lastToolAssistTranscript,
                pendingLiveInputTranscript,
                latestLiveTranscript
            )
            val explicitLearnRequest = learnCandidates.any {
                AssistantIntentParser.isExplicitLearnRequest(it.trim())
            }
            if (!explicitLearnRequest) {
                Log.w(
                    TAG,
                    "Rejected learn_topic tool call for non-explicit transcript: ${lastToolAssistTranscript.take(160)}"
                )
                lifecycleScope.launch(Dispatchers.IO) {
                    val responseId = callId.trim().ifBlank { "tool-${System.currentTimeMillis()}" }
                    geminiLiveSession?.sendToolResponse(
                        responseId,
                        functionName,
                        "This tool is not available for this turn. Continue the conversation normally and answer directly without mentioning tutor mode."
                    )
                }
                return
            }
            learnLmToolCallActive = true
            keepLearnLmSessionAliveUntilManualClose = true
            Log.d(TAG, "learn_topic tool call — learnLmToolCallActive=true, 30s timeout set")
            pinLearnLmLiveSessionIfNeeded(pendingLiveInputTranscript.trim())
        }

        if (functionName == "daily_briefing" && !looksLikeDailyBriefingIntent(lastToolAssistTranscript)) {
            Log.w(
                TAG,
                "Rejected daily_briefing tool call for non-explicit transcript: ${lastToolAssistTranscript.take(160)}"
            )
            lifecycleScope.launch(Dispatchers.IO) {
                val responseId = callId.trim().ifBlank { "tool-${System.currentTimeMillis()}" }
                geminiLiveSession?.sendToolResponse(
                    responseId,
                    functionName,
                    "Daily briefing is only available when the user explicitly asks for a daily briefing by name. Use calendar, routes, places, weather, or research tools for this request instead."
                )
            }
            return
        }

        // ── Anti-interruption guard ──────────────────────────────────
        // If Gemini is already streaming audio output and decides to proactively
        // call google_places or ask_maps, reject the call. These tools should
        // only fire at the start of a response to a user's explicit request,
        // not mid-sentence as a proactive suggestion.
        val proactiveLocationTools = setOf("google_places", "ask_maps")
        if (functionName in proactiveLocationTools && awaitingServerTurnComplete && currentGeminiOutputTurnStartedMs != 0L) {
            val elapsed = SystemClock.uptimeMillis() - currentGeminiOutputTurnStartedMs
            if (elapsed > 500L) {  // >500ms into a response = mid-sentence, not start-of-turn
                Log.w(TAG, "Rejected mid-response $functionName tool call (${elapsed}ms into output) to prevent interruption")
                lifecycleScope.launch(Dispatchers.IO) {
                    val responseId = callId.trim().ifBlank { "tool-${System.currentTimeMillis()}" }
                    geminiLiveSession?.sendToolResponse(
                        responseId,
                        functionName,
                        "Tool call skipped — do not interrupt your current response. Continue speaking naturally."
                    )
                }
                return
            }
        }

        val toolIntentCandidates = listOf(
            lastToolAssistTranscript,
            pendingLiveInputTranscript,
            latestLiveTranscript
        )
        if (functionName == "google_routes" && toolIntentCandidates.none { looksLikeRoutesIntent(it) }) {
            Log.w(
                TAG,
                "Rejected google_routes tool call for non-navigation transcript: ${toolIntentCandidates.joinToString(" | ").take(220)}"
            )
            lifecycleScope.launch(Dispatchers.IO) {
                val responseId = callId.trim().ifBlank { "tool-${System.currentTimeMillis()}" }
                geminiLiveSession?.sendToolResponse(
                    responseId,
                    functionName,
                    "Route lookup skipped because the user did not ask for directions, traffic, or ETA information in this turn."
                )
            }
            return
        }

        if (functionName == "google_places" && toolIntentCandidates.none { looksLikeNearbyPlacesIntent(it) }) {
            Log.w(
                TAG,
                "Rejected google_places tool call for non-nearby transcript: ${toolIntentCandidates.joinToString(" | ").take(220)}"
            )
            lifecycleScope.launch(Dispatchers.IO) {
                val responseId = callId.trim().ifBlank { "tool-${System.currentTimeMillis()}" }
                geminiLiveSession?.sendToolResponse(
                    responseId,
                    functionName,
                    "Nearby place lookup skipped because the user did not ask for nearby businesses or what's open nearby in this turn."
                )
            }
            return
        }

        val sideEffectAuthorization = authorizeCurrentTurnSideEffectTool(functionName, effectiveArgs)
        if (sideEffectAuthorization.checked) {
            val gateTranscript = currentTranscriptForSideEffectAuthorization()
            val logLine =
                "SideEffectGate ${if (sideEffectAuthorization.allowed) "ALLOW" else "BLOCK"} " +
                    "tool=$functionName reason=${sideEffectAuthorization.reason} " +
                    "currentTurn='${gateTranscript.take(180)}'"
            if (sideEffectAuthorization.allowed) {
                Log.d(TAG, logLine)
            } else {
                Log.w(TAG, logLine)
                dispatchSkippedToolResponse(callId, functionName, sideEffectAuthorization.reason)
                return
            }
        }

        // ── Research tool: show "Researching…" while fetching ────────
        // research_topic takes 10-45 seconds. Show a HUD indicator so the
        // user knows work is happening. Gemini's audio output continues
        // normally — when the tool result arrives via sendToolResponse,
        // Gemini will receive the full report and read it.
        if (functionName == "research_topic") {
            runOnUiThread {
                showHudNotification("Researching…")
                chatFragment.setStreamActiveIndicator(true)
            }
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val result = toolDispatcher.dispatch(functionName, effectiveArgs)
            val resultTextRaw = result.getOrElse { err ->
                Log.e(TAG, "Tool dispatch error for $functionName", err)
                err.message?.trim().takeUnless { it.isNullOrBlank() }
                    ?: "Tool $functionName is unavailable right now."
            }
            // Rewrite any hallucinated media URLs in the tool result BEFORE
            // they're extracted or sent back to Gemini. This catches GPT-5.4
            // (OpenClaw) and any other tool that invents wrong domains.
            val resultText = rewriteAllUrlsInText(resultTextRaw)
            // When a TapClaw research task returns, start background polling
            // for the report file instead of keeping the Gemini session open.
            if (functionName == "tapclaw_agent" || functionName == "hermes_agent") {
                // Tag this turn so armSilenceWatchdog can apply the
                // Hermes follow-up window when the user has the
                // companion-app toggle on. Cleared on the next user
                // input transcript (see onInputTranscription).
                // NOTE: previously only fired for tapclaw_agent — so the
                // "Auto follow-up after Hermes" toggle had no effect on
                // actual Hermes turns. Both agents now set the flag.
                lastToolCallWasHermesAgent = true
                cacheTapClawReadableArtifact(resultText)
                runOnUiThread {
                    chatFragment.setStreamActiveIndicator(false)
                    chatFragment.hideHeartbeat()
                    restoreOpenClawTicker()
                }
                // Background research polling is a TapClaw-only path
                // (the agent drops a report file the relay polls). Hermes
                // returns its full response synchronously over SSE so there
                // is nothing to poll for.
                if (functionName == "tapclaw_agent") {
                    val queryLower = try {
                        JSONObject(args).optString("query", "").lowercase()
                    } catch (_: Exception) { "" }
                    if (queryLower.contains("deep research") || queryLower.contains("background research")) {
                        val topic = queryLower
                            .substringAfter("research")
                            .replace(Regex("""[.!,;:'"]+"""), "")
                            .substringBefore("use the google")
                            .substringBefore("save the")
                            .substringBefore("this is a background")
                            .trim()
                            .ifBlank { "unknown topic" }
                        startResearchPoll(topic)
                    }
                }
            }
            /** Extract a URL after "open_taplink:" and normalize only valid absolute URLs. */
            fun sanitizeOpenTapLinkUrl(text: String): String? {
                val raw = text.substringAfter("open_taplink:").substringBefore("\n").trim()
                return AssistantIntentParser.normalizeTapLinkUrl(raw)
            }

            val autoOpenUrl = when {
                functionName == "open_taplink" ->
                    AssistantIntentParser.extractTapLinkUrl(resultText)
                // send_video_list returns a taplink://file:///android_asset/video_list.html?data=...
                // URL — same shape as open_taplink, so reuse the same extractor.
                functionName == "send_video_list" ->
                    AssistantIntentParser.extractTapLinkUrl(resultText)
                // send_link_list returns the same taplink:// asset-URL shape
                // for link_list.html — reuse the extractor for it too.
                functionName == "send_link_list" ->
                    AssistantIntentParser.extractTapLinkUrl(resultText)
                functionName == "tapradio" && resultText.contains("open_taplink:") ->
                    sanitizeOpenTapLinkUrl(resultText)
                // Intercept open_taplink: URLs directly from tapclaw_agent results
                // so they don't round-trip through Gemini (which can mangle the URL).
                functionName == "tapclaw_agent" && resultText.contains("open_taplink:") ->
                    sanitizeOpenTapLinkUrl(resultText)
                // ask_maps navigate_3d returns an ar_nav.html URL via open_taplink:
                functionName == "ask_maps" && resultText.contains("open_taplink:") ->
                    sanitizeOpenTapLinkUrl(resultText)
                // spotify_player routes through radio.html (preview clips) or
                // spotify.html (Web Playback SDK for Premium) via open_taplink:
                // URLs that point at file:///android_asset/ pages.  Without
                // intercepting here the URL round-trips through Gemini, which
                // either swallows it entirely ("Here's your song…" with no
                // link) or mangles it — so the browser never opens and the
                // music never plays.  Intercept so launchTapBrowser is called
                // directly with the asset URL (see the file:///android_asset/
                // branch below).
                functionName == "spotify_player" && resultText.contains("open_taplink:") ->
                    sanitizeOpenTapLinkUrl(resultText)
                else -> null
            }
            val currentTurnYouTubeTranscripts = listOf(
                pendingLiveInputTranscript,
                latestLiveTranscript,
                lastHandledLiveInputTranscript,
                lastToolAssistTranscript
            )
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
            val currentTurnYouTubeText = currentTurnYouTubeTranscripts.firstOrNull().orEmpty()
            val isYouTubeAutoOpen =
                autoOpenUrl?.let { openUrl ->
                    openUrl.contains("youtube.com", ignoreCase = true) ||
                        openUrl.contains("youtu.be", ignoreCase = true)
                } == true
            val currentTurnHasExplicitYouTubePlayback = currentTurnYouTubeTranscripts.any { transcript ->
                hasExplicitYouTubePlaybackConsent(transcript) ||
                    looksLikeYouTubeFollowUpSelectionReference(transcript)
            }
            val currentTurnResolvedYouTubeFollowUp =
                currentTurnYouTubeTranscripts.firstNotNullOfOrNull { transcript ->
                    resolveRecentYouTubeFollowUpPlaybackRequest(transcript)
                }
            val responseId = callId.trim().ifBlank { "tool-${System.currentTimeMillis()}" }
            Log.d(
                TAG,
                "Tool result ready callId=$responseId function=$functionName text=${resultText.take(220)}"
            )

            var effectiveAutoOpenUrl = autoOpenUrl
            // Both tapclaw_agent and hermes_agent reuse the OpenClaw HUD
            // ticker / heartbeat pipeline and the local-readout branch
            // below. Treat them as a single "local AI agent" class.
            val isLocalAgentTool = functionName == "tapclaw_agent" || functionName == "hermes_agent"
            val agentLabel = if (functionName == "hermes_agent") "Hermes" else "TapClaw"
            var finalResultText = when {
                isLocalAgentTool -> {
                    val hb = lastTapClawHeartbeat
                    lastTapClawHeartbeat = null
                    if (!hb.isNullOrBlank()) "$resultText\n[$agentLabel last status: $hb]" else resultText
                }
                else -> resultText
            }
            var voiceFirstAutoOpenBlocked = false

            if (functionName == "open_taplink" &&
                isYouTubeAutoOpen &&
                currentTurnYouTubeTranscripts.any { AssistantIntentParser.isYouTubeLookupRequest(it) } &&
                !currentTurnHasExplicitYouTubePlayback &&
                currentTurnResolvedYouTubeFollowUp == null
            ) {
                Log.w(
                    TAG,
                    "Skipped YouTube auto-open for lookup-style turn: ${currentTurnYouTubeText.take(160)}"
                )
                effectiveAutoOpenUrl = null
                finalResultText =
                    "YouTube browser open skipped because the current turn is a lookup question, not a play request. " +
                        "Reply verbally with a concise rundown of recent or relevant videos, then offer to show a list or play one."
            }

            val isTapRadioPlaybackOpen = functionName == "tapradio" &&
                !effectiveAutoOpenUrl.isNullOrBlank() &&
                effectiveAutoOpenUrl.contains("radio.html", ignoreCase = true) &&
                runCatching {
                    val action = JSONObject(effectiveArgs.ifBlank { "{}" })
                        .optString("action", "")
                        .trim()
                        .lowercase(Locale.US)
                    action in setOf("play", "podcast")
                }.getOrDefault(false)
            if (!effectiveAutoOpenUrl.isNullOrBlank() &&
                !isTapRadioPlaybackOpen &&
                shouldBlockVoiceFirstAutoOpen(functionName, effectiveAutoOpenUrl, currentTurnYouTubeTranscripts)
            ) {
                voiceFirstAutoOpenBlocked = true
                Log.w(
                    TAG,
                    "Skipped browser/media auto-open for voice-first reference turn: " +
                        currentTurnYouTubeText.take(160)
                )
                effectiveAutoOpenUrl = null
                finalResultText =
                    "Browser/media open skipped: the user asked for information or a reference, not playback or a web page. " +
                        "Answer verbally first with the best available source details. If a source or media item cannot be found, say what you tried and ask one concise follow-up before opening anything."
            } else if (isTapRadioPlaybackOpen) {
                Log.d(
                    TAG,
                    "Voice-first auto-open guard bypassed for confirmed TapRadio playback: " +
                        currentTurnYouTubeText.take(160)
                )
            }

            if (isLocalAgentTool && effectiveAutoOpenUrl.isNullOrBlank() && !voiceFirstAutoOpenBlocked) {
                suppressGeminiOutputUntilMs = maxOf(
                    suppressGeminiOutputUntilMs,
                    SystemClock.uptimeMillis() + 30_000L
                )
                val contextResult =
                    "$agentLabel result has been displayed locally as a visible chat card and read aloud by TapInsight. " +
                        "Do not repeat it unless the user asks. Use the result below for immediate follow-up context.\n\n" +
                        "[$agentLabel result]\n$finalResultText"
                val sent = geminiLiveSession?.sendToolResponse(responseId, functionName, contextResult) == true
                Log.d(TAG, "$functionName delivered locally sentContext=$sent callId=$responseId")
                runOnUiThread {
                    armLocalDirectResponseHandoff()
                    chatFragment.setStreamActiveIndicator(false)
                    val presented = presentTapClawResultLocally(finalResultText, autoRead = true)
                    if (!presented) {
                        showHudNotification("$agentLabel returned, but there was no readable result.")
                    }
                    setHudConnectionStatus(
                        if (sent) ChatPanelFragment.ConnectionStatus.TOOLS_READY
                        else ChatPanelFragment.ConnectionStatus.GEMINI_CONNECTED
                    )
                }
                return@launch
            }

            val mediaGuardedResultText = appendMediaTypeGuardToToolResult(
                transcript = currentTurnTranscript,
                functionName = functionName,
                resultText = finalResultText
            )
            if (mediaGuardedResultText != finalResultText) {
                Log.d(TAG, "Media type guard appended for function=$functionName transcript=${currentTurnTranscript.take(120)}")
            }
            finalResultText = mediaGuardedResultText
            recordRecentMediaToolContext(
                functionName = functionName,
                transcript = currentTurnTranscript,
                args = effectiveArgs,
                resultText = finalResultText
            )

            // If this tool call will open a URL (open_taplink), suppress Gemini
            // audio output BEFORE sending the tool response.  This prevents
            // Gemini from generating audio that overlaps with the local action.
            // NOTE: Only set the suppression timestamp and flush audio here
            // (both are thread-safe).  Do NOT call armLocalDirectResponseHandoff()
            // from the IO thread — it calls shutdownMultimodalSession() which
            // touches UI elements and corrupts session state.  The full session
            // shutdown happens on the UI thread below via shutdownMultimodalSession().
            if (!effectiveAutoOpenUrl.isNullOrBlank()) {
                Log.d(TAG, "open_taplink URL detected — suppressing Gemini output before tool response")
                suppressGeminiOutputUntilMs = maxOf(
                    suppressGeminiOutputUntilMs,
                    SystemClock.uptimeMillis() + LOCAL_DIRECT_OUTPUT_SUPPRESS_MS
                )
                stopAllSpeechPlayback()
            }

            // For tapclaw_agent, append the latest heartbeat context to the tool
            // result so Gemini knows what happened during the task — without
            // injecting a disruptive client turn into the conversation.
            if (functionName == "research_topic") {
                val researchBranchStartMs = SystemClock.elapsedRealtime()
                Log.d(TAG, "research_topic result (${resultText.length} chars) — saving + reading via Gemini readout")
                val topic = runCatching { JSONObject(effectiveArgs).optString("topic", "").trim() }.getOrDefault("")
                val responseId = callId.trim().ifBlank { "tool-${System.currentTimeMillis()}" }
                // ── Suppress Gemini Live audio BEFORE sending the tool response ──
                // Without this, Gemini reacts to the acknowledgment by generating
                // a spoken summary/elaboration through the Live session. That audio
                // reaches onModelAudio → geminiAudioPlayer before
                // presentResearchReportLocally can set the suppress flag, so the
                // user hears Gemini rambling instead of the verbatim readout.
                //
                // 30 s is plenty: the readout coroutine will call
                // beginProtectedReadoutWindow() which extends suppression for the
                // actual read. 180 s was overkill and delayed session recovery.
                suppressGeminiOutputUntilMs =
                    SystemClock.uptimeMillis() + 30_000L
                geminiAudioPlayer?.stopAndFlush()
                // Send a MINIMAL acknowledgment so the Live session doesn't hang
                // waiting for a tool response (its audio output is suppressed).
                // The previous long sentence was the root cause of the ~20 s
                // delay: Gemini was synthesizing ~5 s of PCM for that ack even
                // though the bytes were being suppressed client-side, and the
                // server round-trip + turn-complete still blocked us.
                geminiLiveSession?.sendToolResponse(
                    responseId, functionName,
                    "ok"
                )
                Log.d(
                    TAG,
                    "research_topic: ack sent (elapsed=" +
                        "${SystemClock.elapsedRealtime() - researchBranchStartMs}ms), " +
                        "handing off to presentResearchReportLocally"
                )
                runOnUiThread {
                    chatFragment.setStreamActiveIndicator(false)
                    recordHudFunctionTicker(
                        completedToolTickerLabel(functionName, resultText),
                        gatewayHealthy = lastOpenClawGatewayHealthy
                    )
                    presentResearchReportLocally(resultText, topicHint = topic)
                }
                return@launch
            }

            val sent = geminiLiveSession?.sendToolResponse(responseId, functionName, finalResultText) == true
            Log.d(TAG, "sendToolResponse sent=$sent callId=$responseId")
            // Gemini is the router. If it called open_taplink with a URL,
            // trust that decision — DEFAULT_URL_RULES instructs Gemini to
            // describe results first for informational queries and only call
            // open_taplink when the user actually wants to play/watch.
            // We keep the URL hygiene pass below (rebuild any YouTube URL as
            // a clean search URL) but do NOT veto based on client-side verb
            // heuristics, which were fragile across follow-ups like
            // "play the first one".
            val hudText = when {
                !effectiveAutoOpenUrl.isNullOrBlank() -> "Opening ${AssistantIntentParser.displayLabelForUrl(effectiveAutoOpenUrl)}"
                else -> hudSafeCalendarResult(finalResultText)
            }
            val completedTickerLabel = completedToolTickerLabel(
                functionName,
                finalResultText,
                effectiveAutoOpenUrl
            )
            runOnUiThread {
                recordHudFunctionTicker(
                    completedTickerLabel,
                    gatewayHealthy = lastOpenClawGatewayHealthy || isLocalAgentTool
                )
                if (isLocalAgentTool) {
                    presentTapClawResultLocally(finalResultText, autoRead = false)
                }
                if (!effectiveAutoOpenUrl.isNullOrBlank()) {
                    // Capture a non-null local so smart-casts persist through
                    // nested lambdas / local functions below. The !! is safe
                    // because we just verified isNullOrBlank() returned false.
                    val openUrl: String = effectiveAutoOpenUrl!!
                    shutdownMultimodalSession()
                    // Detect YouTube/video intent from open_taplink URLs:
                    //  1. Direct youtube.com / youtu.be links
                    //  2. Any URL containing "youtube" in path or query
                    //  3. Google Video search (tbm=vid) — Gemini often uses this
                    //     even when the user asked for YouTube specifically
                    // Exception: file:///android_asset/ pages (our picker UIs)
                    // are NEVER YouTube intents even if their ?data=… payload
                    // happens to URL-encode the word "youtube" in a video
                    // title. Without this guard the hygiene rewrite below
                    // would replace the asset URL with a search URL and the
                    // list page would never load.
                    val urlLower = openUrl.lowercase()
                    val isLocalAsset = urlLower.startsWith("file:///android_asset/")
                    val isYouTubeIntent = !isLocalAsset && (
                        urlLower.contains("youtube.com") ||
                            urlLower.contains("youtu.be") ||
                            urlLower.contains("youtube") ||
                            urlLower.contains("tbm=vid")
                    )

                    if (isYouTubeIntent) {
                        // Cancel the settle timer to prevent double-launch
                        uiHandler.removeCallbacks(settledLiveInputRunnable)
                        lastHandledLiveInputTranscript = pendingLiveInputTranscript.trim()
                        // Phase 2 Step 2c.3.1: surface the finalized user turn
                        // for the unipanel mini-card stack.
                        viewModel.appendUserUtterance(lastHandledLiveInputTranscript)

                        val uri = android.net.Uri.parse(openUrl)
                        val path = uri.path.orEmpty()

                        // Always rebuild as a YouTube search URL. Tightened
                        // extraction below pulls a clean search phrase out of
                        // URL query params, path tails (for @handle / channel
                        // paths Gemini sometimes sends), the last voice
                        // transcript, and tool-assist hints — then strips
                        // filler verbs ("play", "open", "pull up"...), content
                        // types ("music", "videos", "songs", "playlist"),
                        // prepositions ("by", "from", "on", "of"), and the
                        // word "youtube" itself so the final query is just the
                        // subject the user actually asked for.

                        /** Strip leading filler like "play some music by ..." so the
                         *  search query is just the subject. Runs top-down; each
                         *  pattern may chew off one leading token at a time. */
                        fun cleanSearchPhrase(raw: String): String {
                            if (raw.isBlank()) return ""
                            var s = raw.trim()
                            // Strip surrounding quotes Gemini sometimes adds.
                            s = s.trim('"', '\'', '\u201C', '\u201D', '\u2018', '\u2019')
                            // Strip the word "youtube" wherever it appears.
                            s = s.replace(Regex("(?i)\\byoutube\\b"), " ")
                            // Peel off leading imperative verbs and filler,
                            // repeatedly so chains like "can you please play"
                            // fully resolve.
                            val leadStrip = Regex(
                                "(?i)^\\s*(?:" +
                                    "please|can you|could you|would you|will you|hey|ok|okay|" +
                                    "play(?:ing)?|open|launch|start|pull up|bring up|go to|" +
                                    "find|search( for)?|show(?: me)?|put on|queue( up)?|" +
                                    "i (?:want|need|would like) to (?:see|watch|hear|play)|" +
                                    "i (?:want|need|would like)|let(?:'s| us)|" +
                                    "the|some|any|a|an" +
                                    ")\\b[\\s,.:;-]*"
                            )
                            var prev: String
                            do { prev = s; s = leadStrip.replace(s, "") } while (s != prev && s.isNotEmpty())
                            // Strip a leading content-type word ("music", "video(s)",
                            // "song(s)", "playlist", "channel", "stream(s)", "live").
                            val typeStrip = Regex(
                                "(?i)^\\s*(?:" +
                                    "music|videos?|songs?|tracks?|playlists?|channels?|" +
                                    "streams?|live|shorts?|clips?|album" +
                                    ")\\b[\\s,.:;-]*"
                            )
                            do { prev = s; s = typeStrip.replace(s, "") } while (s != prev && s.isNotEmpty())
                            // Strip a leading preposition ("by", "from", "about",
                            // "on", "of", "with", "for") once the filler is gone.
                            s = Regex("(?i)^\\s*(?:by|from|about|on|of|with|for|featuring|feat\\.?)\\b[\\s,.:;-]*")
                                .replace(s, "")
                            // Collapse whitespace and trim terminal punctuation.
                            s = s.replace(Regex("\\s+"), " ").trim()
                            s = s.trimEnd('.', '!', '?', ',', ';', ':')
                            return s
                        }

                        // Try the URL query param first (Gemini's Google Video /results page).
                        val rawQueryParam = (uri.getQueryParameter("search_query")
                            ?: uri.getQueryParameter("q")
                            ?: "")
                        // If the URL was a direct-navigation form (/@handle, /channel/…,
                        // /watch?v=…), grab a readable label from the path/ID as a
                        // secondary source so we still end up searching for the subject.
                        val pathHint = when {
                            path.startsWith("/@") ->
                                Regex("/@([^/?#]+)").find(path)?.groupValues?.get(1).orEmpty()
                                    .replace('_', ' ').replace('-', ' ')
                            path.startsWith("/c/") || path.startsWith("/user/") || path.startsWith("/channel/") ->
                                path.substringAfterLast('/').replace('_', ' ').replace('-', ' ')
                            else -> ""
                        }

                        val explicitSpec = currentTurnYouTubeTranscripts.firstNotNullOfOrNull { transcript ->
                            AssistantIntentParser.parseExplicitYouTubePlaybackRequest(transcript)
                        }
                        val query = explicitSpec?.items?.firstOrNull()?.takeIf { it.isNotBlank() }
                            ?: cleanSearchPhrase(rawQueryParam).takeIf { it.isNotBlank() }
                            ?: cleanSearchPhrase(pathHint).takeIf { it.isNotBlank() }
                            ?: cleanSearchPhrase(pendingLiveInputTranscript).takeIf { it.isNotBlank() }
                            ?: cleanSearchPhrase(lastHandledLiveInputTranscript).takeIf { it.isNotBlank() }
                            ?: cleanSearchPhrase(lastToolAssistTranscript).takeIf { it.isNotBlank() }
                            ?: "trending"

                        val transcript = (pendingLiveInputTranscript + " " + lastToolAssistTranscript).lowercase()
                        val mode = explicitSpec?.mode ?: if (transcript.contains("music") ||
                            transcript.contains("song")) "music" else "video"
                        val queue = explicitSpec?.items?.takeIf { it.size > 1 }.orEmpty()
                        val searchUrl = buildYouTubeSearchUrl(query, mode)
                        Log.d(TAG, "YouTube open_taplink intercepted → TapBrowser query='$query' mode='$mode' originalUrl=$openUrl transcript='${pendingLiveInputTranscript.take(80)}'")
                        launchTapBrowser(
                            initialUrl = searchUrl,
                            youtubeAutoplayQuery = query,
                            youtubeAutoplayMode = mode,
                            youtubeAutoplayQueue = queue
                        )
                    } else if (openUrl.startsWith("file:///android_asset/")) {
                        // Asset URLs (ar_nav.html, etc.) must go through TapBrowser
                        // because WebPanelFragment has allowFileAccess=false
                        launchTapBrowser(initialUrl = openUrl)
                    } else {
                        viewModel.openUrl(openUrl)
                    }
                }
                if (hudText != null) showHudNotification(hudText)
                if (sent) {
                    setHudConnectionStatus(ChatPanelFragment.ConnectionStatus.TOOLS_READY)
                } else {
                    Log.w(TAG, "sendToolResponse failed — Gemini session may have closed")
                    setHudConnectionStatus(ChatPanelFragment.ConnectionStatus.GEMINI_CONNECTED)
                }
            }
        }
    }

    private fun maybeRouteLocalIntentDirectly(
        transcript: String,
        forcedSkill: String? = null,
        forcedIntent: String? = null
    ): Boolean {
        parseReadoutCommand(transcript)?.let { command ->
            armLocalDirectResponseHandoff()
            showHudNotification(
                when (command.mode) {
                    ReadoutMode.VERBATIM -> "Preparing saved readout…"
                    ReadoutMode.SUMMARY -> "Summarizing saved text…"
                }
            )
            executeReadoutCommand(command)
            return true
        }

        resolveRecentYouTubeSuggestionListDisplayRequest(transcript)?.let { listRequest ->
            handleYouTubeSuggestionListDisplayRequest(listRequest)
            return true
        }

        resolveRecentYouTubeFollowUpPlaybackRequest(transcript)?.let { youtubeRequest ->
            armLocalDirectResponseHandoff()
            showHudNotification(youtubeRequest.hudLabel)
            runOnUiThread {
                viewModel.appendDirectAssistantResponse(youtubeRequest.responseText)
                recordHudFunctionTicker("Opened YouTube video", gatewayHealthy = lastOpenClawGatewayHealthy)
                ttsController?.stop()
                ttsController?.speak(youtubeRequest.hudLabel)
                launchTapBrowser(
                    initialUrl = youtubeRequest.searchUrl,
                    youtubeAutoplayQuery = youtubeRequest.query,
                    youtubeAutoplayMode = youtubeRequest.mode,
                    youtubeAutoplayQueue = youtubeRequest.queue
                )
            }
            return true
        }

        // Voice-resolve "open the first/Nth/<keyword>/all link(s)" against the
        // line-start URL entries embedded in the focused chat card. Mirrors the
        // pattern used by send_video_list follow-ups. The HUD label includes
        // the inferred media type so the user sees what TapInsight is about
        // to do ("Opening as podcast: Holmes Archive Ep 160") and can
        // voice-correct if the classifier picked wrong.
        resolveOpenChatCardLinkRequest(transcript)?.let { linkRequest ->
            armLocalDirectResponseHandoff()
            showHudNotification(linkRequest.hudLabel)
            runOnUiThread {
                viewModel.appendDirectAssistantResponse(linkRequest.responseText)
                recordHudFunctionTicker(
                    "Opened chat-card link (${linkRequest.mediaType.userLabel()})",
                    gatewayHealthy = lastOpenClawGatewayHealthy
                )
                ttsController?.stop()
                ttsController?.speak(linkRequest.hudLabel)
                launchUrlByMediaType(linkRequest.firstUrl, linkRequest.mediaType)
            }
            return true
        }

        parseYouTubePlaybackIntent(transcript)?.let { youtubeRequest ->
            armLocalDirectResponseHandoff()
            showHudNotification(youtubeRequest.hudLabel)
            runOnUiThread {
                viewModel.appendDirectAssistantResponse(youtubeRequest.responseText)
                recordHudFunctionTicker("Opened YouTube video", gatewayHealthy = lastOpenClawGatewayHealthy)
                ttsController?.stop()
                ttsController?.speak(youtubeRequest.hudLabel)
                launchTapBrowser(
                    initialUrl = youtubeRequest.searchUrl,
                    youtubeAutoplayQuery = youtubeRequest.query,
                    youtubeAutoplayMode = youtubeRequest.mode,
                    youtubeAutoplayQueue = youtubeRequest.queue
                )
            }
            return true
        }

        resolveRecentTapRadioFollowUpPlaybackRequest(transcript)?.let { tapRadioRequest ->
            handleTapRadioPlaybackRequest(tapRadioRequest)
            return true
        }

        resolveLocalMediaPlaybackRequest(transcript)?.let { mediaRequest ->
            armLocalDirectResponseHandoff()
            showHudNotification(mediaRequest.hudLabel)
            runOnUiThread {
                viewModel.appendDirectAssistantResponse(mediaRequest.responseText)
                recordHudFunctionTicker("Opened local media", gatewayHealthy = lastOpenClawGatewayHealthy)
                ttsController?.stop()
                ttsController?.speak(mediaRequest.hudLabel)
                launchTapBrowser(initialUrl = mediaRequest.openUrl)
            }
            return true
        }

        if (looksLikeDailyBriefingIntent(transcript)) {
            armLocalDirectResponseHandoff()
            showHudNotification("Generating daily briefing")
            lifecycleScope.launch(Dispatchers.IO) {
                val result = toolDispatcher.dispatch(
                    "daily_briefing",
                    JSONObject().put("focus", "today").toString()
                )
                val resultText = result.getOrElse { error ->
                    Log.e(TAG, "Daily briefing dispatch failed", error)
                    "Daily briefing unavailable right now."
                }
                val speech = dailyBriefSpeechSummary(resultText)
                runOnUiThread {
                    viewModel.appendDirectAssistantResponse(resultText)
                    if (speech.isNotBlank()) {
                        ttsController?.stop()
                        ttsController?.speak(speech)
                        showHudNotification(speech.take(120))
                    } else {
                        showHudNotification(resultText.take(120))
                    }
                }
            }
            return true
        }

        if (looksLikeNearbyPlacesIntent(transcript)) {
            val engine = toolAssistEngine ?: return false
            armLocalDirectResponseHandoff()
            showHudNotification("Checking nearby places")
            lifecycleScope.launch(Dispatchers.IO) {
                val assist = runCatching { engine.maybeAssist(transcript) }.getOrNull()
                if (assist == null || assist.toolName != "google_places") {
                    runOnUiThread { showHudNotification("Nearby places unavailable.") }
                    return@launch
                }
                val resultText = assist.resultText
                val spokenSummary = placesSpeechSummary(resultText)
                runOnUiThread {
                    viewModel.appendDirectAssistantResponse(resultText)
                    if (spokenSummary.isNotBlank()) {
                        ttsController?.stop()
                        ttsController?.speak(spokenSummary)
                        showHudNotification(spokenSummary.take(120))
                    } else {
                        showHudNotification(resultText.take(120))
                    }
                }
            }
            return true
        }

        val intent = AssistantIntentParser.parse(transcript) ?: return false
        val preserveLearnLmSession =
            intent is AssistantIntent.Learn &&
                geminiLiveSession != null &&
                (
                    keepLearnLmSessionAliveUntilManualClose ||
                        AssistantIntentParser.isExplicitLearnRequest(transcript)
                )
        if (preserveLearnLmSession) {
            armPinnedLearnLmResponseHandoff()
        } else {
            armLocalDirectResponseHandoff()
        }
        when (intent) {
            is AssistantIntent.OpenWeb -> {
                showHudNotification("Opening ${intent.displayLabel}")
                viewModel.handleDirectAssistantIntent(intent)
            }
            is AssistantIntent.Research -> {
                showHudNotification("Researching ${intent.topic}")
                executeResearchIntentLocally(intent.topic)
            }
            is AssistantIntent.Learn -> {
                showHudNotification("Teaching ${intent.topicHint.ifBlank { "that topic" }}")
                viewModel.handleDirectAssistantIntent(intent)
            }
        }
        return true
    }

    private fun armLocalDirectResponseHandoff() {
        keepLearnLmSessionAliveUntilManualClose = false
        suppressGeminiOutputUntilMs =
            maxOf(
                suppressGeminiOutputUntilMs,
                SystemClock.uptimeMillis() + LOCAL_DIRECT_OUTPUT_SUPPRESS_MS
            )
        uiHandler.removeCallbacks(settledLiveInputRunnable)
        // Stop ALL audio output — streaming Gemini, Gemini readout TTS, and local TTS.
        stopAllSpeechPlayback()
        if (geminiLiveSession != null || liveState != GeminiLiveState.IDLE || viewModel.voiceAssistantActive.value == true) {
            shutdownMultimodalSession()
        }
    }

    private fun stopAllSpeechPlayback(cancelReadoutJob: Boolean = true) {
        if (cancelReadoutJob) {
            activeReadoutJob?.cancel()
            activeReadoutJob = null
        }
        speechStopJob?.cancel()
        speechStopJob =
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching { ttsController?.stop() }
                    .onFailure { Log.w(TAG, "Failed to stop local TTS cleanly", it) }
                runCatching { geminiAudioPlayer?.stopAndFlush() }
                    .onFailure { Log.w(TAG, "Failed to stop Gemini audio player cleanly", it) }
                runCatching { geminiReadoutAudioPlayer?.stopAndFlush() }
                    .onFailure { Log.w(TAG, "Failed to stop Gemini readout player cleanly", it) }
            }
    }

    private fun resolveGeminiReadoutApiKey(): String? {
        return viewModel.preferences.geminiApiKey.trim().takeIf { it.isNotBlank() }
            ?: viewModel.appConfig.apiKeys.geminiKey.trim().takeIf {
                it.isNotBlank() && !it.equals("YOUR_KEY_HERE", ignoreCase = true)
            }
            ?: run {
                val prefs = viewModel.preferences
                if (prefs.researchProvider.trim().equals("gemini", ignoreCase = true)) {
                    prefs.researchApiKey.trim().takeIf { it.isNotBlank() }
                } else {
                    null
                }
            }
    }

    private fun armPinnedLearnLmResponseHandoff() {
        keepLearnLmSessionAliveUntilManualClose = true
        suppressGeminiOutputUntilMs = Long.MAX_VALUE
        uiHandler.removeCallbacks(settledLiveInputRunnable)
        stopAllSpeechPlayback()
        awaitingServerTurnComplete = false
        liveState = GeminiLiveState.FOLLOW_UP
        if (!geminiCaptureActive && geminiLiveSession != null) {
            startGeminiAudioStreaming()
        }
        armSilenceWatchdog()   // 30s timeout while learnlm flag is set
        runOnUiThread {
            showListeningOverlay(true)
            clearLiveSpeechPreview()
            updateListeningTranscript("LearnLM active (30s timeout). Ask a follow-up.")
            chatFragment.setStreamActiveIndicator(false)
            setHudConnectionStatus(ChatPanelFragment.ConnectionStatus.GEMINI_CONNECTED)
            showHudNotification("LearnLM session active — 30s idle timeout.")
        }
    }

    private fun pinLearnLmLiveSessionIfNeeded(transcript: String) {
        if (keepLearnLmSessionAliveUntilManualClose) return
        val candidates = listOf(
            transcript,
            pendingLiveInputTranscript,
            lastHandledLiveInputTranscript,
            lastToolAssistTranscript
        )
        if (candidates.none { AssistantIntentParser.isExplicitLearnRequest(it) }) return
        keepLearnLmSessionAliveUntilManualClose = true
        if (!geminiCaptureActive && geminiLiveSession != null) {
            startGeminiAudioStreaming()
        }
        armSilenceWatchdog()   // 30s timeout while learnlm flag is set
        runOnUiThread {
            updateListeningTranscript("LearnLM active (30s timeout). Ask a follow-up.")
            setHudConnectionStatus(ChatPanelFragment.ConnectionStatus.GEMINI_CONNECTED)
            showHudNotification("LearnLM session active — 30s idle timeout.")
        }
    }

    private fun isGeminiOutputSuppressed(): Boolean =
        SystemClock.uptimeMillis() < suppressGeminiOutputUntilMs

    private fun looksLikeDailyBriefingIntent(transcript: String): Boolean {
        val normalized = transcript
            .trim()
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (normalized.isBlank()) return false
        return normalized in setOf(
            "daily briefing",
            "daily brief",
            "ultimate daily brief",
            "morning briefing",
            "brief me on today",
            "give me my briefing",
            "give me a daily briefing"
        )
    }

    private fun resolveLocalMediaPlaybackRequest(transcript: String): LocalMediaPlaybackRequest? {
        val trimmed = transcript.trim().trimEnd('.', '!', '?')
        if (trimmed.isBlank()) return null
        if (!looksLikeExplicitLocalMediaPlayback(trimmed)) return null

        val requestedItems = extractLocalMediaItemNames(trimmed)
        if (requestedItems.isEmpty()) return null

        val library = MediaLibraryService(this).apply { ensureBootstrap() }
        val playableFiles = collectPlayableLibraryFiles(library)
        if (playableFiles.isEmpty()) return null

        val matches = requestedItems.mapNotNull { item ->
            findBestLibraryMediaMatch(item, playableFiles, library)
        }
        if (matches.size != requestedItems.size || matches.isEmpty()) {
            Log.d(
                TAG,
                "Local media deterministic route skipped; requested=$requestedItems matches=${matches.map { it.name }}"
            )
            return null
        }

        return if (matches.size == 1) {
            buildSingleLocalMediaPlaybackRequest(matches.first(), library)
        } else {
            buildLocalMediaPlaylistPlaybackRequest(matches, library)
        }
    }

    private fun looksLikeExplicitLocalMediaPlayback(transcript: String): Boolean {
        val lower = transcript.lowercase(Locale.US)
        val explicitPlayback = Regex(
            """(?i)^\s*(?:please\s+)?(?:play|start|open|launch|listen\s+to|put\s+on|queue(?:\s+up)?)\b"""
        ).containsMatchIn(transcript)
        if (!explicitPlayback) return false
        return lower.contains("local") ||
            lower.contains("media browser") ||
            lower.contains("media library") ||
            lower.contains("library track") ||
            lower.contains("library tracks") ||
            lower.contains("from my files") ||
            lower.contains("from files") ||
            lower.contains("on my glasses") ||
            lower.contains("saved track") ||
            lower.contains("saved tracks")
    }

    private fun extractLocalMediaItemNames(transcript: String): List<String> {
        val quoted = Regex(""""([^"]{2,160})"|'([^']{2,160})'""")
            .findAll(transcript)
            .mapNotNull { match ->
                match.groupValues.drop(1).firstOrNull { it.isNotBlank() }?.trim()
            }
            .toList()
        if (quoted.isNotEmpty()) return quoted

        var body = transcript
        body = Regex(
            """(?i)^\s*(?:please\s+)?(?:play|start|open|launch|listen\s+to|put\s+on|queue(?:\s+up)?)\b[,\s:;-]*"""
        ).replace(body, "")
        body = Regex("""(?i)\b(?:from|in|on)\s+(?:my\s+)?(?:local\s+)?(?:media\s+library|media\s+browser|library|files|device|glasses)\b""")
            .replace(body, "")
        body = Regex("""(?i)\b(?:local|saved|media\s+browser|media\s+library|library|tracks?|songs?|audio|music\s+files?|files?)\b""")
            .replace(body, " ")
        body = body.replace(Regex("\\s+"), " ").trim()
        if (body.isBlank()) return emptyList()

        val normalized = body
            .replace(Regex("(?i)\\s+(?:and\\s+then|then|followed\\s+by|after\\s+that)\\s+"), ";;")
            .replace(Regex("\\s*;\\s*"), ";;")
            .replace(Regex("\\s*,\\s*(?:and\\s+)?"), ";;")
        if (normalized == body && Regex("(?i)\\s+and\\s+").containsMatchIn(body)) {
            val parts = Regex("(?i)\\s+and\\s+").split(body, limit = 2).map { it.trim() }
            if (parts.size == 2 && parts.all { it.split(Regex("\\s+")).size >= 2 }) {
                return parts.distinctBy { it.lowercase(Locale.US) }
            }
        }
        return normalized
            .split(";;")
            .map { it.trim().trim('"', '\'').trimEnd('.', '!', '?', ',', ';', ':') }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase(Locale.US) }
    }

    private fun collectPlayableLibraryFiles(library: MediaLibraryService): List<File> {
        val allowedExtensions = MediaLibraryService.AUDIO_EXTENSIONS +
            MediaLibraryService.VIDEO_EXTENSIONS +
            MediaLibraryService.PLAYLIST_EXTENSIONS
        return runCatching {
            library.mediaRoot.walkTopDown()
                .filter { file ->
                    file.isFile &&
                        !file.name.startsWith(".") &&
                        file.extension.lowercase(Locale.US) in allowedExtensions
                }
                .toList()
        }.getOrDefault(emptyList())
    }

    private fun findBestLibraryMediaMatch(
        requestedName: String,
        files: List<File>,
        library: MediaLibraryService
    ): File? {
        val target = normalizeMediaSearchText(requestedName)
        if (target.isBlank()) return null
        val targetTokens = target.split(' ').filter { it.length >= 2 }
        return files
            .mapNotNull { file ->
                val name = normalizeMediaSearchText(file.nameWithoutExtension)
                val relative = normalizeMediaSearchText(library.relativize(file))
                val searchable = "$name $relative".trim()
                val score = when {
                    name == target -> 100
                    relative == target -> 95
                    searchable.contains(target) -> 85
                    target.contains(name) && name.length >= 4 -> 75
                    targetTokens.isNotEmpty() && targetTokens.all { searchable.contains(it) } -> 50 + targetTokens.size
                    else -> 0
                }
                if (score > 0) file to score else null
            }
            .maxByOrNull { it.second }
            ?.first
    }

    private fun normalizeMediaSearchText(text: String): String =
        text.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun buildSingleLocalMediaPlaybackRequest(
        file: File,
        library: MediaLibraryService
    ): LocalMediaPlaybackRequest? {
        val relative = library.relativize(file)
        if (relative.isBlank()) return null
        val ext = file.extension.lowercase(Locale.US)
        val encodedTitle = java.net.URLEncoder.encode(file.nameWithoutExtension, "UTF-8")
        return if (ext in MediaLibraryService.PLAYLIST_EXTENSIONS) {
            val playlistParam = java.net.URLEncoder.encode(relative, "UTF-8")
            LocalMediaPlaybackRequest(
                openUrl = "https://appassets.androidplatform.net/assets/media_player.html?type=playlist&playlist=$playlistParam&start=0&source=bridge",
                hudLabel = "Playing ${file.nameWithoutExtension}",
                responseText = "Playing ${file.nameWithoutExtension} from your Media Library."
            )
        } else {
            val mediaUrl = libraryMediaAssetUrl(relative)
            val encodedUrl = java.net.URLEncoder.encode(mediaUrl, "UTF-8")
            val type = if (ext in MediaLibraryService.VIDEO_EXTENSIONS) "video" else "audio"
            LocalMediaPlaybackRequest(
                openUrl = "https://appassets.androidplatform.net/assets/media_player.html?type=$type&url=$encodedUrl&title=$encodedTitle&source=bridge",
                hudLabel = "Playing ${file.nameWithoutExtension}",
                responseText = "Playing ${file.nameWithoutExtension} from your Media Library."
            )
        }
    }

    private fun buildLocalMediaPlaylistPlaybackRequest(
        files: List<File>,
        library: MediaLibraryService
    ): LocalMediaPlaybackRequest? {
        val playlistName = "TapInsight-Voice-Queue-" +
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) +
            ".m3u"
        val playlistFile = library.resolveSafe("${MediaLibraryService.DEFAULT_PLAYLISTS_DIR}/$playlistName")
            ?: return null
        val entries = files.map { file ->
            MediaLibraryService.PlaylistWriteEntry(
                targetPathOrUrl = file.path,
                title = file.nameWithoutExtension,
                durationSeconds = null
            )
        }
        if (!library.writePlaylist(playlistFile, entries)) return null
        val relative = library.relativize(playlistFile)
        val playlistParam = java.net.URLEncoder.encode(relative, "UTF-8")
        return LocalMediaPlaybackRequest(
            openUrl = "https://appassets.androidplatform.net/assets/media_player.html?type=playlist&playlist=$playlistParam&start=0&source=bridge",
            hudLabel = "Queuing ${files.size} local tracks",
            responseText = "Queuing ${files.size} local tracks from your Media Library."
        )
    }

    private fun libraryMediaAssetUrl(relativePath: String): String {
        val encodedPath = relativePath
            .split('/')
            .joinToString("/") { segment ->
                java.net.URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
            }
        return "https://appassets.androidplatform.net/media/$encodedPath"
    }

    private fun shouldBlockVoiceFirstAutoOpen(
        functionName: String,
        openUrl: String?,
        transcripts: List<String>
    ): Boolean {
        if (openUrl.isNullOrBlank()) return false
        val candidates = transcripts.map { it.trim() }.filter { it.isNotBlank() }
        if (candidates.isEmpty()) return false
        // send_link_list is the visible-URL-list page; the URL it opens is
        // file:///android_asset/link_list.html?data=… which encodes the
        // candidate URLs into the query string. The mediaUrl heuristic
        // below would false-positive on the encoded blob (e.g. an entry
        // for a YouTube link makes the encoded data include "youtube.com")
        // and incorrectly block the open. The user reaches this tool only
        // after they've explicitly accepted the offer ('yes, send the
        // links'), so the voice-first reference guard doesn't apply here.
        if (functionName == "send_link_list") return false
        // camera_action is the photo-save tool. When it returns an
        // open_taplink: pointing at the on-glasses photos gallery, that
        // URL is the user's direct request ("view it") — not a
        // reference-style pull that the voice-first guard exists to
        // intercept. Letting the gallery open here is the whole point
        // of the save → view flow.
        if (functionName == "camera_action") return false
        val primaryTranscript = candidates.first()
        // GOOGLE SEARCH BYPASS — same intent as the corresponding bypass
        // in explicitMediaTypeConflictResponse(). When the user asks for
        // a Google search ("google search X", "use google search", "web
        // search Y") or after a failed tool path explicitly says "use
        // Google", they are NOT making a voice-first reference query —
        // they are explicitly authorising the browser to open. Two
        // signals let it through:
        //   1. ANY recent transcript candidate (not just the most recent
        //      utterance) contains a Google-search trigger phrase. We
        //      check the whole candidates list because the user's
        //      "use google search" follow-up may arrive in a separate
        //      transcript from the original radio query.
        //   2. The outgoing URL is a literal google.com/search target.
        //      An explicit search URL is its own consent — no media
        //      tool should second-guess it.
        val transcriptSaysGoogleSearch = candidates.any { t ->
            val lower = t.lowercase(java.util.Locale.US)
            lower.contains("google search") ||
                lower.contains("search google") ||
                lower.contains("google for ") ||
                lower.contains("use google") ||
                lower.contains("web search") ||
                lower.contains("search the web") ||
                Regex("""(?i)\bgoogle\s+(?:up\s+|on\s+)?(?:the\s+web\s+)?for\b""")
                    .containsMatchIn(lower) ||
                Regex("""(?i)^\s*google\s+\S""").containsMatchIn(lower)
        }
        val urlIsGoogleSearch = openUrl.lowercase(java.util.Locale.US).let { u ->
            u.contains("google.com/search") || u.contains("www.google.com/search")
        }
        if (functionName == "open_taplink" && (transcriptSaysGoogleSearch || urlIsGoogleSearch)) {
            return false
        }
        // Photos gallery is the user's own saved photos. Reaching it
        // requires the user to either (a) have just saved a photo and
        // followed up with "view it" or (b) explicitly asked to open
        // their photo gallery. Neither is a "voice-first reference
        // request" — the user actively wants the image surface, not a
        // verbal answer. Bypass before the lookup-style heuristic kicks
        // in. Covers Gemini's open_taplink follow-up after a save and
        // any direct "open my photos" command.
        val urlLowerEarly = openUrl.lowercase(Locale.US)
        if (urlLowerEarly.contains("photos_gallery.html")) {
            return false
        }
        if (hasExplicitBrowserOrMediaConsent(primaryTranscript)) return false
        if (hasExplicitYouTubePlaybackConsent(primaryTranscript)) return false
        if (candidates.none { looksLikeVoiceFirstReferenceRequest(it) }) return false

        val urlLower = openUrl.lowercase(Locale.US)
        val mediaOrBrowserTool = functionName in setOf(
            "open_taplink",
            "send_video_list",
            "tapradio",
            "tapclaw_agent",
            "spotify_player"
        )
        val mediaUrl = urlLower.contains("youtube.com") ||
            urlLower.contains("youtu.be") ||
            urlLower.contains("radio.html") ||
            urlLower.contains("media_player.html") ||
            urlLower.contains("podcast") ||
            urlLower.contains("spotify") ||
            urlLower.contains("audio") ||
            urlLower.contains("video")
        return mediaOrBrowserTool || mediaUrl
    }

    private fun explicitMediaTypeConflictResponse(
        transcript: String,
        functionName: String,
        args: String
    ): String? {
        // GOOGLE SEARCH BYPASS — mirrors the GOOGLE SEARCH PRIORITY rule
        // in GeminiRouter. The user's transcript "google search college
        // radio stations in Oklahoma" used to fail this gate: the
        // word "radio" classified the request as RequestedMediaType.RADIO,
        // so an open_taplink call to google.com/search?q=... was rejected
        // with "Media type mismatch — call tapradio". That fed the
        // rejection back to Gemini, which then verbally apologised and
        // tried to pivot, completely defeating an explicit user request
        // for a Google search.
        //
        // Two complementary signals reliably identify a Google-search
        // intent and let it through every type-integrity check:
        //   1. The TRANSCRIPT explicitly mentions Google search — phrases
        //      that match the prompt-side trigger set ("google search",
        //      "google for", "search google", "web search", "search the
        //      web", or a leading "google "). This wins even if Gemini
        //      somehow built a non-google URL — the user said it.
        //   2. The OUTGOING URL is on google.com/search. Even if the
        //      transcript was ambiguous, an explicit search URL is its
        //      own signal that no media tool should second-guess.
        val urlLowerEarly = extractToolArgUrl(args).lowercase(Locale.US)
        val transcriptLower = transcript.lowercase(Locale.US)
        val transcriptSaysGoogleSearch =
            transcriptLower.contains("google search") ||
                transcriptLower.contains("search google") ||
                transcriptLower.contains("google for ") ||
                transcriptLower.contains("web search") ||
                transcriptLower.contains("search the web") ||
                Regex("""(?i)\bgoogle\s+(?:up\s+|on\s+)?(?:the\s+web\s+)?for\b""")
                    .containsMatchIn(transcriptLower) ||
                Regex("""(?i)^\s*google\s+\S""")
                    .containsMatchIn(transcriptLower)
        val urlIsGoogleSearch =
            urlLowerEarly.contains("google.com/search") ||
                urlLowerEarly.contains("www.google.com/search")
        if (functionName == "open_taplink" && (transcriptSaysGoogleSearch || urlIsGoogleSearch)) {
            return null
        }

        val requested = requestedMediaTypeForTranscript(transcript) ?: return null
        val urlLower = urlLowerEarly
        return when (requested) {
            RequestedMediaType.PODCAST -> {
                if (functionName == "tapradio") null else {
                    "Media type mismatch: the user explicitly asked for podcasts. Do not substitute " +
                        "YouTube, Spotify music, radio stations, or generic browser media yet. Call tapradio " +
                        "with action='top_podcasts', 'preview_podcast', or 'podcast' for the requested podcast query. " +
                        "Only suggest another media type after the podcast search path returns no usable result and " +
                        "you clearly tell the user what was tried."
                }
            }
            RequestedMediaType.RADIO -> {
                if (functionName == "tapradio" || urlLower.contains("radio.html")) null else {
                    "Media type mismatch: the user explicitly asked for radio or a station. Do not substitute " +
                        "podcasts, YouTube, Spotify music, or unrelated browser media. Call tapradio with " +
                        "action='search_stations', 'preview_station', or 'play' for radio. Only suggest podcasts " +
                        "or another medium after the radio search path fails and you say what you tried."
                }
            }
            RequestedMediaType.YOUTUBE -> {
                if (functionName == "open_taplink" || functionName == "send_video_list") null else {
                    "Media type mismatch: the user explicitly asked for YouTube/videos. Do not route this to " +
                        "TapRadio, podcasts, radio, or Spotify. Use the YouTube/open_taplink path or let the " +
                        "local deterministic YouTube playback route handle the command."
                }
            }
            RequestedMediaType.LOCAL_MEDIA -> {
                val localUrl = urlLower.contains("media_player.html") ||
                    urlLower.contains("library_local.html") ||
                    urlLower.contains("/media/")
                if (functionName == "open_taplink" && localUrl) null else {
                    "Media type mismatch: the user asked for local or saved Media Library media. Do not substitute " +
                        "radio, podcasts, YouTube, or Spotify. Use the Media Browser/Media Library player or " +
                        "create/use an M3U playlist for multiple named local items."
                }
            }
            RequestedMediaType.DIRECT_AUDIO -> {
                val directAudioUrl = urlLower.contains("radio.html") ||
                    urlLower.contains("media_player.html") ||
                    Regex("""(?i)\.(?:mp3|m4a|aac|ogg|opus|wav|flac|m3u8?|pls)(?:[?#].*)?$""")
                        .containsMatchIn(urlLower)
                if (functionName == "tapradio" || (functionName == "open_taplink" && directAudioUrl)) null else {
                    "Media type mismatch: the user asked for a direct audio/archive stream. Do not substitute " +
                        "YouTube, podcasts, or Spotify. Use TapRadio/Exo for streams and M3U playlists, or " +
                        "Media Browser for seekable audio files."
                }
            }
        }
    }

    private fun tapRadioPodcastActionWithoutCurrentConsentResponse(
        transcript: String,
        args: String
    ): String? {
        val trimmed = transcript.trim()
        if (trimmed.isBlank()) return null
        val action = runCatching {
            JSONObject(args.ifBlank { "{}" }).optString("action", "").trim().lowercase(Locale.US)
        }.getOrDefault("")
        if (action !in setOf("top_podcasts", "trending_podcasts", "recent_podcasts", "podcast", "preview_podcast")) {
            return null
        }
        if (hasCurrentPodcastConsent(trimmed)) return null
        val recentSurface = recentMediaSurface()
        if (recentSurface == "podcast" && looksLikeGenericMediaPickReference(trimmed)) return null

        return "TapRadio podcast lookup skipped because the current user transcript did not explicitly ask for podcasts. " +
            "Do not infer podcasts from similar words like 'posts' or from stale prior context. Answer the user's current request directly, " +
            "or ask one concise clarification such as: 'Did you mean posts or podcasts?' Only call tapradio for podcasts after the user says podcast, " +
            "or for a clear selection follow-up to the TapRadio podcast list that was just read."
    }

    private fun youtubeSuggestionListWithoutCurrentConsentResponse(transcript: String): String? {
        val trimmed = transcript.trim()
        if (trimmed.isBlank()) return null
        if (hasExplicitYouTubePlaybackConsent(trimmed)) return null
        if (AssistantIntentParser.isYouTubeLookupRequest(trimmed)) return null
        if (requestedMediaTypeForTranscript(trimmed) == RequestedMediaType.YOUTUBE) return null
        if (!looksLikeVoiceFirstFilmOrShowRecommendation(trimmed)) return null

        return "YouTube suggestion list skipped because the current user turn asks for an informational " +
            "film/movie/show recommendation or ranking, not a YouTube/video picker and not playback. " +
            "Answer verbally with the requested ranked list using general knowledge. Include concise titles, " +
            "countries/creators/years when useful, and ask before opening YouTube, showing a picker, or playing anything."
    }

    private fun redundantMediaLookupAfterSuggestionCacheResponse(
        transcript: String,
        functionName: String
    ): String? {
        if (functionName !in setOf("spotify_player", "tapradio", "open_taplink")) return null
        val trimmed = transcript.trim()
        if (trimmed.isBlank()) return null
        if (!hasRecentYouTubeSuggestionCache(maxAgeMs = 60_000L)) return null
        if (!looksLikeMediaSuggestionTurn(trimmed)) return null

        val requested = requestedMediaTypeForTranscript(trimmed)
        if (requested in setOf(
                RequestedMediaType.PODCAST,
                RequestedMediaType.RADIO,
                RequestedMediaType.LOCAL_MEDIA,
                RequestedMediaType.DIRECT_AUDIO
            )
        ) {
            return null
        }

        val lower = trimmed.lowercase(Locale.US)
        if (functionName == "spotify_player" && Regex("""(?i)\b(?:spotify|sonos)\b""").containsMatchIn(trimmed)) {
            return null
        }
        if (functionName == "tapradio" && Regex("""(?i)\b(?:tapradio|radio|station|podcast)\b""").containsMatchIn(trimmed)) {
            return null
        }
        val explicitPlayback = hasExplicitYouTubePlaybackConsent(trimmed) ||
            (AssistantIntentParser.hasExplicitYouTubePlaybackVerb(trimmed) &&
                !AssistantIntentParser.isYouTubeLookupRequest(trimmed))
        if (explicitPlayback && !lower.contains("suggest") && !lower.contains("recommend")) return null

        return "A structured YouTube suggestion list is already cached for this recommendation turn. " +
            "Do not run Spotify, TapRadio, open_taplink, or another media search. Read the exact cached " +
            "titles from the send_video_list result, then stop and ask whether to show the picker or play one/all of them."
    }

    private fun recordRecentMediaToolContext(
        functionName: String,
        transcript: String,
        args: String,
        resultText: String
    ) {
        val surface = inferRecentMediaSurface(functionName, transcript, resultText) ?: return
        val now = System.currentTimeMillis()
        val tapRadioPlayable = if (functionName == "tapradio") extractTapRadioPlayableContext(surface, args) else null
        getSharedPreferences("visionclaw_prefs", MODE_PRIVATE)
            .edit()
            .putString("recent_media_surface", surface)
            .putLong("recent_media_surface_ms", now)
            .putString("recent_media_surface_function", functionName)
            .putString("recent_media_surface_transcript", transcript.take(1000))
            .apply {
                if (tapRadioPlayable != null) {
                    putString("recent_tapradio_play_surface", tapRadioPlayable.surface)
                    putString("recent_tapradio_play_query", tapRadioPlayable.query)
                    putString("recent_tapradio_play_name", tapRadioPlayable.name.orEmpty())
                    putString("recent_tapradio_play_genre", tapRadioPlayable.genre.orEmpty())
                    putLong("recent_tapradio_play_ms", now)
                }
            }
            .apply()
        Log.d(TAG, "Recorded recent media surface=$surface function=$functionName")
    }

    private fun extractTapRadioPlayableContext(surface: String, args: String): TapRadioPlaybackRequest? {
        if (surface !in setOf("radio", "podcast")) return null
        return runCatching {
            val json = JSONObject(args.ifBlank { "{}" })
            val action = json.optString("action", "").trim().lowercase(Locale.US)
            if (action in setOf("list", "stop", "add", "search", "search_stations", "search_station", "search_radio", "search_radios", "top_podcasts", "trending_podcasts", "recent_podcasts")) {
                return@runCatching null
            }
            val query = json.optString("query", "").trim()
            if (query.isBlank()) return@runCatching null
            TapRadioPlaybackRequest(
                surface = surface,
                query = query,
                name = json.optString("name", "").trim().takeIf { it.isNotBlank() },
                genre = json.optString("genre", "").trim().takeIf { it.isNotBlank() }
            )
        }.getOrNull()
    }

    private fun inferRecentMediaSurface(
        functionName: String,
        transcript: String,
        resultText: String
    ): String? {
        val requested = requestedMediaTypeForTranscript(transcript)
        return when (functionName) {
            "send_video_list" -> "youtube"
            "spotify_player" -> "spotify"
            "tapradio" -> when (requested) {
                RequestedMediaType.PODCAST -> "podcast"
                RequestedMediaType.RADIO -> "radio"
                RequestedMediaType.DIRECT_AUDIO -> "direct_audio"
                else -> inferTapRadioSurfaceFromResult(resultText)
            }
            "open_taplink" -> when (requested) {
                RequestedMediaType.YOUTUBE -> "youtube"
                RequestedMediaType.LOCAL_MEDIA -> "local_media"
                RequestedMediaType.DIRECT_AUDIO -> "direct_audio"
                RequestedMediaType.RADIO -> "radio"
                RequestedMediaType.PODCAST -> "podcast"
                else -> null
            }
            else -> null
        }
    }

    private fun inferTapRadioSurfaceFromResult(resultText: String): String? {
        val lower = resultText.lowercase(Locale.US)
        return when {
            lower.contains("episodes about") ||
                lower.contains("podcast") ||
                lower.contains("preview_podcast") -> "podcast"
            lower.contains("radio station") ||
                lower.contains("stations") ||
                lower.contains("search_stations") ||
                lower.contains("radio.html") -> "radio"
            Regex("""(?i)\.(?:mp3|m4a|aac|ogg|opus|wav|flac|m3u8?|pls)(?:[?#\s].*)?$""")
                .containsMatchIn(resultText) -> "direct_audio"
            else -> null
        }
    }

    private fun recentMediaSurface(): String? {
        val prefs = getSharedPreferences("visionclaw_prefs", MODE_PRIVATE)
        val at = prefs.getLong("recent_media_surface_ms", 0L)
        if (at <= 0L || System.currentTimeMillis() - at > 10 * 60 * 1000L) return null
        return prefs.getString("recent_media_surface", null)?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun resolveRecentTapRadioFollowUpPlaybackRequest(transcript: String): TapRadioPlaybackRequest? {
        val trimmed = transcript.trim().trimEnd('.', '!', '?')
        if (trimmed.isBlank()) return null
        if (isMediaServiceCapabilityQuestion(trimmed)) return null
        if (mentionsExplicitYouTubeOrMusicSurface(trimmed)) return null

        val lower = trimmed.lowercase(Locale.US)
        val confirmsPlayback = lower in setOf(
            "yes",
            "yeah",
            "yep",
            "sure",
            "ok",
            "okay",
            "go ahead",
            "do it",
            "start it",
            "play it",
            "play that",
            "play this",
            "play that one",
            "play this one",
            "turn it on",
            "put it",
            "put it on",
            "put that",
            "put that on",
            "put this",
            "put this on",
            "listen to it"
        ) || Regex("""(?i)^\s*(?:yes|yeah|yep|sure|ok|okay),?\s+(?:play|start|open|put\s+on|turn\s+on|go\s+ahead|do\s+it)\b""")
            .containsMatchIn(trimmed) ||
            Regex("""(?i)^\s*(?:yes|yeah|yep|sure|ok|okay),?\s+(?:go\s+ahead\s+and\s+)?(?:play|start|open|put(?:\s+on)?|turn\s+on|listen\s+to)\s+(?:it|that|this|that\s+one|this\s+one)?\s*$""")
                .containsMatchIn(trimmed) ||
            Regex("""(?i)^\s*(?:please\s+)?(?:play|start|open|put(?:\s+on)?|turn\s+on|listen\s+to)\s+(?:it|that|this|that\s+one|this\s+one)\s*$""")
                .containsMatchIn(trimmed)
        if (!confirmsPlayback) return null

        val prefs = getSharedPreferences("visionclaw_prefs", MODE_PRIVATE)
        val at = prefs.getLong("recent_tapradio_play_ms", 0L)
        if (at <= 0L || System.currentTimeMillis() - at > 10 * 60 * 1000L) return null
        val surface = prefs.getString("recent_tapradio_play_surface", null)
            ?.trim()
            ?.takeIf { it in setOf("radio", "podcast") }
            ?: return null
        val query = prefs.getString("recent_tapradio_play_query", null)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return TapRadioPlaybackRequest(
            surface = surface,
            query = query,
            name = prefs.getString("recent_tapradio_play_name", null)?.trim()?.takeIf { it.isNotBlank() },
            genre = prefs.getString("recent_tapradio_play_genre", null)?.trim()?.takeIf { it.isNotBlank() }
        )
    }

    private fun maybeCacheTapRadioOfferFromGeminiOutput(modelText: String) {
        val offeredPlayback = Regex(
            """(?i)\b(?:want me to|would you like me to|should i|i can)\s+(?:play|start|put\s+on|turn\s+on|tune\s+in)\b"""
        ).containsMatchIn(modelText) ||
            Regex("""(?i)\b(?:say|tell me)\s+['"]?(?:yes|play it|go ahead)['"]?\b""")
                .containsMatchIn(modelText)
        if (!offeredPlayback) return

        val transcript = listOf(
            pendingLiveInputTranscript,
            lastHandledLiveInputTranscript,
            latestLiveTranscript,
            lastToolAssistTranscript
        )
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
        val station = extractLikelyRadioStationName(transcript) ?: return
        val now = System.currentTimeMillis()
        getSharedPreferences("visionclaw_prefs", MODE_PRIVATE)
            .edit()
            .putString("recent_media_surface", "radio")
            .putLong("recent_media_surface_ms", now)
            .putString("recent_media_surface_function", "gemini_offer")
            .putString("recent_media_surface_transcript", transcript.take(1000))
            .putString("recent_tapradio_play_surface", "radio")
            .putString("recent_tapradio_play_query", station)
            .putString("recent_tapradio_play_name", station)
            .putString("recent_tapradio_play_genre", "Radio")
            .putLong("recent_tapradio_play_ms", now)
            .apply()
        Log.d(TAG, "Cached TapRadio playback offer from Gemini output station=$station")
    }

    private fun extractLikelyRadioStationName(transcript: String): String? {
        val trimmed = transcript.trim()
        if (trimmed.isBlank()) return null
        val callsign = Regex("""(?i)\b([KW][A-Z0-9]{2,5}(?:[-\s]?(?:FM|AM|HD\d?))?)\b""")
            .find(trimmed)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.uppercase(Locale.US)
        if (!callsign.isNullOrBlank()) return callsign

        if (!Regex("""(?i)\b(?:radio|station|tapradio|fm|am)\b""").containsMatchIn(trimmed)) return null
        return Regex("""(?i)\b(?:radio\s+station|station|radio|tapradio)\s+(?:called\s+|named\s+)?(.+?)\s*[?.!]*$""")
            .find(trimmed)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.trim('"', '\'', ',', '.', '?', '!')
            ?.takeIf { it.length in 2..80 }
    }

    private fun handleTapRadioPlaybackRequest(request: TapRadioPlaybackRequest) {
        val label = if (request.surface == "podcast") {
            "Playing podcast"
        } else {
            "Playing TapRadio"
        }
        runOnUiThread {
            armLocalDirectResponseHandoff()
            showHudNotification(label)
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val args = JSONObject()
                .put("action", if (request.surface == "podcast") "podcast" else "play")
                .put("query", request.query)
                .apply {
                    if (request.surface == "radio") put("kind", "radio")
                    request.name?.let { put("name", it) }
                    request.genre?.let { put("genre", it) }
                }
                .toString()
            Log.d(TAG, "Deterministic TapRadio follow-up playback surface=${request.surface} query=${request.query.take(120)}")
            val result = toolDispatcher.dispatch("tapradio", args)
            val resultTextRaw = result.getOrElse { error ->
                Log.e(TAG, "TapRadio follow-up playback failed", error)
                error.message?.takeIf { it.isNotBlank() } ?: "TapRadio playback failed."
            }
            val resultText = rewriteAllUrlsInText(resultTextRaw)
            val openUrl = if (resultText.contains("open_taplink:", ignoreCase = true)) {
                resultText.substringAfter("open_taplink:")
                    .substringBefore("\n")
                    .trim()
                    .let { AssistantIntentParser.normalizeTapLinkUrl(it) }
            } else {
                null
            }
            val spoken = resultText
                .lineSequence()
                .firstOrNull { !it.startsWith("open_taplink:", ignoreCase = true) && it.isNotBlank() }
                ?: label
            runOnUiThread {
                viewModel.appendDirectAssistantResponse(resultText)
                recordHudFunctionTicker("Opened TapRadio", gatewayHealthy = lastOpenClawGatewayHealthy)
                ttsController?.stop()
                ttsController?.speak(spoken.take(180))
                if (!openUrl.isNullOrBlank()) {
                    launchTapBrowser(initialUrl = openUrl)
                }
                showHudNotification(spoken.take(120))
            }
        }
    }

    private fun shouldDeferYouTubeFollowUpToNewerNonYouTubeSurface(transcript: String): Boolean {
        val trimmed = transcript.trim()
        val genericFollowUp =
            looksLikeGenericMediaPickReference(trimmed) ||
                looksLikeGenericMediaCollectionReference(trimmed)
        if (!genericFollowUp) return false
        if (mentionsExplicitYouTubeOrMusicSurface(trimmed)) return false

        val prefs = getSharedPreferences("visionclaw_prefs", MODE_PRIVATE)
        val surface = prefs.getString("recent_media_surface", null)?.trim().orEmpty()
        val surfaceAt = prefs.getLong("recent_media_surface_ms", 0L)
        if (surfaceAt <= 0L || System.currentTimeMillis() - surfaceAt > 10 * 60 * 1000L) return false
        if (surface.isBlank() || surface == "youtube") return false

        val youtubeAt = prefs.getLong("recent_youtube_suggestions_ms", 0L)
        if (youtubeAt > 0L && youtubeAt > surfaceAt) return false

        Log.d(
            TAG,
            "Deferring generic YouTube follow-up to newer media surface=$surface transcript=${trimmed.take(120)}"
        )
        return true
    }

    private fun hasCurrentPodcastConsent(transcript: String): Boolean {
        return !excludesPodcastMedia(transcript) &&
            Regex("""(?i)\bpodcasts?\b""").containsMatchIn(transcript)
    }

    private fun mentionsExplicitYouTubeOrMusicSurface(transcript: String): Boolean {
        return Regex(
            """(?i)\b(?:youtube|yt|videos?|watch\s+video|music\s+videos?|songs?|tracks?|spotify|youtube\s+music)\b"""
        ).containsMatchIn(transcript)
    }

    private fun looksLikeGenericMediaPickReference(transcript: String): Boolean {
        val trimmed = transcript.trim().trimEnd('.', '!', '?')
        if (trimmed.isBlank()) return false
        val lower = trimmed.lowercase(Locale.US)
        return ordinalVideoPickIndex(lower) != null ||
            lower in setOf(
                "play it",
                "play that",
                "play this",
                "play that one",
                "play this one",
                "play one",
                "play one of them",
                "play one of those",
                "open it",
                "open that one",
                "start it",
                "start that one"
            ) ||
            Regex("""(?i)\b(?:play|open|start|queue)\s+(?:the\s+)?(?:first|second|third|fourth|fifth|last|top|next|previous)\s+one\b""")
                .containsMatchIn(trimmed) ||
            Regex("""(?i)\b(?:that|this|the)\s+one\b""").containsMatchIn(trimmed)
    }

    private fun looksLikeGenericMediaCollectionReference(transcript: String): Boolean {
        val trimmed = transcript.trim().trimEnd('.', '!', '?')
        if (trimmed.isBlank()) return false
        val lower = trimmed.lowercase(Locale.US)
        return lower in setOf(
            "play them all",
            "queue them all",
            "play all of them",
            "queue all of them",
            "play all those",
            "queue all those",
            "play all of those",
            "queue all of those",
            "play the whole list",
            "queue the whole list"
        ) ||
            Regex("""(?i)\b(?:play|queue|open|start)\s+(?:all\s+)?(?:of\s+)?(?:the\s+)?(?:suggested\s+)?(?:ones|items|results|list|them|those)\b""")
                .containsMatchIn(trimmed) ||
            Regex("""(?i)\b(?:all\s+of\s+them|all\s+of\s+those|all\s+those|the\s+whole\s+list)\b""")
                .containsMatchIn(trimmed)
    }

    private fun appendMediaTypeGuardToToolResult(
        transcript: String,
        functionName: String,
        resultText: String
    ): String {
        val guard = mediaTypeGuardInstruction(transcript, functionName) ?: return resultText
        if (resultText.contains("[TapInsight media-type guard]", ignoreCase = true)) return resultText
        return resultText.trimEnd() + "\n\n" + guard
    }

    private fun mediaTypeGuardInstruction(
        transcript: String,
        functionName: String
    ): String? {
        val requested = requestedMediaTypeForTranscript(transcript) ?: return null
        if (functionName !in setOf("open_taplink", "send_video_list", "spotify_player", "tapradio")) return null
        if (!toolMatchesRequestedMediaType(requested, functionName)) return null
        if (requested == RequestedMediaType.PODCAST && hasExplicitNonPodcastMediaSurfaceInPodcastTurn(transcript)) {
            return null
        }
        return when (requested) {
            RequestedMediaType.PODCAST ->
                "[TapInsight media-type guard]\n" +
                    "This is a podcast-only answer for the current user turn. Answer only from the TapRadio " +
                    "podcast result above. Do not include prior songs, YouTube suggestions, Spotify/music " +
                    "results, radio stations, or local-media results unless the current user turn explicitly " +
                    "asked for those too. Topic words such as \"music\", artist names, albums, genres, or song " +
                    "titles can be podcast subjects; they do not make this a music playback request. If the " +
                    "podcast result above has no usable match, say what TapRadio tried, then use Google Search " +
                    "grounding for podcast availability/context if available. If that still does not find a " +
                    "useful result, offer one TapClaw/OpenClaw deeper search and wait for confirmation. Do not " +
                    "open or play a different media type without confirmation."
            RequestedMediaType.RADIO ->
                "[TapInsight media-type guard]\n" +
                    "This is a radio-station/IP-radio answer for the current user turn. Answer only from the " +
                    "TapRadio radio result above. Do not include prior podcasts, YouTube suggestions, Spotify/music " +
                    "results, or local-media results unless the current user turn explicitly asked for those too. " +
                    "If the radio result above has no usable match, say what TapRadio tried, then use Google Search " +
                    "grounding for station availability/context if available. If that still does not find a useful " +
                    "result, offer one TapClaw/OpenClaw deeper search and wait for confirmation. Do not open or " +
                    "play a different media type without confirmation."
            RequestedMediaType.YOUTUBE ->
                "[TapInsight media-type guard]\n" +
                    "This is a YouTube/video answer for the current user turn. Answer only from the YouTube/video " +
                    "result above. Do not include prior podcast, radio, Spotify, or local-media results unless the " +
                    "current user turn explicitly asked for those too. If the YouTube/video result above has no usable " +
                    "match, say what YouTube query was tried, then use Google Search grounding for availability/context " +
                    "if available. If that still does not find a useful result, offer one TapClaw/OpenClaw deeper search " +
                    "and wait for confirmation. Do not substitute podcasts, radio, or local media without confirmation."
            RequestedMediaType.LOCAL_MEDIA ->
                "[TapInsight media-type guard]\n" +
                    "This is a local Media Library answer for the current user turn. Answer only from the local media " +
                    "or M3U result above. Do not include prior podcasts, radio, YouTube, or Spotify results unless the " +
                    "current user turn explicitly asked for those too. If the local media result above has no usable " +
                    "match, say what local file/library search was tried, then offer TapClaw/OpenClaw to resolve the " +
                    "files or generate an M3U playlist. Do not open the last unrelated browser or media item."
            RequestedMediaType.DIRECT_AUDIO ->
                "[TapInsight media-type guard]\n" +
                    "This is a direct-audio/archive-stream answer for the current user turn. Answer only from the direct " +
                    "audio, stream, or playlist result above. Do not include prior podcasts, radio-station searches, " +
                    "YouTube suggestions, Spotify/music results, or local-media results unless the current user turn " +
                    "explicitly asked for those too. If the direct audio result above has no usable match, say what URL " +
                    "or archive path was tried, then use Google Search grounding for source context if available or offer " +
                    "TapClaw/OpenClaw to resolve the stream. Do not open unrelated media without confirmation."
        }
    }

    private fun toolMatchesRequestedMediaType(
        requested: RequestedMediaType,
        functionName: String
    ): Boolean {
        return when (requested) {
            RequestedMediaType.PODCAST,
            RequestedMediaType.RADIO -> functionName == "tapradio"
            RequestedMediaType.YOUTUBE -> functionName == "open_taplink" || functionName == "send_video_list"
            RequestedMediaType.LOCAL_MEDIA -> functionName == "open_taplink"
            RequestedMediaType.DIRECT_AUDIO -> functionName == "tapradio" || functionName == "open_taplink"
        }
    }

    private fun hasExplicitNonPodcastMediaSurfaceInPodcastTurn(transcript: String): Boolean {
        if (!Regex("""(?i)\bpodcasts?\b""").containsMatchIn(transcript)) return false
        if (Regex("""(?i)\b(?:youtube|yt|spotify|sonos|radio\s+stations?|ip\s+radio|internet\s+radio|fm\s+radio|am\s+radio|media\s+library|media\s+browser|local\s+media|saved\s+(?:songs?|tracks?|videos?)|m3u8?|playlist\s+file|archive\.org)\b""")
                .containsMatchIn(transcript)
        ) {
            return true
        }
        if (Regex("""(?i)\b(?:videos?|watch\s+video|music\s+videos?)\b""").containsMatchIn(transcript)) {
            return true
        }
        return Regex(
            """(?i)\b(?:songs?|tracks?|albums?|music)\b\s*(?:,|and|or|plus|also)\s*(?:podcasts?)\b|\bpodcasts?\b\s*(?:,|and|or|plus|also)\s*(?:songs?|tracks?|albums?|music)\b"""
        ).containsMatchIn(transcript)
    }

    private fun looksLikeVoiceFirstFilmOrShowRecommendation(transcript: String): Boolean {
        val trimmed = transcript.trim()
        if (trimmed.isBlank()) return false
        val lower = trimmed.lowercase(Locale.US)
        if (Regex("""(?i)\b(?:youtube|yt|videos?|clips?|music\s+videos?)\b""").containsMatchIn(trimmed)) {
            return false
        }
        if (Regex("""(?i)^\s*(?:please\s+)?(?:play|watch|open|start|pull\s+up|put\s+on|queue(?:\s+up)?|launch)\b""")
                .containsMatchIn(trimmed)
        ) {
            return false
        }
        val recommendationIntent = Regex(
            """(?i)\b(?:best|top|greatest|favorite|favourite|recommend|recommendations?|suggest|suggestions?|find|list|rank|ranking|what\s+are\s+some|give\s+me)\b"""
        ).containsMatchIn(trimmed)
        if (!recommendationIntent) return false
        return Regex(
            """(?i)\b(?:films?|movies?|cinema|documentar(?:y|ies)|feature\s+films?|short\s+films?|animated|animation|anime|tv\s+shows?|series)\b"""
        ).containsMatchIn(trimmed) ||
            lower.contains("not usa") ||
            lower.contains("non-us") ||
            lower.contains("international")
    }

    private fun hasRecentYouTubeSuggestionCache(maxAgeMs: Long): Boolean {
        val prefs = getSharedPreferences("visionclaw_prefs", MODE_PRIVATE)
        val cachedAt = prefs.getLong("recent_youtube_suggestions_ms", 0L)
        if (cachedAt <= 0L || System.currentTimeMillis() - cachedAt > maxAgeMs) return false
        return prefs.getString("recent_youtube_suggestions_json", null)
            ?.contains("\"videos\"", ignoreCase = true) == true
    }

    private fun cachedYouTubeSuggestionPayload(maxAgeMs: Long = 30 * 60 * 1000L): String? {
        val prefs = getSharedPreferences("visionclaw_prefs", MODE_PRIVATE)
        val cachedAt = prefs.getLong("recent_youtube_suggestions_ms", 0L)
        if (cachedAt <= 0L || System.currentTimeMillis() - cachedAt > maxAgeMs) return null
        val payload = prefs.getString("recent_youtube_suggestions_json", null)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return runCatching {
            val videos = JSONObject(payload).optJSONArray("videos") ?: return@runCatching null
            if (videos.length() > 0) payload else null
        }.getOrNull()
    }

    /**
     * If the user just said something like "send the list / show me the
     * links / put them on my glasses" AND we have grounding chunks
     * accumulated from the previous turn, render them directly via
     * link_list.html and return true.
     *
     * The grounding chunks come from the model's googleSearch retrieval
     * pass (parsed in GeminiRouter, surfaced via onGroundingMetadata).
     * Their URIs are the canonical Google grounding-redirect URLs — these
     * resolve server-side to the actual source page. Crucially, the URL
     * and title within a single chunk ARE paired by Google (not by the
     * model's hallucination), so when the user taps a "Wikipedia" titled
     * row, the browser actually lands on Wikipedia.
     *
     * Returns false if either the transcript doesn't match the display
     * request shape OR we have no chunks accumulated. The caller then
     * falls through to the existing YouTube / TapRadio / Gemini path.
     */
    private fun resolveGroundedLinkListDisplayRequest(transcript: String): Boolean {
        val chunks = pendingGroundingChunks ?: return false
        if (chunks.isEmpty()) return false
        val trimmed = transcript.trim().trimEnd('.', '!', '?')
        if (trimmed.isBlank()) return false
        if (!looksLikeListDisplayRequest(trimmed)) return false

        Log.d(
            TAG,
            "Grounded link-list dispatch: ${chunks.size} chunks for transcript='${trimmed.take(120)}'"
        )

        // Build a send_link_list-shaped payload from the chunks.
        // No type classification yet — every entry is "web" (the link_list
        // page renders that as a neutral cyan badge). Type-aware routing
        // can come later once we have stable URL classification.
        val linksJson = JSONArray()
        for (c in chunks.take(8)) {
            val title = c.title.ifBlank { hostFromUri(c.uri) }
            linksJson.put(
                JSONObject()
                    .put("title", title)
                    .put("url", c.uri)
                    .put("type", classifyChunkType(c.uri))
                    .put("summary", "")
            )
        }
        val topic = "Sources"
        val payload = JSONObject()
            .put("title", topic)
            .put("topic", topic)
            .put("links", linksJson)
            .toString()
        val encoded = java.net.URLEncoder.encode(payload, "UTF-8")
        val url = "file:///android_asset/link_list.html?data=$encoded"

        // Acknowledge to the user and launch TapBrowser. We close the
        // Live session implicitly when the panel switches; the chunks
        // were already captured so nothing is lost.
        runOnUiThread {
            armLocalDirectResponseHandoff()
            showHudNotification("Showing ${chunks.size} sources")
            viewModel.appendDirectAssistantResponse(
                "Here you go — ${chunks.size} sources on your glasses."
            )
            ttsController?.stop()
            launchTapBrowser(initialUrl = url)
        }
        // Consume the chunks so we don't replay them on a second tap.
        pendingGroundingChunks = null
        return true
    }

    /** Coarse classifier for a chunk URL — used only for the HTML badge color. */
    private fun classifyChunkType(uri: String): String {
        val lower = uri.lowercase(Locale.US)
        return when {
            lower.contains("youtube.com") || lower.contains("youtu.be") ||
                lower.contains("vimeo.com") -> "video"
            lower.contains("podcasts.apple.com") || lower.contains("podbean") ||
                lower.contains("anchor.fm") -> "podcast"
            lower.contains("wikipedia.org") || lower.contains("/article/") ||
                lower.contains("/blog/") -> "article"
            lower.endsWith(".pdf") -> "pdf"
            else -> "web"
        }
    }

    /** Extract the readable host from a URL for fallback titles. */
    private fun hostFromUri(uri: String): String = runCatching {
        java.net.URI(uri).host?.removePrefix("www.").orEmpty().ifBlank { uri.take(40) }
    }.getOrDefault(uri.take(40))

    /**
     * Detects "send the list / show me the links / put them on my glasses"
     * style requests broadly enough to catch the variations Gemini Live
     * produces in transcription, but tightly enough not to false-match
     * unrelated turns. Used by the grounded-link-list resolver above.
     */
    private fun looksLikeListDisplayRequest(transcript: String): Boolean {
        val lower = transcript.lowercase(Locale.US)
        // ACTION verb (send / show / put / open / display / give …)
        val hasAction = Regex(
            """(?i)\b(?:send|show|display|open|pull\s+up|put|push|cast|give|bring|launch)\b"""
        ).containsMatchIn(lower)
        if (!hasAction) return false
        // OBJECT (list / links / them / sources / it)
        val hasObject = Regex(
            """(?i)\b(?:the\s+)?(?:list|links?|urls?|sources?|them|it|these)\b"""
        ).containsMatchIn(lower)
        if (!hasObject) return false
        return true
    }

    private fun resolveRecentYouTubeSuggestionListDisplayRequest(
        transcript: String
    ): YouTubeSuggestionListDisplayRequest? {
        val trimmed = transcript.trim().trimEnd('.', '!', '?')
        if (trimmed.isBlank()) return null
        if (isMediaServiceCapabilityQuestion(trimmed)) return null
        val recentSurface = recentMediaSurface()
        if (recentSurface != null &&
            recentSurface != "youtube" &&
            !mentionsExplicitYouTubeOrMusicSurface(trimmed)
        ) {
            return null
        }
        if (shouldDeferYouTubeFollowUpToNewerNonYouTubeSurface(trimmed)) return null
        if (!looksLikeRecentYouTubeSuggestionListDisplayRequest(trimmed)) return null

        val payload = cachedYouTubeSuggestionPayload() ?: return null
        val payloadJson = runCatching { JSONObject(payload) }.getOrNull() ?: return null
        val videos = payloadJson.optJSONArray("videos") ?: return null
        if (videos.length() == 0) return null

        val encoded = java.net.URLEncoder.encode(payload, "UTF-8")
        val title = payloadJson.optString("title").trim()
            .takeIf { it.isNotBlank() }
            ?: payloadJson.optString("topic").trim().takeIf { it.isNotBlank() }
            ?: "YouTube picks"
        val count = videos.length()
        return YouTubeSuggestionListDisplayRequest(
            openUrl = "file:///android_asset/video_list.html?data=$encoded",
            hudLabel = "Showing $count YouTube picks",
            responseText = "Showing $count cached YouTube picks on your glasses: $title."
        )
    }

    private fun looksLikeRecentYouTubeSuggestionListDisplayRequest(transcript: String): Boolean {
        val trimmed = transcript.trim().trimEnd('.', '!', '?')
        if (trimmed.isBlank()) return false

        // "send me a list of ..." is a new search/list request. Keep that on
        // Gemini so it can build a fresh list instead of replaying stale picks.
        if (Regex(
                """(?i)\b(?:send|show|display|give|get|make|create|find|search)\s+(?:me\s+)?a\s+""" +
                    """(?:youtube\s+|video\s+|song\s+|track\s+|music\s+video\s+)?list\s+(?:of|about|by|for)\b"""
            ).containsMatchIn(trimmed)
        ) {
            return false
        }

        val action = Regex(
            """(?i)\b(?:send|show|display|open|pull\s+up|bring\s+up|put|push|cast|launch)\b"""
        )
        if (!action.containsMatchIn(trimmed)) return false

        val definiteListObject = Regex(
            """(?i)\b(?:the|that|this|your|suggested|recommended|cached)\s+""" +
                """(?:youtube\s+)?(?:video\s+|song\s+|track\s+|music\s+video\s+)?""" +
                """(?:list|picker|suggestions?|recommendations?|picks)\b"""
        )
        if (definiteListObject.containsMatchIn(trimmed)) return true

        val directListObject = Regex(
            """(?i)\b(?:youtube\s+)?(?:video\s+|song\s+|track\s+|music\s+video\s+)?""" +
                """(?:list|picker|suggestions?|recommendations?|picks)\b"""
        )
        if (directListObject.containsMatchIn(trimmed) &&
            Regex("""(?i)^\s*(?:please\s+)?(?:send|show|display|open|pull\s+up|bring\s+up)\b""")
                .containsMatchIn(trimmed)
        ) {
            return true
        }

        val displaySurface = Regex(
            """(?i)\b(?:to|on|onto|into|in)\s+(?:the\s+|my\s+)?(?:glasses|hud|screen|display|browser)\b"""
        )
        val suggestedItems = Regex(
            """(?i)\b(?:all\s+)?(?:the\s+)?(?:suggested|recommended)\s+""" +
                """(?:youtube\s+)?(?:videos?|songs?|tracks?|picks|items)\b|""" +
                """\b(?:videos?|songs?|tracks?|picks|items)\s+you\s+(?:suggested|recommended)\b|""" +
                """\b(?:those|these|them)\s+(?:videos?|songs?|tracks?|picks|items)?\b"""
        )
        if (displaySurface.containsMatchIn(trimmed) && suggestedItems.containsMatchIn(trimmed)) {
            return true
        }

        val anaphoricSuggestedItems = Regex(
            """(?i)^\s*(?:please\s+)?(?:send|show|display)\s+(?:all\s+)?(?:of\s+)?""" +
                """(?:(?:those|these)\s+|(?:the\s+)?(?:suggested|recommended)\s+)""" +
                """(?:youtube\s+)?(?:videos?|songs?|tracks?|picks|items)\s*$"""
        )
        if (anaphoricSuggestedItems.containsMatchIn(trimmed)) return true

        val pronounDisplay = Regex(
            """(?i)^\s*(?:please\s+)?(?:send|show|display|open|put|push|cast|launch)\s+""" +
                """(?:it|them|those|these)(?:\s+(?:to|on|onto|into|in)\s+""" +
                """(?:the\s+|my\s+)?(?:glasses|hud|screen|display|browser))?\s*$"""
        )
        if (pronounDisplay.containsMatchIn(trimmed) && recentMediaSurface() == "youtube") {
            return true
        }

        return false
    }

    private fun handleYouTubeSuggestionListDisplayRequest(request: YouTubeSuggestionListDisplayRequest) {
        runOnUiThread {
            armLocalDirectResponseHandoff()
            showHudNotification(request.hudLabel)
            viewModel.appendDirectAssistantResponse(request.responseText)
            recordHudFunctionTicker("Opened YouTube list", gatewayHealthy = lastOpenClawGatewayHealthy)
            ttsController?.stop()
            ttsController?.speak(request.hudLabel)
            launchTapBrowser(initialUrl = request.openUrl)
        }
    }

    private fun looksLikeMediaSuggestionTurn(transcript: String): Boolean {
        val hasSuggestionVerb = Regex(
            """(?i)\b(?:suggest|recommend|find|search|look\s+up|list|what\s+are\s+some|give\s+me|show\s+me\s+some)\b"""
        ).containsMatchIn(transcript)
        if (!hasSuggestionVerb) return false
        return Regex(
            """(?i)\b(?:youtube|videos?|songs?|tracks?|music|artists?|albums?|documentaries|clips?)\b"""
        ).containsMatchIn(transcript)
    }

    private fun rewriteTapRadioArgsForRequestedMediaType(transcript: String, args: String): String? {
        val requested = requestedMediaTypeForTranscript(transcript) ?: return null
        if (requested !in setOf(RequestedMediaType.PODCAST, RequestedMediaType.RADIO)) return null
        return runCatching {
            val json = JSONObject(args.ifBlank { "{}" })
            val action = json.optString("action", "").trim().lowercase(Locale.US)
            val query = json.optString("query", "").trim()
            val queryLooksUrl = query.startsWith("http://", ignoreCase = true) ||
                query.startsWith("https://", ignoreCase = true)
            var changed = false
            when (requested) {
                RequestedMediaType.PODCAST -> {
                    when (action) {
                        "preview" -> {
                            json.put("action", "preview_podcast")
                            changed = true
                        }
                        "search" -> {
                            json.put("action", "top_podcasts")
                            if (json.optString("genre", "").isBlank() && query.isNotBlank()) {
                                json.put("genre", query)
                            }
                            changed = true
                        }
                        "play" -> if (!queryLooksUrl) {
                            json.put("action", "podcast")
                            changed = true
                        }
                    }
                }
                RequestedMediaType.RADIO -> {
                    when (action) {
                        "preview" -> {
                            json.put("action", "preview_station")
                            changed = true
                        }
                        "search" -> {
                            json.put("action", "search_stations")
                            changed = true
                        }
                        "podcast", "preview_podcast", "top_podcasts" -> {
                            json.put("action", "search_stations")
                            changed = true
                        }
                    }
                    if (json.optString("kind", "").isBlank() && action == "play") {
                        json.put("kind", "radio")
                        changed = true
                    }
                }
                else -> Unit
            }
            if (changed) json.toString() else null
        }.getOrNull()
    }

    private fun requestedMediaTypeForTranscript(transcript: String): RequestedMediaType? {
        val trimmed = transcript.trim()
        if (trimmed.isBlank()) return null
        val lower = trimmed.lowercase(Locale.US)
        val excludesPodcasts = excludesPodcastMedia(trimmed)
        if (!excludesPodcasts && Regex("""(?i)\bpodcasts?\b""").containsMatchIn(trimmed)) {
            return RequestedMediaType.PODCAST
        }
        if (excludesPodcasts && Regex("""(?i)\b(?:audio|listen|stream|streams|stations?|radio)\b""")
                .containsMatchIn(trimmed)
        ) {
            return RequestedMediaType.RADIO
        }
        if (Regex("""(?i)\b(?:ip\s+radio|internet\s+radio|radio\s+station|radio\s+stations|tapradio|fm\s+radio|am\s+radio|streaming\s+station|station)\b""")
                .containsMatchIn(trimmed)
        ) {
            return RequestedMediaType.RADIO
        }
        if (hasExplicitYouTubePlaybackConsent(trimmed) ||
            AssistantIntentParser.parseExplicitYouTubePlaybackRequest(trimmed) != null ||
            Regex("""(?i)\b(?:youtube|yt|youtube\s+music|videos?|watch\s+video|music\s+video)\b""")
                .containsMatchIn(trimmed)
        ) {
            return RequestedMediaType.YOUTUBE
        }
        if (Regex("""(?i)\b(?:local|saved|media\s+library|media\s+browser|on\s+the\s+glasses|my\s+(?:music|videos?|tracks?|songs?)|m3u8?|playlist\s+file)\b""")
                .containsMatchIn(trimmed)
        ) {
            return RequestedMediaType.LOCAL_MEDIA
        }
        if (lower.contains("archive.org") ||
            Regex("""(?i)\b(?:direct\s+audio|audio\s+url|mp3|m4a|aac|ogg|opus|wav|flac|audio\s+file|stream\s+url)\b""")
                .containsMatchIn(trimmed)
        ) {
            return RequestedMediaType.DIRECT_AUDIO
        }
        return null
    }

    private fun excludesPodcastMedia(transcript: String): Boolean {
        return Regex(
            """(?i)\b(?:besides|except|other\s+than|not|no|without)\s+(?:any\s+)?podcasts?\b|\bpodcasts?\s+(?:excluded|excepted)\b"""
        ).containsMatchIn(transcript)
    }

    private fun extractToolArgUrl(args: String): String {
        return runCatching {
            val json = JSONObject(args.ifBlank { "{}" })
            listOf("url", "link", "query", "openUrl", "playUrl")
                .firstNotNullOfOrNull { key -> json.optString(key, "").trim().takeIf { it.isNotBlank() } }
                .orEmpty()
        }.getOrDefault("")
    }

    private fun forceTapRadioPlaybackArgsIfExplicit(transcript: String, args: String): String? {
        val trimmed = transcript.trim()
        if (trimmed.isBlank()) return null
        val lower = trimmed.lowercase(Locale.US)
        val explicitPlayback = Regex(
            """(?i)^\s*(?:please\s+)?(?:play|start|turn\s+on|put\s+on|listen\s+to|open|launch)\b"""
        ).containsMatchIn(trimmed)
        val radioSurface = lower.contains("tapradio") ||
            lower.contains("radio") ||
            lower.contains("station") ||
            lower.contains("podcast") ||
            lower.contains("stream")
        if (!explicitPlayback || !radioSurface) return null

        return runCatching {
            val json = JSONObject(args.ifBlank { "{}" })
            val action = json.optString("action", "").trim().lowercase(Locale.US)
            if (action !in setOf("preview", "preview_station", "preview_podcast")) {
                return@runCatching null
            }
            val targetAction = if (action == "preview_podcast" || lower.contains("podcast")) "podcast" else "play"
            json.put("action", targetAction)
            if (targetAction == "play" && json.optString("kind", "").isBlank()) {
                json.put("kind", "radio")
            }
            json.toString()
        }.getOrNull()
    }

    private fun hasExplicitBrowserOrMediaConsent(transcript: String): Boolean {
        val trimmed = transcript.trim().trimEnd('.', '!', '?')
        if (trimmed.isBlank()) return false
        if (hasExplicitYouTubePlaybackConsent(trimmed)) return true
        if (AssistantIntentParser.parseExplicitYouTubePlaybackRequest(trimmed) != null) return true

        val lower = trimmed.lowercase(Locale.US)
        val explicitSurface = Regex(
            """(?i)\b(?:browser|youtube|video|videos|podcast|radio|station|song|music|track|tracks|link|url|website|site|webpage|page|article|paper|journal)\b"""
        ).containsMatchIn(trimmed)
        val explicitAction = Regex(
            """(?i)^\s*(?:please\s+)?(?:open|launch|go\s+to|visit|browse\s+to|pull\s+up|bring\s+up|show\s+me|show|display|play|watch|start|listen\s+to|queue(?:\s+up)?|put\s+on)\b"""
        ).containsMatchIn(trimmed)
        if (explicitAction && explicitSurface) return true
        // "Send them to my glasses" / "show it on the glasses" / "display
        // on my hud" — any combination of an action verb (send/show/display
        // /open/put/cast/push) with an on-device display surface (glasses,
        // hud, screen, display) reads as explicit consent. The previous
        // version required the literal phrase "on my glasses" which missed
        // every "to my glasses" / "to glasses" / "to display" variant.
        val displaySurfacePhrase = Regex(
            """(?i)\b(?:to|on|onto|into|in)\s+(?:the\s+|my\s+)?(?:glasses|hud|screen|display|browser)\b"""
        )
        val displayActionVerb = Regex(
            """(?i)\b(?:send|show|display|open|put|push|cast|throw|bring|pull|launch)\b"""
        )
        if (displaySurfacePhrase.containsMatchIn(trimmed) &&
            displayActionVerb.containsMatchIn(trimmed)
        ) {
            return true
        }
        return false
    }

    private fun hasExplicitYouTubePlaybackConsent(transcript: String): Boolean {
        val trimmed = transcript.trim().trimEnd('.', '!', '?')
        if (trimmed.isBlank()) return false
        if (isMediaServiceCapabilityQuestion(trimmed)) return false
        if (AssistantIntentParser.parseExplicitYouTubePlaybackRequest(trimmed) != null) return true

        val clauses = Regex("""(?:[.!?;]+\s+|\s+(?:and\s+then|then)\s+)""")
            .split(trimmed)
            .map { it.trim().trimEnd('.', '!', '?', ',', ';', ':') }
            .filter { it.isNotBlank() }
        val candidates = if (clauses.isEmpty()) listOf(trimmed) else clauses
        return candidates.any { clause ->
            val hasPlaybackAction = Regex(
                """(?i)\b(?:play|watch|open|start|pull\s+up|put\s+on|queue(?:\s+up)?|launch|bring\s+up|listen\s+to)\b"""
            ).containsMatchIn(clause)
            if (!hasPlaybackAction) return@any false

            val mentionsYouTube = Regex("""(?i)\b(?:youtube|yt)\b""").containsMatchIn(clause)
            if (mentionsYouTube) return@any true

            Regex("""(?i)\b(?:that|this|the)\s+(?:song|track|video|one|item)\b|\b(?:it|them)\b""")
                .containsMatchIn(clause) && recentYouTubeSuggestionCandidates().isNotEmpty()
        }
    }

    private fun isMediaServiceCapabilityQuestion(transcript: String): Boolean {
        val trimmed = transcript.trim().trimEnd('.', '!', '?')
        if (trimmed.isBlank()) return false
        if (Regex("""(?i)^\s*(?:can|could|would|will|do|does|is|are)\s+you\b""")
                .containsMatchIn(trimmed)
        ) {
            return false
        }
        val serviceAsSubject = Regex(
            """(?i)^\s*(?:can|could|would|will|do|does|is|are)\s+(?:the\s+)?""" +
                """(?:youtube|yt|tap\s*radio|tapradio|spotify|sonos|media\s+browser|media\s+player|""" +
                """media\s+library|local\s+media|browser|tap\s*claw|tapclaw|open\s*claw|openclaw|""" +
                """gemini|aitap|tapinsight)\b"""
        ).containsMatchIn(trimmed)
        if (!serviceAsSubject) return false
        return transcript.trim().endsWith("?") ||
            Regex(
                """(?i)\b(?:play|stream|open|watch|queue|find|search|support|handle|use|load|run|""" +
                    """access|have|has|able|capable|work)\b"""
            ).containsMatchIn(trimmed)
    }

    private fun mediaServiceCapabilityQuestionResponse(transcript: String): String? {
        if (!isMediaServiceCapabilityQuestion(transcript)) return null
        return "The user asked a capability or availability question about a media service, not a playback directive. " +
            "Do not open, search, queue, or play media in this turn. Answer the question briefly, then ask one follow-up " +
            "such as whether they want you to try playing it or search that service. If they answer yes or give a second " +
            "explicit play/open command, the deterministic playback route can handle it."
    }

    private fun looksLikeVoiceFirstReferenceRequest(transcript: String): Boolean {
        val trimmed = transcript.trim()
        if (trimmed.isBlank()) return false
        if (hasExplicitBrowserOrMediaConsent(trimmed)) return false
        val lower = trimmed.lowercase(Locale.US)
        return lower.contains("reference") ||
            lower.contains("cite") ||
            lower.contains("citation") ||
            lower.contains("source") ||
            lower.contains("according to") ||
            lower.contains("medical journal") ||
            lower.contains("pubmed") ||
            lower.contains("peer reviewed") ||
            lower.contains("journal article") ||
            lower.contains("research paper") ||
            lower.contains("study") ||
            lower.contains("tell me") ||
            lower.contains("explain") ||
            lower.contains("summarize") ||
            lower.contains("summarise") ||
            lower.contains("compare") ||
            lower.contains("look up") ||
            lower.contains("find out") ||
            lower.startsWith("what ") ||
            lower.startsWith("what's ") ||
            lower.startsWith("how ") ||
            lower.startsWith("why ") ||
            lower.startsWith("is ") ||
            lower.startsWith("are ")
    }

    /**
     * Local fast-path for explicit YouTube playback. Lookup/recommend/search
     * turns still go through Gemini so it can suggest first, but direct play
     * commands, subscriptions, and history route deterministically so Gemini
     * cannot refuse or accidentally open stale media.
     */
    private fun parseYouTubePlaybackIntent(transcript: String): YouTubePlaybackRequest? {
        val trimmed = transcript.trim().trimEnd('.', '!', '?')
        if (trimmed.isBlank()) return null
        if (isMediaServiceCapabilityQuestion(trimmed)) return null

        // RESEARCH-MODE OVERRIDE — bail BEFORE any keyword matching below.
        // When the user explicitly says "research [...]" (or a related
        // research verb), they are asking for the research_topic flow,
        // NOT for local YouTube playback. Without this guard, queries
        // like "research YouTube videos on the history of jazz" trip
        // the (youtube + history) catch-all below and start playing
        // their watch history. Same fix-shape as the EXPLICIT-RESEARCH
        // OVERRIDE we added to the Gemini prompt; this is the local
        // mirror so no path can accidentally steal a research request.
        val lowerTrimmed = trimmed.lowercase()
        val isResearchRequest = Regex(
            """(?i)\b(?:research|deep[- ]search|deep[- ]dive(?:\s+into)?)\b"""
        ).containsMatchIn(lowerTrimmed)
        if (isResearchRequest) return null

        resolveRecentYouTubeFollowUpPlaybackRequest(trimmed)?.let { return it }

        // Subscriptions feed — "play my subscriptions", "my subscribed channels".
        val subscriptionsPatterns = listOf(
            Regex("""(?i)^\s*(?:play|open|start)?\s*(?:my\s+)?(?:youtube\s+)?subscribed\s+channels\s*$"""),
            Regex("""(?i)^\s*(?:play|open|start)?\s*(?:my\s+)?youtube\s+subscriptions?\s*$"""),
            Regex("""(?i)^\s*(?:play|open|start)\s+(?:my\s+)?subscriptions?\s*$""")
        )
        if (subscriptionsPatterns.any { it.matches(trimmed) }) {
            return YouTubePlaybackRequest(
                query = "subscriptions",
                mode = "subscriptions",
                searchUrl = buildYouTubeSubscriptionsUrl(),
                hudLabel = "Playing your newest subscribed channel videos",
                responseText = "Playing the newest videos from your subscribed channels with captions enabled."
            )
        }

        // Watch history feed — require an explicit playback verb AND a
        // possessive/article-style cue so we only fire on intents like
        // "play my YouTube history", "open the watch history",
        // "pull up my viewing history". This avoids stealing every
        // sentence that happens to contain "youtube" + "history" — most
        // notably topic queries like "youtube videos on the history of
        // jazz" or "watch a video about the history of computing", AND
        // research queries that the override above already filtered.
        // The earlier "any 'youtube' + 'history' anywhere" rule was too
        // greedy and caused false plays on topic-shaped requests.
        val lower = trimmed.lowercase()
        val hasPlaybackVerb = Regex(
            """(?i)\b(?:play|open|show|pull\s+up|bring\s+up|launch|start|resume|continue)\b"""
        ).containsMatchIn(lower)
        val hasPossessiveCue = Regex(
            """(?i)\b(?:my|the|all\s+of\s+my|my\s+own)\s+(?:youtube\s+)?(?:watch[- ]|viewing\s+)?history\b"""
        ).containsMatchIn(lower)
        val mentionsYouTubeHistoryFeed = Regex(
            """(?i)\byoutube\s+(?:watch[- ]|viewing\s+)?history\b"""
        ).containsMatchIn(lower)
        val isExplicitlyHistoryFeed = mentionsYouTubeHistoryFeed && hasPlaybackVerb
        val isHistoryCommand = hasPlaybackVerb && (hasPossessiveCue || isExplicitlyHistoryFeed)
        if (isHistoryCommand) {
            return YouTubePlaybackRequest(
                query = "history",
                mode = "history",
                searchUrl = buildYouTubeHistoryUrl(),
                hudLabel = "Playing videos from your YouTube history",
                responseText = "Playing videos from your YouTube watch history with captions enabled."
            )
        }

        // Explicit YouTube playback should never be refused by Gemini. Route it
        // deterministically; if the browser cannot resolve a playable result,
        // Gemini can do one helpful search/suggestion turn afterward.
        AssistantIntentParser.parseExplicitYouTubePlaybackRequest(trimmed)?.let { spec ->
            val first = spec.items.firstOrNull()?.trim().orEmpty()
            if (first.isNotBlank()) {
                if (looksLikeBareRecentYouTubeSuggestionPhrase(first)) return null
                val queue = spec.items.takeIf { it.size > 1 }.orEmpty()
                return YouTubePlaybackRequest(
                    query = first,
                    mode = spec.mode,
                    searchUrl = buildYouTubeSearchUrl(first, spec.mode),
                    hudLabel = if (queue.isNotEmpty()) {
                        "Queuing ${queue.size} YouTube ${if (spec.mode == "music") "music picks" else "videos"}"
                    } else {
                        "Playing YouTube ${if (spec.mode == "music") "music" else "video"}"
                    },
                    responseText = if (queue.isNotEmpty()) {
                        "Queuing ${queue.size} YouTube ${if (spec.mode == "music") "music picks" else "videos"} starting with $first."
                    } else {
                        "Playing the newest YouTube ${if (spec.mode == "music") "music" else "video"} for $first with captions enabled."
                    },
                    queue = queue
                )
            }
        }

        // Everything else — let Gemini route it.
        return null
    }

    private fun looksLikeBareRecentYouTubeSuggestionPhrase(text: String): Boolean {
        val lower = text.trim().lowercase(Locale.US)
        if (lower.isBlank()) return false
        return Regex(
            """(?i)^(?:of\s+)?all(?:\s+of)?\s+(?:the|those|that)?\s*(?:suggested\s+)?(?:youtube\s+)?(?:videos|songs|tracks|music)$"""
        ).matches(lower) ||
            lower in setOf(
                "all of them",
                "all of those",
                "all those",
                "of all those",
                "them all",
                "those videos",
                "those songs",
                "those tracks"
            )
    }

    /**
     * Voice intent payload for "open the first/Nth/<keyword>/all link(s)"
     * resolved against the focused chat card's line-start URL entries.
     * [mediaType] drives the routing decision and the type-confirmation
     * heartbeat ("Opening as podcast: …") so the user sees what TapInsight
     * inferred and can voice-correct if classification was wrong.
     */
    private data class OpenChatCardLinkRequest(
        val firstUrl: String,
        val hudLabel: String,
        val responseText: String,
        val mediaType: com.rayneo.visionclaw.ui.panels.chat.CardUrlExtractor.MediaType
    )

    /**
     * If [transcript] looks like an "open link N" / "open the X link" /
     * "open all the links" intent and the focused chat card carries
     * line-start URL entries, return the playback payload that the local
     * dispatcher should execute. Returns null otherwise — the rest of the
     * pipeline (Gemini routing) takes over.
     */
    private fun resolveOpenChatCardLinkRequest(transcript: String): OpenChatCardLinkRequest? {
        val trimmed = transcript.trim().trimEnd('.', '!', '?')
        if (trimmed.isBlank()) return null
        val entries = chatFragment.getFocusedCardLinkEntries()
        if (entries.isEmpty()) return null
        val intent = com.rayneo.visionclaw.ui.panels.chat.CardUrlExtractor
            .resolveVoiceIntent(trimmed, entries) ?: return null
        return when (intent) {
            is com.rayneo.visionclaw.ui.panels.chat.CardUrlExtractor.VoiceIntent.OpenOne -> {
                val entry = intent.entry
                val host = hostForHeartbeat(entry.rawUrl)
                val titleSuffix = if (entry.displayTitle.contains(host, ignoreCase = true)) {
                    entry.displayTitle
                } else {
                    "$host — ${entry.displayTitle}"
                }
                OpenChatCardLinkRequest(
                    firstUrl = entry.rawUrl,
                    hudLabel = "Opening ${entry.mediaType.userLabel()}: $titleSuffix",
                    responseText = "Opening $titleSuffix",
                    mediaType = entry.mediaType
                )
            }
            is com.rayneo.visionclaw.ui.panels.chat.CardUrlExtractor.VoiceIntent.OpenAll -> {
                // Open the first; user can tap subsequent ones from the
                // chat card or use the browser back-and-forth flow.
                val entry = intent.entries.first()
                val host = hostForHeartbeat(entry.rawUrl)
                OpenChatCardLinkRequest(
                    firstUrl = entry.rawUrl,
                    hudLabel = "Opening ${intent.entries.size} links — starting with $host",
                    responseText = "Opening ${intent.entries.size} links — starting with $host (${entry.displayTitle}). " +
                        "Use back to return to the chat card and tap the next one.",
                    mediaType = entry.mediaType
                )
            }
        }
    }

    /**
     * Extract the host from a URL for the routing-confirmation heartbeat
     * so the user hears the intended destination before any embedded
     * autoplay content starts. Falls back to the raw URL when parsing
     * fails — defensive but rare in practice.
     */
    private fun hostForHeartbeat(url: String): String {
        return runCatching {
            android.net.Uri.parse(url).host
                ?.removePrefix("www.")
                ?.takeIf { it.isNotBlank() }
        }.getOrNull() ?: url
    }

    /**
     * Dispatch the URL to the appropriate handler based on the inferred
     * media type. All types currently land in TapBrowser since OpenClaw
     * hasn't yet been extended to extract article text into the media
     * library — but the routing infrastructure is in place so when
     * OpenClaw provides extracted-text file paths, ARTICLE entries can
     * route to the media-library text reader without further changes.
     *
     * Returns the action the caller should take next: launch TapBrowser
     * with these args, or hand off to a different surface.
     */
    private fun launchUrlByMediaType(
        url: String,
        mediaType: com.rayneo.visionclaw.ui.panels.chat.CardUrlExtractor.MediaType
    ) {
        // Future: ARTICLE could check whether url is a media-library Text/
        // file path (set by OpenClaw extraction) and route to the text
        // reader. PODCAST/AUDIO could check for direct MP3 streams and
        // route to the media player. For now everything routes through
        // TapBrowser, which itself can play media files via media_player.html.
        launchTapBrowser(initialUrl = url)
    }

    private fun resolveRecentYouTubeFollowUpPlaybackRequest(transcript: String): YouTubePlaybackRequest? {
        val trimmed = transcript.trim().trimEnd('.', '!', '?')
        if (trimmed.isBlank()) return null
        if (isMediaServiceCapabilityQuestion(trimmed)) return null

        val lower = trimmed.lowercase(Locale.US)
        // Expanded playback-verb set. The original list missed `hear`,
        // `listen`, `view`, `see` — so phrases like "i want to hear all
        // of them" or "view them all" matched the all-collection
        // substring patterns but failed the verb gate and returned
        // null, surfacing as "play-them-all stopped working" to the
        // user. Adding these makes the verb test match every common
        // listening/watching intent without sweeping in non-media
        // verbs (e.g. "show", "give" stay out — those are already
        // handled by the start-of-string "all"/"of all" prefix path).
        val hasPlaybackVerb = Regex(
            """(?i)\b(?:play|watch|hear|listen(?:\s+to)?|view|see|open|start|pull\s+up|put\s+on|queue(?:\s+up)?)\b"""
        ).containsMatchIn(trimmed)
        val questionLike = transcript.trim().endsWith("?") ||
            Regex("""(?i)^\s*(?:what|which|who|when|where|why|how|tell\s+me|describe|explain)\b""")
                .containsMatchIn(trimmed)

        val ordinalIndex = ordinalVideoPickIndex(lower)
        val descriptor = extractVideoPickDescriptor(trimmed)
        val wantsSuggestedMediaCollection =
            lower.contains("all the suggested videos") ||
                lower.contains("all suggested videos") ||
                lower.contains("all the videos you suggested") ||
                lower.contains("all the suggested songs") ||
                lower.contains("all suggested songs") ||
                lower.contains("all the songs you suggested") ||
                lower.contains("all the suggested tracks") ||
                lower.contains("all suggested tracks") ||
                lower.contains("all the tracks you suggested") ||
                lower.contains("all those youtube videos") ||
                lower.contains("all of those youtube videos") ||
                lower.contains("all those videos") ||
                lower.contains("all of those videos") ||
                lower.contains("of all those videos") ||
                lower.contains("all those songs") ||
                lower.contains("all of those songs") ||
                lower.contains("all of the songs") ||
                lower.contains("of all those songs") ||
                lower.contains("all those tracks") ||
                lower.contains("all of those tracks") ||
                lower.contains("all of the tracks") ||
                lower.contains("of all those tracks") ||
                lower.contains("all that music") ||
                lower.contains("all of that music") ||
                lower.contains("of all that music") ||
                lower.contains("all of the videos") ||
                lower.contains("all of those") ||
                lower.contains("all those") ||
                lower.contains("of all those") ||
                lower.contains("all of them") ||
                lower.contains("play them all") ||
                lower.contains("queue them all") ||
                // "them all" standalone — covers "view them all", "watch
                // them all", "hear them all", "listen to them all", etc.
                // The hasPlaybackVerb gate below still requires a verb
                // somewhere in the utterance, so this isn't sweeping in
                // bare pronouns from non-playback contexts.
                lower.contains("them all") ||
                // "<verb> them" without a count ALSO means "all of them"
                // when there's a recent suggestion list in scope. The
                // user explicitly listed "play them" as a phrase that
                // should map to play-all here. We also recognise the
                // sibling verbs from the expanded playback-verb regex
                // above so the various ways the user might phrase it
                // work the same.
                Regex(
                    """(?i)\b(?:play|watch|hear|listen\s+to|view|see|open|queue|start)\s+them\b"""
                ).containsMatchIn(trimmed)
        val wantsAllSuggestedVideos = wantsSuggestedMediaCollection &&
            (hasPlaybackVerb || lower.startsWith("all ") || lower.startsWith("of all "))
        val genericPick = lower.contains("one of the videos") ||
            lower.contains("one of the songs") ||
            lower.contains("one of the tracks") ||
            lower.contains("one of those") ||
            lower.contains("one of them") ||
            lower.contains("that song") ||
            lower.contains("this song") ||
            lower.contains("the song") ||
            lower.contains("that track") ||
            lower.contains("this track") ||
            lower.contains("the track") ||
            lower.contains("that video") ||
            lower.contains("this video") ||
            lower.contains("the video") ||
            lower.contains("play one of") ||
            lower.contains("watch one of") ||
            lower.contains("play one") ||
            lower.contains("watch one") ||
            Regex("""(?i)\b(?:that|this)\s+one\b""").containsMatchIn(trimmed) ||
            Regex("""(?i)\b(?:play|watch|open)\s+it\b""").containsMatchIn(trimmed)

        if (ordinalIndex == null && descriptor.isNullOrBlank() && !genericPick && !wantsAllSuggestedVideos) return null
        if (!hasPlaybackVerb && questionLike) return null
        if (shouldDeferYouTubeFollowUpToNewerNonYouTubeSurface(trimmed)) return null

        val candidates = recentYouTubeSuggestionCandidates()
        if (candidates.isEmpty()) return null

        if (wantsAllSuggestedVideos) {
            val queueMode = if (
                lower.contains("song") ||
                    lower.contains("track") ||
                    lower.contains("music") ||
                    candidates.any { candidate ->
                        Regex("""(?i)\b(?:songs?|tracks?|music|artist|album)\b""")
                            .containsMatchIn(candidate.matchText)
                    }
            ) "music" else "video"
            val queue = candidates
                .map { candidate ->
                    listOfNotNull(
                        candidate.title.takeIf { it.isNotBlank() },
                        candidate.creator?.takeIf { it.isNotBlank() }
                    ).joinToString(" ").trim()
                }
                .filter { it.isNotBlank() }
                .distinctBy { it.lowercase(Locale.US) }
                .take(6)
            val first = queue.firstOrNull() ?: return null
            return YouTubePlaybackRequest(
                query = first,
                mode = queueMode,
                searchUrl = buildYouTubeSearchUrl(first, mode = queueMode),
                hudLabel = "Queuing ${queue.size} suggested YouTube ${if (queueMode == "music") "songs" else "videos"}",
                responseText = "Queuing ${queue.size} suggested YouTube ${if (queueMode == "music") "songs" else "videos"}, starting with ${candidates.first().title}.",
                queue = queue
            )
        }

        val picked = when {
            ordinalIndex != null -> candidates.getOrNull(ordinalIndex)
            !descriptor.isNullOrBlank() -> pickRecentVideoCandidateByDescriptor(candidates, descriptor)
            else -> candidates.firstOrNull()
        } ?: return null

        val query = listOfNotNull(
            picked.title.takeIf { it.isNotBlank() },
            picked.creator?.takeIf { it.isNotBlank() }
        ).joinToString(" ").trim()
        if (query.isBlank()) return null
        val pickedMode = if (
            lower.contains("song") ||
                lower.contains("track") ||
                lower.contains("music")
        ) "music" else "video"

        return YouTubePlaybackRequest(
            query = query,
            mode = pickedMode,
            searchUrl = buildYouTubeSearchUrl(query, mode = pickedMode),
            hudLabel = "Playing ${picked.title.take(60)}",
            responseText = buildString {
                append("Playing ")
                append(picked.title)
                if (!picked.creator.isNullOrBlank()) {
                    append(" by ")
                    append(picked.creator)
                }
                append(" on YouTube with captions enabled.")
            }
        )
    }

    private fun recentYouTubeSuggestionCandidates(): List<RecentVideoCandidate> {
        val cached = cachedYouTubeSuggestionCandidates()
        if (cached.isNotEmpty()) return cached

        val assistantCards = viewModel.getAssistantCardsSnapshot().asReversed()
        for (card in assistantCards) {
            val picks = extractRecentVideoCandidates(card.text)
            if (picks.isNotEmpty()) return picks
        }
        return cachedYouTubeSuggestionCandidates()
    }

    private fun cachedYouTubeSuggestionCandidates(): List<RecentVideoCandidate> {
        val prefs = getSharedPreferences("visionclaw_prefs", MODE_PRIVATE)
        val cachedAt = prefs.getLong("recent_youtube_suggestions_ms", 0L)
        if (cachedAt <= 0L || System.currentTimeMillis() - cachedAt > 30 * 60 * 1000L) return emptyList()
        val payload = prefs.getString("recent_youtube_suggestions_json", null).orEmpty()
        if (payload.isBlank()) return emptyList()
        return runCatching {
            val videos = JSONObject(payload).optJSONArray("videos") ?: return@runCatching emptyList()
            val picks = mutableListOf<RecentVideoCandidate>()
            for (i in 0 until videos.length()) {
                val item = videos.optJSONObject(i) ?: continue
                val title = item.optString("title").trim()
                if (title.isBlank()) continue
                val creator = item.optString("creator").trim().takeIf { it.isNotBlank() }
                picks += RecentVideoCandidate(
                    title = title,
                    creator = creator,
                    matchText = listOf(title, creator.orEmpty(), item.optString("reason"))
                        .joinToString(" ")
                        .lowercase(Locale.US)
                )
            }
            picks.take(6)
        }.getOrElse { emptyList() }
    }

    private fun extractRecentVideoCandidates(cardText: String): List<RecentVideoCandidate> {
        if (cardText.isBlank()) return emptyList()

        val lower = cardText.lowercase(Locale.US)
        val looksVideoCard = lower.contains("youtube") ||
            lower.contains(" videos") ||
            lower.contains(" video ") ||
            lower.contains(" songs") ||
            lower.contains(" song ") ||
            lower.contains(" tracks") ||
            lower.contains(" track ") ||
            lower.contains("music") ||
            lower.contains("watch on youtube") ||
            lower.contains("send this list") ||
            lower.contains("play one of them")

        val markerRegex = Regex("""^\s*(?:\d+[\).\:-]|[-*•])\s*(.+)$""")
        val ordered = LinkedHashMap<String, RecentVideoCandidate>()

        cardText.lines().forEach { rawLine ->
            val body = markerRegex.find(rawLine)?.groupValues?.getOrNull(1)?.trim() ?: return@forEach
            val parsed = parseRecentVideoCandidate(body) ?: return@forEach
            ordered.putIfAbsent(parsed.title.lowercase(Locale.US), parsed)
        }

        if (ordered.isNotEmpty() && looksVideoCard) {
            return ordered.values.take(6)
        }

        if (!looksVideoCard) return emptyList()

        val quotedTitleRegex = Regex("""["“”]([^"“”]{3,120})["“”]""")
        quotedTitleRegex.findAll(cardText).forEach { match ->
            val title = match.groupValues[1].trim()
            if (title.length < 3) return@forEach
            if (title.split(Regex("\\s+")).size > 12) return@forEach
            ordered.putIfAbsent(
                title.lowercase(Locale.US),
                RecentVideoCandidate(
                    title = title,
                    creator = null,
                    matchText = title.lowercase(Locale.US)
                )
            )
        }
        return ordered.values.take(6)
    }

    private fun parseRecentVideoCandidate(body: String): RecentVideoCandidate? {
        val cleaned = body
            .trim()
            .trimStart('-', '—', '–')
            .trim()
        if (cleaned.isBlank()) return null

        val quoted = Regex("""["“”]([^"“”]{3,120})["“”]""")
            .find(cleaned)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()

        val byMatch = Regex("""^(.+?)\s+by\s+(.+)$""").find(cleaned)
        val separated = parseSeparatedMediaCandidate(cleaned)
        val creator = byMatch
            ?.groupValues
            ?.getOrNull(2)
            ?.let { stripTrailingCreatorExplanation(it) }
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: separated?.second

        var title = quoted
            ?: byMatch?.groupValues?.getOrNull(1)?.trim()
            ?: separated?.first
            ?: stripTrailingMediaExplanation(cleaned)

        title = stripLeadingMediaLabel(title)
        title = title.trim('"', '\'', '“', '”', '‘', '’')
        if (title.length < 3) return null
        if (title.split(Regex("\\s+")).size > 24) return null

        return RecentVideoCandidate(
            title = title,
            creator = creator,
            matchText = listOf(title, creator.orEmpty(), cleaned)
                .joinToString(" ")
                .lowercase(Locale.US)
        )
    }

    private fun stripLeadingMediaLabel(value: String): String {
        return Regex("""(?i)^\s*(?:title|song|track|video|artist|creator|channel)\s*:\s*""")
            .replace(value.trim(), "")
            .trim()
    }

    private fun stripTrailingMediaExplanation(value: String): String {
        var text = stripLeadingMediaLabel(value)
        val explanationSplit = Regex(
            """(?i)\s+[—–-]\s+(?:because|for\s+|if\s+you|a\s+|an\s+|the\s+|good\s+|great\s+|classic\s+|recommended|works\s+|fits\s+|has\s+)"""
        ).find(text)
        if (explanationSplit != null) {
            text = text.substring(0, explanationSplit.range.first).trim()
        }
        return text.substringBefore(" (").trim()
    }

    private fun stripTrailingCreatorExplanation(value: String): String {
        return stripLeadingMediaLabel(value)
            .split(Regex("""\s+[—–-]\s+"""), limit = 2)
            .firstOrNull()
            .orEmpty()
            .substringBefore(" (")
            .trim()
    }

    private fun parseSeparatedMediaCandidate(cleaned: String): Pair<String, String?>? {
        val parts = Regex("""\s+[—–-]\s+""")
            .split(cleaned)
            .map { stripTrailingMediaExplanation(it).trim() }
            .filter { it.isNotBlank() }
        if (parts.size < 2) return null

        val first = stripLeadingMediaLabel(parts[0])
        val second = stripLeadingMediaLabel(parts[1])
        if (first.length < 3) return null

        val secondLooksLikeExplanation = Regex(
            """(?i)^(?:because|for\s+|a\s+|an\s+|the\s+|good\s+|great\s+|classic\s+|recommended|works\s+|fits\s+|has\s+)"""
        ).containsMatchIn(second)
        val creator = second
            .takeUnless { secondLooksLikeExplanation }
            ?.takeIf { it.split(Regex("\\s+")).size <= 10 }
        return first to creator
    }

    private fun pickRecentVideoCandidateByDescriptor(
        candidates: List<RecentVideoCandidate>,
        descriptor: String
    ): RecentVideoCandidate? {
        val tokens = descriptor
            .lowercase(Locale.US)
            .split(Regex("[^a-z0-9]+"))
            .map { it.trim() }
            .filter { it.length >= 3 }
        if (tokens.isEmpty()) return null

        return candidates
            .map { candidate ->
                candidate to tokens.count { token -> candidate.matchText.contains(token) }
            }
            .maxByOrNull { it.second }
            ?.takeIf { it.second > 0 }
            ?.first
    }

    private fun extractVideoPickDescriptor(transcript: String): String? {
        val patterns = listOf(
            Regex("""(?i)\b(?:the\s+)?one\s+(?:about|on|from|with)\s+(.+?)\s*$"""),
            Regex("""(?i)\bvideo\s+(?:about|on|from|with)\s+(.+?)\s*$""")
        )
        return patterns.firstNotNullOfOrNull { pattern ->
            pattern.find(transcript)?.groupValues?.getOrNull(1)
        }?.trim()?.trimEnd('.', '!', '?', ',', ';', ':')
    }

    private fun looksLikeYouTubeFollowUpSelectionReference(transcript: String): Boolean {
        val trimmed = transcript.trim().trimEnd('.', '!', '?')
        if (trimmed.isBlank()) return false

        val lower = trimmed.lowercase(Locale.US)
        val mentionsVideoContext = lower.contains("youtube") ||
            lower.contains("video") ||
            lower.contains("videos") ||
            lower.contains("song") ||
            lower.contains("songs") ||
            lower.contains("track") ||
            lower.contains("tracks") ||
            lower.contains("music") ||
            lower.contains("one of those") ||
            lower.contains("one of them") ||
            lower.contains("one of the videos") ||
            lower.contains("one of the songs") ||
            lower.contains("one of the tracks")
        if (!mentionsVideoContext) return false

        val questionLike = transcript.trim().endsWith("?") ||
            Regex("""(?i)^\s*(?:what|which|who|when|where|why|how|tell\s+me|describe|explain)\b""")
                .containsMatchIn(trimmed)
        if (questionLike && !hasExplicitYouTubePlaybackConsent(trimmed)) return false

        return ordinalVideoPickIndex(lower) != null ||
            !extractVideoPickDescriptor(trimmed).isNullOrBlank() ||
            Regex("""(?i)\b(?:that|this|the)\s+(?:song|track|video)\b""").containsMatchIn(trimmed) ||
            Regex("""(?i)\b(?:that|this)\s+one\b""").containsMatchIn(trimmed) ||
            Regex("""(?i)\b(?:first|second|third|fourth|last|final)\s+one\b""").containsMatchIn(trimmed) ||
            Regex("""(?i)\b(?:one of the videos|one of the songs|one of the tracks|one of those|one of them)\b""").containsMatchIn(trimmed) ||
            Regex("""(?i)\b(?:all\s+of\s+those|all\s+those|of\s+all\s+those)\b""").containsMatchIn(trimmed) ||
            Regex("""(?i)\b(?:(?:of\s+)?all(?:\s+of)?\s+(?:the|those|that)?\s*(?:suggested\s+)?(?:youtube\s+)?(?:videos|songs|tracks|music)|(?:videos|songs|tracks)\s+you\s+suggested|play\s+them\s+all|queue\s+them\s+all)\b""")
                .containsMatchIn(trimmed)
    }

    private fun ordinalVideoPickIndex(lower: String): Int? {
        return when {
            Regex("""\b(?:first|1st|number 1|#1|top one)\b""").containsMatchIn(lower) -> 0
            Regex("""\b(?:second|2nd|number 2|#2)\b""").containsMatchIn(lower) -> 1
            Regex("""\b(?:third|3rd|number 3|#3)\b""").containsMatchIn(lower) -> 2
            Regex("""\b(?:fourth|4th|number 4|#4)\b""").containsMatchIn(lower) -> 3
            Regex("""\b(?:last|final)\b""").containsMatchIn(lower) -> {
                val candidates = recentYouTubeSuggestionCandidates()
                if (candidates.isEmpty()) null else candidates.lastIndex
            }
            else -> null
        }
    }

    private fun isYouTubeDiscussionPlaybackRefusal(lowerModelText: String): Boolean {
        val refusesPlayback = lowerModelText.contains("can't play") ||
            lowerModelText.contains("cannot play") ||
            lowerModelText.contains("can't open") ||
            lowerModelText.contains("cannot open") ||
            lowerModelText.contains("can't watch") ||
            lowerModelText.contains("cannot watch")
        val discussionGate = lowerModelText.contains("discussion") ||
            lowerModelText.contains("discussing") ||
            lowerModelText.contains("current turn") ||
            lowerModelText.contains("lookup")
        val mentionsVideo = lowerModelText.contains("video") || lowerModelText.contains("youtube")
        return refusesPlayback && discussionGate && mentionsVideo
    }

    private fun isGeminiVerbalTurnDecline(lowerModelText: String): Boolean {
        return lowerModelText.contains("can't take this turn") ||
            lowerModelText.contains("cannot take this turn") ||
            lowerModelText.contains("can't take that turn") ||
            lowerModelText.contains("cannot take that turn") ||
            lowerModelText.contains("can't help with this turn") ||
            lowerModelText.contains("cannot help with this turn")
    }

    private fun buildYouTubeSearchUrl(query: String, mode: String = "video"): String {
        val searchPhrase =
            if (mode == "music" && !Regex("(?i)\\b(?:music|songs?|audio|track)\\b").containsMatchIn(query)) {
                "$query music"
            } else {
                query
            }
        val encoded = java.net.URLEncoder.encode(searchPhrase, "UTF-8")
        // Plain search — no &sp filter. Double-encoding of sp=CAI%253D caused
        // YouTube to occasionally return 404 instead of search results; our
        // autoplay layer picks the right video from vanilla /results anyway.
        return "https://www.youtube.com/results?search_query=$encoded&taplink_autoplay=$mode"
    }

    private fun buildYouTubeSubscriptionsUrl(): String {
        return "https://www.youtube.com/feed/subscriptions?taplink_autoplay=subscriptions"
    }

    private fun buildYouTubeHistoryUrl(): String {
        return "https://www.youtube.com/feed/history?taplink_autoplay=history"
    }

    private data class YouTubePlaybackRequest(
        val query: String,
        val mode: String,
        val searchUrl: String,
        val hudLabel: String,
        val responseText: String,
        val queue: List<String> = emptyList()
    )

    private data class TapRadioPlaybackRequest(
        val surface: String,
        val query: String,
        val name: String? = null,
        val genre: String? = null
    )

    private data class LocalMediaPlaybackRequest(
        val openUrl: String,
        val hudLabel: String,
        val responseText: String
    )

    private data class YouTubeSuggestionListDisplayRequest(
        val openUrl: String,
        val hudLabel: String,
        val responseText: String
    )

    private data class RecentVideoCandidate(
        val title: String,
        val creator: String?,
        val matchText: String
    )

    private enum class RequestedMediaType {
        RADIO,
        PODCAST,
        YOUTUBE,
        LOCAL_MEDIA,
        DIRECT_AUDIO
    }

    private fun looksLikeNearbyPlacesIntent(transcript: String): Boolean {
        val lower = transcript.trim().lowercase(Locale.US)
        if (lower.isBlank()) return false
        if (lower.contains("tapclaw")) return false
        val mentionsPlaceType = listOf(
            "coffee", "coffee shop", "cafe", "restaurant", "food", "gas station",
            "fuel", "pharmacy", "grocery", "supermarket", "bar", "bakery", "parking"
        ).any { lower.contains(it) }
        if (!mentionsPlaceType) return false
        return listOf(
            "nearest", "closest", "nearby", "near me", "open", "around here", "around me", "where can i get"
        ).any { lower.contains(it) }
    }

    private fun looksLikeRoutesIntent(transcript: String): Boolean {
        val lower = transcript
            .trim()
            .lowercase(Locale.US)
            .replace(Regex("\\s+"), " ")
        if (lower.isBlank()) return false
        if (lower.contains("tapclaw")) return false
        return listOf(
            Regex("""\b(direction|directions|navigate|navigation|route|routing|traffic|commute|eta|travel time|drive time|turn by turn)\b"""),
            Regex("""\b(how do i get to|take me to|navigate to|drive to|go to|head to)\b"""),
            Regex("""\bhow long (is|does it take|to get)\b"""),
            Regex("""\bfrom .+ to .+\b""")
        ).any { it.containsMatchIn(lower) }
    }

    private fun placesSpeechSummary(resultText: String): String {
        return resultText.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filterNot {
                it.startsWith("Maps:", ignoreCase = true) ||
                    it.startsWith("Nearby alternatives", ignoreCase = true)
            }
            .take(4)
            .joinToString(". ")
    }

    private fun routesSpeechSummary(resultText: String): String {
        return resultText.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filterNot { it.startsWith("Maps:", ignoreCase = true) }
            .take(3)
            .joinToString(". ")
    }

    // ── Battery helpers ───────────────────────────────────────────────

    private fun getBatteryLevel(): Int {
        val batteryIntent = registerReceiver(null,
            android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100) ?: 100
        return if (scale > 0) (level * 100) / scale else -1
    }

    private fun isBatteryCharging(): Boolean {
        val batteryIntent = registerReceiver(null,
            android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val status = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        return status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
               status == android.os.BatteryManager.BATTERY_STATUS_FULL ||
               plugged != 0
    }

    private fun onBatterySaverToggled(enabled: Boolean) {
        Log.d(TAG, "Battery saver toggled: $enabled")
        val appPrefs = viewModel.preferences
        if (enabled) {
            // Store original HUD refresh interval, then reduce to 5 minutes
            appPrefs.batterySaverOrigRefresh = appPrefs.hudRefreshIntervalSeconds
            appPrefs.hudRefreshIntervalSeconds = 300  // 5 minutes
            // Disable camera-based multimodal analysis to save power
            viewModel.setMultimodalCameraEnabled(false)
        } else {
            // Restore original HUD refresh interval
            val orig = appPrefs.batterySaverOrigRefresh
            if (orig > 0) {
                appPrefs.hudRefreshIntervalSeconds = orig
                appPrefs.batterySaverOrigRefresh = 0
            }
            // Re-enable camera analysis
            viewModel.setMultimodalCameraEnabled(true)
        }
    }

    /**
     * Bridges battery-saving dark mode (display-only) with the battery saver
     * backend optimizations (HUD polling, camera, background polling).
     *
     * When dark mode is entered:
     *   - If battery saver is already active, do nothing (avoid double-toggle).
     *   - Otherwise, activate it and record that dark mode was the trigger.
     *
     * When dark mode is exited:
     *   - If dark mode was the trigger, deactivate battery saver.
     *   - If battery saver was already active before dark mode, leave it alone.
     */
    private fun onDarkModeBatterySaverBridge(darkModeEnabled: Boolean) {
        val appPrefs = viewModel.preferences
        if (darkModeEnabled) {
            if (appPrefs.batterySaverActive) {
                // Battery saver was already on — don't double-toggle; just note
                // that dark mode did NOT activate it so we don't turn it off on exit.
                darkModeActivatedBatterySaver = false
                Log.d(TAG, "Dark mode entered — battery saver already active, skipping")
            } else {
                darkModeActivatedBatterySaver = true
                appPrefs.batterySaverActive = true
                onBatterySaverToggled(true)
                Log.d(TAG, "Dark mode entered — battery saver activated (HUD polling ↓, camera off)")
            }
        } else {
            if (darkModeActivatedBatterySaver) {
                darkModeActivatedBatterySaver = false
                appPrefs.batterySaverActive = false
                onBatterySaverToggled(false)
                Log.d(TAG, "Dark mode exited — battery saver deactivated, full features restored")
            } else {
                Log.d(TAG, "Dark mode exited — battery saver was not dark-mode-triggered, leaving as-is")
            }
        }
    }

    private fun clawSpeechSummary(resultText: String): String {
        val body = resultText.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filterNot { it.startsWith("[OpenClaw", ignoreCase = true) }
            .take(4)
            .joinToString(". ")
            .ifBlank { "completed the request." }
        return "Open Claw says: $body"
    }

    private fun translateSpeechSummary(resultText: String): String {
        // Extract just the translation result, skip metadata lines
        return resultText.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filterNot { it.startsWith("[TRANSLATE_", ignoreCase = true) }
            .filterNot { it.startsWith("Mode:", ignoreCase = true) }
            .filterNot { it.startsWith("Instruction:", ignoreCase = true) }
            .take(3)
            .joinToString(". ")
            .ifBlank { "Translation ready." }
    }

    private fun batterySpeechSummary(resultText: String): String {
        return resultText.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filterNot { it.startsWith("Available commands", ignoreCase = true) }
            .filterNot { it.startsWith("•") }
            .take(3)
            .joinToString(". ")
            .ifBlank { "Battery status checked." }
    }

    private fun statusSpeechSummary(resultText: String): String {
        return resultText.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .take(4)
            .joinToString(". ")
            .ifBlank { "Status update ready." }
    }

    private fun quickActionSpeechSummary(resultText: String): String {
        // For quick actions, summarize the combined tool results
        return resultText.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filterNot { it.startsWith("—") }
            .filterNot { it.startsWith("⚡") }
            .take(4)
            .joinToString(". ")
            .ifBlank { "Quick action completed." }
    }

    private fun locationSpeechSummary(resultText: String): String {
        return resultText.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .take(2)
            .joinToString(". ")
            .ifBlank { "Location ready." }
    }

    // ── Deep Research background polling ──────────────────────────────
    // When a research task is fired via TapClaw, we poll the relay to check
    // if the report file has appeared in the workspace. Once found, we notify
    // the user on the HUD so they can ask Gemini to read it.

    private var researchPollJob: kotlinx.coroutines.Job? = null
    private var lastResearchTopic: String? = null
    /** Latest heartbeat snippet from OpenClaw — appended to tool results so Gemini has context. */
    @Volatile private var lastTapClawHeartbeat: String? = null
    /**
     * Accumulator for the streaming agent response so the HUD ticker
     * shows readable scrolling text instead of flashing one token at a
     * time. With OpenClaw, each `onProgressUpdate` delta arrived as a
     * complete short stage label ("Browsing https://…") and `.take(200)`
     * worked. With Hermes (OpenAI-compatible SSE), each delta is a
     * single token — replacing the ticker every chunk made the bar
     * flash unreadably. We append every delta to this buffer, take the
     * last 200 chars for display, and clear it on `onProgressComplete`
     * so the next turn starts fresh.
     */
    @Volatile private var heartbeatStreamBuffer: StringBuilder = StringBuilder()

    /**
     * Set true after dispatching a hermes_agent / tapclaw_agent tool call
     * to Gemini Live, so [armSilenceWatchdog] knows whether to apply the
     * Hermes follow-up window when the next idle timer is armed. Cleared
     * on any new user input transcript so a non-Hermes turn isn't tagged.
     */
    @Volatile private var lastToolCallWasHermesAgent: Boolean = false
    /** Most recent human-readable OpenClaw task ticker line shown on the HUD. */
    @Volatile private var lastOpenClawTaskLabel: String? = null
    /** Monotonic timestamp of the latest OpenClaw task heartbeat delta. */
    @Volatile private var lastOpenClawActivityMs = 0L
    /** Latest gateway connectivity verdict used by the crab status icon. */
    @Volatile private var lastOpenClawGatewayHealthy = false
    /** Latest idle gateway label shown when there is no active task heartbeat. */
    @Volatile private var lastOpenClawConnectionLabel = "OpenClaw checking..."
    /** Stationary HUD ticker label for the last user-requested function that completed. */
    @Volatile private var lastHudFunctionTickerLabel: String? = null
    /** Throttle active-task ticker repaints so streaming status still feels live. */
    private var lastHeartbeatUiUpdateMs = 0L
    /** Guard to prevent overlapping ping coroutines when a ping hangs. */
    @Volatile private var pingInFlight = false

    /** OpenClaw client instance — promoted from onCreate for periodic ping access. */
    private var openClawClientField: com.rayneo.visionclaw.core.network.OpenClawClient? = null
    // Hermes equivalent — drives the HUD status icon (red/green) on this branch.
    // When set, hermesPingRunnable polls `/v1/models` to color the icon.
    private var hermesClientField: com.rayneo.visionclaw.core.network.HermesClient? = null
    private var hermesPingInFlight: Boolean = false
    private var lastHermesGatewayHealthy: Boolean = false

    private fun openClawProgressLabel(deltaText: String): String {
        val lower = deltaText.lowercase()
        return when {
            // ── Terminal states (check first so the label "sticks" correctly) ──
            lower.contains("task complete") || lower.contains("all done") ||
            lower.contains("finished task") || lower.contains("completed successfully") ->
                "Task complete"
            lower.contains("success") && !lower.contains("successfully launched") ->
                "Success"

            // ── Errors / blockers ──
            lower.contains("timed out") || lower.contains("timeout") ->
                "Timed out — retrying..."
            lower.contains("blocked") || lower.contains("captcha") ->
                "Blocked — checking options..."
            lower.contains("retry") || lower.contains("retrying") || lower.contains("trying again") ->
                "Retrying..."
            lower.contains("rate limit") -> "Hit a rate limit — pausing..."
            (lower.contains("error") || lower.contains("failed") || lower.contains("problem")) &&
                !lower.contains("no error") ->
                "Ran into an issue..."

            // ── Connection / setup ──
            lower.contains("connecting") || lower.contains("establishing connection") ->
                "Connecting..."
            lower.contains("authenticating") || lower.contains("signing in") ||
                lower.contains("logging in") -> "Signing in..."

            // ── Planning / cognition ──
            lower.contains("thinking") || lower.contains("planning") ->
                "Thinking..."
            lower.contains("analyzing") || lower.contains("analysing") ||
                lower.contains("reviewing") -> "Analyzing..."
            lower.contains("deciding") || lower.contains("choosing") ->
                "Deciding next step..."
            lower.contains("summarizing") || lower.contains("summarising") ->
                "Summarizing..."

            // ── App / tab management ──
            lower.contains("installing") -> "Installing app..."
            lower.contains("install permission") || lower.contains("approval needed") ->
                "Needs permission to install an app"
            lower.contains("opening tab") || lower.contains("switching to tab") ->
                "Opening tab..."
            lower.contains("tab found") || lower.contains("reusing tab") ->
                "Found existing tab"
            lower.contains("app not found") -> "App not found — checking options..."
            lower.contains("searching tabs") || lower.contains("scanning tabs") ->
                "Scanning Chrome tabs..."
            lower.contains("launching app") || lower.contains("starting app") ->
                "Launching app..."

            // ── Browser navigation ──
            lower.contains("navigating") || lower.contains("going to") ->
                "Navigating..."
            lower.contains("loading page") || lower.contains("waiting for page") ||
                lower.contains("page load") -> "Loading page..."
            lower.contains("opening url") || lower.contains("opening link") ||
                lower.contains("opening page") -> "Opening page..."
            lower.contains("redirect") -> "Following redirect..."

            // ── Page interaction ──
            lower.contains("clicking") || lower.contains("tapping") -> "Clicking..."
            lower.contains("typing") || lower.contains("filling in") ||
                lower.contains("filling out") || lower.contains("entering text") ->
                "Typing..."
            lower.contains("scrolling") -> "Scrolling..."
            lower.contains("submitting") || lower.contains("submit form") ->
                "Submitting..."
            lower.contains("selecting") && !lower.contains("selecting model") ->
                "Selecting..."

            // ── Content extraction / reading ──
            lower.contains("reading page") || lower.contains("reading content") ->
                "Reading page..."
            lower.contains("extracting") || lower.contains("pulling data") ->
                "Extracting content..."
            lower.contains("scanning page") || lower.contains("looking for") ||
                lower.contains("searching page") -> "Scanning page..."
            lower.contains("parsing") -> "Parsing content..."

            // ── Search ──
            lower.contains("searching") || lower.contains("querying") ->
                "Searching..."

            // ── File operations ──
            lower.contains("downloading") -> "Downloading..."
            lower.contains("uploading") -> "Uploading..."
            lower.contains("saving file") || lower.contains("writing file") ->
                "Saving file..."
            lower.contains("reading file") || lower.contains("opening file") ->
                "Reading file..."

            // ── Waiting ──
            lower.contains("waiting for") || lower.contains("waiting on") ->
                "Waiting..."

            else -> deltaText.take(120).replace('\n', ' ')
        }
    }

    private fun formatOpenClawTicker(label: String): String {
        val timestamp = SimpleDateFormat("h:mm:ss a", Locale.getDefault()).format(Date())
        return "$timestamp • $label"
    }

    private fun formatHudFunctionTicker(label: String): String {
        val clean = label
            .replace('\n', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(140)
        val prefix = if (clean.startsWith("OpenClaw", ignoreCase = true)) "" else "Last: "
        return prefix + clean
    }

    private fun recordHudFunctionTicker(label: String?, gatewayHealthy: Boolean = lastOpenClawGatewayHealthy) {
        val clean = label
            ?.replace('\n', ' ')
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread { recordHudFunctionTicker(clean, gatewayHealthy) }
            return
        }
        lastHudFunctionTickerLabel = clean
        renderOpenClawTicker(clean, gatewayHealthy = gatewayHealthy, transient = false)
    }

    private fun completedToolTickerLabel(
        toolName: String,
        resultText: String = "",
        autoOpenUrl: String? = null
    ): String? {
        autoOpenUrl?.takeIf { it.isNotBlank() }?.let { url ->
            return "Opened ${AssistantIntentParser.displayLabelForUrl(url)}"
        }
        return when (toolName) {
            "google_places" -> "Places lookup complete"
            "google_routes" -> "Route lookup complete"
            "ask_maps" -> "Map ready"
            "google_air_quality" -> "Air quality checked"
            "location" -> "Location checked"
            "weather" -> "Weather checked"
            "calendar" -> "Calendar checked"
            "tasks" -> "Tasks checked"
            "gmail" -> "Email lookup complete"
            "contacts" -> "Contact lookup complete"
            "notes" -> "Notes updated"
            "status_briefing" -> "Status briefing ready"
            "battery_saver" -> "Battery status checked"
            "research_topic" -> "Research report ready"
            "learn_topic" -> "Learning response ready"
            "tapclaw_agent" -> "OpenClaw task complete"
            "tapradio" -> "TapRadio request complete"
            "spotify_player" -> "Spotify request complete"
            "send_video_list" -> "Video list ready"
            "translate_text" -> "Translation ready"
            "quick_action" -> "Action complete"
            "open_taplink" -> null
            else -> {
                val firstLine = resultText.lineSequence()
                    .map { it.trim() }
                    .firstOrNull { it.isNotBlank() }
                    .orEmpty()
                if (firstLine.isNotBlank() && firstLine.length <= 72) {
                    firstLine
                } else {
                    toolName.replace('_', ' ')
                        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() } +
                        " complete"
                }
            }
        }
    }

    private fun openClawActivityFresh(nowMs: Long = SystemClock.uptimeMillis()): Boolean {
        val lastHeartbeatMs = lastOpenClawActivityMs
        if (lastHeartbeatMs <= 0L) return false
        val configuredIntervalMs = viewModel.preferences.openClawHeartbeatIntervalSeconds * 1000L
        val staleAfterMs = max(HEARTBEAT_UI_INTERVAL_MS + 5_000L, configuredIntervalMs + 5_000L)
        return nowMs - lastHeartbeatMs <= staleAfterMs
    }

    private fun renderOpenClawTicker(label: String?, gatewayHealthy: Boolean, transient: Boolean = false) {
        if (label.isNullOrBlank()) {
            chatFragment.clearHeartbeat()
            chatFragment.setOpenClawGatewayStatus(ChatPanelFragment.OpenClawGatewayStatus.HIDDEN)
            publishUnipanelHeartbeat(null, persistent = false, scroll = true)
            publishUnipanelGatewayStatus(
                hermes = false,
                ChatPanelFragment.OpenClawGatewayStatus.HIDDEN
            )
            return
        }
        if (transient) {
            val ticker = formatOpenClawTicker(label)
            chatFragment.showHeartbeat(ticker, OPENCLAW_PROGRESS_TICKER_DISPLAY_MS, scroll = true)
            publishUnipanelHeartbeat(ticker, persistent = false, scroll = true)
        } else {
            val ticker = formatHudFunctionTicker(label)
            chatFragment.showHeartbeat(ticker, 0L, scroll = false)
            publishUnipanelHeartbeat(ticker, persistent = true, scroll = false)
        }
        val status = if (gatewayHealthy) {
            ChatPanelFragment.OpenClawGatewayStatus.GOOD
        } else {
            ChatPanelFragment.OpenClawGatewayStatus.BAD
        }
        chatFragment.setOpenClawGatewayStatus(status)
        publishUnipanelGatewayStatus(hermes = false, status)
    }

    private fun restoreOpenClawTicker() {
        if (!viewModel.preferences.openClawEnabled) {
            chatFragment.clearHeartbeat()
            chatFragment.setOpenClawGatewayStatus(ChatPanelFragment.OpenClawGatewayStatus.HIDDEN)
            publishUnipanelHeartbeat(null, persistent = false, scroll = true)
            publishUnipanelGatewayStatus(
                hermes = false,
                ChatPanelFragment.OpenClawGatewayStatus.HIDDEN
            )
            return
        }
        val stickyLabel = lastHudFunctionTickerLabel
            ?.takeIf { it.isNotBlank() }
            ?: lastOpenClawTaskLabel
                ?.takeIf { it.isNotBlank() }
            ?: lastOpenClawConnectionLabel
                .takeIf { it.isNotBlank() }
            ?: "OpenClaw ready"
        renderOpenClawTicker(stickyLabel, gatewayHealthy = lastOpenClawGatewayHealthy, transient = false)
    }

    private fun clearOpenClawTaskHeartbeat() {
        lastOpenClawTaskLabel = null
        lastOpenClawActivityMs = 0L
        lastHeartbeatUiUpdateMs = 0L
        OpenClawStatusService.clearTaskLabel()
    }

    /** Periodic OpenClaw connection-status ping. Always performs a real
     *  network health check — no short-circuiting on stale task labels. */
    private val openClawPingRunnable = object : Runnable {
        override fun run() {
            val client = openClawClientField
            if (!viewModel.preferences.openClawEnabled) {
                clearOpenClawTaskHeartbeat()
                lastOpenClawGatewayHealthy = false
                pingInFlight = false
                runOnUiThread {
                    chatFragment.clearHeartbeat()
                    // OC and Hermes drive separate HUD icons now, so this
                    // can freely hide its own crab without affecting the
                    // wings.
                    chatFragment.setOpenClawGatewayStatus(ChatPanelFragment.OpenClawGatewayStatus.HIDDEN)
                    publishUnipanelHeartbeat(null, persistent = false, scroll = true)
                    publishUnipanelGatewayStatus(
                        hermes = false,
                        ChatPanelFragment.OpenClawGatewayStatus.HIDDEN
                    )
                    chatFragment.setStreamActiveIndicator(false)
                }
                scheduleNextPing()
                return
            }
            if (client == null) {
                clearOpenClawTaskHeartbeat()
                lastOpenClawGatewayHealthy = false
                lastOpenClawConnectionLabel = "OpenClaw checking..."
                pingInFlight = false
                OpenClawStatusService.updateConnection(lastOpenClawConnectionLabel, healthy = false)
                runOnUiThread {
                    restoreOpenClawTicker()
                    chatFragment.setStreamActiveIndicator(false)
                }
                scheduleNextPing()
                return
            }

            // Skip if a previous ping is still in-flight (WebSocket hanging).
            if (pingInFlight) {
                scheduleNextPing()
                return
            }

            pingInFlight = true
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val result = kotlinx.coroutines.withTimeout(12_000L) {
                        client.ping()
                    }
                    val (label, gatewayHealthy) = when (result) {
                        is com.rayneo.visionclaw.core.network.OpenClawClient.ClawResult.Success ->
                            "OpenClaw connected" to true
                        is com.rayneo.visionclaw.core.network.OpenClawClient.ClawResult.Error ->
                            "OpenClaw offline" to false
                        is com.rayneo.visionclaw.core.network.OpenClawClient.ClawResult.NotConfigured ->
                            "OpenClaw not configured" to false
                        else -> null to false
                    }
                    if (label != null) {
                        if (!gatewayHealthy) {
                            clearOpenClawTaskHeartbeat()
                        }
                        lastOpenClawConnectionLabel = label
                        // If there's an active task AND gateway is healthy, prefer
                        // the task label — but only while activity is genuinely fresh.
                        val taskLabel = lastOpenClawTaskLabel
                            ?.takeIf { gatewayHealthy && openClawActivityFresh() }
                        val effectiveHealthy = taskLabel != null || gatewayHealthy
                        lastOpenClawGatewayHealthy = effectiveHealthy
                        OpenClawStatusService.updateConnection(label, healthy = effectiveHealthy)
                        runOnUiThread {
                            restoreOpenClawTicker()
                        }
                    }
                } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                    // Ping hung — treat as unreachable.
                    clearOpenClawTaskHeartbeat()
                    lastOpenClawGatewayHealthy = false
                    lastOpenClawConnectionLabel = "OpenClaw timeout"
                    OpenClawStatusService.updateConnection("OpenClaw timeout", healthy = false)
                    runOnUiThread {
                        restoreOpenClawTicker()
                    }
                } catch (e: Exception) {
                    Log.w("OpenClawPing", "Ping exception", e)
                    lastOpenClawGatewayHealthy = false
                    lastOpenClawConnectionLabel = "OpenClaw error"
                    OpenClawStatusService.updateConnection("OpenClaw error", healthy = false)
                    runOnUiThread {
                        restoreOpenClawTicker()
                    }
                } finally {
                    pingInFlight = false
                }
            }
            scheduleNextPing()
        }

        private fun scheduleNextPing() {
            val intervalMs = viewModel.preferences.openClawHeartbeatIntervalSeconds * 1000L
            uiHandler.postDelayed(this, intervalMs)
        }
    }

    private fun startOpenClawPing() {
        uiHandler.removeCallbacks(openClawPingRunnable)
        if (viewModel.preferences.openClawEnabled) {
            runOnUiThread {
                restoreOpenClawTicker()
            }
        }
        // First ping after a short delay to let the UI settle; the ticker is
        // rendered immediately above so the HUD never appears empty.
        uiHandler.postDelayed(openClawPingRunnable, 2_500L)
    }

    private fun stopOpenClawPing() {
        uiHandler.removeCallbacks(openClawPingRunnable)
    }

    /**
     * Hermes equivalent of [openClawPingRunnable]. Pings the user's
     * configured hermes-agent server every `openclaw_heartbeat_interval_seconds`
     * (reusing the same cadence pref) and updates the HUD status icon
     * red (unreachable) / green (reachable). Independent of the OpenClaw
     * ping so the two integrations don't fight over the same icon.
     */
    private val hermesPingRunnable = object : Runnable {
        override fun run() {
            val prefs = viewModel.preferences
            val client = hermesClientField
            if (!prefs.hermesEnabled || client == null) {
                hermesPingInFlight = false
                lastHermesGatewayHealthy = false
                runOnUiThread {
                    chatFragment.setHermesGatewayStatus(ChatPanelFragment.OpenClawGatewayStatus.HIDDEN)
                    publishUnipanelGatewayStatus(
                        hermes = true,
                        ChatPanelFragment.OpenClawGatewayStatus.HIDDEN
                    )
                }
                scheduleNextPing()
                return
            }
            // Quick local config check — if endpoint or key blank, paint red
            // immediately and don't waste a network round-trip.
            if (prefs.hermesEndpoint.isBlank() || prefs.hermesApiKey.isBlank()) {
                lastHermesGatewayHealthy = false
                runOnUiThread {
                    chatFragment.setHermesGatewayStatus(ChatPanelFragment.OpenClawGatewayStatus.BAD)
                    publishUnipanelGatewayStatus(
                        hermes = true,
                        ChatPanelFragment.OpenClawGatewayStatus.BAD
                    )
                }
                scheduleNextPing()
                return
            }
            if (hermesPingInFlight) {
                scheduleNextPing()
                return
            }
            hermesPingInFlight = true
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val result = kotlinx.coroutines.withTimeout(12_000L) { client.ping() }
                    val healthy = result is com.rayneo.visionclaw.core.network.HermesClient.ClawResult.Success
                    lastHermesGatewayHealthy = healthy
                    runOnUiThread {
                        val status =
                            if (healthy) ChatPanelFragment.OpenClawGatewayStatus.GOOD
                            else ChatPanelFragment.OpenClawGatewayStatus.BAD
                        chatFragment.setHermesGatewayStatus(status)
                        publishUnipanelGatewayStatus(hermes = true, status)
                    }
                } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                    lastHermesGatewayHealthy = false
                    runOnUiThread {
                        chatFragment.setHermesGatewayStatus(ChatPanelFragment.OpenClawGatewayStatus.BAD)
                        publishUnipanelGatewayStatus(
                            hermes = true,
                            ChatPanelFragment.OpenClawGatewayStatus.BAD
                        )
                    }
                } catch (e: Exception) {
                    Log.w("HermesPing", "Ping exception", e)
                    lastHermesGatewayHealthy = false
                    runOnUiThread {
                        chatFragment.setHermesGatewayStatus(ChatPanelFragment.OpenClawGatewayStatus.BAD)
                        publishUnipanelGatewayStatus(
                            hermes = true,
                            ChatPanelFragment.OpenClawGatewayStatus.BAD
                        )
                    }
                } finally {
                    hermesPingInFlight = false
                }
            }
            scheduleNextPing()
        }

        private fun scheduleNextPing() {
            val intervalMs = viewModel.preferences.openClawHeartbeatIntervalSeconds * 1000L
            uiHandler.postDelayed(this, intervalMs.coerceAtLeast(5_000L))
        }
    }

    private fun startHermesPing() {
        uiHandler.removeCallbacks(hermesPingRunnable)
        // Paint the Hermes wings immediately so the user sees it from
        // the first frame (red = "checking"). Hides itself in the
        // ping runnable below when the toggle is off.
        runOnUiThread {
            if (viewModel.preferences.hermesEnabled) {
                chatFragment.setHermesGatewayStatus(ChatPanelFragment.OpenClawGatewayStatus.BAD)
                publishUnipanelGatewayStatus(
                    hermes = true,
                    ChatPanelFragment.OpenClawGatewayStatus.BAD
                )
            } else {
                chatFragment.setHermesGatewayStatus(ChatPanelFragment.OpenClawGatewayStatus.HIDDEN)
                publishUnipanelGatewayStatus(
                    hermes = true,
                    ChatPanelFragment.OpenClawGatewayStatus.HIDDEN
                )
            }
        }
        uiHandler.postDelayed(hermesPingRunnable, 1_500L)
    }

    private fun stopHermesPing() {
        uiHandler.removeCallbacks(hermesPingRunnable)
    }

    /** Start polling the relay for a research report file. Called when
     *  a "research [topic]" tapclaw_agent call returns (fire-and-forget). */
    fun startResearchPoll(topic: String) {
        lastResearchTopic = topic
        researchPollJob?.cancel()
        researchPollJob = lifecycleScope.launch(Dispatchers.IO) {
            val relayBase = buildRelayBaseUrlFromPrefs() ?: return@launch
            // Build expected filename pattern: Gemini-ResearchTopic-YYYY-MM-DD
            val dateStr = java.time.LocalDate.now().toString()
            val checkUrl = "$relayBase/media/Gemini-Research/Gemini-ResearchTopic-$dateStr.txt"
            Log.d(TAG, "Research poll started: checking $checkUrl every 30s")
            runOnUiThread {
                chatFragment.showHeartbeat("Research started: $topic", 10_000L)
                chatFragment.setStreamActiveIndicator(true)
            }
            var found = false
            while (!found && isActive) {
                kotlinx.coroutines.delay(30_000) // Check every 30 seconds
                try {
                    val conn = java.net.URL(checkUrl).openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 5000
                    conn.readTimeout = 5000
                    conn.requestMethod = "HEAD"
                    val code = conn.responseCode
                    conn.disconnect()
                    if (code == 200) {
                        found = true
                        Log.d(TAG, "Research report ready: $checkUrl")
                        val reportText = runCatching {
                            ActiveNetworkHttp.get(
                                url = checkUrl,
                                connectTimeoutMs = 5_000,
                                readTimeoutMs = 12_000
                            )
                        }.getOrNull()
                            ?.takeIf { it.code in 200..299 }
                            ?.body
                            ?.trim()
                            .orEmpty()
                        if (reportText.isNotBlank()) {
                            saveResearchReportArtifact(
                                reportText = reportText,
                                topicHint = topic,
                                titleHint = "Research report: $topic"
                            )
                        }
                        runOnUiThread {
                            chatFragment.setStreamActiveIndicator(false)
                            recordHudFunctionTicker("Research report ready", gatewayHealthy = lastOpenClawGatewayHealthy)
                            chatFragment.showHeartbeat("Research report ready! Say 'read the research report' or 'summarize the research report'.", 15_000L)
                            viewModel.appendDirectAssistantResponse(
                                "Research report on \"$topic\" is ready.\nSay \"read the research report\" or \"summarize the research report\"."
                            )
                        }
                    } else {
                        Log.d(TAG, "Research poll: not ready yet (HTTP $code)")
                        runOnUiThread {
                            chatFragment.showHeartbeat("Researching: $topic...", 35_000L)
                        }
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Research poll error: ${e.message}")
                }
            }
        }
    }

    /** Build relay base URL from preferences (shared RelayUrlHelper logic;
     *  prefers the public tunnel for LAN endpoints so the URL stays
     *  reachable away from the home network). */
    private fun buildRelayBaseUrlFromPrefs(): String? {
        return RelayUrlHelper.baseFromEndpoint(
            viewModel.preferences.openClawEndpoint.trim(),
            preferTapInsightPublicForLocal = true
        )
    }

    private fun shouldOwnToolAssistLocally(toolName: String): Boolean {
        // google_places and google_routes are intentionally NOT in this set.
        // Local ownership calls armLocalDirectResponseHandoff() which kills the
        // Gemini Live session, preventing follow-up questions. By routing places
        // and routes through Gemini's voice (sendClientText), the session stays
        // alive and the user can have a multi-turn conversation about the results.
        return toolName in setOf("google_air_quality", "location", "learn_topic", "translate_text", "battery_saver", "quick_action", "research_topic", "tapclaw_agent")
    }

    private fun hasGeminiStartedReplyForCurrentTurn(): Boolean {
        return geminiLiveSession != null &&
            awaitingServerTurnComplete &&
            currentGeminiOutputTurnStartedMs != 0L
    }

    /**
     * Scan a block of text for any https:// URLs on hallucinated domains
     * and rewrite them in-place. This is applied to tool result text before
     * it's sent back to Gemini or parsed for open_taplink URLs.
     */
    private fun rewriteAllUrlsInText(text: String): String {
        // Match any https://...  or http://... URL in the text
        val urlPattern = Regex("""https?://[^\s"'<>\]]+""")
        var result = text
        for (match in urlPattern.findAll(text)) {
            val original = match.value
            val rewritten = AssistantIntentParser.rewriteHallucinatedMediaDomain(original)
            if (rewritten != original) {
                result = result.replace(original, rewritten)
                Log.w(TAG, "Rewrote hallucinated URL in tool result: $original → $rewritten")
            }
        }
        return result
    }

    private fun toolAssistHudStatus(toolName: String, resultText: String): String {
        return when (toolName) {
            "ask_maps" -> "Checking place details…"
            "google_places" -> "Checking nearby places…"
            "google_routes" -> "Checking route details…"
            "google_air_quality" -> "Checking air quality…"
            "location" -> "Refreshing location…"
            "status_briefing" -> "Preparing brief…"
            "research_topic" -> "Reading research report…"
            "tapradio" -> "Preparing audio…"
            else -> resultText.take(120)
        }
    }

    private fun extractVerbatimResearchText(resultText: String): String {
        return resultText
            .lineSequence()
            .dropWhile { line ->
                val trimmed = line.trim()
                trimmed.isBlank() ||
                    trimmed.startsWith("[Research model:", ignoreCase = true) ||
                    trimmed.startsWith("Research model:", ignoreCase = true)
            }
            .joinToString("\n")
            .trim()
    }

    private fun extractReadableTapClawText(resultText: String): String {
        return resultText
            .lineSequence()
            .map { it.trimEnd() }
            .filterNot { line ->
                val trimmed = line.trim()
                trimmed.isBlank() ||
                    trimmed.startsWith("open_taplink:", ignoreCase = true) ||
                    trimmed.startsWith("[TapClaw last status:", ignoreCase = true) ||
                    trimmed.startsWith("[OpenClaw session:", ignoreCase = true)
            }
            .joinToString("\n")
            .trim()
    }

    private fun parseReadoutCommand(transcript: String): ReadoutCommand? {
        val normalized = transcript
            .trim()
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (normalized.isBlank()) return null

        val resumeRequested = normalized in setOf(
            "resume report",
            "resume the report",
            "resume research report",
            "resume the research report",
            "resume reading",
            "resume the reading",
            "continue reading",
            "continue the report",
            "continue report",
            "continue the research report"
        )
        if (resumeRequested) {
            return ReadoutCommand(
                target = ReadoutTarget.RESEARCH_REPORT,
                mode = ReadoutMode.VERBATIM,
                action = ReadoutAction.RESUME
            )
        }

        val topicSpecificMatch = Regex(
            """(?i)^\s*(read|say|speak|summarize|summarise)\s+(?:me\s+)?(?:the\s+)?(?:research\s+report|research|rsearch|report)\s+on\s+(.+?)\s*$"""
        ).find(transcript.trim())
        if (topicSpecificMatch != null) {
            val mode = when (topicSpecificMatch.groupValues[1].trim().lowercase(Locale.US)) {
                "summarize", "summarise" -> ReadoutMode.SUMMARY
                else -> ReadoutMode.VERBATIM
            }
            val topicQuery = topicSpecificMatch.groupValues[2]
                .trim()
                .trimEnd('.', '?', '!')
                .takeIf { it.isNotBlank() }
            return ReadoutCommand(
                target = ReadoutTarget.RESEARCH_REPORT,
                mode = mode,
                topicQuery = topicQuery
            )
        }

        val mode = when {
            normalized.startsWith("summarize ") || normalized.startsWith("summarise ") ->
                ReadoutMode.SUMMARY
            normalized.startsWith("read ") ||
                normalized.startsWith("read me ") ||
                normalized.startsWith("say ") ||
                normalized.startsWith("speak ") ->
                ReadoutMode.VERBATIM
            else -> null
        } ?: return null

        val target = when {
            normalized.contains("research report") || normalized.contains("research brief") ||
                normalized.contains("research") ||
                // Catch "read the report", "read me the report", "read the report again"
                (normalized.contains("report") && !normalized.contains("tapclaw") && !normalized.contains("openclaw")) ->
                ReadoutTarget.RESEARCH_REPORT
            normalized.contains("tapclaw result") ||
                normalized.contains("tapclaw response") ||
                normalized.contains("tapclaw text") ||
                normalized.contains("tapclaw output") ||
                normalized.contains("openclaw result") ||
                normalized.contains("openclaw response") ||
                normalized.contains("openclaw text") ||
                normalized.contains("openclaw output") ->
                ReadoutTarget.TAPCLAW_RESULT
            normalized.contains("chat card") ||
                normalized.contains("last response") ||
                normalized.contains("previous response") ||
                normalized.contains("latest response") ||
                normalized.contains("last card") ||
                normalized.contains("previous card") ||
                normalized.contains("generated chat card") ->
                ReadoutTarget.LAST_CHAT_CARD
            (normalized == "summarize it" ||
                normalized == "summarise it" ||
                normalized == "summarize that" ||
                normalized == "summarise that") ->
                ReadoutTarget.LAST_ARTIFACT
            (mode == ReadoutMode.VERBATIM &&
                (normalized == "read it verbatim" ||
                    normalized == "read that verbatim" ||
                    normalized == "say it verbatim" ||
                    normalized == "speak it verbatim" ||
                    normalized == "read it word for word" ||
                    normalized == "read that word for word" ||
                    normalized == "read it exactly")) ->
                ReadoutTarget.LAST_ARTIFACT
            else -> null
        } ?: return null

        return ReadoutCommand(target = target, mode = mode)
    }

    private fun executeReadoutCommand(command: ReadoutCommand) {
        activeReadoutJob?.cancel()
        activeReadoutJob =
            lifecycleScope.launch(Dispatchers.IO) {
                // Let any in-flight speechStopJob from a preceding
                // armLocalDirectResponseHandoff() / stopAllSpeechPlayback() call
                // finish before we begin playback. Without this join, the async
                // stop races playChunk and aborts it via writeGeneration bump,
                // leaving the HUD saying "Preparing saved readout…" forever.
                Log.d(TAG, "executeReadoutCommand: starting (target=${command.target}, mode=${command.mode})")
                val stopJoinStartMs = SystemClock.elapsedRealtime()
                runCatching {
                    // 400 ms: the only thing this is waiting on is an AudioTrack
                    // stop() which takes tens of milliseconds. 1500 ms was paranoid
                    // and contributed to the multi-second delay before playback.
                    withTimeoutOrNull(400L) { speechStopJob?.join() }
                }
                Log.d(
                    TAG,
                    "executeReadoutCommand: speechStopJob join took " +
                        "${SystemClock.elapsedRealtime() - stopJoinStartMs}ms"
                )
                if (command.action == ReadoutAction.RESUME) {
                    val interrupted = interruptedResearchReadout?.takeIf { it.hasRemaining }
                    if (interrupted == null) {
                        runOnUiThread { showHudNotification("No interrupted report to resume.") }
                        return@launch
                    }

                    lastResolvedReadoutArtifact = interrupted.artifact
                    beginProtectedReadoutWindow()
                    try {
                        when (
                            playTrackedResearchReadout(
                                artifact = interrupted.artifact,
                                startSegmentIndex = interrupted.nextSegmentIndex,
                                hudText = "Resuming research report…"
                            )
                        ) {
                            // Verification-of-completion has been retired — any
                            // outcome that played audio to the end (COMPLETED or
                            // UNVERIFIED) is treated as a successful read. Only
                            // FAILED still surfaces a user-visible retry prompt.
                            ReadoutPlaybackOutcome.COMPLETED,
                            ReadoutPlaybackOutcome.UNVERIFIED -> {
                                if (interrupted.artifact.unread &&
                                    !interrupted.artifact.artifactId.isNullOrBlank()
                                ) {
                                    readableArtifactStore.markResearchReportRead(interrupted.artifact.artifactId)
                                    lastResolvedReadoutArtifact = interrupted.artifact.copy(unread = false)
                                }
                                runOnUiThread {
                                    showHudNotification("Research complete")
                                }
                            }

                            ReadoutPlaybackOutcome.FAILED -> {
                                runOnUiThread {
                                    showHudNotification("Playback stopped early. Say \"resume report\".")
                                }
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        Log.d(TAG, "Interrupted report resume cancelled")
                        throw cancelled
                    } finally {
                        endProtectedReadoutWindow()
                    }
                    return@launch
                }

                val artifact = resolveReadoutArtifact(command)
                if (artifact == null) {
                    runOnUiThread {
                        showHudNotification(
                            when (command.target) {
                                ReadoutTarget.RESEARCH_REPORT ->
                                    command.topicQuery?.let { "No saved report found for $it." }
                                        ?: "No saved research report yet."
                                ReadoutTarget.TAPCLAW_RESULT -> "No saved TapClaw result yet."
                                ReadoutTarget.LAST_CHAT_CARD -> "No recent chat card to read."
                                ReadoutTarget.LAST_ARTIFACT -> "Nothing saved to read yet."
                            }
                        )
                    }
                    return@launch
                }

                lastResolvedReadoutArtifact = artifact
                beginProtectedReadoutWindow()
                var readCompleted = false
                try {
                    when (command.mode) {
                        ReadoutMode.VERBATIM -> {
                            if (artifact.target == ReadoutTarget.RESEARCH_REPORT ||
                                artifact.target == ReadoutTarget.TAPCLAW_RESULT
                            ) {
                                when (
                                    playTrackedResearchReadout(
                                        artifact = artifact,
                                        startSegmentIndex = 0,
                                        hudText = if (artifact.target == ReadoutTarget.TAPCLAW_RESULT) {
                                            "Reading TapClaw result..."
                                        } else {
                                            "Reading research report…"
                                        }
                                    )
                                ) {
                                    // Verification-of-completion retired: both
                                    // COMPLETED and UNVERIFIED are treated as a
                                    // successful read (mark artifact read, no
                                    // user-visible "wasn't verified" banner).
                                    ReadoutPlaybackOutcome.COMPLETED,
                                    ReadoutPlaybackOutcome.UNVERIFIED -> {
                                        readCompleted = true
                                        if (artifact.unread && !artifact.artifactId.isNullOrBlank()) {
                                            readableArtifactStore.markResearchReportRead(artifact.artifactId)
                                            lastResolvedReadoutArtifact = artifact.copy(unread = false)
                                        }
                                    }

                                    ReadoutPlaybackOutcome.FAILED -> {
                                        runOnUiThread {
                                            showHudNotification(
                                                if (artifact.target == ReadoutTarget.TAPCLAW_RESULT) {
                                                    "Playback stopped early. Say \"read the TapClaw result\"."
                                                } else {
                                                    "Playback stopped early. Say \"resume report\"."
                                                }
                                            )
                                        }
                                    }
                                }
                            } else {
                                speakTextWithGeminiReadout(
                                    text = artifact.text,
                                    label = artifact.title,
                                    hudText = "Reading ${artifact.title.lowercase(Locale.US)}…"
                                )
                                readCompleted = true
                            }
                        }

                        ReadoutMode.SUMMARY -> {
                            val summary = summarizeArtifactText(artifact)
                                .ifBlank { fallbackSummaryForArtifact(artifact.text) }
                            if (summary.isBlank()) {
                                runOnUiThread {
                                    showHudNotification("Summary unavailable right now.")
                                }
                                return@launch
                            }
                            runOnUiThread {
                                viewModel.appendDirectAssistantResponse(summary)
                                viewModel.saveChatContextForNextSession()
                            }
                            speakTextWithGeminiReadout(
                                text = summary,
                                label = "summary of ${artifact.title}",
                                hudText = "Reading summary…"
                            )
                            readCompleted = true
                        }
                    }
                } catch (cancelled: CancellationException) {
                    Log.d(TAG, "Readout cancelled for ${artifact.title}")
                    throw cancelled
                } catch (e: Exception) {
                    Log.e(TAG, "Readout failed", e)
                    runOnUiThread {
                        showHudNotification(
                            if (artifact.target == ReadoutTarget.TAPCLAW_RESULT) {
                                "Could not read - TapClaw result is saved in chat"
                            } else {
                                "Could not read - report is saved in chat"
                            }
                        )
                    }
                } finally {
                    if (readCompleted && artifact.target == ReadoutTarget.TAPCLAW_RESULT) {
                        readableArtifactStore.markLatestRead(ReadableArtifactStore.ArtifactKind.TAPCLAW_RESULT)
                        lastResolvedReadoutArtifact = artifact.copy(unread = false)
                        runOnUiThread { refreshTapClawResultReadyIndicator() }
                    } else if (!readCompleted && artifact.target == ReadoutTarget.TAPCLAW_RESULT) {
                        runOnUiThread { refreshTapClawResultReadyIndicator() }
                    }
                    if (!readCompleted && artifact.target == ReadoutTarget.RESEARCH_REPORT) {
                        runOnUiThread { refreshResearchReadyIndicator() }
                    }
                    endProtectedReadoutWindow()
                }
            }
    }

    private suspend fun resolveReadoutArtifact(command: ReadoutCommand): ResolvedReadoutArtifact? {
        return when (command.target) {
            ReadoutTarget.RESEARCH_REPORT -> resolveResearchReportArtifact(command.topicQuery)
            ReadoutTarget.TAPCLAW_RESULT -> resolveTapClawArtifact()
            ReadoutTarget.LAST_CHAT_CARD -> resolveLatestChatCardArtifact()
            ReadoutTarget.LAST_ARTIFACT ->
                lastResolvedReadoutArtifact
                    ?: resolveResearchReportArtifact(command.topicQuery)
                    ?: resolveTapClawArtifact()
                    ?: resolveLatestChatCardArtifact()
        }
    }

    private suspend fun resolveResearchReportArtifact(topicQuery: String? = null): ResolvedReadoutArtifact? {
        val requestedTopic = topicQuery?.trim().takeIf { !it.isNullOrBlank() }
        if (requestedTopic != null) {
            readableArtifactStore.findResearchReportByTopic(requestedTopic)?.let { stored ->
                return stored.toResolvedReadoutArtifact(ReadoutTarget.RESEARCH_REPORT)
            }
            readableArtifactStore.loadLatest(ReadableArtifactStore.ArtifactKind.RESEARCH_REPORT)
                ?.takeIf { artifact ->
                    val haystack = listOfNotNull(artifact.topic, artifact.title)
                        .joinToString(" ")
                        .lowercase(Locale.US)
                    requestedTopic.lowercase(Locale.US)
                        .split(Regex("\\s+"))
                        .filter { it.isNotBlank() }
                        .all { haystack.contains(it) }
                }
                ?.let { stored ->
                    return stored.toResolvedReadoutArtifact(ReadoutTarget.RESEARCH_REPORT)
                }
            return null
        }
        readableArtifactStore.loadLatestUnreadResearchReport()?.let { stored ->
            return stored.toResolvedReadoutArtifact(ReadoutTarget.RESEARCH_REPORT)
        }
        readableArtifactStore.loadLatestResearchReport()?.let { stored ->
            return stored.toResolvedReadoutArtifact(ReadoutTarget.RESEARCH_REPORT)
        }
        return fetchResearchReportArtifactFromRelay()
    }

    private fun resolveTapClawArtifact(): ResolvedReadoutArtifact? {
        return readableArtifactStore
            .loadLatest(ReadableArtifactStore.ArtifactKind.TAPCLAW_RESULT)
            ?.toResolvedReadoutArtifact(ReadoutTarget.TAPCLAW_RESULT)
    }

    private fun resolveLatestChatCardArtifact(): ResolvedReadoutArtifact? {
        val card = viewModel.getAssistantCardsSnapshot()
            .asReversed()
            .firstOrNull { message ->
                val text = message.text.trim()
                text.isNotBlank() && !looksLikeResearchReadyNotice(text)
            }
            ?: return null
        val text = card.text.trim()
        return ResolvedReadoutArtifact(
            target = ReadoutTarget.LAST_CHAT_CARD,
            title = deriveArtifactTitle(text, "last chat card"),
            text = text,
            createdAtMs = card.timestampMs
        )
    }

    private suspend fun fetchResearchReportArtifactFromRelay(): ResolvedReadoutArtifact? {
        val relayBase = buildRelayBaseUrlFromPrefs() ?: return null
        val dateStr = java.time.LocalDate.now().toString()
        val reportUrl = "$relayBase/media/Gemini-Research/Gemini-ResearchTopic-$dateStr.txt"
        val response = runCatching {
            ActiveNetworkHttp.get(
                url = reportUrl,
                connectTimeoutMs = 5_000,
                readTimeoutMs = 8_000
            )
        }.getOrNull() ?: return null
        if (response.code !in 200..299) return null

        val reportText = response.body.trim()
        if (reportText.isBlank()) return null
        return saveResearchReportArtifact(
            reportText = reportText,
            topicHint = lastResearchTopic,
            titleHint = lastResearchTopic?.let { "Research report: $it" }
        )
    }

    private suspend fun summarizeArtifactText(artifact: ResolvedReadoutArtifact): String {
        val prompt = buildString {
            append("Summarize the following saved text for spoken playback on AR glasses.\n")
            append("Return only one concise paragraph with no greeting, no bullets, and no follow-up question.\n")
            append("Focus on the highest-signal points.\n\n")
            append("### SOURCE TYPE\n")
            append(artifact.title)
            append("\n\n### SOURCE TEXT\n")
            append(artifact.text)
        }
        val result = viewModel.geminiRouter.sendPrompt(
            prompt = prompt,
            model = "gemini-3-flash-preview",
            systemInstruction =
                "You create short spoken summaries for RayNeo X3 AR glasses. " +
                    "Return only a concise summary paragraph. No greeting. No meta commentary. No follow-up question."
        )
        return when (result) {
            is com.rayneo.visionclaw.core.network.GeminiRouter.GeminiResult.Success ->
                result.text.trim()
            else -> ""
        }
    }

    private fun fallbackSummaryForArtifact(text: String): String {
        return text
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(420)
            .trimEnd()
            .let { summary ->
                if (summary.length == 420) "$summary..." else summary
            }
    }

    private fun beginProtectedReadoutWindow() {
        // Research reports can be several minutes long. 180 s is the minimum
        // safe value — any shorter and the suppression expires mid-read,
        // letting Live audio through that can trigger stopAllSpeechPlayback
        // callbacks and kill the readout player. Each segment call extends
        // this window (via renewProtectedReadoutWindow) so the readout stays
        // protected for arbitrarily long reports.
        suppressGeminiOutputUntilMs = SystemClock.uptimeMillis() + 180_000L
        runOnUiThread { disarmSilenceWatchdog() }
    }

    /**
     * Called at the start of every segment to extend the suppression window.
     * Without this, a >180 s report would lose suppression mid-read.
     */
    private fun renewProtectedReadoutWindow() {
        suppressGeminiOutputUntilMs = maxOf(
            suppressGeminiOutputUntilMs,
            SystemClock.uptimeMillis() + 180_000L
        )
    }

    private fun endProtectedReadoutWindow() {
        suppressGeminiOutputUntilMs = 0L
        runOnUiThread {
            if (geminiLiveSession != null) {
                armSilenceWatchdog()
            }
        }
    }

    private fun rememberInterruptedResearchReadout(
        artifact: ResolvedReadoutArtifact,
        segments: List<String>,
        nextSegmentIndex: Int
    ) {
        interruptedResearchReadout =
            if (nextSegmentIndex in segments.indices) {
                InterruptedResearchReadout(
                    artifact = artifact,
                    segments = segments,
                    nextSegmentIndex = nextSegmentIndex
                )
            } else {
                null
            }
    }

    private suspend fun playTrackedResearchReadout(
        artifact: ResolvedReadoutArtifact,
        startSegmentIndex: Int,
        hudText: String
    ): ReadoutPlaybackOutcome {
        val readoutSpeechText = cleanReadoutTextForSpeechIfEnabled(artifact.text)
        val segments = geminiReadoutTtsClient.segmentTranscriptForPlayback(
            readoutSpeechText,
            READOUT_SEGMENT_MAX_CHARS
        )
        if (segments.isEmpty()) {
            // Segmenter produced nothing (edge case — e.g., pathological whitespace or
            // non-standard characters). Don't hang silently: read the raw artifact text
            // via the Android TTS fallback so the user still hears something.
            Log.w(
                TAG,
                "playTrackedResearchReadout: segmenter returned empty list for " +
                    "${artifact.title} (${artifact.text.length} chars) — " +
                    "falling back to Android TTS"
            )
            interruptedResearchReadout = null
            if (readoutSpeechText.isNotBlank()) {
                playLocalReadoutFallback(text = readoutSpeechText, hudText = hudText)
                return ReadoutPlaybackOutcome.UNVERIFIED
            }
            return ReadoutPlaybackOutcome.FAILED
        }

        val safeStartIndex = startSegmentIndex.coerceAtLeast(0)
        if (safeStartIndex >= segments.size) {
            interruptedResearchReadout = null
            return ReadoutPlaybackOutcome.COMPLETED
        }

        rememberInterruptedResearchReadout(artifact, segments, safeStartIndex)

        // PREFETCH PIPELINE: the Gemini TTS REST call for a ~400-char segment
        // takes a few seconds of HTTP round-trip. Running it serially
        // (synth N → play N → synth N+1 → play N+1 → ...) leaves audible silent
        // gaps between segments equal to the next synth's latency. Instead we
        // kick off synth for segment N+1 via `async` the moment we start
        // playing segment N, so by the time N finishes draining, N+1's audio
        // bytes are typically already in memory and play back with minimal
        // gap. A `coroutineScope { }` wrapper guarantees structured cancellation
        // of any outstanding prefetch if this coroutine is cancelled.
        return coroutineScope {
            var nextSegmentIndex = safeStartIndex
            var consecutiveUnverified = 0
            // Total verified segments across the whole readout. If this stays
            // 0 after the loop ends (e.g., single short segment + transient
            // Gemini TTS HTTP 500), we fall through to Android TTS so the
            // user actually hears the result. Without this, short answers
            // like "5:57 PM Pacific Time" went silent because the existing
            // 2-consecutive-unverified guard never fires for a 1-segment
            // readout.
            var verifiedSegmentCount = 0
            var prefetch: Deferred<GeminiTtsClient.TtsResult>? = null

            try {
                for (index in safeStartIndex until segments.size) {
                    coroutineContext.ensureActive()
                    val trimmed = segments[index].trim()
                    if (trimmed.isBlank()) {
                        // Skip blank segments without consuming the prefetch or
                        // touching the unverified counter.
                        nextSegmentIndex = index + 1
                        rememberInterruptedResearchReadout(artifact, segments, nextSegmentIndex)
                        continue
                    }

                    // Extend the suppress window on every segment so Live audio
                    // can't interrupt a long report partway through.
                    renewProtectedReadoutWindow()

                    // Acquire the current segment's TTS bytes: either from the
                    // prefetch kicked off during the previous iteration's playback,
                    // or synthesize inline (first iteration only — or after the
                    // previous prefetch was cancelled/reset).
                    val pending = prefetch
                    prefetch = null
                    val currentResult: GeminiTtsClient.TtsResult = if (pending != null) {
                        val awaitStartMs = SystemClock.elapsedRealtime()
                        val result = pending.await()
                        Log.d(
                            TAG,
                            "playTrackedResearchReadout: segment $index prefetch await took " +
                                "${SystemClock.elapsedRealtime() - awaitStartMs}ms " +
                                "(chars=${trimmed.length})"
                        )
                        result
                    } else {
                        val synthStartMs = SystemClock.elapsedRealtime()
                        val result = synthesizeVerbatimRouted(trimmed, artifact.title)
                        Log.d(
                            TAG,
                            "playTrackedResearchReadout: segment $index inline synth took " +
                                "${SystemClock.elapsedRealtime() - synthStartMs}ms " +
                                "(chars=${trimmed.length})"
                        )
                        result
                    }

                    // Kick off the prefetch for the NEXT non-blank segment BEFORE
                    // we start playing the current one. The synth HTTP call and
                    // the AudioTrack playback then overlap.
                    val nextIdx = nextNonBlankSegmentIndex(segments, index + 1)
                    if (nextIdx != -1) {
                        val nextTrimmed = segments[nextIdx].trim()
                        prefetch = async(Dispatchers.IO) {
                            val prefetchStartMs = SystemClock.elapsedRealtime()
                            val result = synthesizeVerbatimRouted(
                                nextTrimmed,
                                artifact.title
                            )
                            Log.d(
                                TAG,
                                "playTrackedResearchReadout: segment $nextIdx prefetch synth took " +
                                    "${SystemClock.elapsedRealtime() - prefetchStartMs}ms " +
                                    "(chars=${nextTrimmed.length})"
                            )
                            result
                        }
                    }

                    val outcome = playPreSynthesizedReadoutSegment(
                        ttsResult = currentResult,
                        label = artifact.title,
                        hudText = hudText,
                        resetPlayback = index == safeStartIndex
                    )

                    when (outcome) {
                        GeminiSegmentOutcome.VERIFIED -> {
                            nextSegmentIndex = index + 1
                            consecutiveUnverified = 0
                            verifiedSegmentCount += 1
                            rememberInterruptedResearchReadout(artifact, segments, nextSegmentIndex)
                        }

                        GeminiSegmentOutcome.UNVERIFIED -> {
                            // One flaky segment (e.g., transient TTS HTTP error or
                            // drain timeout) should NOT abort the whole report.
                            // Count consecutive failures and only bail out to
                            // Android-TTS fallback after 2 in a row — otherwise
                            // keep going with Gemini TTS. This makes the readout
                            // resilient to single-segment glitches, which matter
                            // much more now that segments are ~400 chars (several
                            // times more HTTP calls than the old 1800-char setting).
                            consecutiveUnverified += 1
                            nextSegmentIndex = index + 1
                            rememberInterruptedResearchReadout(artifact, segments, nextSegmentIndex)
                            Log.w(
                                TAG,
                                "playTrackedResearchReadout: segment $index UNVERIFIED " +
                                    "(consecutive=$consecutiveUnverified) — continuing with " +
                                    "Gemini TTS for next segment"
                            )
                            if (consecutiveUnverified >= 2) {
                                Log.w(
                                    TAG,
                                    "playTrackedResearchReadout: 2 consecutive UNVERIFIED " +
                                        "segments — falling back to Android TTS for the remainder"
                                )
                                prefetch?.cancel()
                                prefetch = null
                                playLocalReadoutFallback(
                                    text = segments.drop(nextSegmentIndex).joinToString("\n\n"),
                                    hudText = hudText
                                )
                                return@coroutineScope ReadoutPlaybackOutcome.UNVERIFIED
                            }
                        }

                        GeminiSegmentOutcome.FAILED -> {
                            prefetch?.cancel()
                            prefetch = null
                            rememberInterruptedResearchReadout(artifact, segments, nextSegmentIndex)
                            return@coroutineScope ReadoutPlaybackOutcome.FAILED
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                prefetch?.cancel()
                prefetch = null
                rememberInterruptedResearchReadout(artifact, segments, nextSegmentIndex)
                throw cancelled
            } finally {
                // Belt-and-suspenders: if we fall through to COMPLETED with a
                // dangling prefetch (shouldn't happen given the `nextIdx != -1`
                // guard, but the segment iteration could end early for any
                // future reason), cancel it.
                prefetch?.cancel()
            }

            // If the loop ended naturally but NOT a single segment played
            // (e.g., 1-segment readout where Gemini TTS returned 500, or
            // every segment hit a transient error without ever crossing the
            // 2-consecutive-unverified threshold), run Android TTS over the
            // full text now. Without this, short TapClaw answers were silent
            // whenever the cloud TTS endpoint had a hiccup.
            if (verifiedSegmentCount == 0) {
                Log.w(
                    TAG,
                    "playTrackedResearchReadout: no segments verified — " +
                        "falling back to Android TTS for the full text"
                )
                playLocalReadoutFallback(
                    text = segments.filter { it.isNotBlank() }.joinToString("\n\n"),
                    hudText = hudText
                )
                interruptedResearchReadout = null
                return@coroutineScope ReadoutPlaybackOutcome.UNVERIFIED
            }

            interruptedResearchReadout = null
            ReadoutPlaybackOutcome.COMPLETED
        }
    }

    /**
     * Return the first index >= [startIndex] whose segment is non-blank, or -1
     * if there is no such segment. Used by the prefetch pipeline to skip over
     * whitespace-only segments without spending an HTTP round-trip on them.
     */
    /**
     * Engine-aware verbatim synthesis. Reads the `readout_engine` pref
     * the companion app writes (Readout Voice tabs: gemini | fish) and
     * routes the call through the matching engine.
     *
     * Routing rules:
     *   1. engine == "fish" AND Fish has a configured key + active voice:
     *      synthesize via Fish.audio with format=wav, strip the RIFF
     *      header, return TtsResult.Success with PCM bytes the existing
     *      GeminiAudioPlayer can stream. On Fish error, fall back to
     *      Gemini rather than silently failing the entire readout.
     *   2. Otherwise: delegate to [geminiReadoutTtsClient] verbatim.
     *
     * Both branches return the same [GeminiTtsClient.TtsResult] shape
     * so every caller in the readout pipeline ([playTrackedResearchReadout],
     * [playGeminiReadoutSegment], etc.) is engine-agnostic.
     */
    private suspend fun synthesizeVerbatimRouted(
        text: String,
        label: String?
    ): GeminiTtsClient.TtsResult {
        val prefs = getSharedPreferences("visionclaw_prefs", MODE_PRIVATE)
        val engine = (prefs.getString("readout_engine", "gemini") ?: "gemini").trim()
        val speechText = cleanReadoutTextForSpeechIfEnabled(text)
        if (engine == "fish" && com.TapLink.app.media.isFishReadoutReady(this)) {
            // Wrap the entire Fish path in a try/catch so an unhandled
            // exception (e.g., the WAV-header parse crash from a streaming
            // chunk-size sentinel) cannot kill the whole readout. If Fish
            // throws or returns anything other than Success, we silently
            // fall through to Gemini TTS — the user still hears the result.
            val fishResult: GeminiTtsClient.TtsResult? = try {
                synthesizeVerbatimViaFish(speechText)
            } catch (e: Exception) {
                Log.w(TAG, "Verbatim Fish synth threw — falling back to Gemini.", e)
                null
            }
            if (fishResult is GeminiTtsClient.TtsResult.Success) return fishResult
            if (fishResult != null) {
                Log.w(TAG, "Verbatim Fish synth returned non-success ($fishResult); falling back to Gemini.")
            }
        }
        return geminiReadoutTtsClient.synthesizeVerbatim(speechText, label)
    }

    private fun cleanReadoutTextForSpeechIfEnabled(text: String): String {
        val prefs = getSharedPreferences("visionclaw_prefs", MODE_PRIVATE)
        if (!prefs.getBoolean("fish_clean_text", true)) return text
        // Belt-and-suspenders: a single bad regex used to take the whole
        // readout pipeline down (caught upstream as "TapClaw local readout
        // failed" and silently fell through to a broken Android system TTS,
        // so Fish never even got to synthesize). If anything in this chain
        // throws, return the raw text — the speech will include some
        // markdown markers but the user will still HEAR the response.
        return try {
            // First, optionally strip URLs from the readout text. Reading
            // raw URLs aloud on glasses is awful — "h-t-t-p-s colon slash
            // slash kpfa dot org slash program slash avant dash garde" — and
            // the URLs are still present in the chat card as bold tappable
            // headers, so dropping them from the spoken text loses nothing
            // for the user.
            val urlStripped = if (prefs.getBoolean("strip_urls_readout", true)) {
                val entries = com.rayneo.visionclaw.ui.panels.chat.CardUrlExtractor.extract(text)
                val withoutLineStartUrls = if (entries.isEmpty()) {
                    text
                } else {
                    com.rayneo.visionclaw.ui.panels.chat.CardUrlExtractor
                        .stripEntriesForReadout(text, entries)
                }
                // Also drop any remaining mid-prose URLs.
                withoutLineStartUrls.replace(
                    Regex("""(?:https?://|www\.)[^\s<>"']+""", RegexOption.IGNORE_CASE),
                    ""
                )
            } else {
                text
            }
            urlStripped
                .replace("\r\n", "\n")
                .replace(Regex("""(?s)```.*?```"""), " ")
                .replace(Regex("""\*\*([^*\n]+?)\*\*"""), "$1")
                .replace(Regex("""__([^_\n]+?)__"""), "$1")
                .replace(Regex("""\*([^*\n]+?)\*"""), "$1")
                .replace(Regex("""_([^_\n]+?)_"""), "$1")
                .replace(Regex("""(?m)^[\s]*[*_=\-]{3,}[\s]*$"""), ". ")
                .replace(Regex("""[*_\-=]{3,}"""), " ")
                .replace(Regex("""\s*\[[^\]\n]{1,80}\]\s*"""), " ")
                .replace(Regex("""`+"""), "")
                // Java's regex engine is stricter than Python/JS about
                // nested character classes: an unescaped `[` inside `[...]`
                // opens a sub-class, and the engine then needs both classes
                // to balance. The original pattern `[\s([{]` was treated as
                // an unclosed outer class and threw PatternSyntaxException
                // at runtime, aborting the entire readout. Escaping the
                // literal `[` (and the trailing `]` mirror in the next
                // pattern, for consistency) makes the classes single-level
                // and Java-safe.
                .replace(Regex("""(^|[\s(\[{])[*_]{1,3}(?=\S)"""), "$1")
                .replace(Regex("""(\S)[*_]{1,3}(?=$|[\s.,;:!?)}\]])"""), "$1")
                .replace(Regex("""[*_]+"""), " ")
                .replace(Regex("""[|~^§¦>#]+"""), " ")
                .replace(Regex("""\.{3,}"""), "... ")
                .replace(Regex("""[ \t]+"""), " ")
                .replace(Regex("""\s*\n\s*\n\s*"""), "\n\n")
                .trim()
        } catch (e: Exception) {
            Log.w(
                TAG,
                "cleanReadoutTextForSpeechIfEnabled: regex chain threw — " +
                    "returning raw text so the readout pipeline still runs",
                e
            )
            text
        }
    }

    /**
     * Synthesize one segment via Fish.audio and convert the WAV response
     * into the PCM-bytes-with-rate-mimeType shape [GeminiAudioPlayer]
     * already understands. The conversion is just two operations: parse
     * the RIFF header to pull the sample rate, then strip everything up
     * through the `data` chunk to leave only PCM samples.
     */
    private suspend fun synthesizeVerbatimViaFish(
        text: String
    ): GeminiTtsClient.TtsResult = withContext(Dispatchers.IO) {
        val result = fishReadoutTtsClient.synthesize(text)
        when (result) {
            is com.TapLink.app.media.FishTtsClient.SynthesisResult.Success -> {
                val parsed = stripWavHeaderToPcm(result.wavBytes)
                if (parsed == null) {
                    return@withContext GeminiTtsClient.TtsResult.Error(
                        "Fish.audio returned non-WAV audio (got ${result.mimeType})"
                    )
                }
                val (pcm, sampleRate) = parsed
                GeminiTtsClient.TtsResult.Success(
                    audioBytes = pcm,
                    mimeType = "audio/L16;rate=$sampleRate",
                    model = result.model,
                    chunkCount = 1
                )
            }
            is com.TapLink.app.media.FishTtsClient.SynthesisResult.NotConfigured ->
                GeminiTtsClient.TtsResult.ApiKeyMissing
            is com.TapLink.app.media.FishTtsClient.SynthesisResult.Error ->
                GeminiTtsClient.TtsResult.Error(result.message)
        }
    }

    /**
     * Parse a RIFF/WAV byte array, return the raw PCM payload + the
     * sample rate from the `fmt ` chunk. Returns null if the bytes
     * don't look like a standard PCM WAV — caller should treat that
     * as a synth failure.
     *
     * We deliberately walk the chunk list (rather than assuming the
     * `data` chunk starts at offset 44) because some encoders insert
     * an INFO or LIST chunk between `fmt ` and `data`. The cost is a
     * few extra bytes of arithmetic; the payoff is robustness against
     * any future Fish.audio header changes.
     */
    private fun stripWavHeaderToPcm(wav: ByteArray): Pair<ByteArray, Int>? {
        if (wav.size < 44) return null
        // RIFF + WAVE magic
        if (wav[0] != 'R'.code.toByte() || wav[1] != 'I'.code.toByte() ||
            wav[2] != 'F'.code.toByte() || wav[3] != 'F'.code.toByte()) return null
        if (wav[8] != 'W'.code.toByte() || wav[9] != 'A'.code.toByte() ||
            wav[10] != 'V'.code.toByte() || wav[11] != 'E'.code.toByte()) return null
        // Read 4 little-endian bytes as an UNSIGNED 32-bit value, returned as Long.
        // Doing this with Int produced a signed negative value when Fish.audio
        // sent a streaming WAV with the chunk-size sentinel 0xFFFFFFFF
        // ("until end of stream"), which became e.g. -212 and caused
        // `copyOfRange(44, -212)` → IllegalArgumentException at runtime.
        fun u32(off: Int): Long =
            (wav[off].toLong() and 0xFFL) or
            ((wav[off + 1].toLong() and 0xFFL) shl 8) or
            ((wav[off + 2].toLong() and 0xFFL) shl 16) or
            ((wav[off + 3].toLong() and 0xFFL) shl 24)
        // Streaming-size sentinels Fish.audio (and other servers) use to mean
        // "size unknown — read to end of buffer". 0xFFFFFFFF is the canonical
        // one; we also treat anything within 256 of it as a sentinel, since
        // some encoders subtract a few bytes for header padding.
        fun isStreamingSizeSentinel(sz: Long): Boolean =
            sz >= 0xFFFFFF00L || sz < 0L
        var sampleRate = 0
        var pos = 12
        while (pos + 8 <= wav.size) {
            val id = String(wav, pos, 4, Charsets.US_ASCII)
            val rawSz = u32(pos + 4)
            // For chunk-walking arithmetic we need a real byte count. When the
            // field is a streaming sentinel, pretend the chunk fills the rest
            // of the buffer.
            val sz: Long =
                if (isStreamingSizeSentinel(rawSz)) (wav.size - (pos + 8)).toLong().coerceAtLeast(0L)
                else rawSz.coerceAtLeast(0L)
            when (id) {
                "fmt " -> {
                    // sample rate at +12 inside the fmt chunk; always small,
                    // so safe to narrow back to Int.
                    if (pos + 12 + 4 <= wav.size) sampleRate = u32(pos + 12).toInt()
                }
                "data" -> {
                    val dataStart = pos + 8
                    val dataEnd = (dataStart.toLong() + sz)
                        .coerceAtMost(wav.size.toLong())
                        .coerceAtLeast(dataStart.toLong())
                        .toInt()
                    if (sampleRate <= 0) return null
                    val pcm = wav.copyOfRange(dataStart, dataEnd)
                    return pcm to sampleRate
                }
            }
            // Chunk sizes are rounded up to even when the field reports odd.
            val advance = sz + (sz and 1L)
            // Cap the advance so a malicious / corrupt size can't push us
            // outside the buffer and re-trigger the same crash class.
            val nextPos = (pos.toLong() + 8L + advance).coerceAtMost(wav.size.toLong()).toInt()
            if (nextPos <= pos) return null  // no progress — abort cleanly
            pos = nextPos
        }
        return null
    }

    private fun nextNonBlankSegmentIndex(segments: List<String>, startIndex: Int): Int {
        var i = startIndex
        while (i < segments.size) {
            if (segments[i].isNotBlank()) return i
            i += 1
        }
        return -1
    }

    private suspend fun playGeminiReadoutSegment(
        segmentText: String,
        label: String,
        hudText: String,
        resetPlayback: Boolean
    ): GeminiSegmentOutcome {
        val trimmed = segmentText.trim()
        if (trimmed.isBlank()) return GeminiSegmentOutcome.VERIFIED

        // Extend the suppress window on every segment so Live audio can't
        // interrupt a long report partway through.
        renewProtectedReadoutWindow()

        val synthStartMs = SystemClock.elapsedRealtime()
        val ttsResult = synthesizeVerbatimRouted(trimmed, label)
        Log.d(
            TAG,
            "playGeminiReadoutSegment: synthesizeVerbatim took " +
                "${SystemClock.elapsedRealtime() - synthStartMs}ms " +
                "(chars=${trimmed.length}, label=\"$label\")"
        )
        return playPreSynthesizedReadoutSegment(
            ttsResult = ttsResult,
            label = label,
            hudText = hudText,
            resetPlayback = resetPlayback
        )
    }

    /**
     * Play a previously-synthesized Gemini TTS result. This is the "play only"
     * half of [playGeminiReadoutSegment]; the synth half runs separately so the
     * calling loop can overlap the HTTP round-trip for segment N+1 with the
     * AudioTrack playback of segment N (see [playTrackedResearchReadout]).
     */
    private suspend fun playPreSynthesizedReadoutSegment(
        ttsResult: GeminiTtsClient.TtsResult,
        label: String,
        hudText: String,
        resetPlayback: Boolean
    ): GeminiSegmentOutcome {
        return when (ttsResult) {
            is GeminiTtsClient.TtsResult.Success -> {
                val player = geminiReadoutAudioPlayer
                if (player == null) {
                    // Readout player wasn't initialized (e.g., init error or Gemini
                    // key missing at startup). Route through Android TTS fallback
                    // instead of silently failing the entire readout.
                    Log.w(
                        TAG,
                        "playPreSynthesizedReadoutSegment: geminiReadoutAudioPlayer is null — " +
                            "falling back to Android TTS"
                    )
                    return GeminiSegmentOutcome.UNVERIFIED
                }
                // Wait for any in-flight speechStopJob (launched asynchronously by
                // armLocalDirectResponseHandoff / stopAllSpeechPlayback elsewhere)
                // to complete BEFORE we touch the readout player. Without this,
                // that async stopAndFlush can bump writeGeneration AFTER playChunk
                // captures it — aborting the write loop on its first slice check
                // so zero audio is produced and the user sees a silent hang.
                val segmentStopJoinStartMs = SystemClock.elapsedRealtime()
                runCatching {
                    // 400 ms: AudioTrack stop() only needs tens of ms. A longer
                    // window just delayed playback without added safety.
                    withTimeoutOrNull(400L) { speechStopJob?.join() }
                }
                Log.d(
                    TAG,
                    "playPreSynthesizedReadoutSegment: speechStopJob join took " +
                        "${SystemClock.elapsedRealtime() - segmentStopJoinStartMs}ms"
                )
                if (resetPlayback) {
                    // Stop sibling speech sources only — don't call
                    // stopAllSpeechPlayback(), which posts another async
                    // stopAndFlush on the readout player that would race
                    // with our playChunk below.
                    runOnUiThread {
                        showHudNotification(hudText)
                    }
                    runCatching { ttsController?.stop() }
                        .onFailure { Log.w(TAG, "resetPlayback: ttsController.stop failed", it) }
                    runCatching { geminiAudioPlayer?.stopAndFlush() }
                        .onFailure { Log.w(TAG, "resetPlayback: geminiAudioPlayer.stopAndFlush failed", it) }
                    // Flush any leftover readout from a previous turn SYNCHRONOUSLY
                    // on this coroutine thread. This bumps writeGeneration BEFORE
                    // playChunk captures it below, so no race is possible.
                    runCatching { player.stopAndFlush() }
                        .onFailure { Log.w(TAG, "resetPlayback: readout player stopAndFlush failed", it) }
                }
                player.cancelDrain()
                Log.d(
                    TAG,
                    "playPreSynthesizedReadoutSegment: writing ${ttsResult.audioBytes.size} bytes " +
                        "(mime=${ttsResult.mimeType}, label=\"$label\", volume=" +
                        "${viewModel.preferences.ttsVolume.coerceIn(0.1f, 1.0f)})"
                )
                player.playChunk(
                    mimeType = ttsResult.mimeType,
                    data = ttsResult.audioBytes,
                    muted = false,
                    volume = viewModel.preferences.ttsVolume.coerceIn(0.1f, 1.0f)
                )
                if (awaitGeminiReadoutDrain()) {
                    GeminiSegmentOutcome.VERIFIED
                } else {
                    Log.w(
                        TAG,
                        "playPreSynthesizedReadoutSegment: drain timed out for \"$label\" — " +
                            "treating as unverified so Android TTS can pick up"
                    )
                    GeminiSegmentOutcome.UNVERIFIED
                }
            }

            is GeminiTtsClient.TtsResult.ApiKeyMissing,
            is GeminiTtsClient.TtsResult.Error -> {
                Log.w(TAG, "Gemini readout unavailable — falling back to Android TTS: $ttsResult")
                GeminiSegmentOutcome.UNVERIFIED
            }
        }
    }

    private fun playLocalReadoutFallback(text: String, hudText: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return
        runOnUiThread {
            // Do NOT call stopAllSpeechPlayback here: that launches an async
            // speechStopJob on Dispatchers.IO which races our immediate
            // ttsController.speak() below. Stop only the other sources —
            // synchronously on the UI thread — so TTS starts cleanly.
            runCatching { geminiAudioPlayer?.stopAndFlush() }
                .onFailure { Log.w(TAG, "playLocalReadoutFallback: geminiAudioPlayer stop failed", it) }
            runCatching { geminiReadoutAudioPlayer?.stopAndFlush() }
                .onFailure { Log.w(TAG, "playLocalReadoutFallback: geminiReadoutAudioPlayer stop failed", it) }
            runCatching { ttsController?.stop() }
                .onFailure { Log.w(TAG, "playLocalReadoutFallback: ttsController.stop failed", it) }

            val controller = ttsController
            if (controller == null) {
                Log.e(
                    TAG,
                    "playLocalReadoutFallback: ttsController is null — cannot speak " +
                        "(textLen=${trimmed.length}). Showing HUD warning to user."
                )
                showHudNotification("Cannot read aloud — TTS engine unavailable. Report is saved in chat.")
                return@runOnUiThread
            }

            if (viewModel.preferences.ttsMuted) {
                Log.w(
                    TAG,
                    "playLocalReadoutFallback: ttsMuted=true — bypassing mute for " +
                        "user-requested readout"
                )
                showHudNotification("TTS was muted — reading via forced playback. Unmute in Settings.")
            }

            // ignoreMute = true because this is a user-requested readout;
            // force = true bypasses the ttsAutoRead preference.
            controller.speak(trimmed, force = true, ignoreMute = true)
            showHudNotification(hudText)
        }
    }

    private suspend fun awaitGeminiReadoutDrain(): Boolean {
        val player = geminiReadoutAudioPlayer ?: return false
        player.cancelDrain()
        val drainStartMs = SystemClock.elapsedRealtime()
        val drained = withTimeoutOrNull(READOUT_DRAIN_TIMEOUT_MS) {
            suspendCancellableCoroutine<Boolean> { continuation ->
                player.onDrainComplete = {
                    player.onDrainComplete = null
                    if (continuation.isActive) {
                        continuation.resume(true)
                    }
                }
                continuation.invokeOnCancellation {
                    player.onDrainComplete = null
                    player.cancelDrain()
                }
                player.notifyTurnComplete()
            }
        } ?: false
        Log.d(
            TAG,
            "awaitGeminiReadoutDrain: elapsed=" +
                "${SystemClock.elapsedRealtime() - drainStartMs}ms drained=$drained"
        )
        if (!drained) {
            player.onDrainComplete = null
            player.cancelDrain()
        }
        return drained
    }

    private suspend fun speakTextWithGeminiReadout(
        text: String,
        label: String,
        hudText: String
    ) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) {
            runOnUiThread { showHudNotification("Nothing to read.") }
            return
        }

        when (val ttsResult = synthesizeVerbatimRouted(trimmed, label)) {
            is GeminiTtsClient.TtsResult.Success -> {
                // Stop ONLY the other speech sources — not geminiReadoutAudioPlayer,
                // which we're about to write to. Calling stopAllSpeechPlayback here
                // would post an async stopAndFlush on the readout player that can
                // bump writeGeneration AFTER playChunk captures it, aborting the
                // write loop and producing a silent hang.
                runOnUiThread {
                    runCatching { ttsController?.stop() }
                        .onFailure { Log.w(TAG, "speakTextWithGeminiReadout: ttsController.stop failed", it) }
                    runCatching { geminiAudioPlayer?.stopAndFlush() }
                        .onFailure { Log.w(TAG, "speakTextWithGeminiReadout: geminiAudioPlayer stop failed", it) }
                    showHudNotification(hudText)
                }
                // Wait for any in-flight speechStopJob (from a preceding
                // armLocalDirectResponseHandoff / stopAllSpeechPlayback call)
                // to drain before we start writing to the readout player.
                // 400 ms is enough: AudioTrack stop() returns quickly.
                val speakStopJoinStartMs = SystemClock.elapsedRealtime()
                runCatching {
                    withTimeoutOrNull(400L) { speechStopJob?.join() }
                }
                Log.d(
                    TAG,
                    "speakTextWithGeminiReadout: speechStopJob join took " +
                        "${SystemClock.elapsedRealtime() - speakStopJoinStartMs}ms"
                )
                // IMPORTANT: playChunk can hold this thread for the entire
                // playback duration (several minutes for a research report).
                // We're already inside a Dispatchers.IO coroutine, so the
                // blocking write happens off the main thread.
                val player = geminiReadoutAudioPlayer
                if (player == null) {
                    Log.w(
                        TAG,
                        "speakTextWithGeminiReadout: geminiReadoutAudioPlayer is null — " +
                            "falling back to Android TTS"
                    )
                    playLocalReadoutFallback(trimmed, hudText)
                    return
                }
                player.cancelDrain()
                Log.d(
                    TAG,
                    "speakTextWithGeminiReadout: writing ${ttsResult.audioBytes.size} bytes " +
                        "(mime=${ttsResult.mimeType}, label=\"$label\")"
                )
                player.playChunk(
                    mimeType = ttsResult.mimeType,
                    data = ttsResult.audioBytes,
                    muted = false,
                    volume = viewModel.preferences.ttsVolume.coerceIn(0.1f, 1.0f)
                )
            }

            is GeminiTtsClient.TtsResult.ApiKeyMissing,
            is GeminiTtsClient.TtsResult.Error -> {
                Log.w(TAG, "Gemini readout unavailable — falling back to Android TTS: $ttsResult")
                playLocalReadoutFallback(trimmed, hudText)
            }
        }
    }

    private fun saveResearchReportArtifact(
        reportText: String,
        topicHint: String? = null,
        titleHint: String? = null
    ): ResolvedReadoutArtifact? {
        val topic = topicHint?.trim().takeUnless { it.isNullOrBlank() }
            ?: titleHint?.substringAfter(':', "")?.trim()?.takeIf { it.isNotBlank() }
            ?: lastResearchTopic?.trim()?.takeIf { it.isNotBlank() }
            ?: "research"
        val saved = readableArtifactStore.saveResearchReport(
            topic = topic,
            title = titleHint?.trim().takeUnless { it.isNullOrBlank() } ?: "Research report: $topic",
            text = reportText,
            sourceLabel = "research"
        ) ?: return null
        refreshResearchReadyIndicator()
        return saved.toResolvedReadoutArtifact(ReadoutTarget.RESEARCH_REPORT)
    }

    private fun cacheTapClawReadableArtifact(resultText: String): ResolvedReadoutArtifact? {
        val readable = extractReadableTapClawText(resultText)
        if (readable.isBlank()) return null
        val saved = readableArtifactStore.saveLatest(
            kind = ReadableArtifactStore.ArtifactKind.TAPCLAW_RESULT,
            title = deriveArtifactTitle(readable, "TapClaw result"),
            text = readable,
            sourceLabel = "tapclaw",
            unread = true
        ) ?: return null
        refreshTapClawResultReadyIndicator()
        return saved.toResolvedReadoutArtifact(ReadoutTarget.TAPCLAW_RESULT)
    }

    private fun presentTapClawResultLocally(resultText: String, autoRead: Boolean = true): Boolean {
        val artifact = cacheTapClawReadableArtifact(resultText) ?: return false
        val text = artifact.text.trim()
        if (text.isBlank()) return false

        lastResolvedReadoutArtifact = artifact
        chatFragment.setStreamActiveIndicator(false)
        viewModel.appendDirectAssistantResponse(text)
        viewModel.saveChatContextForNextSession()
        recordHudFunctionTicker("TapClaw result ready", gatewayHealthy = lastOpenClawGatewayHealthy)
        chatFragment.showHeartbeat(
            "TapClaw result ready - full response saved in chat.",
            12_000L,
            scroll = false
        )
        publishUnipanelHeartbeat(
            "TapClaw result ready - full response saved in chat.",
            persistent = false,
            scroll = false
        )
        showHudNotification(
            if (autoRead) "TapClaw result ready - reading now" else "TapClaw result ready",
            displayMs = 8_000L
        )

        if (!autoRead) return true

        activeReadoutJob?.cancel()
        activeReadoutJob =
            lifecycleScope.launch(Dispatchers.IO) {
                val stopJoinStartMs = SystemClock.elapsedRealtime()
                runCatching {
                    withTimeoutOrNull(400L) { speechStopJob?.join() }
                }
                Log.d(
                    TAG,
                    "presentTapClawResultLocally: speechStopJob join took " +
                        "${SystemClock.elapsedRealtime() - stopJoinStartMs}ms"
                )
                beginProtectedReadoutWindow()
                try {
                    when (
                        playTrackedResearchReadout(
                            artifact = artifact,
                            startSegmentIndex = 0,
                            hudText = "Reading TapClaw result..."
                        )
                    ) {
                        ReadoutPlaybackOutcome.COMPLETED,
                        ReadoutPlaybackOutcome.UNVERIFIED -> {
                            readableArtifactStore.markLatestRead(ReadableArtifactStore.ArtifactKind.TAPCLAW_RESULT)
                            lastResolvedReadoutArtifact = artifact.copy(unread = false)
                            runOnUiThread {
                                refreshTapClawResultReadyIndicator()
                                showHudNotification("TapClaw result read")
                            }
                        }

                        ReadoutPlaybackOutcome.FAILED -> {
                            runOnUiThread {
                                refreshTapClawResultReadyIndicator()
                                showHudNotification("TapClaw result saved. Say \"read the TapClaw result\".")
                            }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    Log.d(TAG, "TapClaw local readout cancelled")
                    throw cancelled
                } catch (e: Exception) {
                    Log.e(TAG, "TapClaw local readout failed", e)
                    runOnUiThread {
                        refreshTapClawResultReadyIndicator()
                        showHudNotification("TapClaw result saved in chat")
                    }
                } finally {
                    endProtectedReadoutWindow()
                }
            }
        return true
    }

    private fun ReadableArtifactStore.ReadableArtifact.toResolvedReadoutArtifact(
        target: ReadoutTarget
    ): ResolvedReadoutArtifact {
        return ResolvedReadoutArtifact(
            target = target,
            title = title.trim().ifBlank { deriveArtifactTitle(text, "saved text") },
            text = text,
            createdAtMs = createdAtMs,
            artifactId = id,
            topic = topic,
            unread = unread
        )
    }

    private fun deriveArtifactTitle(text: String, fallback: String): String {
        val firstLine = text.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
        if (firstLine.isBlank()) return fallback
        return firstLine.take(72).trimEnd().ifBlank { fallback }
    }

    private fun looksLikeResearchReadyNotice(text: String): Boolean {
        val normalized = text.trim()
        return normalized.startsWith("Research report on \"") &&
            normalized.contains("Say \"read the research report\"")
    }

    private fun presentResearchReportLocally(resultText: String, topicHint: String? = null) {
        Log.d(
            TAG,
            "presentResearchReportLocally: entered (topicHint=$topicHint, " +
                "resultTextLen=${resultText.length})"
        )
        val reportText = extractVerbatimResearchText(resultText)
        if (reportText.isBlank()) {
            Log.w(
                TAG,
                "presentResearchReportLocally: extractVerbatimResearchText returned " +
                    "blank for result of length ${resultText.length} — bailing with HUD"
            )
            showHudNotification("Research unavailable right now.")
            return
        }
        // Save to artifact store (keeps it unread so the book icon shows)
        val artifact = saveResearchReportArtifact(
            reportText = reportText,
            topicHint = topicHint,
            titleHint = topicHint?.let { "Research report: $it" }
        )
        lastResolvedReadoutArtifact = artifact
        // Show the book icon in the HUD to notify user research is ready
        refreshResearchReadyIndicator()
        // Save as chat card and persist for cross-session reference
        viewModel.appendDirectAssistantResponse(reportText)
        viewModel.saveChatContextForNextSession()
        // Notify the user
        showHudNotification("Research ready — reading now")
        // Read the report aloud using Gemini readout (separate from Live session)
        activeReadoutJob?.cancel()
        activeReadoutJob =
            lifecycleScope.launch(Dispatchers.IO) {
                Log.d(
                    TAG,
                    "activeReadoutJob: starting for \"${artifact?.title ?: "(no artifact)"}\" " +
                        "(reportLen=${reportText.length}, artifactNull=${artifact == null}, " +
                        "readoutPlayerNull=${geminiReadoutAudioPlayer == null})"
                )
                // Wait for any in-flight stop job to drain BEFORE starting playback.
                // Otherwise speechStopJob can async-flush geminiReadoutAudioPlayer
                // concurrently with our fresh playChunk, bumping writeGeneration
                // and aborting the write loop (producing silent "hang" behavior).
                // 400 ms is enough: AudioTrack stop() finishes in tens of ms.
                val presentStopJoinStartMs = SystemClock.elapsedRealtime()
                runCatching {
                    withTimeoutOrNull(400L) { speechStopJob?.join() }
                }
                Log.d(
                    TAG,
                    "activeReadoutJob: speechStopJob join took " +
                        "${SystemClock.elapsedRealtime() - presentStopJoinStartMs}ms"
                )
                beginProtectedReadoutWindow()
                try {
                    if (artifact == null) {
                        Log.w(
                            TAG,
                            "activeReadoutJob: artifact is null — using Android TTS fallback " +
                                "to read raw report text (reportLen=${reportText.length})"
                        )
                        playLocalReadoutFallback(
                            text = reportText,
                            hudText = "Reading research report (fallback)…"
                        )
                    }
                    when (
                        artifact?.let {
                            playTrackedResearchReadout(
                                artifact = it,
                                startSegmentIndex = 0,
                                hudText = "Reading research report…"
                            )
                        } ?: ReadoutPlaybackOutcome.UNVERIFIED
                    ) {
                        // Verification-of-completion retired: COMPLETED and
                        // UNVERIFIED both count as a successful read.
                        ReadoutPlaybackOutcome.COMPLETED,
                        ReadoutPlaybackOutcome.UNVERIFIED -> {
                            artifact?.artifactId?.takeIf { it.isNotBlank() }?.let { artifactId ->
                                readableArtifactStore.markResearchReportRead(artifactId)
                                artifact?.let { savedArtifact ->
                                    lastResolvedReadoutArtifact = savedArtifact.copy(unread = false)
                                }
                            }
                            runOnUiThread {
                                showHudNotification("Research complete")
                            }
                        }

                        ReadoutPlaybackOutcome.FAILED -> {
                            // Last-resort fallback: if the Gemini readout pipeline
                            // failed before speaking anything (e.g., segmenter empty,
                            // player null, or synth + drain both failed), read the
                            // full report through Android's built-in TTS so the user
                            // isn't left staring at a silent "reading now" HUD.
                            Log.w(
                                TAG,
                                "Research readout FAILED for " +
                                    "\"${artifact?.title ?: "report"}\" — invoking " +
                                    "Android TTS fallback with full report text"
                            )
                            playLocalReadoutFallback(
                                text = reportText,
                                hudText = "Reading research report (fallback)…"
                            )
                            runOnUiThread {
                                refreshResearchReadyIndicator()
                                showHudNotification("Reading via fallback voice. Say \"resume report\" to retry.")
                            }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    Log.d(TAG, "Research readout cancelled for ${artifact?.title ?: "report"}")
                    throw cancelled
                } catch (e: Exception) {
                    Log.e(TAG, "Research readout failed", e)
                    // Still try the Android TTS fallback so an exception in the
                    // Gemini readout path doesn't leave the user with silence.
                    runCatching {
                        playLocalReadoutFallback(
                            text = reportText,
                            hudText = "Reading research report (fallback)…"
                        )
                    }.onFailure { fallbackErr ->
                        Log.e(TAG, "Android TTS fallback also failed", fallbackErr)
                    }
                    runOnUiThread {
                        showHudNotification("Report saved — reading via fallback voice")
                    }
                } finally {
                    runOnUiThread { refreshResearchReadyIndicator() }
                    endProtectedReadoutWindow()
                }
            }
    }

    private fun executeResearchIntentLocally(topic: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val result = toolDispatcher.dispatch(
                "research_topic",
                JSONObject().put("topic", topic).toString()
            )
            val resultText = result.getOrElse { error ->
                Log.e(TAG, "Research dispatch failed", error)
                "Research unavailable right now."
            }
            runOnUiThread {
                presentResearchReportLocally(resultText, topicHint = topic)
            }
        }
    }

    private fun looksLikeMapInfoIntent(transcript: String): Boolean {
        val lower = transcript.trim().lowercase(Locale.US)
        if (lower.isBlank()) return false
        if (lower.contains("tapclaw")) return false
        if (looksLikeNearbyPlacesIntent(lower)) return true
        if (looksLikeRoutesIntent(lower)) return true
        return listOf(
            "address", "directions", "route", "traffic", "eta", "how far", "how long",
            "where is", "located", "near me", "nearby", "closest", "nearest", "map",
            "parking", "air quality", "aqi", "walk time", "drive time", "transit"
        ).any { lower.contains(it) }
    }

    private fun presentToolAssistLocally(toolName: String, resultText: String) {
        if (toolName == "research_topic") {
            presentResearchReportLocally(resultText)
            return
        }
        if (toolName == "tapclaw_agent") {
            armLocalDirectResponseHandoff()
            presentTapClawResultLocally(
                resultText = resultText,
                autoRead = !resultText.contains("open_taplink:", ignoreCase = true)
            )
            return
        }
        // Any learn_topic tool call means we should use the 30s timeout
        val preservePinnedLearnLmSession = toolName == "learn_topic"
        if (preservePinnedLearnLmSession) {
            learnLmToolCallActive = true
            keepLearnLmSessionAliveUntilManualClose = true
            Log.d(TAG, "presentToolAssistLocally learn_topic — 30s timeout set")
            armPinnedLearnLmResponseHandoff()
        } else {
            armLocalDirectResponseHandoff()
        }
        val speech = when (toolName) {
            "google_places" -> placesSpeechSummary(resultText)
            "google_routes" -> routesSpeechSummary(resultText)
            "location", "google_air_quality" -> locationSpeechSummary(resultText)
            "learn_topic" -> learnSpeechSummary(resultText)
            "tapclaw_agent" -> clawSpeechSummary(resultText)
            "translate_text" -> translateSpeechSummary(resultText)
            "battery_saver" -> batterySpeechSummary(resultText)
            "status_briefing" -> statusSpeechSummary(resultText)
            "quick_action" -> quickActionSpeechSummary(resultText)
            else -> resultText.lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() }.orEmpty()
        }
        viewModel.appendDirectAssistantResponse(resultText)
        recordHudFunctionTicker(
            completedToolTickerLabel(toolName, resultText),
            gatewayHealthy = lastOpenClawGatewayHealthy
        )
        if (speech.isNotBlank()) {
            ttsController?.stop()
            ttsController?.speak(speech)
            showHudNotification(speech.take(120))
        } else {
            showHudNotification(resultText.take(120))
        }
    }

    private fun learnSpeechSummary(resultText: String): String {
        return resultText.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filterNot { it.startsWith("[LearnLM model:", ignoreCase = true) }
            .take(3)
            .joinToString(". ")
            .ifBlank { "Tutor response ready." }
    }

    private fun dailyBriefSpeechSummary(resultText: String): String {
        val summary = resultText.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filterNot { it.endsWith(":") || it.startsWith("-") || it.startsWith("Ultimate daily brief") }
            .take(3)
            .joinToString(". ")
            .trim()
        return if (summary.isBlank()) {
            "Daily briefing ready."
        } else {
            "Daily briefing ready. $summary"
        }
    }

    private fun hudSafeCalendarResult(raw: String): String {
        val cleaned = raw.replace('\r', '\n').trim()
        if (cleaned.isBlank()) return "No upcoming events."
        // If the cleaned text looks like a direct calendar answer, return it
        // before running line-level noise filtering.
        val looksLikeCalendarAnswer = cleaned.contains("—") ||
            cleaned.contains(" AM") || cleaned.contains(" PM") ||
            cleaned.lowercase(Locale.US).let {
                it.startsWith("no upcoming events") ||
                it.startsWith("no events") ||
                it.startsWith("you have no") ||
                it.startsWith("your next event") ||
                it.startsWith("here are your") ||
                it.startsWith("today's events") ||
                it.startsWith("tomorrow's events")
            }
        if (looksLikeCalendarAnswer) {
            val firstLines = cleaned.lineSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .take(6)
                .toList()
            return if (firstLines.isEmpty()) "No upcoming events." else firstLines.joinToString("\n")
        }
        val lines =
            cleaned
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .map { line ->
                    when {
                        line.startsWith("•") -> line.removePrefix("•").trim()
                        line.startsWith("-") -> line.removePrefix("-").trim()
                        line.matches(Regex("^\\d+[.)].*")) ->
                            line.replaceFirst(Regex("^\\d+[.)]\\s*"), "").trim()
                        else -> line
                    }
                }
                .filterNot { looksLikeInternalHudNoise(it) }
                .take(6)
                .toList()
        if (lines.isEmpty()) return "No upcoming events."
        return lines.joinToString("\n")
    }

    private fun looksLikeInternalHudNoise(line: String): Boolean {
        val value = line.trim().lowercase(Locale.US)
        if (value.isBlank()) return false
        // Only filter genuinely internal/diagnostic output.
        // Do NOT filter lines starting with '[' generically — calendar
        // answers like "[10:00] Meeting" are valid.
        return value.startsWith("[tools]") ||
            value.startsWith("ps ") ||
            value.startsWith("http/") ||
            (value.startsWith("{") && value.contains("\"")) ||
            value.startsWith("error:") ||
            value.startsWith("tool dispatch error") ||
            (value.contains("exception") && value.contains(" at ")) ||
            value.contains("stack trace") ||
            value.contains("restart gateway") ||
            value.contains(" at com.")
    }

    private fun syncCameraToGeminiState(active: Boolean) {
        if (!active) {
            assistantSessionStartsAudioOnly = false
            stopCameraCapture()
            return
        }

        if (assistantSessionStartsAudioOnly && !cameraCaptureActive && !pendingCameraStart) {
            Log.d(TAG, "Holding fresh assistant session in audio-only mode")
            return
        }

        // Respect user preference: if default mode is audio-only and camera
        // isn't already running, skip camera start — UNLESS a manual toggle
        // (double-tap) set pendingCameraStart, in which case honour it.
        if (!viewModel.preferences.assistantDefaultCamera && !cameraCaptureActive && !pendingCameraStart) {
            return
        }

        if (!cameraPermissionGranted) {
            pendingCameraStart = true
            return
        }

        if (!isChatUiReady()) {
            pendingCameraStart = true
            return
        }

        // Ensure PiP is visible before checking TextureView readiness.
        chatFragment.setCoreEyeCaptureEnabled(true)

        if (!coreEyeSurfaceReady || !chatFragment.isCoreEyeSurfaceReady()) {
            pendingCameraStart = true
            return
        }

        if (!cameraCaptureActive) {
            pendingCameraStart = false
            startCameraCapture()
        } else {
            pendingCameraStart = false
            viewModel.setMultimodalCameraEnabled(true)
            chatFragment.setCoreEyeCaptureEnabled(true)
            chatFragment.setDarkModeCameraActive(true)
        }
    }

    private fun refreshCameraForGeminiSession() {
        if (viewModel.voiceAssistantActive.value != true) {
            showHudNotification("Activate Gemini first")
            return
        }
        if (!cameraPermissionGranted) {
            showHudNotification("Camera permission required")
            return
        }

        if (!isChatUiReady() || !coreEyeSurfaceReady || !chatFragment.isCoreEyeSurfaceReady()) {
            pendingCameraStart = true
            showHudNotification("Preparing camera…")
            return
        }

        if (cameraCaptureActive) {
            stopCameraCapture()
        }
        assistantSessionStartsAudioOnly = false
        syncCameraToGeminiState(active = true)
        showHudNotification("Camera refreshed")
    }

    private fun startCameraCapture() {
        if (viewModel.voiceAssistantActive.value != true) {
            stopCameraCapture()
            return
        }
        if (cameraCaptureActive) return
        val surfaceProvider = chatFragment.getCoreEyeSurfaceProvider()
        if (surfaceProvider == null) {
            pendingCameraStart = true
            // Ensure the camera surface is being created so pending start resolves
            chatFragment.setCoreEyeCaptureEnabled(true)
            showHudNotification("Waiting for camera surface…")
            return
        }
        assistantSessionStartsAudioOnly = false
        pendingCameraStart = false
        chatFragment.setCoreEyeCaptureEnabled(true)
        frameCapture?.start(this, surfaceProvider) { base64 ->
            latestFrame = base64
            viewModel.updateLatestLearnFrame(base64)
            maybeSendMultimodalImageFrame(base64)
            runOnUiThread { chatFragment.onCoreEyeFrameStreamed() }
        }
        cameraCaptureActive = true
        viewModel.setMultimodalCameraEnabled(true)
        chatFragment.setDarkModeCameraActive(true)
    }

    private fun stopCameraCapture() {
        uiHandler.removeCallbacks(cameraIdleTimeoutRunnable)
        if (cameraCaptureActive) {
            frameCapture?.stop()
        }
        cameraCaptureActive = false
        pendingCameraStart = false
        latestFrame = null
        viewModel.updateLatestLearnFrame(null)
        lastMultimodalFrameSentMs = 0L
        viewModel.setMultimodalCameraEnabled(false)
        if (isChatUiReady()) {
            chatFragment.setCoreEyeCaptureEnabled(false)
            chatFragment.setDarkModeCameraActive(false)
        }
    }

    private fun isGeminiListeningOrThinking(): Boolean {
        return viewModel.voiceAssistantActive.value == true ||
                geminiCaptureActive ||
                geminiLiveSession != null ||
                liveSessionReady
    }

    private fun scheduleCameraIdleTimeout() {
        if (!cameraCaptureActive) return
        uiHandler.removeCallbacks(cameraIdleTimeoutRunnable)
        uiHandler.postDelayed(cameraIdleTimeoutRunnable, CAMERA_IDLE_TIMEOUT_MS)
    }

    /** Forward trackpad touch events and update chat HUD idle timers. */
    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (ev == null) return super.dispatchTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_MOVE,
            MotionEvent.ACTION_UP -> markTrackpadActivity()
        }
        if (customKeyboardView?.visibility == View.VISIBLE) {
            if (gestureEngine.onTouchEvent(ev)) {
                return true
            }
            return super.dispatchTouchEvent(ev)
        }

        // Left-arm (cyttsp6_mt) single-tap → Gemini camera-mode shortcut.
        // We let DOWN/MOVE fall through to gestureEngine so existing
        // scroll/swipe behavior on the left pad is untouched, and only
        // intercept ACTION_UP when the gesture qualifies as a tap during
        // an active Gemini Live session.
        if (consumedByLeftArmTap(ev)) {
            return true
        }

        if (gestureEngine.onTouchEvent(ev)) {
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    /**
     * Returns true if [ev] is a left-arm (cyttsp6_mt) tap that we've
     * routed to [toggleCameraDuringGemini] — meaning the caller MUST
     * NOT also forward this event to [gestureEngine] (otherwise the
     * engine would also fire its own onShortTap from the same UP).
     *
     * Always returns false for non-left-arm devices, for DOWN/MOVE
     * events, and for left-arm UPs that don't qualify as taps (too
     * long, moved too far, or no Gemini session active). Those cases
     * fall through to the regular gestureEngine path.
     */
    private fun consumedByLeftArmTap(ev: MotionEvent): Boolean {
        if (!isLeftArmTrackpadDevice(ev)) return false

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                leftArmTapDownTimeMs = SystemClock.uptimeMillis()
                leftArmTapDownX = ev.x
                leftArmTapDownY = ev.y
                leftArmTapMovedTooFar = false
                leftArmTapTracking = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (leftArmTapTracking && !leftArmTapMovedTooFar) {
                    val dx = ev.x - leftArmTapDownX
                    val dy = ev.y - leftArmTapDownY
                    if (kotlin.math.hypot(dx.toDouble(), dy.toDouble()) >
                            LEFT_ARM_TAP_MOVE_TOLERANCE_PX) {
                        leftArmTapMovedTooFar = true
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                val wasTracking = leftArmTapTracking
                val movedTooFar = leftArmTapMovedTooFar
                leftArmTapTracking = false
                if (!wasTracking || movedTooFar) return false
                val elapsed = SystemClock.uptimeMillis() - leftArmTapDownTimeMs
                if (elapsed >= LEFT_ARM_TAP_MAX_MS) return false
                // Only fire the shortcut while a Gemini Live session is
                // active — outside of that, leave the left pad alone so
                // the existing volume / scroll behavior is unchanged.
                val geminiActive = viewModel.voiceAssistantActive.value == true
                if (!geminiActive) return false
                Log.d(
                    TAG,
                    "Left-arm tap (${elapsed}ms) during Gemini session " +
                        "→ toggleCameraDuringGemini()"
                )
                runOnUiThread { toggleCameraDuringGemini() }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                leftArmTapTracking = false
            }
        }
        return false
    }

    /**
     * Detect whether [ev] originated on the LEFT-arm volume pad
     * (cyttsp6_mt on the X3 Pro). Mirrors the device-name allow-list
     * in tapbrowser/MainActivity.kt's mask gesture filter so dim-mode
     * and Gemini-mode share the same source-of-truth for "this came
     * from the left arm."
     */
    private fun isLeftArmTrackpadDevice(ev: MotionEvent): Boolean {
        return try {
            val name = InputDevice.getDevice(ev.deviceId)?.name.orEmpty()
            name.isNotEmpty() && name.contains(LEFT_ARM_DEVICE_NAME, ignoreCase = true)
        } catch (_: Exception) {
            false
        }
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_SCROLL -> {
                val isPointerLike =
                        ev.isFromSource(InputDevice.SOURCE_CLASS_POINTER) ||
                                ev.isFromSource(InputDevice.SOURCE_MOUSE) ||
                                ev.isFromSource(InputDevice.SOURCE_TOUCHPAD)
                if (isPointerLike) {
                    markTrackpadActivity()
                    var deltaX = ev.getAxisValue(MotionEvent.AXIS_HSCROLL) * GENERIC_SCROLL_SCALE
                    var deltaY = -ev.getAxisValue(MotionEvent.AXIS_VSCROLL) * GENERIC_SCROLL_SCALE

                    if (deltaX == 0f && deltaY == 0f) {
                        deltaX = ev.getAxisValue(MotionEvent.AXIS_RELATIVE_X)
                        deltaY = ev.getAxisValue(MotionEvent.AXIS_RELATIVE_Y)
                    }

                    if (gestureEngine.onGenericScroll(deltaX, deltaY)) {
                        return true
                    }
                    if (currentTrackpadPanel()?.onTrackpadPan(deltaX, deltaY) == true) {
                        return true
                    }
                }
            }

            MotionEvent.ACTION_BUTTON_PRESS -> {
                if ((ev.buttonState and MotionEvent.BUTTON_PRIMARY) != 0) {
                    markTrackpadActivity()
                    if (dispatchSyntheticTrackpadTap(MotionEvent.ACTION_DOWN, ev)) {
                        return true
                    }
                }
            }

            MotionEvent.ACTION_BUTTON_RELEASE -> {
                markTrackpadActivity()
                if (dispatchSyntheticTrackpadTap(MotionEvent.ACTION_UP, ev)) {
                    return true
                }
            }
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    private fun dispatchSyntheticTrackpadTap(action: Int, sourceEvent: MotionEvent): Boolean {
        val synthetic =
                MotionEvent.obtain(
                        sourceEvent.downTime.takeIf { it > 0L } ?: SystemClock.uptimeMillis(),
                        SystemClock.uptimeMillis(),
                        action,
                        sourceEvent.x,
                        sourceEvent.y,
                        0
                )
        synthetic.source = sourceEvent.source
        return try {
            gestureEngine.onTouchEvent(synthetic)
        } finally {
            synthetic.recycle()
        }
    }

    /**
     * Forward hardware key events from the temple trackpad. Signature uses NON-nullable KeyEvent to
     * match AppCompatActivity.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            markTrackpadActivity()
        }
        if (gestureEngine.onKeyEvent(event)) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    // ══════════════════════════════════════════════════════════════════════
    // Speech & Audio Handlers
    // ══════════════════════════════════════════════════════════════════════

    private fun touchGeminiLiveActivity(force: Boolean = false) {
        if (geminiLiveSession == null) return
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastLiveActivityHeartbeatMs < GEMINI_LIVE_ACTIVITY_HEARTBEAT_MS) {
            return
        }
        lastLiveActivityHeartbeatMs = now
        // Reset the silence watchdog while the model is actively streaming audio/text.
        // This prevents the idle timeout from firing mid-response when the model
        // takes a brief pause between audio chunks or tool-call rounds.
        // Also cancel any pending audio-drain watcher from a previous turn.
        geminiAudioPlayer?.cancelDrain()
        disarmSilenceWatchdog()
    }

    private fun armSilenceWatchdog() {
        uiHandler.removeCallbacks(stopGeminiCaptureRunnable)
        if (geminiLiveSession == null) return
        val userLiveIdleSeconds = viewModel.preferences.timeoutLiveIdleSeconds
        val defaultTimeout = if (keepLearnLmSessionAliveUntilManualClose) LEARNLM_IDLE_TIMEOUT_MS else GEMINI_LIVE_IDLE_TIMEOUT_MS
        val resolvedTimeout = if (userLiveIdleSeconds > 0) userLiveIdleSeconds * 1000L else defaultTimeout

        // Hermes auto-follow-up: when the toggle in the Hermes companion
        // page is on AND the most recent tool call was hermes_agent /
        // tapclaw_agent, override the watchdog timeout to a short
        // "follow-up window" so the mic stays listening for a brief but
        // bounded period after Gemini's TTS finishes. Long enough for a
        // natural follow-up sentence, short enough that the session
        // doesn't sit open burning audio for two minutes after the user
        // has clearly moved on.
        val timeout = if (
            viewModel.preferences.hermesAutoFollowupEnabled &&
            lastToolCallWasHermesAgent
        ) {
            AppPreferences.HERMES_FOLLOWUP_WINDOW_MS
        } else {
            resolvedTimeout
        }
        uiHandler.postDelayed(stopGeminiCaptureRunnable, timeout)
    }

    private fun disarmSilenceWatchdog() {
        uiHandler.removeCallbacks(stopGeminiCaptureRunnable)
    }

    private fun handleGeminiLiveIdleTimeout() {
        if (geminiLiveSession == null || liveState == GeminiLiveState.IDLE) {
            hideOscilloscope()
            return
        }
        if (shouldDeferGeminiLiveIdleTimeoutForUserSpeech()) {
            uiHandler.removeCallbacks(stopGeminiCaptureRunnable)
            uiHandler.postDelayed(stopGeminiCaptureRunnable, GEMINI_FOLLOW_UP_SPEECH_RECHECK_MS)
            Log.d(TAG, "Deferring Gemini Live idle timeout while user is still speaking")
            return
        }

        // ── Last-chance learnLM detection ──
        if (!keepLearnLmSessionAliveUntilManualClose) {
            val isLearnLm = learnLmToolCallActive ||
                listOf(lastToolAssistTranscript, pendingLiveInputTranscript, lastHandledLiveInputTranscript)
                    .any { AssistantIntentParser.isExplicitLearnRequest(it.trim()) }
            if (isLearnLm) {
                keepLearnLmSessionAliveUntilManualClose = true
                Log.d(TAG, "LearnLM last-chance detection — flag set, 30s timeout will apply")
            }
        }

        val userLiveIdleSeconds = viewModel.preferences.timeoutLiveIdleSeconds
        val effectiveTimeoutSec = if (userLiveIdleSeconds > 0) userLiveIdleSeconds
            else if (keepLearnLmSessionAliveUntilManualClose) (LEARNLM_IDLE_TIMEOUT_MS / 1000).toInt()
            else (GEMINI_LIVE_IDLE_TIMEOUT_MS / 1000).toInt()
        val msg = "Session ended after ${effectiveTimeoutSec}s of silence."
        // Idle-timeout shutdown: preserve whatever the user is currently
        // reading. They may be focused on a research card or browsing the
        // chat history — yanking them back to the latest card (and out of
        // reader mode) is the wrong behavior for "we just timed out
        // because you went silent." Explicit shutdowns (errors, manual
        // exit) still reset focus by default.
        shutdownMultimodalSession(msg, preserveCardFocus = true)
    }

    private fun shutdownMultimodalSession(
        message: String? = null,
        preserveCardFocus: Boolean = false
    ) {
        keepLearnLmSessionAliveUntilManualClose = false
        learnLmToolCallActive = false
        assistantSessionStartsAudioOnly = false
        resetFollowUpSpeechTracking()
        if (suppressGeminiOutputUntilMs == Long.MAX_VALUE) {
            suppressGeminiOutputUntilMs = 0L
        }
        disarmSilenceWatchdog()
        awaitingServerTurnComplete = false
        releaseGeminiAudioCapture(cancelOnly = true)

        // Unipanel v2: tear down the microphone-typed FGS as part of
        // session shutdown. Mirrors the direct .start(this) call from
        // activateChatVoiceAssistant — bypasses LiveData so it fires
        // even while visionclaw is STOPPED (i.e. the normal background
        // case after the launcher swap). Idempotent inside the service.
        com.rayneo.visionclaw.core.session.GeminiSessionForegroundService.stop(this)

        viewModel.deactivateVoiceAssistant()
        showListeningOverlay(false)
        clearLiveSpeechPreview()
        clearListeningTranscript()
        hideOscilloscope()
        chatFragment.setStreamActiveIndicator(false)
        setHudConnectionStatus(ChatPanelFragment.ConnectionStatus.IDLE)
        stopCameraCapture()
        // Only auto-scroll to the latest card on EXPLICIT shutdowns (manual
        // exit, error). On idle-timeout shutdowns we preserve the user's
        // current reading position so they can finish reading the research
        // card / link list / whatever they were on without getting yanked
        // back to "now" and ejected from reader mode.
        if (!preserveCardFocus) {
            runOnUiThread { chatFragment.focusNewChatCard(animate = true) }
        }
        message?.trim()?.takeIf { it.isNotBlank() }?.let { showHudNotification(it) }
    }

    private fun markUserSpeechActivity() {
        lastUserSpeechActivityMs = SystemClock.uptimeMillis()
        lastGeminiOutputActivityMs = 0L
        currentGeminiOutputTurnStartedMs = 0L
        dropOutputTranscriptionUntilNextInput = false
        resetFollowUpSpeechTracking()
        awaitingServerTurnComplete = true
        disarmSilenceWatchdog()
        if (liveState == GeminiLiveState.FOLLOW_UP || liveState == GeminiLiveState.THINKING) {
            liveState = GeminiLiveState.LISTENING
            runOnUiThread { updateListeningTranscript("Listening… Speak now") }
        }
        touchGeminiLiveActivity(force = true)
    }

    private fun noteGeminiOutputActivity() {
        val now = SystemClock.uptimeMillis()
        if (currentGeminiOutputTurnStartedMs == 0L || now - lastGeminiOutputActivityMs > 1_500L) {
            currentGeminiOutputTurnStartedMs = now
        }
        lastGeminiOutputActivityMs = now
        resetFollowUpSpeechTracking()
        touchGeminiLiveActivity()
    }

    private fun resetFollowUpSpeechTracking() {
        geminiFollowUpSpeechCandidateSinceMs = 0L
        geminiFollowUpSpeechLastPeakMs = 0L
        geminiFollowUpSpeechEvidenceMs = 0L
    }

    private fun isAwaitingFollowUpUserSpeech(): Boolean {
        if (geminiLiveSession == null || liveState == GeminiLiveState.IDLE) return false
        if (awaitingServerTurnComplete) return false
        if (geminiAudioPlayer?.isActivelySpeaking() == true) return false
        return liveState == GeminiLiveState.FOLLOW_UP || liveState == GeminiLiveState.LISTENING
    }

    private fun noteFollowUpUserSpeechIfNeeded(peak: Int, silenceThreshold: Int) {
        val now = SystemClock.uptimeMillis()
        val trackingEligible = viewModel.preferences.liveDisableInterrupt && isAwaitingFollowUpUserSpeech()
        if (!trackingEligible) {
            resetFollowUpSpeechTracking()
            return
        }
        if (peak < silenceThreshold) {
            if (geminiFollowUpSpeechLastPeakMs != 0L &&
                now - geminiFollowUpSpeechLastPeakMs > GEMINI_FOLLOW_UP_SPEECH_GAP_MS) {
                geminiFollowUpSpeechCandidateSinceMs = 0L
            }
            return
        }
        if (geminiFollowUpSpeechCandidateSinceMs == 0L ||
            now - geminiFollowUpSpeechLastPeakMs > GEMINI_FOLLOW_UP_SPEECH_GAP_MS) {
            geminiFollowUpSpeechCandidateSinceMs = now
        }
        geminiFollowUpSpeechLastPeakMs = now
        if (now - geminiFollowUpSpeechCandidateSinceMs >= GEMINI_FOLLOW_UP_SPEECH_HOLD_MS) {
            geminiFollowUpSpeechEvidenceMs = now
        }
    }

    private fun shouldDeferGeminiLiveIdleTimeoutForUserSpeech(): Boolean {
        if (!viewModel.preferences.liveDisableInterrupt) return false
        if (!isAwaitingFollowUpUserSpeech()) {
            resetFollowUpSpeechTracking()
            return false
        }
        val lastEvidence = geminiFollowUpSpeechEvidenceMs
        if (lastEvidence == 0L) return false
        return SystemClock.uptimeMillis() - lastEvidence <= GEMINI_FOLLOW_UP_SPEECH_RECHECK_MS
    }

    private fun maybeSendMultimodalImageFrame(imageBase64: String) {
        if (!viewModel.canSendMultimodalFrame()) return
        if (!liveSessionReady || geminiLiveSession == null) return
        if (liveState == GeminiLiveState.IDLE) {
            return
        }
        val now = SystemClock.uptimeMillis()
        if (now - lastMultimodalFrameSentMs < MULTIMODAL_FRAME_INTERVAL_MS) return
        val sent = geminiLiveSession?.sendImageChunkBase64(imageBase64, "image/jpeg") == true
        if (sent) {
            lastMultimodalFrameSentMs = now
            Log.d(TAG, "Sent multimodal frame to Gemini Live")
        }
    }

    private fun startVoiceInputSession() {
        if (USE_NATIVE_STT) {
            val controller = speechController
            if (controller != null) {
                controller.startListening()
            } else {
                fallbackToGeminiLiveFromNativeStt("speech_controller_unavailable")
            }
            return
        }
        startGeminiAudioCapture()
    }

    private fun fallbackToGeminiLiveFromNativeStt(reason: String): Boolean {
        if (!USE_NATIVE_STT) return false
        if (nativeSttFallbackTriggered) return false
        if (geminiLiveSession != null || geminiCaptureActive) return false

        nativeSttFallbackTriggered = true
        Log.w(TAG, "Native STT fallback -> Gemini Live ($reason)")
        showHudNotification("Voice fallback: Gemini Live")
        startGeminiAudioCapture()
        return true
    }

    private fun startGeminiAudioCapture() {
        if (geminiCaptureActive || geminiLiveSession != null || geminiSessionInitJob?.isActive == true) return

        startGeminiAudioCaptureInternal()
    }

    private fun startGeminiAudioCaptureInternal() {
        if (geminiCaptureActive || geminiLiveSession != null || geminiSessionInitJob?.isActive == true) return

        viewModel.resetLiveAssistantStream()
        stopAllSpeechPlayback()
        liveSessionReady = false
        liveSessionClosingByApp = false
        liveState = GeminiLiveState.LISTENING
        awaitingServerTurnComplete = false
        lastLiveActivityHeartbeatMs = 0L
        lastMultimodalFrameSentMs = 0L
        lastUserSpeechActivityMs = 0L
        lastGeminiOutputActivityMs = 0L
        currentGeminiOutputTurnStartedMs = 0L
        resetFollowUpSpeechTracking()
        latestLiveTranscript = ""
        latestLiveOutputTranscript = ""
        pendingLiveInputTranscript = ""
        lastHandledLiveInputTranscript = ""
        sawNonSilentGeminiAudio = false
        loggedGeminiAudioProbe = false
        updateListeningTranscript("Connecting to Gemini Live…")
        setHudConnectionStatus(ChatPanelFragment.ConnectionStatus.CONNECTING)
        pushOscilloscopeLevel(0.06f, OSCILLOSCOPE_USER_COLOR, force = true)
        uiHandler.removeCallbacks(liveSetupTimeoutRunnable)
        uiHandler.removeCallbacks(stopGeminiCaptureRunnable)
        uiHandler.removeCallbacks(settledLiveInputRunnable)
        uiHandler.postDelayed(liveSetupTimeoutRunnable, GEMINI_LIVE_CONNECT_TIMEOUT_MS)

        // Capture the epoch so callbacks from THIS session can detect staleness.
        val sessionEpoch = geminiSessionEpoch

        geminiSessionInitJob =
                lifecycleScope.launch(Dispatchers.IO) {
                    if (locationPermissionGranted) {
                        val prepared = ensureLiveSessionLocationContext()
                        if (prepared != null) {
                            prefetchLiveNearbyPlaceSnapshot(prepared)
                        }
                        Log.d(
                            TAG,
                            "Prepared Gemini Live context provider=${prepared?.provider} lat=${prepared?.latitude} lon=${prepared?.longitude} acc=${prepared?.accuracyMeters}"
                        )
                    }

                    val session =
                            viewModel.geminiRouter.startLiveAudioSession(
                        listener =
                                object :
                                        com.rayneo.visionclaw.core.network.GeminiRouter.LiveSessionListener {
                                    /** True only while this session is still the active one. */
                                    private fun isCurrentSession(): Boolean =
                                            sessionEpoch == geminiSessionEpoch

                                    override fun onSessionReady() {
                                        if (!isCurrentSession()) return
                                        runOnUiThread {
                                            if (!isCurrentSession()) return@runOnUiThread
                                            uiHandler.removeCallbacks(liveSetupTimeoutRunnable)
                                            liveSessionReady = true
                                            forceDirectGeminiLive = true
                                            liveState = GeminiLiveState.LISTENING
                                            awaitingServerTurnComplete = false
                                            // Minimal UI updates first — get the mic streaming ASAP.
                                            setHudConnectionStatus(
                                                    ChatPanelFragment.ConnectionStatus.GEMINI_CONNECTED
                                            )
                                            updateListeningTranscript("Listening… Speak now")
                                            chatFragment.setStreamActiveIndicator(false)
                                            lifecycleScope.launch(Dispatchers.IO) {
                                                startGeminiAudioStreaming()
                                            }
                                            touchGeminiLiveActivity(force = true)
                                            // Deferred: bridge-reachability ping is cosmetic —
                                            // run it after the session is fully streaming.
                                            uiHandler.postDelayed({ refreshToolBridgeStatus() }, 800L)
                                        }
                                    }

                                    override fun onInputTranscription(text: String) {
                                        if (!isCurrentSession()) return
                                        val safe = text.trim()
                                        if (safe.isBlank()) return
                                        // Detect a fresh user turn: onTurnComplete flips this to
                                        // false, so if it's currently false we know the previous
                                        // assistant turn fully completed before this transcript
                                        // arrived. Without this reset, mergeLiveTranscript happily
                                        // concatenates this turn's text onto the prior turns', and
                                        // downstream guards (requestedMediaTypeForTranscript,
                                        // explicitMediaTypeConflictResponse, …) read stale media
                                        // keywords from earlier turns and reject valid tool calls.
                                        // Symptom that drove this fix: turn N said "podcasts",
                                        // turn N+2 said "play Pale Blue Dot" with a YouTube URL,
                                        // and the YouTube open was rejected as a podcast mismatch.
                                        val startingFreshTurn =
                                                !awaitingServerTurnComplete ||
                                                        dropOutputTranscriptionUntilNextInput
                                        liveState = GeminiLiveState.LISTENING
                                        awaitingServerTurnComplete = true
                                        dropOutputTranscriptionUntilNextInput = false
                                        markUserSpeechActivity()
                                        if (startingFreshTurn) {
                                            // Clear the Hermes-follow-up tag on every fresh user
                                            // turn — if the user does ask a follow-up, the
                                            // dispatcher will tag the turn again when (and if)
                                            // hermes_agent fires. Otherwise this guarantees the
                                            // short-window override doesn't bleed into unrelated
                                            // non-Hermes turns.
                                            lastToolCallWasHermesAgent = false
                                        }
                                        if (startingFreshTurn && latestLiveTranscript.isNotBlank()) {
                                            Log.d(
                                                TAG,
                                                "Resetting latestLiveTranscript on fresh turn boundary; previous='" +
                                                    latestLiveTranscript.take(120) + "'"
                                            )
                                            latestLiveTranscript = ""
                                        }
                                        // pendingGroundingChunks is the previous turn's grounded
                                        // sources. The user's NEW utterance might be either:
                                        //   (a) "send me that list" — wants the chunks rendered.
                                        //       Don't reset until the resolver has a chance to use
                                        //       them. Below, the resolveGroundedLinkListDisplay
                                        //       call clears them after dispatch.
                                        //   (b) anything else — fresh question, chunks are stale.
                                        //       The resolver gates on transcript match, so a non-
                                        //       matching new utterance leaves chunks dangling.
                                        //       Clear them here once the resolver has decided.
                                        latestLiveTranscript =
                                                mergeLiveTranscript(latestLiveTranscript, safe)
                                        pendingLiveInputTranscript = latestLiveTranscript

                                        // Early learnlm detection: set the flag NOW so that
                                        // onTurnComplete() uses the 30s timeout instead of 5s.
                                        // Without this, onTurnComplete fires before onToolCall
                                        // and arms the 5s watchdog before the flag is set.
                                        // LearnLM early detection: set the flag NOW so that
                                        // onTurnComplete() uses the 30s timeout instead of 5s.
                                        if (!keepLearnLmSessionAliveUntilManualClose &&
                                            AssistantIntentParser.isExplicitLearnRequest(latestLiveTranscript)) {
                                            keepLearnLmSessionAliveUntilManualClose = true
                                            Log.d(TAG, "LearnLM prefix detected early — flag set, 30s timeout will apply")
                                        }

                                        // Immediately intercept "status" / status-brief phrases before Gemini
                                        // can choose a similarly-named battery action. This
                                        // keeps the Live session open while forcing the
                                        // status_briefing flow to be the first-class path.
                                        if (AssistantIntentParser.isStatusBriefingRequest(latestLiveTranscript)) {
                                            uiHandler.removeCallbacks(settledLiveInputRunnable)
                                            lastHandledLiveInputTranscript = latestLiveTranscript
                                            lastToolAssistTranscript = latestLiveTranscript
                                            // Phase 2 Step 2c.3.1: surface the finalized user
                                            // turn for the unipanel mini-card stack.
                                            viewModel.appendUserUtterance(latestLiveTranscript)
                                            runOnUiThread {
                                                showHudNotification("Preparing status…")
                                                chatFragment.setStreamActiveIndicator(true)
                                            }
                                            lifecycleScope.launch(Dispatchers.IO) {
                                                try {
                                                    val result = toolDispatcher.dispatch("status_briefing", "{}")
                                                    val resultText = result.getOrElse { error ->
                                                        Log.e(TAG, "status_briefing early intercept failed", error)
                                                        "Status unavailable right now."
                                                    }
                                                    val contextPrompt =
                                                        "[TOOL RESULT — status_briefing]\n$resultText\n" +
                                                            "[Read this status briefing naturally and keep the conversation open for follow-up questions.]"
                                                    val sent = geminiLiveSession?.sendClientText(contextPrompt) == true
                                                    Log.d(TAG, "Status early intercept injected=$sent")
                                                    runOnUiThread {
                                                        chatFragment.setStreamActiveIndicator(false)
                                                        if (sent) {
                                                            showHudNotification("Status ready")
                                                        } else {
                                                            presentToolAssistLocally("status_briefing", resultText)
                                                        }
                                                    }
                                                } catch (e: Exception) {
                                                    Log.e(TAG, "Status early intercept error", e)
                                                    runOnUiThread {
                                                        chatFragment.setStreamActiveIndicator(false)
                                                        showHudNotification("Status unavailable")
                                                    }
                                                }
                                            }
                                            return
                                        }

                                        // Immediately intercept LearnLM prefix before Gemini
                                        // can respond — same pattern as the YouTube intercept
                                        // below. Without this, Gemini hears the audio over the
                                        // WebSocket and starts answering before the 900ms settle
                                        // timer fires, so the user gets a Gemini response instead
                                        // of a LearnLM tutoring response.
                                        if (AssistantIntentParser.isExplicitLearnRequest(latestLiveTranscript)) {
                                            uiHandler.removeCallbacks(settledLiveInputRunnable)
                                            lastHandledLiveInputTranscript = latestLiveTranscript
                                            lastToolAssistTranscript = latestLiveTranscript
                                            // Phase 2 Step 2c.3.1: surface the finalized user
                                            // turn for the unipanel mini-card stack.
                                            viewModel.appendUserUtterance(latestLiveTranscript)
                                            val learnPrompt = AssistantIntentParser.extractExplicitLearnPrompt(latestLiveTranscript)
                                                ?.ifBlank { "continue on the previous problem" }
                                                ?: latestLiveTranscript
                                            val topicHint = learnPrompt
                                                .replace(Regex("(?i)^\\s*(?:teach me|help me with|explain|learn)\\s+"), "")
                                                .trim()
                                                .takeIf { it.isNotBlank() } ?: "that topic"
                                            runOnUiThread {
                                                armPinnedLearnLmResponseHandoff()
                                                showHudNotification("Teaching $topicHint")
                                            }
                                            lifecycleScope.launch(Dispatchers.IO) {
                                                try {
                                                    val result = viewModel.learnLmRouter.teach(learnPrompt)
                                                    if (result is LearnLmRouter.LearnResult.Success) {
                                                        val rendered = LearnLmRouter.formatForDisplay(result)
                                                        viewModel.appendDirectAssistantResponse(rendered)
                                                        // Inject into Live session so Gemini speaks it
                                                        val contextPrompt = "[TOOL RESULT — learn_topic]\n${result.text}\n" +
                                                            "[Present this as a concise tutoring response. The user asked: \"$learnPrompt\"]"
                                                        val sent = geminiLiveSession?.sendClientText(contextPrompt) == true
                                                        Log.d(TAG, "LearnLM early intercept injected=$sent topic=${result.topic}")
                                                        if (!sent) {
                                                            // Live session unavailable — use TTS fallback
                                                            runOnUiThread { ttsController?.speak(result.text.take(500)) }
                                                        }
                                                    } else {
                                                        val errorMsg = when (result) {
                                                            is LearnLmRouter.LearnResult.Error -> result.message
                                                            is LearnLmRouter.LearnResult.ApiKeyMissing -> "LearnLM API key missing"
                                                            else -> "Tutor unavailable"
                                                        }
                                                        viewModel.appendDirectAssistantResponse("LearnLM: $errorMsg")
                                                        runOnUiThread { showHudNotification(errorMsg) }
                                                    }
                                                } catch (e: Exception) {
                                                    Log.e(TAG, "LearnLM early intercept error", e)
                                                    runOnUiThread { showHudNotification("LearnLM unavailable") }
                                                }
                                            }
                                            return
                                        }

                                        // Immediately check YouTube patterns before Gemini can respond.
                                        // These patterns require complete keywords ("subscriptions",
                                        // "history") so partial transcripts won't false-match.
                                        val directIntentTranscript = latestLiveTranscript
                                            .trim()
                                            .takeIf { it.isNotBlank() }
                                            ?: safe
                                        // Grounding auto-dispatch is intentionally disabled in
                                        // baseline mode. We measured Gemini Live's grounding
                                        // capping at ~1 chunk per turn, which produced a list
                                        // too thin to feel useful. URL-list discovery now
                                        // routes through tapclaw_agent (quick) or
                                        // research_topic mode='links' (deep) — the user picks.
                                        // The grounding listener remains active for diagnostic
                                        // logging only; pendingGroundingChunks accumulates but
                                        // is not consumed.
                                        val youtubeListReq =
                                            resolveRecentYouTubeSuggestionListDisplayRequest(directIntentTranscript)
                                        if (youtubeListReq != null) {
                                            uiHandler.removeCallbacks(settledLiveInputRunnable)
                                            lastHandledLiveInputTranscript = directIntentTranscript
                                            handleYouTubeSuggestionListDisplayRequest(youtubeListReq)
                                            return
                                        }
                                        val youtubeReq = parseYouTubePlaybackIntent(directIntentTranscript)
                                        if (youtubeReq != null) {
                                            uiHandler.removeCallbacks(settledLiveInputRunnable)
                                            lastHandledLiveInputTranscript = directIntentTranscript
                                            runOnUiThread {
                                                armLocalDirectResponseHandoff()
                                                showHudNotification(youtubeReq.hudLabel)
                                                viewModel.appendDirectAssistantResponse(youtubeReq.responseText)
                                                ttsController?.stop()
                                                ttsController?.speak(youtubeReq.hudLabel)
                                                launchTapBrowser(
                                                    initialUrl = youtubeReq.searchUrl,
                                                    youtubeAutoplayQuery = youtubeReq.query,
                                                    youtubeAutoplayMode = youtubeReq.mode,
                                                    youtubeAutoplayQueue = youtubeReq.queue
                                                )
                                            }
                                            return
                                        }

                                        val tapRadioReq = resolveRecentTapRadioFollowUpPlaybackRequest(directIntentTranscript)
                                        if (tapRadioReq != null) {
                                            uiHandler.removeCallbacks(settledLiveInputRunnable)
                                            lastHandledLiveInputTranscript = directIntentTranscript
                                            handleTapRadioPlaybackRequest(tapRadioReq)
                                            return
                                        }

                                        uiHandler.removeCallbacks(settledLiveInputRunnable)
                                        uiHandler.postDelayed(
                                                settledLiveInputRunnable,
                                                LIVE_INPUT_SETTLE_MS
                                        )
                                        runOnUiThread {
                                            updateListeningTranscript(safe)
                                        }
                                    }

                                    override fun onOutputTranscription(text: String) {
                                        if (!isCurrentSession() || isGeminiOutputSuppressed()) return
                                        val safe = text.trim()
                                        if (safe.isBlank()) return
                                        if (maybeRecoverFromGeminiFallback(safe)) return
                                        if (dropOutputTranscriptionUntilNextInput) {
                                            Log.d(
                                                TAG,
                                                "Dropping late Gemini outputTranscription after turnComplete: '${safe.take(120)}'"
                                            )
                                            return
                                        }

                                        liveState = GeminiLiveState.THINKING
                                        awaitingServerTurnComplete = true
                                        noteGeminiOutputActivity()
                                        latestLiveOutputTranscript =
                                                mergeLiveTranscript(latestLiveOutputTranscript, safe)
                                        maybeCacheTapRadioOfferFromGeminiOutput(safe)
                                        runOnUiThread {
                                            chatFragment.setStreamActiveIndicator(true)
                                            updateListeningTranscript(safe)
                                            viewModel.appendLiveAssistantStreamChunk(
                                                    latestLiveOutputTranscript
                                            )
                                            if (Patterns.WEB_URL.matcher(latestLiveOutputTranscript).find()) {
                                                chatFragment.autoFocusLatestAssistantUrl()
                                            }
                                        }
                                    }
                                    override fun onModelText(text: String) {
                                        if (!isCurrentSession() || isGeminiOutputSuppressed()) return
                                        if (maybeRecoverFromGeminiFallback(text)) return
                                        if (dropOutputTranscriptionUntilNextInput) {
                                            Log.d(
                                                TAG,
                                                "Dropping late Gemini modelText after turnComplete: '${text.take(120)}'"
                                            )
                                            return
                                        }

                                        liveState = GeminiLiveState.THINKING
                                        awaitingServerTurnComplete = true
                                        noteGeminiOutputActivity()
                                        // Verbatim dialog mode: ignore free-form model text/metadata payloads.
                                        // Chat persistence is driven only by outputTranscription.
                                    }
                                    override fun onModelAudio(mimeType: String, data: ByteArray) {
                                        if (!isCurrentSession() || isGeminiOutputSuppressed()) return
                                        liveState = GeminiLiveState.THINKING
                                        awaitingServerTurnComplete = true
                                        noteGeminiOutputActivity()
                                        val outputPeak = calculatePcm16Peak(data, data.size)
                                        val normalised = (outputPeak / 32767f).coerceIn(0f, 1f)
                                        pushOscilloscopeLevel(normalised, OSCILLOSCOPE_MODEL_COLOR)
                                        runOnUiThread { chatFragment.setStreamActiveIndicator(true) }
                                        val prefs = viewModel.preferences
                                        geminiAudioPlayer?.playChunk(
                                                mimeType = mimeType,
                                                data = data,
                                                muted = prefs.ttsMuted,
                                                volume = prefs.ttsVolume
                                        )
                                    }

                                    override fun onToolCall(callId: String, name: String, args: String) {
                                        if (!isCurrentSession()) {
                                            Log.w(
                                                TAG,
                                                "Dropping Live tool call from stale session name=$name callId=$callId epoch=$sessionEpoch current=$geminiSessionEpoch"
                                            )
                                            return
                                        }
                                        if (isGeminiOutputSuppressed()) {
                                            Log.w(
                                                TAG,
                                                "Dropping Live tool call while local handoff suppresses Gemini output name=$name args=${args.take(180)}"
                                            )
                                            return
                                        }
                                        awaitingServerTurnComplete = true
                                        touchGeminiLiveActivity()
                                        // Cancel the ToolAssist settled-input timer so we don't
                                        // inject a duplicate client-text response alongside the
                                        // Gemini tool-call response.  Mark transcript as handled
                                        // so the runnable is a no-op even if it fires anyway.
                                        uiHandler.removeCallbacks(settledLiveInputRunnable)
                                        val transcript = pendingLiveInputTranscript.trim()
                                        if (transcript.isNotBlank()) {
                                            lastHandledLiveInputTranscript = transcript
                                        }
                                        dispatchLiveToolCall(callId = callId, name = name, args = args)
                                    }

                                    override fun onGroundingMetadata(
                                        chunks: List<GeminiRouter.GroundingChunk>
                                    ) {
                                        if (!isCurrentSession()) return
                                        if (chunks.isEmpty()) return
                                        // Accumulate this turn's grounding chunks. The same turn
                                        // may emit chunks across multiple frames, so we union
                                        // (deduped by URI) rather than replace.
                                        val accumulated = pendingGroundingChunks?.toMutableList()
                                            ?: mutableListOf()
                                        val existingUris = accumulated.map { it.uri }.toHashSet()
                                        for (c in chunks) {
                                            if (existingUris.add(c.uri)) accumulated.add(c)
                                        }
                                        pendingGroundingChunks = accumulated
                                        Log.d(
                                            TAG,
                                            "Grounding chunks accumulated: " +
                                                "added=${chunks.size} total=${accumulated.size}"
                                        )
                                    }

                                    override fun onTurnComplete(finishReason: String?) {
                                        if (!isCurrentSession()) return
                                        viewModel.commitLiveAssistantStreamIfNeeded()
                                        // Phase 2 Step 2c.3.3: drop the accumulated output
                                        // transcript at turn boundary. Without this, the
                                        // next turn's first chunk gets mergeLiveTranscript-
                                        // concatenated with the PRIOR turn's full reply,
                                        // and upsertLiveAssistantWorkingCard sees an empty
                                        // liveAssistantWorkingTurn (correctly reset by the
                                        // commit above) → it appends a NEW assistant card
                                        // whose first ~all-of-prior-answer chars duplicate
                                        // the just-committed card. Logs showed _messages
                                        // jumping size=2 → size=3 with the duplicate before
                                        // appendUserUtterance even ran for the next turn.
                                        latestLiveOutputTranscript = ""
                                        dropOutputTranscriptionUntilNextInput = true
                                        awaitingServerTurnComplete = false
                                        liveState = GeminiLiveState.FOLLOW_UP
                                        uiHandler.removeCallbacks(settledLiveInputRunnable)

                                        // Safety net: check learnlm prefix on the transcript
                                        // in case onInputTranscription had only partial text
                                        if (!keepLearnLmSessionAliveUntilManualClose) {
                                            val candidates = listOf(
                                                pendingLiveInputTranscript,
                                                lastHandledLiveInputTranscript,
                                                lastToolAssistTranscript
                                            )
                                            if (candidates.any { AssistantIntentParser.isExplicitLearnRequest(it.trim()) }) {
                                                keepLearnLmSessionAliveUntilManualClose = true
                                                Log.d(TAG, "LearnLM prefix detected in onTurnComplete — flag set, 30s timeout")
                                            }
                                        }

                                        val shouldStartCleanupTimer =
                                                finishReason.isNullOrBlank() ||
                                                        finishReason.equals("STOP", ignoreCase = true)
                                        if (shouldStartCleanupTimer || keepLearnLmSessionAliveUntilManualClose) {
                                            // Don't arm the watchdog immediately — the AudioTrack
                                            // may still have buffered audio playing.  Notify the
                                            // audio player that the turn is done; it will invoke
                                            // onDrainComplete once all audio has been heard, which
                                            // is where we arm the watchdog.
                                            geminiAudioPlayer?.notifyTurnComplete()
                                                ?: armSilenceWatchdog() // fallback if no player
                                        } else {
                                            disarmSilenceWatchdog()
                                        }
                                        runOnUiThread {
                                            chatFragment.setStreamActiveIndicator(false)
                                            updateListeningTranscript("Listening for follow-up…")
                                            chatFragment.autoFocusLatestAssistantUrl()
                                        }
                                        Log.d(TAG, "Gemini turn complete finishReason=${finishReason ?: "unknown"}")
                                    }

                                    override fun onError(message: String) {
                                        if (!isCurrentSession()) return
                                        uiHandler.removeCallbacks(liveSetupTimeoutRunnable)
                                        awaitingServerTurnComplete = false

                                        val gatewayProtocolMismatch =
                                                !forceDirectGeminiLive &&
                                                        (message.contains("invalid request frame", ignoreCase = true) ||
                                                                (message.contains("unexpected property", ignoreCase = true) &&
                                                                        message.contains("setup", ignoreCase = true)) ||
                                                                (message.contains("required property", ignoreCase = true) &&
                                                                        message.contains("method", ignoreCase = true)))
                                        if (gatewayProtocolMismatch) {
                                            forceDirectGeminiLive = true
                                            runOnUiThread {
                                                setHudConnectionStatus(
                                                        ChatPanelFragment.ConnectionStatus.CONNECTING
                                                )
                                                showHudNotification(
                                                        "Gateway RPC mode detected. Retrying direct Gemini…"
                                                )
                                                releaseGeminiAudioCapture(cancelOnly = true)
                                                uiHandler.postDelayed(
                                                        { startGeminiAudioCapture() },
                                                        150L
                                                )
                                            }
                                            return
                                        }

                                        setHudConnectionStatus(ChatPanelFragment.ConnectionStatus.ERROR)
                                        handleGeminiVoiceFailure(message)
                                    }

                                    override fun onClosed(code: Int, reason: String) {
                                        Log.d(
                                                TAG,
                                                "Gemini Live session closed code=$code reason=$reason epoch=$sessionEpoch current=$geminiSessionEpoch"
                                        )
                                        if (!isCurrentSession()) {
                                            // Stale callback from a previous session — ignore.
                                            Log.d(TAG, "Ignoring onClosed from stale session (epoch $sessionEpoch)")
                                            return
                                        }
                                        uiHandler.removeCallbacks(liveSetupTimeoutRunnable)
                                        uiHandler.removeCallbacks(settledLiveInputRunnable)
                                        val closedByApp = liveSessionClosingByApp
                                        val wasLiveSessionReady = liveSessionReady
                                        liveSessionClosingByApp = false
                                        liveSessionReady = false
                                        liveState = GeminiLiveState.IDLE
                                        awaitingServerTurnComplete = false
                                        if (closedByApp) {
                                            runOnUiThread {
                                                chatFragment.setStreamActiveIndicator(false)
                                                hideOscilloscope()
                                                setHudConnectionStatus(ChatPanelFragment.ConnectionStatus.IDLE)
                                            }
                                            return
                                        }
                                        val invalidGatewayFrame =
                                                code == 1008 &&
                                                        reason.contains(
                                                                "invalid request frame",
                                                                ignoreCase = true
                                                        ) &&
                                                        !forceDirectGeminiLive
                                        if (invalidGatewayFrame) {
                                            forceDirectGeminiLive = true
                                            runOnUiThread {
                                                setHudConnectionStatus(
                                                        ChatPanelFragment.ConnectionStatus.CONNECTING
                                                )
                                                showHudNotification(
                                                        "Gateway live rejected. Retrying direct Gemini…"
                                                )
                                                releaseGeminiAudioCapture(cancelOnly = true)
                                                uiHandler.postDelayed(
                                                        { startGeminiAudioCapture() },
                                                        150L
                                                )
                                            }
                                            return
                                        }
                                        if (!wasLiveSessionReady) {
                                            val suffix =
                                                    reason.takeIf { it.isNotBlank() }?.let {
                                                        ": $it"
                                                    }
                                                            ?: ""
                                            handleGeminiVoiceFailure(
                                                    "Gemini Live closed before ready (code $code)$suffix"
                                            )
                                            return
                                        }
                                        runOnUiThread {
                                            chatFragment.setStreamActiveIndicator(false)
                                            shutdownMultimodalSession("Gemini Live session closed.")
                                        }
                                    }
                                },
                        forceDirect = true
                )
                    geminiSessionInitJob = null

                    if (session == null) {
                        uiHandler.removeCallbacks(liveSetupTimeoutRunnable)
                        // startLiveAudioSession already reports the concrete error via listener.onError.
                        return@launch
                    }
                    geminiLiveSession = session
                    touchGeminiLiveActivity(force = true)
                }
    }

    private fun handleGeminiVoiceFailure(message: String) {
        keepLearnLmSessionAliveUntilManualClose = false
        learnLmToolCallActive = false
        assistantSessionStartsAudioOnly = false
        if (suppressGeminiOutputUntilMs == Long.MAX_VALUE) {
            suppressGeminiOutputUntilMs = 0L
        }
        val display = message.trim().ifBlank { "Voice request failed. Please try again." }
        Log.w(TAG, "Gemini voice failure: $display")
        runOnUiThread {
            awaitingServerTurnComplete = false
            viewModel.resetLiveAssistantStream()
            releaseGeminiAudioCapture(cancelOnly = true)
            viewModel.deactivateVoiceAssistant()
            showListeningOverlay(false)
            clearLiveSpeechPreview()
            clearListeningTranscript()
            chatFragment.setStreamActiveIndicator(false)
            setHudConnectionStatus(ChatPanelFragment.ConnectionStatus.ERROR)
            playVoiceTimeoutBeep()
            showHudNotification(display)
        }
    }

    private fun releaseGeminiAudioEffects() {
        runCatching { geminiAcousticEchoCanceler?.enabled = false }
        runCatching { geminiAcousticEchoCanceler?.release() }
        geminiAcousticEchoCanceler = null
        runCatching { geminiNoiseSuppressor?.enabled = false }
        runCatching { geminiNoiseSuppressor?.release() }
        geminiNoiseSuppressor = null
        runCatching { geminiAutomaticGainControl?.enabled = false }
        runCatching { geminiAutomaticGainControl?.release() }
        geminiAutomaticGainControl = null
    }

    private fun configureGeminiAudioEffects(audioSessionId: Int) {
        releaseGeminiAudioEffects()
        if (audioSessionId <= 0) return
        if (AcousticEchoCanceler.isAvailable()) {
            geminiAcousticEchoCanceler = AcousticEchoCanceler.create(audioSessionId)?.also {
                runCatching { it.enabled = true }
                Log.d(TAG, "Gemini AEC enabled session=$audioSessionId")
            }
        } else {
            Log.d(TAG, "Gemini AEC unavailable on this device")
        }
        if (NoiseSuppressor.isAvailable()) {
            geminiNoiseSuppressor = NoiseSuppressor.create(audioSessionId)?.also {
                runCatching { it.enabled = true }
                Log.d(TAG, "Gemini noise suppressor enabled session=$audioSessionId")
            }
        }
        if (AutomaticGainControl.isAvailable()) {
            geminiAutomaticGainControl = AutomaticGainControl.create(audioSessionId)?.also {
                runCatching { it.enabled = true }
                Log.d(TAG, "Gemini AGC enabled session=$audioSessionId")
            }
        }
    }

    private fun createGeminiAudioRecord(bufferSize: Int): AudioRecord? {
        val sources = intArrayOf(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.MIC
        )
        for (source in sources) {
            val recorder = runCatching {
                AudioRecord(
                    source,
                    GEMINI_AUDIO_SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )
            }.getOrNull() ?: continue
            if (recorder.state == AudioRecord.STATE_INITIALIZED) {
                Log.d(TAG, "Gemini microphone source=${if (source == MediaRecorder.AudioSource.VOICE_COMMUNICATION) "VOICE_COMMUNICATION" else "MIC"}")
                return recorder
            }
            runCatching { recorder.release() }
        }
        return null
    }

    private fun maybeHandleGeminiBargeIn(micPeak: Int) {
        if (viewModel.preferences.liveDisableInterrupt) return  // Never-interrupt mode
        val session = geminiLiveSession ?: return
        if (!liveSessionReady) return
        if (isGeminiOutputSuppressed()) return
        val player = geminiAudioPlayer ?: return
        if (!player.isActivelySpeaking()) {
            geminiBargeInCandidateSinceMs = 0L
            return
        }
        val now = SystemClock.uptimeMillis()
        val outputStartedAt = currentGeminiOutputTurnStartedMs
        if (outputStartedAt == 0L || now - outputStartedAt < GEMINI_BARGE_IN_GRACE_AFTER_OUTPUT_MS) {
            geminiBargeInCandidateSinceMs = 0L
            return
        }
        if (now - geminiBargeInLastTriggerMs < GEMINI_BARGE_IN_COOLDOWN_MS) {
            return
        }
        val micLevel = (micPeak / 32767f).coerceIn(0f, 1f)
        val outputLevel = player.currentOutputLevel().coerceIn(0f, 1f)
        val sensitivity = viewModel.preferences.liveBargeInSensitivity.coerceIn(0.6f, 2.5f)
        val requiredLevel = max(
            GEMINI_BARGE_IN_MIN_MIC_LEVEL * sensitivity,
            max(
                outputLevel + (GEMINI_BARGE_IN_OUTPUT_MARGIN * sensitivity),
                outputLevel * (1f + ((GEMINI_BARGE_IN_OUTPUT_RATIO - 1f) * sensitivity))
            )
        ).coerceAtMost(0.98f)
        if (micLevel < requiredLevel) {
            geminiBargeInCandidateSinceMs = 0L
            return
        }
        if (geminiBargeInCandidateSinceMs == 0L) {
            geminiBargeInCandidateSinceMs = now
            return
        }
        val holdMs = (GEMINI_BARGE_IN_HOLD_MS * sensitivity).toLong().coerceIn(450L, 1_200L)
        if (now - geminiBargeInCandidateSinceMs < holdMs) {
            return
        }
        geminiBargeInCandidateSinceMs = 0L
        geminiBargeInLastTriggerMs = now
        suppressGeminiOutputUntilMs = maxOf(
            suppressGeminiOutputUntilMs,
            now + GEMINI_BARGE_IN_SUPPRESS_MS
        )
        geminiReadoutAudioPlayer?.stopAndFlush()
        ttsController?.stop()
        player.stopAndFlush()
        awaitingServerTurnComplete = true
        liveState = GeminiLiveState.LISTENING
        touchGeminiLiveActivity(force = true)
        Log.d(TAG, "Gemini barge-in triggered micLevel=$micLevel outputLevel=$outputLevel requiredLevel=$requiredLevel sensitivity=$sensitivity")
        runOnUiThread {
            chatFragment.setStreamActiveIndicator(false)
            updateListeningTranscript("Listening… Speak now")
        }
    }

    private fun startGeminiAudioStreaming() {
        if (geminiCaptureActive) return
        if (!liveSessionReady || geminiLiveSession == null) return

        enableRayNeoVoiceAssistantMicRoute()

        val minBuffer =
                AudioRecord.getMinBufferSize(
                        GEMINI_AUDIO_SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT
                )
        if (minBuffer <= 0) {
            handleGeminiVoiceFailure("Microphone buffer could not be created.")
            return
        }

        val bufferSize = maxOf(minBuffer * 2, 4096)
        val recorder = createGeminiAudioRecord(bufferSize)
        if (recorder == null) {
            handleGeminiVoiceFailure("Unable to start microphone capture.")
            return
        }

        geminiAudioRecord = recorder
        geminiCaptureActive = true
        geminiBargeInCandidateSinceMs = 0L
        configureGeminiAudioEffects(recorder.audioSessionId)
        recorder.startRecording()

        geminiAudioThread =
                Thread {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
                    val chunk = ByteArray(2048)
                    var chunkCount = 0
                    while (geminiCaptureActive) {
                        val read = recorder.read(chunk, 0, chunk.size)
                        if (read > 0) {
                            if (!loggedGeminiAudioProbe) {
                                loggedGeminiAudioProbe = true
                                val preview =
                                        chunk.take(read.coerceAtMost(10)).joinToString(" ") { b ->
                                            "%02x".format(b.toInt() and 0xFF)
                                        }
                                Log.d(TAG, "Gemini mic probe bytes: $preview")
                            }
                            val peak = calculatePcm16Peak(chunk, read)
                            val prefs = viewModel.preferences
                            val silenceThreshold = prefs.liveSilenceThreshold
                            noteFollowUpUserSpeechIfNeeded(peak, silenceThreshold)
                            if (peak >= silenceThreshold) {
                                sawNonSilentGeminiAudio = true
                                if (prefs.liveDisableInterrupt) {
                                    // "Never interrupt" should still allow the post-response
                                    // idle timer to expire. We only defer timeout once the mic
                                    // shows sustained follow-up speech; ambient noise alone does
                                    // not reset the watchdog or reopen a full timeout window.
                                    geminiBargeInCandidateSinceMs = 0L
                                } else {
                                    awaitingServerTurnComplete = true
                                    touchGeminiLiveActivity(force = true)
                                    maybeHandleGeminiBargeIn(peak)
                                }
                            } else {
                                geminiBargeInCandidateSinceMs = 0L
                            }
                            val normalisedPeak = (peak / 32767f).coerceIn(0f, 1f)
                            pushOscilloscopeLevel(normalisedPeak, OSCILLOSCOPE_USER_COLOR)
                            if (chunkCount % 12 == 0) {
                                Log.d(
                                        TAG,
                                        "Gemini mic chunk=$chunkCount bytes=$read peak=$peak nonSilent=$sawNonSilentGeminiAudio"
                                )
                            }
                            chunkCount += 1
                            geminiLiveSession?.sendAudioChunkPcm16(
                                    chunk,
                                    read,
                                    GEMINI_AUDIO_SAMPLE_RATE
                            )
                        } else if (read < 0) {
                            Log.w(TAG, "Gemini mic read error code=$read")
                        }
                    }
                }
                        .apply {
                            name = "GeminiLiveAudioThread"
                            start()
                        }
        touchGeminiLiveActivity(force = true)
    }

    private fun stopGeminiAudioStreaming() {
        if (!geminiCaptureActive && geminiAudioRecord == null && geminiAudioThread == null) return

        geminiCaptureActive = false
        geminiBargeInCandidateSinceMs = 0L
        val recorder = geminiAudioRecord
        geminiAudioRecord = null

        // Never join() on the UI thread — it blocked for up to 250ms per tap.
        // The audio thread exits on its own once geminiCaptureActive == false.
        val thread = geminiAudioThread
        geminiAudioThread = null
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { recorder?.stop() }
            releaseGeminiAudioEffects()
            runCatching { recorder?.release() }
            if (thread != null) {
                runCatching { thread.join(300) }
            }
            disableRayNeoVoiceAssistantMicRouteAsync()
        }
    }

    private fun releaseGeminiAudioCapture(cancelOnly: Boolean) {
        disarmSilenceWatchdog()
        uiHandler.removeCallbacks(liveSetupTimeoutRunnable)
        geminiSessionInitJob?.cancel()
        geminiSessionInitJob = null
        stopGeminiAudioStreaming()
        geminiAudioPlayer?.stopAndFlush()
        geminiReadoutAudioPlayer?.stopAndFlush()
        liveSessionReady = false
        liveState = GeminiLiveState.IDLE
        awaitingServerTurnComplete = false
        lastLiveActivityHeartbeatMs = 0L
        lastMultimodalFrameSentMs = 0L
        lastUserSpeechActivityMs = 0L
        lastGeminiOutputActivityMs = 0L
        currentGeminiOutputTurnStartedMs = 0L
        dropOutputTranscriptionUntilNextInput = false

        // Bump the epoch so stale callbacks from the dying session are ignored.
        geminiSessionEpoch++
        liveSessionClosingByApp = true
        // Close the WebSocket on a background thread to avoid blocking the UI.
        val session = geminiLiveSession
        geminiLiveSession = null
        if (session != null) {
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching { session.close() }
            }
        }
        latestLiveTranscript = ""
        latestLiveOutputTranscript = ""
        chatFragment.setStreamActiveIndicator(false)
        hideOscilloscope()
        // stopGeminiAudioStreaming already released the mic route asynchronously;
        // belt-and-suspenders async call here is harmless if the flag was already cleared.
        disableRayNeoVoiceAssistantMicRouteAsync()
        if (cancelOnly) {
            viewModel.resetLiveAssistantStream()
        }
    }

    /**
     * MUST be synchronous — AudioRecord is created immediately after this call
     * in startGeminiAudioStreaming(), and the RayNeo hardware requires the
     * audio_source_record parameter to be set BEFORE the recorder opens.
     * The call typically completes in <5 ms on RayNeo X3 hardware.
     */
    private fun enableRayNeoVoiceAssistantMicRoute() {
        if (rayNeoMicRouteActive) return
        rayNeoMicRouteActive = true
        val audioManager = getSystemService(AUDIO_SERVICE) as? AudioManager ?: return
        runCatching {
            audioManager.setParameters("audio_source_record=voiceassistant")
            Log.i(TAG, "RayNeo mic route enabled (voiceassistant)")
        }.onFailure { Log.w(TAG, "Unable to enable RayNeo mic route: ${it.message}") }
    }

    /** Synchronous variant — only called from stopGeminiAudioStreaming which already defers
     *  to a background thread for cleanup.  Kept for the legacy call-site in releaseGeminiAudioCapture. */
    private fun disableRayNeoVoiceAssistantMicRoute() {
        if (!rayNeoMicRouteActive) return
        rayNeoMicRouteActive = false
        val audioManager = getSystemService(AUDIO_SERVICE) as? AudioManager ?: return
        runCatching {
            audioManager.setParameters("audio_source_record=off")
            Log.i(TAG, "RayNeo mic route released")
        }.onFailure { Log.w(TAG, "Unable to release RayNeo mic route: ${it.message}") }
    }

    /** Async variant — safe to call from the UI thread. */
    private fun disableRayNeoVoiceAssistantMicRouteAsync() {
        if (!rayNeoMicRouteActive) return
        rayNeoMicRouteActive = false
        val audioManager = getSystemService(AUDIO_SERVICE) as? AudioManager ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                audioManager.setParameters("audio_source_record=off")
                Log.i(TAG, "RayNeo mic route released (async)")
            }.onFailure { Log.w(TAG, "Unable to release RayNeo mic route: ${it.message}") }
        }
    }

    private fun mergeLiveTranscript(existing: String, incoming: String): String {
        val prev = existing.trim()
        val next = incoming.trim()
        if (prev.isBlank()) return next
        if (next.isBlank()) return prev
        if (next.startsWith(prev)) return next
        if (prev.startsWith(next)) return prev
        if (next.contains(prev)) return next
        if (prev.contains(next)) return prev
        val maxOverlap = minOf(prev.length, next.length)
        for (n in maxOverlap downTo 1) {
            if (prev.endsWith(next.substring(0, n))) {
                return prev + next.substring(n)
            }
        }
        return "$prev $next"
    }

    private fun calculatePcm16Peak(data: ByteArray, size: Int): Int {
        if (size < 2) return 0
        var peak = 0
        var i = 0
        while (i + 1 < size) {
            val sample = ((data[i + 1].toInt() shl 8) or (data[i].toInt() and 0xFF))
            val signed = if ((sample and 0x8000) != 0) sample - 0x10000 else sample
            val magnitude = abs(signed)
            if (magnitude > peak) peak = magnitude
            i += 2
        }
        return peak
    }

    private fun pushOscilloscopeLevel(level: Float, color: Int, force: Boolean = false) {
        if (!force && !isGeminiListeningOrThinking()) {
            return
        }
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastOscilloscopeUiUpdateMs < OSCILLOSCOPE_UI_THROTTLE_MS) {
            return
        }
        lastOscilloscopeUiUpdateMs = now
        runOnUiThread {
            if (!force && !isGeminiListeningOrThinking()) {
                chatFragment.hideVoiceOscilloscope()
                voiceOscilloscope?.stop()
                voiceOscilloscope?.visibility = View.GONE
                return@runOnUiThread
            }
            chatFragment.pushVoiceOscilloscope(level, color)
            // Legacy overlay view is intentionally disabled in favor of inline chat rendering.
            voiceOscilloscope?.stop()
            voiceOscilloscope?.visibility = View.GONE
        }
    }

    private fun hideOscilloscope() {
        lastOscilloscopeUiUpdateMs = 0L
        runOnUiThread {
            chatFragment.hideVoiceOscilloscope()
            voiceOscilloscope?.stop()
            voiceOscilloscope?.visibility = View.GONE
        }
    }

    /**
     * Handle speech recognition result.
     *
     * Flow:
     * 1. If the active panel implements TrackpadPanel, try injecting
     * ```
     *      text into its focused field via onTextInputFromHold().
     * ```
     * 2. If no panel consumed the text, route through Gemini for
     * ```
     *      intent routing / tool calls.
     * ```
     * Note: STT may run through Android SpeechRecognizer or Gemini audio transcription fallback
     * (device-dependent). Gemini still handles intent routing and tool-call dispatch after
     * transcript extraction.
     */
    private fun handleSpeechResult(text: String) {
        Log.d(TAG, "Speech result: $text")
        runOnUiThread {
            nativeSttFallbackTriggered = false
            viewModel.deactivateVoiceAssistant()
            showListeningOverlay(false)
            clearLiveSpeechPreview()
            clearListeningTranscript()

            if (maybeRouteLocalIntentDirectly(text)) {
                Log.d(TAG, "Voice input — local intent routed directly")
                return@runOnUiThread
            }

            val currentPanel = viewPager?.currentItem ?: MainViewModel.PANEL_CHAT

            // Chat panel: voice input always routes to Gemini directly —
            // the user expects a conversational response, not text sitting
            // in the EditText waiting for a manual Send tap.
            if (currentPanel == MainViewModel.PANEL_CHAT) {
                Log.d(TAG, "Chat panel — routing voice input to Gemini")
                viewModel.routeWithToolCalls(text, latestFrame)
                return@runOnUiThread
            }

            // Other panels (Settings, Web): try injecting into the focused
            // text field first (e.g. dictating into an API key field).
            val panel = currentTrackpadPanel()
            val consumed = panel?.onTextInputFromHold(text) ?: false

            if (!consumed) {
                // No active text field — fall back to Gemini routing
                Log.d(TAG, "No active text field, routing to Gemini")
                viewModel.routeWithToolCalls(text, latestFrame)
            } else {
                Log.d(TAG, "Text injected into active panel field")
            }
        }
    }

    private fun handleSpeechPartial(text: String) {
        runOnUiThread {
            updateListeningTranscript(text)
        }
    }

    /** Handle speech recognition error. Deactivates voice assistant and shows HUD notification. */
    private fun handleSpeechError(errorCode: Int) {
        Log.e(TAG, "Speech error: $errorCode")
        runOnUiThread {
            viewModel.deactivateVoiceAssistant()
            showListeningOverlay(false)
            clearLiveSpeechPreview()
            clearListeningTranscript()

            val message =
                    when (errorCode) {
                        SpeechRecognizer.ERROR_NO_MATCH ->
                                "No speech detected. Hold trackpad ~1s, then speak clearly."
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                                "Speech timed out. Try holding trackpad and speaking sooner."
                        SpeechRecognizer.ERROR_AUDIO ->
                                "Mic error — check microphone permission in Settings."
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                                "Network error — speech service needs internet."
                        SpeechRecognizer.ERROR_SERVER ->
                                "Speech server error. Try again in a moment."
                        SpeechRecognizer.ERROR_SERVER_DISCONNECTED ->
                                "Speech service disconnected — reconnecting..."
                        SpeechRecognizer.ERROR_CLIENT ->
                                "Speech client error. Restarting recognizer."
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                                "Mic permission denied. Grant in system Settings."
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                                "Recognizer busy. Wait a moment and try again."
                        else -> "Voice error (code $errorCode)"
                    }

            val isTimeout =
                    errorCode == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                            errorCode == SpeechRecognizer.ERROR_NO_MATCH
            if (isTimeout) playVoiceTimeoutBeep()

            val shouldFallbackToGeminiLive =
                    USE_NATIVE_STT &&
                            (errorCode == SpeechRecognizer.ERROR_NO_MATCH ||
                                    errorCode == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                                    errorCode == SpeechRecognizer.ERROR_CLIENT ||
                                    errorCode == SpeechRecognizer.ERROR_SERVER ||
                                    errorCode == SpeechRecognizer.ERROR_SERVER_DISCONNECTED ||
                                    errorCode == SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
            if (shouldFallbackToGeminiLive &&
                    fallbackToGeminiLiveFromNativeStt("speech_error_$errorCode")) {
                return@runOnUiThread
            }

            showHudNotification(message)
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Trackpad scroll/input forwarding to active panel
    // ══════════════════════════════════════════════════════════════════════

    /** Returns the current panel fragment cast to TrackpadPanel if applicable. */
    private fun currentTrackpadPanel(): TrackpadPanel? {
        return chatFragment as? TrackpadPanel
    }

    // ══════════════════════════════════════════════════════════════════════
    // ViewModel Observers
    // ══════════════════════════════════════════════════════════════════════

    private fun observeViewModel() {
        viewModel.apiKeyRequired.observe(this) { message ->
            if (message != null) {
                showHudNotification(message)
                viewModel.clearApiKeyRequired()
            }
        }

        viewModel.activePanelIndex.observe(this) { index ->
            if (index == MainViewModel.PANEL_WEB) {
                launchTapBrowser(viewModel.webNavigationUrl.value)
                viewModel.clearWebNavigation()
                return@observe
            }

            if (viewPager?.currentItem != MainViewModel.PANEL_CHAT) {
                viewPager?.setCurrentItem(MainViewModel.PANEL_CHAT, false)
            }
            handlePanelChanged(MainViewModel.PANEL_CHAT)
        }

        viewModel.voiceAssistantActive.observe(this) { active ->
            showListeningOverlay(active)
            syncCameraToGeminiState(active)
            // Phase 2 Step 2c.4: mirror the camera-on state into the
            // tapbrowser overlay's status chip. Bridge dedupes, so
            // repeat publishes with the same value are cheap no-ops.
            com.TapLink.app.unipanel.CameraStateBridge.publish(active == true)
        }

        viewModel.youtubePlaybackEvent.observe(this) { event ->
            if (event == null) return@observe
            viewModel.clearYoutubePlaybackEvent()
            showHudNotification(
                if (event.queue.size > 1) "Queuing ${event.queue.size} YouTube items"
                else "Playing latest YouTube ${event.mode} for ${event.query}"
            )
            launchTapBrowser(
                initialUrl = event.searchUrl,
                youtubeAutoplayQuery = event.query,
                youtubeAutoplayMode = event.mode,
                youtubeAutoplayQueue = event.queue
            )
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    combine(
                        viewModel.calendarSummary,
                        viewModel.tasksSummary,
                        viewModel.newsSummary,
                        viewModel.airQualitySummary
                    ) { calendar, tasks, news, airQuality ->
                        arrayOf(calendar, tasks, news, airQuality)
                    }.collect { values ->
                        chatFragment.syncHudSnapshot(
                            calendarSummary = values[0] as String,
                            tasksSummary = values[1] as String,
                            newsSummary = values[2] as String,
                            airQualityState = values[3] as? MainViewModel.AirQualityHudState
                        )
                        publishUnipanelHudSnapshot(
                            calendarSummary = values[0] as String,
                            tasksSummary = values[1] as String,
                            newsSummary = values[2] as String,
                            airQualityState = values[3] as? MainViewModel.AirQualityHudState
                        )
                    }
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // HUD Notification (non-intrusive overlay)
    // ══════════════════════════════════════════════════════════════════════

    private fun showHudNotification(message: String, displayMs: Long = HUD_NOTIFICATION_DURATION_MS) {
        val formatted =
                message.trim().ifBlank {
                    return
                }
        hudNotification?.apply {
            uiHandler.removeCallbacks(hideHudNotificationRunnable)
            animate().cancel()
            text = formatted

            // Apply accessibility: font scale
            val fontScale = viewModel.preferences.hudFontScale
            if (fontScale > 0f) {
                textSize = 14f * fontScale  // base size 14sp scaled by user preference
            }
            // Apply accessibility: high contrast
            if (viewModel.preferences.hudHighContrast) {
                setTextColor(0xFFFFFFFF.toInt())
                setShadowLayer(4f, 0f, 0f, 0xFF000000.toInt())
            }

            visibility = View.VISIBLE
            alpha = 0f
            animate().alpha(1f).setDuration(160).start()
            uiHandler.postDelayed(hideHudNotificationRunnable, displayMs.coerceAtLeast(1_000L))
        }
    }

    fun showMirroredNotice(message: String) {
        runOnUiThread { showHudNotification(message) }
    }

    // Kept as a no-op for the legacy HUD/chat preview surface — that
    // surface was deliberately silent for privacy. Phase 2 Step 2c.3.1
    // *does* surface finalized user transcripts as ChatMessage cards
    // (visionclaw chat + the tapbrowser unipanel mini-card stack), but
    // those are committed once per finalized turn via
    // viewModel.appendUserUtterance() rather than mid-utterance previews,
    // and are session-only (not persisted to assistant history or the
    // previous-conversation prefs cache). This function is still here
    // so callers don't need to know whether the preview surface exists.
    private fun showLiveSpeechPreview(text: String) = Unit

    private fun clearLiveSpeechPreview() {
        hudNotification?.apply {
            uiHandler.removeCallbacks(hideHudNotificationRunnable)
            animate().cancel()
            visibility = View.GONE
        }
    }

    private fun updateListeningTranscript(text: String) {
        val value = text.trim()
        if (value.isBlank()) return
        listeningTranscript?.apply {
            this.text = value
            visibility = View.VISIBLE
            alpha = 1f
            isSelected = true
        }
    }

    private fun clearListeningTranscript() {
        pendingLiveInputTranscript = ""
        lastHandledLiveInputTranscript = ""
        uiHandler.removeCallbacks(settledLiveInputRunnable)
        listeningTranscript?.apply {
            text = ""
            visibility = View.GONE
            isSelected = false
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Listening Overlay (Siri-style visual)
    // ══════════════════════════════════════════════════════════════════════

    private fun showListeningOverlay(show: Boolean) {
        listeningOverlay?.apply {
            if (show) {
                uiHandler.removeCallbacks(cameraIdleTimeoutRunnable)
                visibility = View.VISIBLE
                alpha = 0f
                animate().alpha(1f).setDuration(200).start()
                listeningTranscript?.visibility = View.VISIBLE
                listeningTranscript?.text = "Listening…"
                listeningTranscript?.isSelected = true
            } else {
                uiHandler.removeCallbacks(settledLiveInputRunnable)
                animate()
                        .alpha(0f)
                        .setDuration(300)
                        .withEndAction { visibility = View.GONE }
                        .start()
                listeningTranscript?.visibility = View.GONE
                listeningTranscript?.isSelected = false
            }
        }
    }

    private fun playVoiceActivateBeep() {
        toneGenerator?.startTone(ToneGenerator.TONE_PROP_ACK, VOICE_ACTIVATE_BEEP_MS)
    }

    private fun playVoiceTimeoutBeep() {
        toneGenerator?.startTone(ToneGenerator.TONE_PROP_NACK, VOICE_TIMEOUT_BEEP_MS)
    }

    // Screen mirroring functions removed – BinocularSbsLayout handles SBS rendering

}
