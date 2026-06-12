package com.rayneo.visionclaw.core.input

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.hypot

/**
 * TrackpadGestureEngine – RayNeo X3 Pro temple-trackpad gesture handler.
 *
 * Gesture map:
 *   Short tap   (<300 ms)   → standard click (after double-tap window)
 *   Double-tap               → panel toggle callback
 *   Long tap    (≥600 ms)   → long-tap callback (HUD notification list)
 *   Scroll/Swipe → forwarded to active panel (deltaX/deltaY)
 *   Side awareness → track LEFT vs RIGHT half for context
 */

enum class TouchSide {
    LEFT,
    RIGHT
}

class TrackpadGestureEngine {

    companion object {
        private const val TAG = "TrackpadGesture"

        /** Upper bound for a "short tap" (click). */
        const val SHORT_TAP_MAX_MS = 300L

        /** Window used to distinguish single tap from double tap. */
        const val DOUBLE_TAP_WINDOW_MS = 280L

        /** Lower bound for a "long tap" (press-and-hold without movement). */
        const val LONG_TAP_MIN_MS = 600L

        /** If moved beyond this distance, do not treat gesture as tap. */
        private const val TAP_MOVE_TOLERANCE_RATIO = 0.04f
        private const val TAP_MOVE_TOLERANCE_MIN_PX = 18f
        private const val TAP_MOVE_GUARD_MS = 150L
        private const val TAP_MOVE_VELOCITY_GUARD_PX_PER_MS = 10f

        /** Disabled for X3 trackpad reliability; raw coords are not always screen-normalized. */
        private const val EDGE_DEADZONE_PX = 0f
    }

    // ── Callbacks ────────────────────────────────────────────────────────
    /** Fired for a short tap (<300 ms) after the double-tap window expires. */
    var onShortTap: (() -> Unit)? = null

    /** Fired when two short taps occur within [DOUBLE_TAP_WINDOW_MS]. */
    var onDoubleTap: (() -> Unit)? = null

    /** Fired for a stationary press held for at least [LONG_TAP_MIN_MS]. */
    var onLongTap: (() -> Unit)? = null

    /** Scroll/swipe callback for forwarding trackpad motion to active panel. */
    var onScroll: ((deltaX: Float, deltaY: Float) -> Unit)? = null

    /** Called when touch is detected on LEFT or RIGHT half of trackpad. */
    var onSideTouch: ((side: TouchSide) -> Unit)? = null

    // ── Internal state ───────────────────────────────────────────────────
    private var touchDownTimeMs = 0L
    private var isTracking = false
    private var lastTapUpTimeMs = 0L
    private var pendingSingleTap = false

    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var downTouchX = 0f
    private var downTouchY = 0f
    private var movedTooFarForTap = false
    private var screenWidthPx = 2000
    private var screenHeightPx = 1200
    private var screenHalfWidth = 1000
    private var gestureBlockedByDeadzone = false
    private var lastScrollEventMs = 0L
    private var lastMoveEventMs = 0L
    private var peakMoveVelocityPxPerMs = 0f

    private val handler = Handler(Looper.getMainLooper())

    private val singleTapRunnable = Runnable {
        if (pendingSingleTap) {
            pendingSingleTap = false
            onShortTap?.invoke()
            Log.d(TAG, "Single tap confirmed after double-tap window")
        }
    }

    // ── Public API ───────────────────────────────────────────────────────

    /**
     * Set the screen width to determine left/right half for side awareness.
     * Call this once during initialization with the trackpad or screen width.
     */
    fun setScreenSize(width: Int, height: Int) {
        screenWidthPx = width.coerceAtLeast(1)
        screenHeightPx = height.coerceAtLeast(1)
        screenHalfWidth = screenWidthPx / 2
        Log.d(TAG, "Screen size set to ${screenWidthPx}x${screenHeightPx}, half-width: $screenHalfWidth")
    }

    fun setScreenWidth(width: Int) {
        setScreenSize(width, screenHeightPx)
    }

    private fun isInEdgeDeadzone(x: Float, y: Float): Boolean {
        if (EDGE_DEADZONE_PX <= 0f) return false
        return x <= EDGE_DEADZONE_PX ||
            y <= EDGE_DEADZONE_PX ||
            x >= screenWidthPx - EDGE_DEADZONE_PX ||
            y >= screenHeightPx - EDGE_DEADZONE_PX
    }

    /**
     * Feed raw [MotionEvent]s from the trackpad.
     * Returns `true` if the engine consumed the event; `false` if system should handle it.
     */
    fun onTouchEvent(event: MotionEvent): Boolean {
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (isInEdgeDeadzone(event.x, event.y)) {
                    gestureBlockedByDeadzone = true
                    isTracking = false
                    Log.d(TAG, "Touch DOWN ignored by edge deadzone at (${event.x}, ${event.y})")
                    return true
                }
                gestureBlockedByDeadzone = false
                touchDownTimeMs = SystemClock.uptimeMillis()
                isTracking = true

                lastTouchX = event.x
                lastTouchY = event.y
                downTouchX = event.x
                downTouchY = event.y
                movedTooFarForTap = false
                lastScrollEventMs = 0L
                lastMoveEventMs = SystemClock.uptimeMillis()
                peakMoveVelocityPxPerMs = 0f

                // Detect which half of trackpad was touched
                val side = if (event.x < screenHalfWidth) TouchSide.LEFT else TouchSide.RIGHT
                onSideTouch?.invoke(side)

                Log.d(TAG, "Touch DOWN at (${event.x}, ${event.y}) — side: $side")
                true
            }

            MotionEvent.ACTION_MOVE -> {
                if (gestureBlockedByDeadzone) {
                    return true
                }
                if (isTracking) {
                    val now = SystemClock.uptimeMillis()
                    val deltaX = event.x - lastTouchX
                    val deltaY = event.y - lastTouchY
                    val dtMs = (now - lastMoveEventMs).coerceAtLeast(1L).toFloat()
                    val velocity = hypot(deltaX.toDouble(), deltaY.toDouble()).toFloat() / dtMs
                    if (velocity > peakMoveVelocityPxPerMs) {
                        peakMoveVelocityPxPerMs = velocity
                    }
                    lastMoveEventMs = now

                    // Forward scroll deltas to active panel
                    onScroll?.invoke(deltaX, deltaY)
                    if (deltaX != 0f || deltaY != 0f) {
                        lastScrollEventMs = now
                    }

                    val totalMove = hypot(event.x - downTouchX, event.y - downTouchY)
                    if (!movedTooFarForTap && totalMove > tapMoveTolerancePx()) {
                        movedTooFarForTap = true
                    }

                    lastTouchX = event.x
                    lastTouchY = event.y

                    Log.d(TAG, "Touch MOVE delta: ($deltaX, $deltaY)")
                }
                true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (gestureBlockedByDeadzone) {
                    gestureBlockedByDeadzone = false
                    return true
                }
                if (!isTracking) {
                    // Ignore stray UP/CANCEL events when no hold is active.
                    return false
                }

                val elapsed = SystemClock.uptimeMillis() - touchDownTimeMs
                isTracking = false
                val now = SystemClock.uptimeMillis()

                val recentMove = lastScrollEventMs > 0L && (now - lastScrollEventMs) <= TAP_MOVE_GUARD_MS
                val fastMove = peakMoveVelocityPxPerMs >= TAP_MOVE_VELOCITY_GUARD_PX_PER_MS
                if (movedTooFarForTap || recentMove || fastMove) {
                    Log.d(TAG, "Touch UP after movement-heavy gesture (${elapsed}ms) — cancelling tap")
                    return true
                }

                when {
                    elapsed < SHORT_TAP_MAX_MS -> {
                        val now = SystemClock.uptimeMillis()
                        val isDoubleTap = pendingSingleTap &&
                            (now - lastTapUpTimeMs <= DOUBLE_TAP_WINDOW_MS)

                        if (isDoubleTap) {
                            pendingSingleTap = false
                            lastTapUpTimeMs = 0L
                            handler.removeCallbacks(singleTapRunnable)
                            Log.d(TAG, "Double tap (${elapsed}ms) — firing panel toggle")
                            onDoubleTap?.invoke()
                        } else {
                            pendingSingleTap = true
                            lastTapUpTimeMs = now
                            Log.d(TAG, "Short tap candidate (${elapsed}ms) — waiting for double tap window")
                            handler.postDelayed(singleTapRunnable, DOUBLE_TAP_WINDOW_MS)
                        }
                    }
                    elapsed >= LONG_TAP_MIN_MS -> {
                        Log.d(TAG, "Long tap (${elapsed}ms) — firing long-tap callback")
                        onLongTap?.invoke()
                    }
                    else -> {
                        Log.d(TAG, "Late release (${elapsed}ms) — ignored")
                    }
                }
                true
            }

            else -> false
        }
    }

    /**
     * Alternative entry-point for [KeyEvent]-based trackpad input.
     * Maps KEYCODE_BUTTON_A / KEYCODE_DPAD_CENTER to touch-down/up.
     * Returns `true` if consumed.
     */
    fun onKeyEvent(event: KeyEvent): Boolean {
        val isTouchKey = event.keyCode == KeyEvent.KEYCODE_BUTTON_A
                || event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER

        if (!isTouchKey) return false

        return when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    touchDownTimeMs = SystemClock.uptimeMillis()
                    isTracking = true
                    Log.d(TAG, "Key DOWN (trackpad)")
                }
                true
            }

            KeyEvent.ACTION_UP -> {
                if (!isTracking) {
                    return false
                }

                val elapsed = SystemClock.uptimeMillis() - touchDownTimeMs
                isTracking = false

                when {
                    elapsed < SHORT_TAP_MAX_MS -> {
                        val now = SystemClock.uptimeMillis()
                        val isDoubleTap = pendingSingleTap &&
                            (now - lastTapUpTimeMs <= DOUBLE_TAP_WINDOW_MS)

                        if (isDoubleTap) {
                            pendingSingleTap = false
                            lastTapUpTimeMs = 0L
                            handler.removeCallbacks(singleTapRunnable)
                            Log.d(TAG, "Key double tap (${elapsed}ms) — firing panel toggle")
                            onDoubleTap?.invoke()
                        } else {
                            pendingSingleTap = true
                            lastTapUpTimeMs = now
                            Log.d(TAG, "Key short tap candidate (${elapsed}ms) — waiting for double tap window")
                            handler.postDelayed(singleTapRunnable, DOUBLE_TAP_WINDOW_MS)
                        }
                    }
                    elapsed >= LONG_TAP_MIN_MS -> {
                        Log.d(TAG, "Key long tap (${elapsed}ms) — firing long-tap callback")
                        onLongTap?.invoke()
                    }
                    else -> {
                        Log.d(TAG, "Key release (${elapsed}ms) — ignored")
                    }
                }
                true
            }

            else -> false
        }
    }

    /**
     * Feed generic scroll deltas (e.g. ACTION_SCROLL with AXIS_VSCROLL/HSCROLL).
     * Returns true when a non-trivial scroll was forwarded.
     */
    fun onGenericScroll(deltaX: Float, deltaY: Float): Boolean {
        if (deltaX == 0f && deltaY == 0f) return false
        if (isTracking) {
            // Some trackpads emit ACTION_MOVE and ACTION_SCROLL for one physical swipe.
            // Ignore generic scroll while touch tracking to prevent duplicate/flip events.
            return true
        }
        onScroll?.invoke(deltaX, deltaY)
        Log.d(TAG, "Generic SCROLL delta: ($deltaX, $deltaY)")
        return true
    }

    /** Clean up handler callbacks (call from Activity/Fragment onDestroy). */
    fun release() {
        isTracking = false
        handler.removeCallbacksAndMessages(null)
        Log.d(TAG, "Released")
    }

    private fun tapMoveTolerancePx(): Float {
        val dynamic = minOf(screenWidthPx, screenHeightPx) * TAP_MOVE_TOLERANCE_RATIO
        return dynamic.coerceAtLeast(TAP_MOVE_TOLERANCE_MIN_PX)
    }
}
