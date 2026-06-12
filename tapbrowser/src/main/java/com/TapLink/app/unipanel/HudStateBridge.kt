package com.TapLink.app.unipanel

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * Unipanel v2 — process-local bridge for the voice / Gemini-Live HUD
 * state that currently lives on visionclaw MainActivity views
 * (R.id.listening_overlay, R.id.listening_transcript,
 * R.id.voice_oscilloscope, R.id.hud_notification).
 *
 * Phase 1 of the Service refactor: this bridge is introduced empty.
 * No publisher yet, no subscriber yet — it exists as the
 * compile-time contract that subsequent phases will fill in. The
 * goal is to give the future GeminiVoiceService a stateless,
 * cross-module way to surface listening state without needing an
 * Activity context.
 *
 * Why a single bridge for four concerns: every one of these fields
 * is published-from the same code path (the AudioRecord read loop
 * + the LiveSessionListener inside MainActivity), and tapbrowser
 * needs to render them in lockstep on the unipanel overlay (a
 * "listening" pulse, the user's last-heard partial transcript, the
 * oscilloscope, and any one-line HUD notice). Bundling them keeps
 * the subscriber side from racing on four independent observables.
 *
 * Threading mirrors [ChatCardBridge] / [CameraStateBridge]: state
 * writes are atomic, listeners fire on the publisher's thread, UI
 * consumers must hop to main themselves.
 *
 * Future phases (see docs/UNIPANEL_V2_SERVICE_REFACTOR.md):
 *   • Phase 2: GeminiVoiceService becomes the publisher.
 *   • Phase 3: unipanel overlay subscribes and renders.
 *   • Phase 4: the corresponding R.id.* views in activity_main are
 *     deleted along with the chat panel.
 */
object HudStateBridge {

    /**
     * Coarse phase of the voice pipeline. Drives whether the overlay
     * shows "listening", "thinking", or nothing at all. Mirrors
     * MainActivity.GeminiLiveState but exposed as an enum here so the
     * tapbrowser module doesn't depend on visionclaw's package layout.
     */
    enum class VoicePhase { IDLE, LISTENING, THINKING, FOLLOW_UP }

    /**
     * Color hint for the oscilloscope bar. The current visionclaw
     * implementation alternates between a "user speaking" red and a
     * "model speaking" blue; we keep that split here.
     */
    enum class OscilloscopeChannel { USER, MODEL }

    /**
     * Connection status as understood by the chat HUD. Maps 1:1 to
     * ChatPanelFragment.ConnectionStatus today; the enum lives here
     * so the Service / tapbrowser side don't need to import the chat
     * fragment.
     */
    enum class ConnectionStatus {
        IDLE,
        CONNECTING,
        GEMINI_CONNECTED,
        TOOLS_READY,
        DEGRADED,
        ERROR
    }

    enum class GatewayStatus { HIDDEN, GOOD, BAD }

    /**
     * One row of the dim-HUD notification bell list. Mirrors the shape
     * of visionclaw's NotificationCenter entries — the Service maps
     * them field-for-field into this module-neutral type so tapbrowser
     * can render the bell list without depending on the app module.
     *
     * • [id] — stable identity for the entry (dedupe / DiffUtil key).
     * • [source] — origin enum name from NotificationCenter (e.g.
     *   "GMAIL", "SYSTEM"); consumers treat it as an opaque label.
     * • [title] / [message] — the two display lines.
     * • [timestampMs] — wall-clock arrival time, epoch millis.
     */
    data class HudNotificationEntry(
        val id: String,
        val source: String,
        val title: String,
        val message: String,
        val timestampMs: Long
    )

    /**
     * Snapshot of everything the overlay needs to render the voice /
     * Live HUD. Immutable; mutate by publishing a new [State].
     *
     * • [phase] — overall voice loop phase. Determines whether the
     *   listening overlay is visible at all.
     * • [transcript] — the user's currently-being-recognized utterance,
     *   or null if there's nothing to display.
     * • [oscilloscopeLevel] — 0.0 to 1.0 amplitude for the bar / wave
     *   in the overlay. Zero means "draw the bar at rest". Negative
     *   is treated as zero by consumers.
     * • [oscilloscopeChannel] — which color to draw the bar in.
     * • [connection] — current connection status pill.
     * • [notification] — a one-line transient HUD message, or null.
     *   Consumers are responsible for fading it out themselves; the
     *   bridge does NOT time it out.
     */
    data class State(
        val phase: VoicePhase = VoicePhase.IDLE,
        val transcript: String? = null,
        val oscilloscopeLevel: Float = 0f,
        val oscilloscopeChannel: OscilloscopeChannel = OscilloscopeChannel.USER,
        val connection: ConnectionStatus = ConnectionStatus.IDLE,
        val notification: String? = null,
        val calendarSummary: String = "",
        val tasksSummary: String = "",
        val newsSummary: String = "",
        /**
         * Comma-separated HUD card order from the companion app
         * ("calendar,tasks,news"). Drives the tiered HUD panel's row
         * order so the glasses match the user's companion preference.
         */
        val hudDisplayOrder: String = "calendar,tasks,news",
        val airQualityText: String? = null,
        val airQualityValue: Int? = null,
        val radioStation: String? = null,
        val radioPlaying: Boolean = false,
        val heartbeatMessage: String? = null,
        val heartbeatPersistent: Boolean = false,
        val heartbeatShouldScroll: Boolean = true,
        val openClawStatus: GatewayStatus = GatewayStatus.HIDDEN,
        val hermesStatus: GatewayStatus = GatewayStatus.HIDDEN,
        // True while a Hermes / TapClaw / research agent query is in flight.
        // The persistent agent-status ticker poll skips publishing its idle
        // status line while this is set, so an in-flight query's progress (and
        // the "Asking <agent>…" line) isn't clobbered to look idle.
        val agentBusy: Boolean = false,
        /**
         * The bell list: NotificationCenter's entries mirrored across the
         * module boundary by the Service, newest-first. Empty when there's
         * nothing to show (or no publisher running yet).
         */
        val notifications: List<HudNotificationEntry> = emptyList(),
        /**
         * Unread badge count for the bell icon. Cleared by the publisher
         * after the [onNotificationsSeen] callback fires (i.e. when the
         * unipanel bell list is opened), not by consumers directly.
         */
        val notificationUnread: Int = 0
    )

    /**
     * Callback the unipanel HUD invokes when the bell list is opened, so
     * the publisher (the Service, via NotificationCenter.markAllSeen())
     * can zero the unread badge — the same semantics as the visionclaw
     * chat panel opening its list. Volatile: registered from the Service
     * thread, invoked from the UI thread.
     */
    @Volatile
    private var onNotificationsSeen: (() -> Unit)? = null

    /** Currently registered bell-opened callback, or null if none. */
    fun getOnNotificationsSeen(): (() -> Unit)? = onNotificationsSeen

    /**
     * Register (or clear, with null) the bell-opened callback. Last
     * writer wins — there is exactly one publisher at a time.
     */
    fun setOnNotificationsSeen(callback: (() -> Unit)?) {
        onNotificationsSeen = callback
    }

    private val state = AtomicReference(State())
    private val listeners = CopyOnWriteArrayList<(State) -> Unit>()

    /**
     * Replace the published state. Triggers every registered listener
     * synchronously on the calling thread.
     */
    fun publish(next: State) {
        state.set(next)
        for (l in listeners) {
            try {
                l(next)
            } catch (_: Throwable) {
                // Misbehaving listeners must never break the publisher.
            }
        }
    }

    /**
     * Apply a transform to the current state and publish the result.
     * Convenient when the caller only wants to bump one field — e.g.
     * `update { it.copy(oscilloscopeLevel = peak) }`.
     */
    inline fun update(transform: (State) -> State) {
        publish(transform(current()))
    }

    /** Most recently published snapshot. */
    fun current(): State = state.get()

    /**
     * Subscribe. The listener fires once synchronously with the
     * current state, then on every future [publish]. Returns an
     * [AutoCloseable] for lifecycle-tied unsubscription.
     */
    fun observe(listener: (State) -> Unit): AutoCloseable {
        listeners.add(listener)
        try {
            listener(state.get())
        } catch (_: Throwable) {
            // ditto.
        }
        return AutoCloseable { listeners.remove(listener) }
    }
}
