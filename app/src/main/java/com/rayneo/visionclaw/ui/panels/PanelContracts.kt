package com.rayneo.visionclaw.ui.panels

/**
 * Contract for HUD panels that receive trackpad and head-tracking input.
 * Implemented by ChatPanelFragment and WebPanelFragment.
 */
interface TrackpadPanel {
    /** Scroll the panel content. Returns true if consumed. */
    fun onTrackpadScroll(deltaY: Float): Boolean

    /**
     * Full pan callback with both axis deltas.
     * Default implementation routes to vertical scroll only.
     */
    fun onTrackpadPan(deltaX: Float, deltaY: Float): Boolean = onTrackpadScroll(deltaY)

    /** Activate/select the currently highlighted target. Returns true if consumed. */
    fun onTrackpadSelect(): Boolean = false

    /** Optional double-tap hook for panel-specific actions. */
    fun onTrackpadDoubleTap(): Boolean = false

    /** Inject dictated text from hold-to-speak. Returns true if consumed. */
    fun onTextInputFromHold(text: String): Boolean

    /** Shift panel position based on head yaw for parallax effect. */
    fun onHeadYaw(yawDegrees: Float)

    /** Return a readable summary of this panel's content (for TTS readback). */
    fun getReadableText(): String
}

/** Contracts for TapClaw panels. */
interface PanelContracts {

    interface WebPanel {
        fun loadUrl(url: String)
        fun goBack(): Boolean
        fun goForward(): Boolean
        fun reload()
        fun getCurrentUrl(): String?
        fun getTitle(): String?
    }
}
