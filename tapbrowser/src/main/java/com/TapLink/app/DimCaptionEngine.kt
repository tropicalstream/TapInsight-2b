package com.TapLinkX3.app

import android.os.SystemClock
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Radio song lyrics for the dim-mode caption slot.
 *
 * When TapRadio learns what song is playing — either from ICY stream metadata
 * ("Artist - Title") or from a manual AudD identification — this engine looks
 * up synced lyrics on LRCLIB (free, no API key) and serves the lyric line for
 * the current playback position. MainActivity polls [radioLine] from the dim
 * caption ticker, gated by the companion "Radio song lyrics" toggle
 * (prefs key "dim_captions_radio", default on).
 *
 * Sync anchoring: ICY track changes anchor t=0 at the moment the metadata
 * arrives (approximate — streams announce at song start). A manual AudD match
 * is better: AudD reports the timecode within the track where the sample
 * matched, so [onManualSongId] anchors mid-song (the caller adds the
 * recognition round-trip time to the offset). If ICY metadata later names the
 * same song, the more accurate AudD anchor is kept rather than restarting.
 *
 * Limitation: LRCLIB needs both artist AND title, so title-only matches show
 * nothing.
 */
object DimCaptionEngine {

    private const val TAG = "DimCaptionEngine"

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** Identity of the track the current cues belong to ("Artist - Title"). */
    @Volatile private var lyricTrackKey = ""

    /** Parsed synced lyrics for the current track, or null if none (yet). */
    @Volatile private var lyricCues: List<Cue>? = null

    /** Uptime instant corresponding to position 0:00 of the current track. */
    @Volatile private var lyricAnchorUptimeMs = 0L

    data class Cue(val t0: Long, val t1: Long, val text: String)

    /**
     * ICY stream metadata announced a (possibly new) track. Expected form is
     * "Artist - Title"; anything else clears the current lyrics.
     */
    fun onRadioTrack(rawTitle: String) {
        val key = rawTitle.trim()
        if (key.isBlank() || key == lyricTrackKey) return
        lyricTrackKey = key
        lyricCues = null
        lyricAnchorUptimeMs = SystemClock.uptimeMillis()
        val dash = key.indexOf(" - ")
        if (dash <= 0) return
        val artist = key.substring(0, dash).trim()
        val track = key.substring(dash + 3).trim()
        if (artist.isBlank() || track.isBlank()) return
        fetchLrclibLyrics(artist, track, key)
    }

    /**
     * A manual song identification (AudD) succeeded. [offsetIntoSongMs] is how
     * far into the song playback already is — AudD's matched timecode plus the
     * recognition round-trip — so lyrics can anchor mid-song. If the same song
     * is already loaded (e.g. ICY named it first), only the anchor is refined.
     */
    fun onManualSongId(artist: String?, title: String, offsetIntoSongMs: Long) {
        val track = title.trim()
        if (track.isBlank()) return
        val cleanArtist = artist?.trim() ?: ""
        val key = if (cleanArtist.isNotBlank()) "$cleanArtist - $track" else track
        val anchor = SystemClock.uptimeMillis() - offsetIntoSongMs.coerceAtLeast(0L)
        if (key == lyricTrackKey && lyricCues != null) {
            // Same song already loaded — keep the cues, adopt the better anchor.
            if (offsetIntoSongMs > 0) {
                lyricAnchorUptimeMs = anchor
            }
        } else {
            lyricTrackKey = key
            lyricCues = null
            lyricAnchorUptimeMs = anchor
            if (cleanArtist.isBlank()) return // LRCLIB needs artist AND title
            fetchLrclibLyrics(cleanArtist, track, key)
        }
    }

    /** The lyric line for right now, or null when there's nothing to show. */
    fun radioLine(): String? {
        val cues = lyricCues ?: return null
        return lineAt(cues, SystemClock.uptimeMillis() - lyricAnchorUptimeMs)
    }

    private fun fetchLrclibLyrics(artist: String, track: String, key: String) {
        Thread {
            try {
                val url = "https://lrclib.net/api/get?artist_name=" + URLEncoder.encode(artist, "UTF-8") +
                    "&track_name=" + URLEncoder.encode(track, "UTF-8")
                val body = httpGet(url, 400000) ?: return@Thread
                val synced = JSONObject(body).optString("syncedLyrics", "")
                if (synced.isNotBlank() && lyricTrackKey == key) {
                    val lrc = parseLrc(synced)
                    if (lrc.isNotEmpty()) {
                        lyricCues = lrc
                        DebugLog.d(TAG, "LRCLIB lyrics: ${lrc.size} lines for '$key'")
                    }
                }
            } catch (e: Exception) {
                DebugLog.d(TAG, "LRCLIB lookup skipped/failed for '$key': ${e.message}")
            }
        }.start()
    }

    private fun lineAt(cues: List<Cue>?, posMs: Long): String? {
        if (cues.isNullOrEmpty()) return null
        var lo = 0
        var hi = cues.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = cues[mid]
            if (c.t0 > posMs) {
                hi = mid - 1
            } else {
                if (c.t1 >= posMs) return c.text
                lo = mid + 1
            }
        }
        return null
    }

    @Throws(IOException::class)
    private fun httpGet(url: String, maxChars: Int): String? {
        val req = Request.Builder().url(url).header("User-Agent", "TapInsight/1.1.2").build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val len = resp.body?.contentLength() ?: -1L
            if (len > 8000000) return null
            return resp.body?.string()?.take(maxChars)
        }
    }

    /**
     * Parse LRC-format synced lyrics into cues. Handles multiple timestamps
     * per line and 1-3 digit fractional parts. Each cue ends where the next
     * begins (or 8 seconds later for the final line).
     */
    private fun parseLrc(lrc: String): List<Cue> {
        val stamped = ArrayList<Pair<Long, String>>()
        val re = Regex("\\[(\\d{1,2}):(\\d{2})(?:[.:](\\d{1,3}))?]")
        for (line in lrc.split('\n')) {
            val matches = re.findAll(line).toList()
            if (matches.isEmpty()) continue
            val text = line.substring(matches.last().range.last + 1).trim()
            if (text.isEmpty()) continue
            for (m in matches) {
                val mm = m.groupValues[1].toLongOrNull() ?: continue
                val ss = m.groupValues[2].toLongOrNull() ?: continue
                val fracRaw = m.groupValues[3]
                val frac = when (fracRaw.length) {
                    0 -> 0L
                    1 -> (fracRaw.toLongOrNull() ?: 0L) * 100
                    2 -> (fracRaw.toLongOrNull() ?: 0L) * 10
                    else -> fracRaw.take(3).toLongOrNull() ?: 0L
                }
                stamped.add(60000L * mm + 1000L * ss + frac to text)
            }
        }
        if (stamped.size > 1) stamped.sortBy { it.first }
        return stamped.mapIndexed { index, (t0, text) ->
            val t1 = if (index + 1 < stamped.size) stamped[index + 1].first else t0 + 8000L
            Cue(t0, t1, text)
        }
    }
}
