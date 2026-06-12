package com.TapLinkX3.app

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Tiny, dependency-free writer that appends canonical URLs observed by the
 * TapBrowser WebView into the same SharedPreferences store the TapInsight
 * companion reads (`last_url_store` / `entries`).
 *
 * Why duplicate the schema instead of depending on the visionclaw app
 * module? The tapbrowser module is a sibling library — app depends on it,
 * not the other way around. Duplicating a ~30-line writer is cheaper than
 * creating a circular dependency just to share a small JSON shape.
 *
 * The JSON shape MUST stay in sync with
 * `core/storage/LastUrlStore.Entry.toJson()`. If that file changes,
 * mirror the change here.
 */
internal object LastUrlBridge {
    private const val TAG = "LastUrlBridge"
    private const val PREFS_NAME = "last_url_store"
    private const val KEY_ENTRIES = "entries"
    private const val MAX_ENTRIES = 12

    /** Classifier mirrors LastUrlStore.UrlKind.classify */
    private fun classify(url: String): String {
        val u = url.trim().lowercase(Locale.US)
        if (u.startsWith("file:///android_asset/")) return "ASSET"
        if (u.contains("youtube.com") || u.contains("youtu.be")) {
            val canonicalWatch = Regex(
                """https?://(?:www\.|m\.)?youtu(?:be\.com/watch\?(?:[^#\s]*&)?v=|\.be/)[A-Za-z0-9_\-]{11}"""
            )
            if (canonicalWatch.containsMatchIn(u)) return "YOUTUBE_VIDEO"
            if (u.contains("/results?search_query=") || u.contains("/results?q=")) return "YOUTUBE_SEARCH"
            return "YOUTUBE_OTHER"
        }
        if (u.contains("open.spotify.com") || u.contains("spotify:")) return "SPOTIFY_MEDIA"
        return "WEB_PAGE"
    }

    /**
     * Record a URL that the TapBrowser has committed to (onPageFinished or
     * a confirmed video navigation). Safe to call on any thread; swallows
     * errors so a bad write can never block page rendering.
     */
    fun record(context: Context?, url: String?, title: String? = null) {
        if (context == null) return
        val clean = (url ?: "").trim()
        if (clean.isBlank()) return
        if (clean.startsWith("about:blank")) return
        if (clean.startsWith("data:")) return
        val kind = classify(clean)
        runCatching {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val existing = prefs.getString(KEY_ENTRIES, null)
            val arr = if (existing.isNullOrBlank()) JSONArray() else JSONArray(existing)

            // Collapse consecutive duplicates — same URL in head position just
            // gets its timestamp bumped.
            val head = arr.optJSONObject(0)
            val now = System.currentTimeMillis()
            if (head != null && head.optString("url") == clean) {
                head.put("savedAtMs", now)
                if (!title.isNullOrBlank()) head.put("title", title)
            } else {
                val entry = JSONObject()
                    .put("url", clean)
                    .put("title", title ?: JSONObject.NULL)
                    .put("kind", kind)
                    .put("savedAtMs", now)
                    .put("topic", JSONObject.NULL)

                val shifted = JSONArray()
                shifted.put(entry)
                val limit = minOf(arr.length(), MAX_ENTRIES - 1)
                for (i in 0 until limit) shifted.put(arr.get(i))
                // Replace arr's content by writing shifted back.
                prefs.edit().putString(KEY_ENTRIES, shifted.toString()).apply()
                Log.d(TAG, "record kind=$kind url=${clean.take(120)}")
                return
            }
            prefs.edit().putString(KEY_ENTRIES, arr.toString()).apply()
            Log.d(TAG, "record (refresh head) kind=$kind url=${clean.take(120)}")
        }.onFailure {
            Log.w(TAG, "record failed: ${it.message}")
        }
    }
}
