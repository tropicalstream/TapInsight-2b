package com.rayneo.visionclaw.core.session

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.TapLink.app.unipanel.CameraStateBridge
import com.TapLink.app.unipanel.HudStateBridge
import com.TapLink.app.unipanel.VoiceServiceApi
import kotlinx.coroutines.launch

/**
 * Unipanel v2 — foreground Service that hosts the Gemini Live voice
 * pipeline. Phase 3: this is now a bindable Service with a
 * [VoiceServiceApi] surface; Phase 4 will move the AudioRecord +
 * WebSocket + AudioTrack ownership in from visionclaw MainActivity.
 *
 * Two ways to wake the Service:
 *
 *   1. [start] / [stop] static helpers — fire-and-forget FGS lifecycle.
 *      visionclaw MainActivity uses these from
 *      `activateChatVoiceAssistant` / `shutdownMultimodalSession`. In
 *      unipanel mode visionclaw isn't running, so this path is a no-op
 *      today, but the code stays for any path that re-enables the
 *      Activity (e.g. a settings shell that opens the chat panel
 *      explicitly).
 *
 *   2. `bindService` from tapbrowser. The Service's [LocalBinder] is
 *      cast to [VoiceServiceApi] in tapbrowser; calling
 *      [VoiceServiceApi.activateVoice] is the unipanel voice-activate
 *      entry. Today the stub publishes a HudStateBridge update so the
 *      bind path can be verified via logcat; Phase 4 wires it to the
 *      actual pipeline.
 *
 * Foreground-vs-bound semantics: `bindService` alone does NOT promote
 * the Service to foreground (no mic privilege yet). The Service goes
 * foreground when [activateVoice] internally calls
 * [startForegroundIfNeeded], which is where the FGS notification +
 * `FOREGROUND_SERVICE_TYPE_MICROPHONE` are attached. Shutdown reverses
 * both (`stopForeground` + drop the notification).
 */
class GeminiSessionForegroundService : LifecycleService() {

    /**
     * In-process Binder. Implements [VoiceServiceApi] so tapbrowser
     * can interact with the Service without depending on this
     * visionclaw class.
     */
    private inner class LocalBinder : Binder(), VoiceServiceApi {
        override fun activateVoice() = this@GeminiSessionForegroundService.activateVoice()
        override fun shutdownVoice() = this@GeminiSessionForegroundService.shutdownVoice()
        override fun currentState(): HudStateBridge.State = HudStateBridge.current()
        override fun toggleCamera() = this@GeminiSessionForegroundService.toggleCamera()
        override fun isCameraOn(): Boolean = cameraOn
        override fun setCameraPreviewSurfaceProvider(
            provider: androidx.camera.core.Preview.SurfaceProvider?
        ) {
            this@GeminiSessionForegroundService.cameraPreviewSurfaceProvider = provider
        }
        override fun speakAgentReply(text: String) {
            // Replay a stored chat-history reply through the readout engine,
            // then start a Gemini Live session so the user can ask follow-up
            // questions about the loaded conversation. Without the chained
            // activate(), the avatar would hang in THINKING (the green
            // output-mode ring) after playback ended because liveSessionReady
            // is false during a pure-replay readout.
            val service = this@GeminiSessionForegroundService
            service.pipeline.speakAgentReplyFromHistory(text) {
                // Completion callback fires on the readout coroutine; hop to
                // main so foreground promotion + AudioRecord open happen on
                // the Service's thread.
                service.mainHandler.post { service.activateVoice() }
            }
        }
        override fun showAssistantCard(text: String) {
            // Notification-tap path: render the text as an assistant chat
            // card WITHOUT starting a session or speaking. The shared
            // MainViewModel feeds ChatCardBridge, which the unipanel
            // overlay observes — same surface as a live agent reply.
            val clean = text.trim()
            if (clean.isBlank()) return
            val service = this@GeminiSessionForegroundService
            runCatching {
                (service.applicationContext as com.rayneo.visionclaw.VisionClawApp)
                    .viewModel.appendDirectAssistantResponse(clean)
            }.onFailure {
                Log.w(TAG, "showAssistantCard failed: ${it.message}")
            }
        }
    }

    private val binder = LocalBinder()

    /** Main-looper handler so cross-thread callers (e.g. the readout
     *  coroutine's completion hook) can post work back onto the Service's
     *  thread without bringing in a coroutine dependency. */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** True while the Service is in foreground (FGS notification attached). */
    @Volatile
    private var foregroundActive: Boolean = false

    /** The real Gemini Live voice pipeline. Phase 4 — runs AudioRecord
     *  + GeminiRouter.startLiveAudioSession + GeminiAudioPlayer entirely
     *  inside this Service, no Activity dependency. Lazy so we don't
     *  allocate the audio stack until voice actually activates. */
    private val pipeline: GeminiVoicePipeline by lazy { GeminiVoicePipeline(this) }

    /** Phase 4d — CameraX frame capture, owned by the Service so it
     *  can stream frames into the same Gemini Live session as the
     *  audio pipeline. Lazy: only allocate the CameraX executor +
     *  ProcessCameraProvider on first toggleCamera(). */
    private val frameCapture by lazy {
        com.rayneo.visionclaw.core.camera.FrameCaptureManager(this)
    }

    @Volatile
    private var cameraOn: Boolean = false

    /** Phase 4g — preview surface provider supplied by tapbrowser
     *  (its PreviewView). When non-null, the next CameraX bind
     *  includes a Preview use case so the user sees a live feed in
     *  the unipanel camera-preview frame. */
    @Volatile
    private var cameraPreviewSurfaceProvider: androidx.camera.core.Preview.SurfaceProvider? = null

    override fun onCreate() {
        super.onCreate()
        // Phase 4e — install the pipeline's auto-camera hook now that
        // the Service is constructed. When the user says a vision
        // phrase ("look at this", etc.) the pipeline calls this hook
        // and we turn the camera on for the rest of the session.
        // Idempotent: subsequent invocations while cameraOn=true no-op.
        pipeline.setAutoCameraEnabler {
            if (!cameraOn) toggleCamera()
        }
        // Phase 4g fix — bridge the shared MainViewModel.messages flow
        // into ChatCardBridge. The publisher used to live in visionclaw
        // MainActivity but that Activity isn't running in unipanel mode,
        // so nothing was hitting ChatCardBridge.publish. Now the
        // Service owns the bridging on its own lifecycleScope, which
        // outlives any Activity. Source of truth stays MainViewModel
        // (Application-scoped per Phase 2).
        lifecycleScope.launch {
            try {
                val vm = (applicationContext as com.rayneo.visionclaw.VisionClawApp).viewModel
                vm.messages.collect { messages ->
                    val cards = messages.map { msg ->
                        com.TapLink.app.unipanel.ChatCardBridge.Card(
                            text = msg.text,
                            fromUser = msg.fromUser,
                            timestampMs = msg.timestampMs
                        )
                    }
                    com.TapLink.app.unipanel.ChatCardBridge.publish(cards)
                }
            } catch (e: Exception) {
                Log.w(TAG, "messages → ChatCardBridge bridge failed: ${e.message}", e)
            }
        }
        lifecycleScope.launch {
            try {
                val vm = (applicationContext as com.rayneo.visionclaw.VisionClawApp).viewModel
                val hudPrefs = com.rayneo.visionclaw.core.storage.AppPreferences(applicationContext)
                kotlinx.coroutines.flow.combine(
                    vm.calendarSummary,
                    vm.tasksSummary,
                    vm.newsSummary,
                    vm.airQualitySummary,
                    vm.radioSummary
                ) { calendar, tasks, news, airQuality, radio ->
                    arrayOf(calendar, tasks, news, airQuality, radio)
                }.collect { values ->
                    val airQuality =
                        values[3] as? com.rayneo.visionclaw.ui.MainViewModel.AirQualityHudState
                    val radio =
                        values[4] as? com.rayneo.visionclaw.ui.MainViewModel.RadioHudState
                    HudStateBridge.update { state ->
                        state.copy(
                            calendarSummary = values[0] as String,
                            tasksSummary = values[1] as String,
                            newsSummary = values[2] as String,
                            // Phase 4k.3 — carry the companion's HUD card
                            // order so the tiered HUD panel matches it.
                            hudDisplayOrder = hudPrefs.hudDisplayOrder,
                            airQualityText = airQuality?.text,
                            airQualityValue = airQuality?.aqi,
                            radioStation = radio?.stationName,
                            radioPlaying = radio?.playing == true
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "HUD flows → HudStateBridge bridge failed: ${e.message}", e)
            }
        }
        // HUD bell → unipanel. Mirror the NotificationCenter list + unread
        // badge count into HudStateBridge so tapbrowser's unipanel HUD can
        // render the bell without a visionclaw Activity running. init() is
        // idempotent — it restores persisted state here in case the Service
        // is the first process entry point (pure unipanel cold start).
        com.rayneo.visionclaw.core.notifications.NotificationCenter.init(applicationContext)
        lifecycleScope.launch {
            try {
                kotlinx.coroutines.flow.combine(
                    com.rayneo.visionclaw.core.notifications.NotificationCenter.notifications,
                    com.rayneo.visionclaw.core.notifications.NotificationCenter.unreadCount
                ) { list, unread ->
                    list to unread
                }.collect { (list, unread) ->
                    HudStateBridge.update { state ->
                        state.copy(
                            notifications = list.map { n ->
                                HudStateBridge.HudNotificationEntry(
                                    id = n.id,
                                    source = n.source.name,
                                    title = n.title,
                                    message = n.message,
                                    timestampMs = n.timestampMs
                                )
                            },
                            notificationUnread = unread
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "NotificationCenter → HudStateBridge bridge failed: ${e.message}", e)
            }
        }
        // Unipanel bell-list open → clear the unread badge, same as the
        // visionclaw chat panel does when its list opens.
        HudStateBridge.setOnNotificationsSeen {
            com.rayneo.visionclaw.core.notifications.NotificationCenter.markAllSeen()
        }
        // Phase 4j — Hermes health ping. In unipanel mode the visionclaw
        // Activity (which normally drives the "H" status badge via
        // hermesPingRunnable) isn't running, so nothing publishes
        // HudStateBridge.hermesStatus and the H badge would stay dark.
        // The Service owns its own Activity-free ping here so the badge
        // reflects Hermes reachability: GOOD (green) when the agent
        // server answers, BAD (red) when configured-but-unreachable,
        // HIDDEN when the integration is switched off. Cadence reuses
        // the same openclaw heartbeat-interval pref as the Activity.
        lifecycleScope.launch {
            val prefs = com.rayneo.visionclaw.core.storage.AppPreferences(applicationContext)
            val hermesClient = com.rayneo.visionclaw.core.network.HermesClient(
                endpointUrlProvider = { prefs.hermesEndpoint.trim().takeIf { it.isNotBlank() } },
                apiKeyProvider = { prefs.hermesApiKey.trim().takeIf { it.isNotBlank() } },
                sessionIdProvider = {
                    prefs.hermesSessionId.trim().takeIf { it.isNotBlank() } ?: "main"
                },
                timeoutMsProvider = {
                    val seconds = prefs.hermesTimeoutSeconds.takeIf { it > 0 } ?: 30
                    seconds.coerceAtLeast(5) * 1000
                }
            )
            while (true) {
                val status = try {
                    when {
                        !prefs.hermesEnabled -> HudStateBridge.GatewayStatus.HIDDEN
                        prefs.hermesEndpoint.isBlank() || prefs.hermesApiKey.isBlank() ->
                            HudStateBridge.GatewayStatus.BAD
                        else -> {
                            val result = kotlinx.coroutines.withTimeoutOrNull(12_000L) {
                                hermesClient.ping()
                            }
                            if (result is
                                com.rayneo.visionclaw.core.network.HermesClient.ClawResult.Success
                            ) {
                                HudStateBridge.GatewayStatus.GOOD
                            } else {
                                HudStateBridge.GatewayStatus.BAD
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Hermes health ping failed: ${e.message}")
                    HudStateBridge.GatewayStatus.BAD
                }
                HudStateBridge.update { it.copy(hermesStatus = status) }
                val intervalMs =
                    (prefs.openClawHeartbeatIntervalSeconds * 1000L).coerceAtLeast(5_000L)
                kotlinx.coroutines.delay(intervalMs)
            }
        }
        // Phase 4k.3 — OpenClaw (TapClaw) health ping, mirroring the
        // Hermes one above so the "O" badge next to "H" reflects the
        // OpenClaw gateway's reachability in unipanel mode. GOOD when the
        // gateway answers, BAD when configured-but-unreachable, HIDDEN
        // when the integration is switched off. Same cadence pref.
        lifecycleScope.launch {
            val prefs = com.rayneo.visionclaw.core.storage.AppPreferences(applicationContext)
            val pairing = applicationContext.getSharedPreferences(
                "visionclaw_prefs", Context.MODE_PRIVATE
            )
            val openClawClient = com.rayneo.visionclaw.core.network.OpenClawClient(
                gatewayUrlProvider = {
                    prefs.openClawEndpoint.takeIf { it.isNotBlank() }
                        ?: pairing.getString("openclaw_pair_device_token_gateway", null)
                            ?.takeIf { it.isNotBlank() }
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
                }
            )
            while (true) {
                val status = try {
                    val gateway = prefs.openClawEndpoint.takeIf { it.isNotBlank() }
                        ?: pairing.getString("openclaw_pair_device_token_gateway", null)
                    val token = prefs.openClawToken.takeIf { it.isNotBlank() }
                        ?: pairing.getString("openclaw_pair_device_token", null)
                    when {
                        !prefs.openClawEnabled -> HudStateBridge.GatewayStatus.HIDDEN
                        gateway.isNullOrBlank() || token.isNullOrBlank() ->
                            HudStateBridge.GatewayStatus.BAD
                        else -> {
                            val result = kotlinx.coroutines.withTimeoutOrNull(12_000L) {
                                openClawClient.ping()
                            }
                            if (result is
                                com.rayneo.visionclaw.core.network.OpenClawClient.ClawResult.Success
                            ) {
                                HudStateBridge.GatewayStatus.GOOD
                            } else {
                                HudStateBridge.GatewayStatus.BAD
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "OpenClaw health ping failed: ${e.message}")
                    HudStateBridge.GatewayStatus.BAD
                }
                HudStateBridge.update { it.copy(openClawStatus = status) }
                val intervalMs =
                    (prefs.openClawHeartbeatIntervalSeconds * 1000L).coerceAtLeast(5_000L)
                kotlinx.coroutines.delay(intervalMs)
            }
        }
        // Persistent agent-status ticker. Keeps the HUD heartbeat ticker
        // populated at all times with the live Hermes / TapClaw reachability,
        // so a stalled agent query is visible instead of leaving the ticker
        // blank ("limbo"). Polls every prefs.agentStatusPollSeconds (default
        // 30s, configurable in the companion app). It publishes a PERSISTENT,
        // non-scrolling heartbeat; live streaming step-labels from an in-flight
        // agent call (which update far more often) override it in between, and
        // the next tick restores the status line once they go quiet.
        lifecycleScope.launch {
            val prefs = com.rayneo.visionclaw.core.storage.AppPreferences(applicationContext)
            fun gatewayWord(s: HudStateBridge.GatewayStatus): String? = when (s) {
                HudStateBridge.GatewayStatus.GOOD -> "ready"
                HudStateBridge.GatewayStatus.BAD -> "offline"
                HudStateBridge.GatewayStatus.HIDDEN -> null
            }
            while (true) {
                val cur = HudStateBridge.current()
                // Don't overwrite a live in-flight agent query's progress ("Asking
                // Hermes…" / streamed step-labels) with the idle status line — that
                // made a working query look idle. Only publish the status line when
                // no agent is busy.
                if (!cur.agentBusy) {
                    val parts = mutableListOf<String>()
                    gatewayWord(cur.hermesStatus)?.let { parts.add("Hermes: $it") }
                    gatewayWord(cur.openClawStatus)?.let { parts.add("TapClaw: $it") }
                    val line = if (parts.isEmpty()) "Assistant ready" else parts.joinToString("   ·   ")
                    HudStateBridge.update {
                        it.copy(
                            heartbeatMessage = line,
                            heartbeatPersistent = true,
                            heartbeatShouldScroll = false
                        )
                    }
                }
                val intervalMs = (prefs.agentStatusPollSeconds * 1000L).coerceAtLeast(10_000L)
                kotlinx.coroutines.delay(intervalMs)
            }
        }
        // Phase 4k — own the companion config server here. It used to be
        // started by visionclaw MainActivity, but that Activity doesn't
        // run in unipanel mode, so https://localhost:19110 was dead even
        // after `adb forward tcp:19110 tcp:19110`. The Service lives for
        // the whole unipanel session, so it's the right owner. Summary
        // providers come from the Application-scoped viewModel; the
        // OAuth / location / camera providers stay null (their endpoints
        // simply report unconfigured) to avoid an Activity dependency.
        startCompanionServerIfNeeded()
        // Phase 4k.2 — populate the calendar / tasks / news / air-quality
        // HUD feeds in unipanel mode. The viewModel fires its refreshes in
        // init(), but calendar and tasks need OAuth-backed clients that
        // only visionclaw MainActivity used to install — so in unipanel
        // mode those feeds stayed blank ("Events --  Tasks --"). Install
        // the OAuth clients here and keep the feeds fresh on a timer.
        setupUnipanelHudDataFeeds()
    }

    /**
     * Phase 4k.2 — shared GoogleOAuthManager for the companion server's
     * OAuth callback AND the calendar / tasks HUD clients. Built lazily
     * from prefs + the application context (no Activity dependency).
     */
    private val oauthManager by lazy {
        com.rayneo.visionclaw.core.network.GoogleOAuthManager(
            com.rayneo.visionclaw.core.storage.AppPreferences(applicationContext),
            applicationContext
        )
    }

    /** Phase 4k — companion config/HTTP server (port 19110), Service-owned. */
    private var companionServer: com.rayneo.visionclaw.core.config.CompanionServer? = null

    /**
     * Device location for the unipanel Service. In Activity mode visionclaw's
     * MainActivity owned the resolver, fed the companion server's
     * locationProvider, and published fixes into the shared ViewModel. In
     * unipanel mode that Activity never runs, so without this the companion
     * "Test Location" returned null and the HUD air-quality feed (which reads
     * latestDeviceLocationContext) had no coordinates. This resolver restores
     * that wiring on the Service side.
     */
    private val deviceLocationResolver by lazy {
        com.rayneo.visionclaw.core.location.DeviceLocationResolver(applicationContext)
    }

    /**
     * Resolve a usable device location (GPS → wifi → IP fallback) and publish
     * it into the shared ViewModel so the HUD AQI feed and the companion
     * location test both have coordinates. Returns the resolved (or last
     * published) context, or null if nothing is available. Blocking — call
     * from a background / HTTP-server thread, never the main thread.
     */
    private fun resolveAndPublishDeviceLocation():
        com.rayneo.visionclaw.core.model.DeviceLocationContext? {
        val vm = (applicationContext as com.rayneo.visionclaw.VisionClawApp).viewModel
        val resolved = runCatching {
            deviceLocationResolver.peekCached(allowApproximate = true)
                ?: deviceLocationResolver.resolveBlocking(allowApproximateFallback = true)
        }.getOrNull()
        if (resolved != null) {
            runCatching { vm.updateDeviceLocationContext(resolved) }
        }
        return resolved ?: vm.getDeviceLocationContext()
    }

    private fun startCompanionServerIfNeeded() {
        if (companionServer != null) return
        try {
            val vm = (applicationContext as com.rayneo.visionclaw.VisionClawApp).viewModel
            val port = runCatching { vm.appConfig.debugServerSettings.port }
                .getOrDefault(19110)
            val server = com.rayneo.visionclaw.core.config.CompanionServer(
                applicationContext,
                port,
                oauthManager = oauthManager,
                locationProvider = { resolveAndPublishDeviceLocation() },
                // Phone-bridge GPS: when the companion app POSTs the phone's
                // location to /api/phone-location, push it straight into the
                // ViewModel (and clear when the bridge turns off) — matching
                // how visionclaw's Activity wired it on the hermes branch. The
                // pull path (locationProvider/peekPhoneBridgeContext) already
                // reads the persisted fix; this just makes the update instant.
                phoneLocationConsumer = { ctx ->
                    if (ctx != null) {
                        runCatching { vm.updateDeviceLocationContext(ctx) }
                    } else if (vm.getDeviceLocationContext()?.provider == "companion_phone") {
                        runCatching { vm.clearDeviceLocationContext() }
                    }
                },
                calendarSummaryProvider = { vm.calendarSummary.value },
                tasksSummaryProvider = { vm.tasksSummary.value },
                newsSummaryProvider = { vm.newsSummary.value },
                airQualityTextProvider = { vm.airQualitySummary.value?.text },
                airQualityValueProvider = { vm.airQualitySummary.value?.aqi }
            )
            server.startServer()
            companionServer = server
            Log.i(TAG, "Companion server started on port $port (unipanel Service-owned)")
        } catch (e: Exception) {
            Log.w(TAG, "Companion server start failed: ${e.message}", e)
        }
    }

    /**
     * Phase 4k.2 — install OAuth-backed calendar + tasks clients (and the
     * air-quality client) on the Application-scoped viewModel, then keep
     * the calendar / tasks / news / AQI HUD feeds refreshed on a timer.
     * setCalendarClient / setTasksClient / setAirQualityClient each force
     * an immediate refresh; the loop below covers ongoing updates the way
     * the Activity's onResume used to. News uses the viewModel's built-in
     * client and needs no auth.
     */
    private fun setupUnipanelHudDataFeeds() {
        try {
            val app = applicationContext as com.rayneo.visionclaw.VisionClawApp
            val vm = app.viewModel
            val prefs = com.rayneo.visionclaw.core.storage.AppPreferences(applicationContext)
            val calendarClient = com.rayneo.visionclaw.core.network.GoogleCalendarClient(
                apiKeyProvider = { prefs.calendarApiKey },
                accessTokenProvider = {
                    kotlinx.coroutines.runBlocking { oauthManager.getValidAccessToken() }
                },
                context = applicationContext
            )
            vm.setCalendarClient(calendarClient)
            val tasksClient = com.rayneo.visionclaw.core.network.GoogleTasksClient(
                accessTokenProvider = {
                    kotlinx.coroutines.runBlocking { oauthManager.getValidAccessToken() }
                },
                context = applicationContext
            )
            vm.setTasksClient(tasksClient)
            val airQualityClient = com.rayneo.visionclaw.core.network.GoogleAirQualityClient(
                apiKeyProvider = { prefs.googleMapsApiKey },
                context = applicationContext
            )
            vm.setAirQualityClient(airQualityClient)
            // Places + Directions for the voice tool path (google_places /
            // google_routes / ask_maps). Both read the SAME Google Maps key the
            // companion app's Places/Traffic tests use. Without injecting these
            // the pipeline's ToolDispatcher fell back to no-key stubs, so Gemini
            // reported "Maps API key not configured" even though the key is set.
            val placesClient = com.rayneo.visionclaw.core.network.GooglePlacesClient(
                apiKeyProvider = { prefs.googleMapsApiKey },
                context = applicationContext
            )
            val directionsClient = com.rayneo.visionclaw.core.network.GoogleDirectionsClient(
                apiKeyProvider = { prefs.googleMapsApiKey },
                context = applicationContext
            )

            // Share the SAME authenticated clients with the voice tool path so
            // google_calendar / google_tasks (and AQI / places / daily_briefing
            // via location) work in conversation — not just on the HUD. Without
            // this the pipeline's ToolDispatcher used no-auth stubs and Gemini
            // reported it had no access to the user's calendar/tasks. Installed
            // here (onCreate) before any session, so the lazy dispatcher sees it.
            runCatching {
                pipeline.setGoogleToolClients(
                    calendarClient = calendarClient,
                    tasksClient = tasksClient,
                    airQualityClient = airQualityClient,
                    placesClient = placesClient,
                    directionsClient = directionsClient,
                    locationProvider = {
                        vm.getDeviceLocationContext()
                            ?: runCatching {
                                deviceLocationResolver.peekCached(allowApproximate = true)
                            }.getOrNull()
                    }
                )
            }

            lifecycleScope.launch {
                // IMMEDIATE first pass so the HUD populates at launch instead of
                // staying blank until the first 5-minute tick. Calendar + tasks
                // were already kicked by setCalendarClient/setTasksClient above;
                // we add news here and force a calendar/tasks refresh too so all
                // three text feeds appear right away.
                runCatching { vm.refreshHudNews(force = true) }
                runCatching { vm.refreshHudUpcomingCalendar(force = true) }
                runCatching { vm.refreshHudTasks(force = true) }
                // Resolve location + AQI on a SEPARATE coroutine so the (slow,
                // blocking) GPS resolve never gates the text feeds or the loop.
                // AQI fills in a few seconds later once coordinates arrive.
                launch {
                    runCatching {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            resolveAndPublishDeviceLocation()
                        }
                    }
                    runCatching { vm.refreshHudAirQuality(force = true) }
                }
                // Ongoing refresh: delay AFTER the immediate pass above.
                while (true) {
                    kotlinx.coroutines.delay(5 * 60 * 1000L)
                    runCatching {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            resolveAndPublishDeviceLocation()
                        }
                    }
                    runCatching { vm.refreshHudUpcomingCalendar(force = true) }
                    runCatching { vm.refreshHudTasks(force = true) }
                    runCatching { vm.refreshHudNews(force = true) }
                    // Drain relay-queued HUD bell notifications on the same
                    // cadence as MainActivity's notificationPollRunnable —
                    // in unipanel mode that Activity poll isn't running, so
                    // this is the only pull path for away-from-home bells.
                    // Own IO coroutine: drainBlocking is a blocking HTTP
                    // round-trip and must never gate the HUD feed loop.
                    launch(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching {
                            com.rayneo.visionclaw.core.notifications.RelayNotifyInbox
                                .drainBlocking(applicationContext)
                        }
                    }
                    runCatching { vm.refreshHudAirQuality(force = true) }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unipanel HUD data feed setup failed: ${e.message}", e)
        }
    }

    override fun onBind(intent: Intent): IBinder {
        Log.d(TAG, "onBind — issuing LocalBinder")
        // LifecycleService's onBind drives its internal lifecycle.
        // Call super so the LifecycleOwner reaches STARTED, which
        // CameraX (and viewModel-backed flows) require.
        super.onBind(intent)
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Preserve the v2 behavior: when started via [start], promote
        // immediately so visionclaw's existing call sites still get the
        // mic privilege. Bound-only consumers won't hit this path.
        super.onStartCommand(intent, flags, startId)
        startForegroundIfNeeded()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        // Phase 4d — stop CameraX before the pipeline so the analyzer
        // doesn't try to push a frame into a closed WebSocket.
        if (cameraOn) {
            runCatching { frameCapture.stop() }
            cameraOn = false
            CameraStateBridge.publish(false)
        }
        // Release the live pipeline FIRST so AudioRecord / WebSocket
        // / AudioTrack are all torn down cleanly before the process
        // event ends. The pipeline's release() is idempotent and
        // tolerates being called when it's already idle.
        runCatching { pipeline.release() }
        // Phase 4k — tear down the companion server we started in onCreate.
        runCatching { companionServer?.stopServer() }
        companionServer = null
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }
        foregroundActive = false
        super.onDestroy()
    }

    // ────────────────────────────────────────────────────────────────
    // VoiceServiceApi implementation — Phase 3 stubs
    // ────────────────────────────────────────────────────────────────

    /**
     * Phase 4 — drive the real Gemini Live voice pipeline.
     *
     * Order matters:
     *   1. Promote to foreground BEFORE the AudioRecord opens. Android
     *      11+ revokes the mic the instant an FGS misses its foreground
     *      window, and the AudioRecord constructor would throw
     *      SecurityException without the privilege already attached.
     *   2. Then [GeminiVoicePipeline.activate] starts the WebSocket;
     *      AudioRecord opens after onSessionReady fires (see the
     *      pipeline's startAudioStreaming).
     */
    private fun activateVoice() {
        Log.i(TAG, "activateVoice()")
        startForegroundIfNeeded()
        pipeline.activate()
    }

    /**
     * Phase 4d — toggle CameraX streaming. When ON, every frame the
     * camera produces is sent to the active Gemini Live session via
     * [GeminiVoicePipeline.sendCameraFrame] → liveSession.sendImage-
     * ChunkBase64. When OFF, CameraX is unbound and the camera is
     * released.
     *
     * Idempotent. Publishes the on/off state to [CameraStateBridge]
     * so the tapbrowser CAM chip + pill reflect reality.
     *
     * Requires:
     *   - CAMERA runtime permission (granted earlier via visionclaw
     *     MainActivity in pre-unipanel builds; user grants on first
     *     toggleCamera if not yet granted).
     *   - FOREGROUND_SERVICE_CAMERA + foregroundServiceType="camera"
     *     so Android 14+ lets us run CameraX from a Service.
     */
    private fun toggleCamera() {
        if (cameraOn) {
            Log.i(TAG, "toggleCamera: stopping CameraX")
            runCatching { frameCapture.stop() }
            cameraOn = false
            CameraStateBridge.publish(false)
            HudStateBridge.update { it.copy(notification = "Camera off") }
        } else {
            Log.i(TAG, "toggleCamera: starting CameraX")
            // FGS must be foreground BEFORE CameraX opens, else
            // Android revokes camera access mid-bind on API 30+.
            startForegroundIfNeeded()
            runCatching {
                frameCapture.start(
                    owner = this,
                    previewSurfaceProvider = cameraPreviewSurfaceProvider,
                    onFrameBase64 = { base64 -> pipeline.sendCameraFrame(base64) }
                )
                cameraOn = true
                CameraStateBridge.publish(true)
                // the user: no "Camera streaming" ticker — the red camera indicator
                // already shows the camera is on, and the preview goes straight
                // to its final position without a ticker to dodge.
            }.onFailure { e ->
                Log.w(TAG, "toggleCamera start failed: ${e.message}", e)
                HudStateBridge.update {
                    it.copy(notification = "Camera couldn't start: ${e.message}")
                }
            }
        }
    }

    /**
     * Phase 4 — tear down the live voice session. The pipeline shuts
     * down AudioRecord + WebSocket + AudioTrack and publishes IDLE
     * state. After that we drop the FGS notification but keep the
     * Service alive so a subsequent activateVoice() can re-promote
     * without paying the bindService round-trip.
     */
    private fun shutdownVoice() {
        Log.i(TAG, "shutdownVoice()")
        pipeline.shutdown(reason = null)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }
        foregroundActive = false
    }

    // ────────────────────────────────────────────────────────────────
    // FGS promotion
    // ────────────────────────────────────────────────────────────────

    private fun startForegroundIfNeeded() {
        if (foregroundActive) return
        ensureNotificationChannel(this)
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("TapInsight assistant")
            .setContentText("Voice session active")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                @Suppress("DEPRECATION")
                startForeground(NOTIFICATION_ID, notification)
            }
            foregroundActive = true
            Log.d(TAG, "startForeground OK — mic privilege attached")
        } catch (e: Exception) {
            Log.w(TAG, "startForeground failed: ${e.message}", e)
        }
    }

    companion object {
        private const val TAG = "GeminiFgs"
        private const val CHANNEL_ID = "tapinsight_voice_session"
        private const val NOTIFICATION_ID = 0x76_3F_45

        /** Fully-qualified class name string used by tapbrowser's
         *  bindService call. Tapbrowser can't import the Service class
         *  (visionclaw → tapbrowser module dependency), so it passes
         *  this name via Intent.setClassName at runtime. */
        const val FQN: String =
            "com.rayneo.visionclaw.core.session.GeminiSessionForegroundService"

        /** Start the FGS. Idempotent. Visionclaw calls this from
         *  activateChatVoiceAssistant when the Activity is hosting voice
         *  itself (legacy path; not used in unipanel mode where the
         *  Activity isn't running). */
        fun start(context: Context) {
            ensureNotificationChannel(context)
            val intent = Intent(context, GeminiSessionForegroundService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                Log.d(TAG, "start() dispatched")
            } catch (e: Exception) {
                Log.w(TAG, "start failed: ${e.message}", e)
            }
        }

        /** Stop the FGS. Legacy companion to [start]. */
        fun stop(context: Context) {
            val intent = Intent(context, GeminiSessionForegroundService::class.java)
            try {
                context.stopService(intent)
                Log.d(TAG, "stop() dispatched")
            } catch (e: Exception) {
                Log.w(TAG, "stop failed: ${e.message}", e)
            }
        }

        private fun ensureNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "TapInsight voice session",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shown while the Gemini voice assistant is listening."
                setShowBadge(false)
                enableVibration(false)
            }
            nm.createNotificationChannel(channel)
        }
    }
}
