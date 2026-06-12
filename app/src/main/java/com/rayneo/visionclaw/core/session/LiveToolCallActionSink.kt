package com.rayneo.visionclaw.core.session

/**
 * Phase 4i — Unipanel/Activity-neutral output surface for the shared
 * [LiveToolCallCoordinator].
 *
 * The Hermes-era tool-call orchestrator (visionclaw MainActivity's
 * `dispatchLiveToolCall`) intertwined ~11 guardrails + transcript
 * state + UI side-effects. We want both visionclaw MainActivity AND
 * GeminiVoicePipeline to drive the SAME orchestrator, so anything
 * the orchestrator wants to do at the world boundary must go through
 * this interface — never reach into Activity views, never call
 * `runOnUiThread`, never assume `chatFragment` exists.
 *
 * Each method maps to one outbound effect from the original
 * Activity-coupled implementation. Documentation describes how the
 * Activity path implements it AND how the Service path should
 * implement it (both will, eventually, in subsequent commits).
 *
 * Implementations:
 *   - `MainActivityActionSink` in visionclaw MainActivity (Activity
 *     path — drives chatFragment, runOnUiThread, etc.)
 *   - `ServiceActionSink` in GeminiVoicePipeline (Service path —
 *     publishes via HudStateBridge, launches TapBrowser via Intent,
 *     speaks via GeminiAudioPlayer)
 *
 * Migration plan lives in tasks/UNIPANEL_V2_SERVICE_REFACTOR.md
 * under "Phase 4i".
 */
interface LiveToolCallActionSink {

    /**
     * Send a tool response back to Gemini Live across the WebSocket.
     * Mirrors `liveSession.sendToolResponse(callId, name, result)`.
     * Returns true on success (matching LiveSessionHandle.sendToolResponse).
     *
     * Activity impl: forwards to the Activity's `geminiLiveSession?.sendToolResponse`.
     * Service impl: forwards to the pipeline's liveSession.sendToolResponse.
     */
    fun sendToolResponse(callId: String, toolName: String, text: String): Boolean

    /**
     * Bring TapBrowser to the foreground with the supplied URL,
     * optionally with the YouTube auto-play extras the existing
     * Activity path uses.
     *
     * Activity impl: existing startActivity(Intent().setClassName(...)) +
     *   FLAG_ACTIVITY_REORDER_TO_FRONT path.
     * Service impl: same, but startActivity needs FLAG_ACTIVITY_NEW_TASK
     *   because the Service has no Activity context to launch from.
     *
     * @param initialUrl direct URL to load (may be null when only
     *   passing autoplay extras for an existing tab).
     * @param youtubeAutoplayQuery if non-null, TapBrowser searches
     *   YouTube for this query on arrival.
     * @param youtubeAutoplayMode "first" / "list" — controls whether
     *   the resolved video plays immediately or shows in a picker.
     * @param youtubeAutoplayQueue JSON-encoded queue payload for the
     *   send_video_list flow.
     */
    fun launchTapBrowser(
        initialUrl: String? = null,
        youtubeAutoplayQuery: String? = null,
        youtubeAutoplayMode: String? = null,
        youtubeAutoplayQueue: String? = null
    )

    /**
     * Show a transient single-line HUD notice.
     *
     * Activity impl: existing `showHudNotification(text)` view path.
     * Service impl: `HudStateBridge.update { it.copy(notification = text) }`.
     */
    fun showHudNotification(text: String)

    /**
     * Append a direct assistant response into the chat history. Used
     * when the coordinator wants to surface a locally-generated reply
     * (e.g. media-capability question, redundant-lookup short-circuit)
     * without going through Gemini.
     *
     * Activity impl: `viewModel.appendDirectAssistantResponse(text)`.
     * Service impl: same — viewModel is Application-scoped so both
     *   paths converge on the same StateFlow.
     */
    fun appendDirectAssistantResponse(text: String)

    /**
     * Speak [text] out loud through whatever TTS path the host has
     * available. Both Activity and Service eventually route through
     * the same engine (GeminiAudioPlayer + Fish/Gemini TTS clients).
     *
     * Activity impl: `ttsController?.speak(text)` or the chat fragment's
     *   readout path.
     * Service impl: pipeline's audio player + TTS client.
     */
    fun speak(text: String)

    /**
     * Render a locally-generated agent result (Hermes / TapClaw
     * direct-presentation behavior). Distinct from
     * [appendDirectAssistantResponse] because the local agent result
     * may also light up the heartbeat ticker and update tool status.
     *
     * Activity impl: existing chat-fragment direct-readout path.
     * Service impl: append to viewModel + publish to HudStateBridge.
     */
    fun presentLocalAgentResult(text: String)

    /**
     * Record a one-line HUD heartbeat ticker label (e.g.
     * "Looking up TapRadio for jazz"). Auto-fades after a short delay
     * on the consumer side.
     *
     * Activity impl: existing `chatFragment.showHeartbeat(label, ...)`.
     * Service impl: HudStateBridge.update { it.copy(notification = label) }
     *   or a future heartbeat slot.
     */
    fun recordHudFunctionTicker(label: String)
}
