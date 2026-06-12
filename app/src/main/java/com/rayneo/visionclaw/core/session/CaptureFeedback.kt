package com.rayneo.visionclaw.core.session

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.SystemClock
import com.TapLink.app.unipanel.HudStateBridge

/**
 * Single source of truth for "image captured / delivered" feedback.
 *
 * The recurring complaint was that there's no reliable signal for WHEN a camera
 * frame is actually grabbed for the AI and WHEN it has actually reached its
 * destination (Gemini, Hermes, the agent workspace). Different code paths
 * (Service voice pipeline, Hermes tool, OpenClaw client) all need to emit the
 * same cue, so it lives here as a context-free singleton rather than being
 * duplicated per class. Each event is a short tone plus a transient HUD note;
 * "captured" and "delivered" use distinct tones so they're audibly different.
 *
 * All methods are best-effort and never throw — feedback must never break the
 * thing it's reporting on. Debounced so a burst of calls can't machine-gun.
 */
object CaptureFeedback {

    @Volatile private var tone: ToneGenerator? = null
    @Volatile private var lastCapturedMs = 0L
    @Volatile private var lastDeliveredMs = 0L

    private const val DEBOUNCE_MS = 1_200L
    private const val TONE_VOLUME = 75

    @Synchronized
    private fun tone(): ToneGenerator? {
        tone?.let { return it }
        val t = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, TONE_VOLUME) }.getOrNull()
        tone = t
        return t
    }

    /**
     * A frame has just been grabbed for the AI — the user can stop holding the
     * shot steady. Single short beep.
     */
    fun captured() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastCapturedMs < DEBOUNCE_MS) return
        lastCapturedMs = now
        runCatching { HudStateBridge.update { it.copy(notification = "Got it — capturing the image…") } }
        runCatching { tone()?.startTone(ToneGenerator.TONE_PROP_BEEP, 120) }
    }

    /**
     * A frame has actually reached [destination] (e.g. "Gemini", "Hermes",
     * "your workspace"). Distinct acknowledgement tone so it's clearly the
     * "done / delivered" event, not the "captured" one.
     */
    fun delivered(destination: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastDeliveredMs < DEBOUNCE_MS) return
        lastDeliveredMs = now
        runCatching { HudStateBridge.update { it.copy(notification = "Image delivered to $destination.") } }
        runCatching { tone()?.startTone(ToneGenerator.TONE_PROP_ACK, 180) }
    }

    /** Release the shared tone generator (e.g. on full app teardown). */
    fun release() {
        runCatching { tone?.release() }
        tone = null
    }
}
