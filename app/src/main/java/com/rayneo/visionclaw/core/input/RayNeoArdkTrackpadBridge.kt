package com.rayneo.visionclaw.core.input

import android.app.Activity
import android.util.Log

/**
 * Reflection-based stub for RayNeo low-level trackpad integration.
 * Replace attachIfAvailable() with direct ARDK APIs when the vendor SDK is linked.
 */
class RayNeoArdkTrackpadBridge {

    fun attachIfAvailable(activity: Activity): Boolean {
        return runCatching {
            Class.forName("com.rayneo.ardk.input.TrackpadManager")
            Log.i(TAG, "RayNeo ARDK TrackpadManager detected. Wire low-level listeners here.")
            // Intentionally left as a placeholder to keep the project compiling without ARDK binaries.
            // Expected integration point:
            // val manager = TrackpadManager.getInstance(activity)
            // manager.setOnSwipeListener { ... }
            // manager.setOnDoubleTapListener { ... }
            true
        }.getOrElse {
            Log.w(TAG, "RayNeo ARDK TrackpadManager not found, using MotionEvent fallback")
            false
        }
    }

    companion object {
        private const val TAG = "RayNeoArdkBridge"
    }
}
