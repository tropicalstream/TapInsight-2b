package com.TapLink.app.unipanel

/**
 * Unipanel v2 Phase 3 — cross-module API surface for the voice
 * foreground Service.
 *
 * Why this interface exists: the Service implementation lives in
 * the visionclaw `app` module (because that's where MainViewModel
 * and the Gemini Live client live), but tapbrowser is a library
 * the `app` module depends on, so tapbrowser can't reference any
 * visionclaw class at compile time. The Binder this Service returns
 * needs to expose a callable API to tapbrowser without that import.
 *
 * Solution: declare the API as a Kotlin interface in this module.
 * The Service's `LocalBinder` (in visionclaw) implements it. Tapbrowser
 * does `bindService(...)` with an Intent constructed by package + class
 * name string, then casts the returned `IBinder` to [VoiceServiceApi]
 * to call its methods.
 *
 * Both Activities run in the same process, so this is a plain in-process
 * call — no AIDL, no parcelization, no IPC overhead.
 *
 * Phase 3 contract:
 *   - [activateVoice] and [shutdownVoice] are STUBS that only update
 *     [HudStateBridge]. They don't yet drive the AudioRecord / Gemini
 *     Live pipeline; that wires in Phase 4.
 *   - [currentState] returns the current HudStateBridge snapshot,
 *     useful for tapbrowser to seed its overlay UI at bind time.
 *
 * Phase 4 will expand this interface with anything else the migrated
 * pipeline needs to expose (e.g. camera-frame sink callback registration).
 */
interface VoiceServiceApi {

    /**
     * Begin a voice session — Phase 3 stub publishes a LISTENING
     * state to [HudStateBridge] so we can verify the bind path
     * end-to-end via logcat. Phase 4 will spin up AudioRecord, open
     * the WebSocket, and feed audio frames.
     *
     * Idempotent: calling while already active is a no-op (or in
     * Phase 4, resets the idle watchdog).
     */
    fun activateVoice()

    /**
     * End the current voice session — Phase 3 stub restores IDLE
     * state. Phase 4 will close the WebSocket, stop AudioRecord,
     * release effects, and drop the FGS notification.
     *
     * Idempotent.
     */
    fun shutdownVoice()

    /**
     * Snapshot of the HUD state the Service has published. Convenience
     * for callers that just bound and want to render the current
     * state immediately without waiting for the next [HudStateBridge]
     * publish.
     */
    fun currentState(): HudStateBridge.State

    /**
     * Phase 4d — toggle CameraX streaming. When ON, the Service binds
     * an ImageAnalysis use case and streams frames into the active
     * Gemini Live session via `sendImageChunkBase64`. When OFF, the
     * camera is unbound and released. Idempotent.
     *
     * The Service is foreground-promoted before opening the camera
     * (Android 14+ requires foregroundServiceType="camera" + the
     * matching runtime permission). State is mirrored via
     * [CameraStateBridge] so the tapbrowser CAM chip + pill reflect
     * the on/off state without polling.
     */
    fun toggleCamera()

    /** True when CameraX is currently streaming. */
    fun isCameraOn(): Boolean

    /**
     * Synthesize [text] through the user's selected readout engine (Fish or
     * Gemini TTS) and play it through the same AudioTrack the agent readout
     * uses. Does NOT start a Gemini Live session — the voice service simply
     * routes the text through the TTS pipeline and plays it back.
     *
     * Used by the H / O badge chat-history overlay: tapping a previous chat
     * card replays the agent's verbatim reply through the readout voice so
     * the user can hear it again without starting a new Gemini turn.
     *
     * Idempotent: if a readout is already in flight, the new request is
     * queued or replaces the previous one (engine dependent — same behaviour
     * as the live agent readout path).
     */
    fun speakAgentReply(text: String)

    /**
     * Publish [text] as a direct assistant chat card (no Gemini turn, no
     * TTS). Used by the HUD notification panel: tapping a notification row
     * shows "<title> — <message>" as an expanded card; readout then only
     * happens if the user taps that expanded card (speakAgentReply path).
     * Routed through the service so the card lands in the shared
     * MainViewModel → ChatCardBridge flow like any real agent reply.
     */
    fun showAssistantCard(text: String)

    /**
     * Phase 4g — install a [androidx.camera.core.Preview.SurfaceProvider]
     * (typically `PreviewView.surfaceProvider`) so the next camera
     * activation binds a Preview use case alongside ImageAnalysis,
     * which lights up the unipanel preview frame. Pass `null` to
     * clear the provider so a subsequent camera-on goes analysis-only.
     *
     * Safe to call before [toggleCamera] — the Service caches the
     * provider and uses it on the next start.
     */
    fun setCameraPreviewSurfaceProvider(provider: androidx.camera.core.Preview.SurfaceProvider?)

    companion object {
        /**
         * Fully-qualified class name of the Service that implements
         * this interface. Tapbrowser uses it with
         * `Intent.setClassName(packageName, SERVICE_FQN)` to bind
         * without importing the visionclaw class (module dependency
         * runs visionclaw → tapbrowser, not the other way).
         *
         * The string MUST match
         * `com.rayneo.visionclaw.core.session.GeminiSessionForegroundService.FQN`
         * — kept in sync manually.
         */
        const val SERVICE_FQN: String =
            "com.rayneo.visionclaw.core.session.GeminiSessionForegroundService"
    }
}
