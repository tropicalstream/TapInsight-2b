package com.rayneo.visionclaw.core.storage

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Small append-only store of the URLs the user actually opened in TapBrowser /
 * companion panels. Purpose: give us a *ground truth* list of URLs so that
 * when Gemini later says "email me this video" — and cannot be trusted to
 * reproduce the URL from memory — we can look up what the user was actually
 * viewing and substitute it into the outgoing query.
 *
 * Design constraints:
 *   • No thread-heavy persistence; backed by SharedPreferences so reads are
 *     cheap from any tool path.
 *   • Tiny rolling window (MAX_ENTRIES). We do not need a full history.
 *   • Classify each entry by [UrlKind] so lookups like "last YouTube video"
 *     or "last generic web URL" are straightforward.
 *
 * NOTE: This store is deliberately separate from `ReadableArtifactStore`.
 * That store is for rendered artifacts (research reports, TapClaw text
 * results). This one is a thin URL ledger.
 */
class LastUrlStore(context: Context) {

    enum class UrlKind {
        YOUTUBE_VIDEO,    // https://www.youtube.com/watch?v=<id>  |  https://youtu.be/<id>
        YOUTUBE_SEARCH,   // https://www.youtube.com/results?search_query=…
        YOUTUBE_OTHER,    // channel, playlist, subscriptions feed, etc.
        SPOTIFY_MEDIA,    // https://open.spotify.com/...
        WEB_PAGE,         // any other http(s)
        ASSET;            // file:///android_asset/… (our own HTML viewers)

        companion object {
            fun classify(url: String): UrlKind {
                val u = url.trim().lowercase(Locale.US)
                if (u.startsWith("file:///android_asset/")) return ASSET
                if (u.contains("youtube.com") || u.contains("youtu.be")) {
                    if (Regex("""https?://(?:www\.)?youtu(?:be\.com/watch\?(?:[^#\s]*&)?v=|\.be/)[A-Za-z0-9_\-]{11}""").containsMatchIn(u))
                        return YOUTUBE_VIDEO
                    if (u.contains("/results?search_query=") || u.contains("/results?q="))
                        return YOUTUBE_SEARCH
                    return YOUTUBE_OTHER
                }
                if (u.contains("open.spotify.com") || u.contains("spotify:"))
                    return SPOTIFY_MEDIA
                return WEB_PAGE
            }
        }
    }

    data class Entry(
        val url: String,
        val title: String?,
        val kind: UrlKind,
        val savedAtMs: Long,
        /** Optional human-readable tag that helps TapClaw's AI describe the link
         *  ("Seattle Space Needle flyover", "Trust your body cycling podcast"…) */
        val topic: String?
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("url", url)
            .put("title", title ?: JSONObject.NULL)
            .put("kind", kind.name)
            .put("savedAtMs", savedAtMs)
            .put("topic", topic ?: JSONObject.NULL)

        companion object {
            fun fromJson(o: JSONObject): Entry? {
                val url = o.optString("url").trim()
                if (url.isBlank()) return null
                val kind = runCatching { UrlKind.valueOf(o.optString("kind")) }
                    .getOrDefault(UrlKind.classify(url))
                return Entry(
                    url = url,
                    title = o.optString("title").trim().ifBlank { null },
                    kind = kind,
                    savedAtMs = o.optLong("savedAtMs", System.currentTimeMillis()),
                    topic = o.optString("topic").trim().ifBlank { null }
                )
            }
        }
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Record a URL the user actually opened. Safe to call from any thread.
     * URLs that look like tool-internal scaffolding (blank, data URIs,
     * taplink-wrapped duplicates) are ignored.
     */
    fun record(url: String, title: String? = null, topic: String? = null) {
        val clean = url.trim()
        if (clean.isBlank()) return
        // Peel any leading "taplink://" wrapper the ToolDispatcher adds.
        val unwrapped = if (clean.startsWith("taplink://")) clean.removePrefix("taplink://") else clean
        if (unwrapped.startsWith("about:blank")) return

        val kind = UrlKind.classify(unwrapped)
        val entries = load().toMutableList()
        // Deduplicate: if the most recent entry is the same URL, refresh its
        // timestamp instead of pushing a dupe.
        val now = System.currentTimeMillis()
        val head = entries.firstOrNull()
        if (head != null && head.url == unwrapped) {
            entries[0] = head.copy(
                title = title?.takeIf { it.isNotBlank() } ?: head.title,
                topic = topic?.takeIf { it.isNotBlank() } ?: head.topic,
                savedAtMs = now
            )
        } else {
            entries.add(
                0,
                Entry(
                    url = unwrapped,
                    title = title?.takeIf { it.isNotBlank() },
                    kind = kind,
                    savedAtMs = now,
                    topic = topic?.takeIf { it.isNotBlank() }
                )
            )
            while (entries.size > MAX_ENTRIES) entries.removeAt(entries.lastIndex)
        }
        save(entries)
        Log.d(TAG, "record kind=$kind url=${unwrapped.take(120)}")
    }

    /** The most recently recorded URL of any kind, or null if we've never recorded one. */
    fun latest(): Entry? = load().firstOrNull()

    /** Most recent entry matching any of the requested kinds. */
    fun latestOfKinds(vararg kinds: UrlKind): Entry? {
        val set = kinds.toSet()
        return load().firstOrNull { it.kind in set }
    }

    /**
     * "Current media" = the thing the user is most likely referring to when
     * they say "this video" / "what's playing" — YouTube or Spotify first,
     * falling back to the most recent web page if neither is present.
     */
    fun currentMedia(): Entry? =
        latestOfKinds(UrlKind.YOUTUBE_VIDEO, UrlKind.YOUTUBE_SEARCH, UrlKind.SPOTIFY_MEDIA)
            ?: latestOfKinds(UrlKind.WEB_PAGE)

    /** Whether any of the recent entries shares the same YouTube video ID. */
    fun hasSeenYouTubeVideoId(videoId: String): Boolean {
        if (videoId.length != 11) return false
        return load().any { extractYouTubeVideoId(it.url) == videoId }
    }

    /** Look up the first seen canonical URL carrying this YouTube video ID. */
    fun findByYouTubeVideoId(videoId: String): Entry? {
        if (videoId.length != 11) return null
        return load().firstOrNull { extractYouTubeVideoId(it.url) == videoId }
    }

    fun all(): List<Entry> = load()

    fun clear() {
        prefs.edit().remove(KEY_ENTRIES).apply()
    }

    // ── internals ─────────────────────────────────────────────────────

    private fun load(): List<Entry> {
        val raw = prefs.getString(KEY_ENTRIES, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    Entry.fromJson(obj)?.let { add(it) }
                }
            }
        }.getOrElse {
            Log.w(TAG, "Failed to parse LastUrlStore entries: ${it.message}")
            emptyList()
        }
    }

    private fun save(entries: List<Entry>) {
        val arr = JSONArray()
        entries.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_ENTRIES, arr.toString()).apply()
    }

    companion object {
        private const val TAG = "LastUrlStore"
        private const val PREFS_NAME = "last_url_store"
        private const val KEY_ENTRIES = "entries"
        private const val MAX_ENTRIES = 12

        /**
         * Parse a YouTube video ID out of any of the canonical URL shapes
         * YouTube ships. Returns null if the URL doesn't cleanly carry one.
         *
         * Accepted shapes:
         *   • https://www.youtube.com/watch?v=XXXXXXXXXXX
         *   • https://youtu.be/XXXXXXXXXXX
         *   • https://www.youtube.com/embed/XXXXXXXXXXX
         *   • https://www.youtube.com/shorts/XXXXXXXXXXX
         *   • https://m.youtube.com/watch?v=…
         */
        fun extractYouTubeVideoId(url: String): String? {
            val u = url.trim()
            if (u.isBlank()) return null
            val patterns = listOf(
                Regex("""https?://(?:www\.|m\.)?youtube\.com/watch\?(?:[^#\s]*&)?v=([A-Za-z0-9_\-]{11})"""),
                Regex("""https?://(?:www\.|m\.)?youtu\.be/([A-Za-z0-9_\-]{11})"""),
                Regex("""https?://(?:www\.|m\.)?youtube\.com/embed/([A-Za-z0-9_\-]{11})"""),
                Regex("""https?://(?:www\.|m\.)?youtube\.com/shorts/([A-Za-z0-9_\-]{11})""")
            )
            for (p in patterns) {
                val m = p.find(u) ?: continue
                return m.groupValues[1]
            }
            return null
        }

        /**
         * True if the URL parses as a canonical YouTube watch URL with an
         * 11-char video ID. This is the single source of truth for
         * "is this a real YouTube link" checks across the app.
         */
        fun isCanonicalYouTubeVideoUrl(url: String): Boolean =
            extractYouTubeVideoId(url) != null

        /** Whether this URL is on the youtube.com / youtu.be domain at all. */
        fun isAnyYouTubeUrl(url: String): Boolean {
            val u = url.lowercase(Locale.US)
            return u.contains("youtube.com") || u.contains("youtu.be")
        }
    }
}
