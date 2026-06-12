package com.TapLinkX3.app

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Native demux of EMBEDDED subtitle tracks for the media library player.
 *
 * media_player.html already handles sidecar caption files (an `.srt`/`.vtt`
 * next to the media file, or a `?srt=` URL parameter) with its own in-page
 * SRT engine. What it cannot do from JavaScript is read subtitle tracks
 * muxed INSIDE the container — MP4 `mov_text` (tx3g) tracks and SRT/VTT
 * streams muxed into MKV/WebM. This module demuxes those natively with
 * [MediaExtractor] and hands the page timed cues in EXACTLY the shape the
 * existing SRT engine produces, so the page-side caption code needs no new
 * render path:
 *
 *     [ { "start": 12.34, "end": 15.6, "text": "line one\nline two" }, ... ]
 *
 * `start`/`end` are SECONDS (fractional), `text` is plain text with markup
 * stripped — the same object shape `parseSrt()` builds in media_player.html,
 * ready to be assigned to its `captions` array.
 *
 * Used automatically when no sidecar caption file exists (the priority
 * order, enforced page/bridge-side, is: `?srt=` param, then sidecar file,
 * then this embedded extraction).
 *
 * Threading: extraction demuxes the whole subtitle track, which on large
 * MKVs takes real time — never call [extract] on the UI thread. The
 * MediaLibraryBridge `@JavascriptInterface` methods already run on the
 * WebView's bridge thread, so the bridge may call [extract] directly;
 * UI-thread callers should use [extractAsync], which runs on this module's
 * own low-priority worker and delivers the result on that worker thread
 * (post back to the UI/WebView thread yourself if needed).
 *
 * Timing caveats (documented, deliberate):
 *  - MP4 `mov_text` writes an explicit EMPTY sample into every gap, so cue
 *    end times are exact.
 *  - Matroska SRT/VTT block durations are not exposed by [MediaExtractor];
 *    a cue's end is approximated as the next cue's start (capped at
 *    [MAX_CUE_HOLD_MS]) or [DEFAULT_CUE_MS] for the last cue.
 */
object EmbeddedCaptionExtractor {

    private const val TAG = "EmbeddedCaptionExtractor"

    /** Subtitle sample payloads are tiny; this is generous headroom. */
    private const val SAMPLE_BUFFER_BYTES = 256 * 1024

    /** Hard cap so a malformed track can't balloon the cue list. */
    private const val MAX_CUES = 4000

    /** Display time for a cue whose true duration is unknowable. */
    private const val DEFAULT_CUE_MS = 4000L

    /** Never let an end-approximated cue linger longer than this. */
    private const val MAX_CUE_HOLD_MS = 7000L

    /**
     * Track mimes accepted as subtitle tracks, as MediaExtractor reports
     * them: SRT muxed in MKV/WebM, WebVTT, and the two spellings Android
     * uses for MP4 mov_text (tx3g) depending on OS version.
     */
    private val SUBTITLE_MIMES = setOf(
        "application/x-subrip",
        "text/vtt",
        "text/3gpp-tt",
        "application/x-quicktime-tx3g"
    )

    /** Looks like a WebVTT cue-settings token, e.g. `align:start`. */
    private val VTT_SETTING = Regex("^(align|line|position|size|vertical|region):\\S+$", RegexOption.IGNORE_CASE)

    private data class Cue(val startMs: Long, val endMs: Long, val text: String)

    private class RawSample(val timeUs: Long, val bytes: ByteArray)

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "EmbeddedCaptionExtractor").apply {
            priority = Thread.MIN_PRIORITY
            isDaemon = true
        }
    }

    /**
     * Off-thread convenience wrapper around [extract]. [onResult] is invoked
     * on the extractor's worker thread with the cue JSON, or null when the
     * source has no usable embedded subtitle track.
     */
    fun extractAsync(context: Context, source: String, onResult: (String?) -> Unit) {
        executor.execute {
            val json = try {
                extract(context, source)
            } catch (e: Exception) {
                Log.d(TAG, "extractAsync failed for $source: ${e.message}")
                null
            }
            onResult(json)
        }
    }

    /**
     * Blocking extraction. [source] is either an absolute file path (media
     * library files) or a `content:`/`file:` URI string (DCIM entries) —
     * the same dual addressing MediaLibraryBridge uses elsewhere.
     *
     * Returns a JSON array string of `{start, end, text}` cues (seconds,
     * matching media_player.html's SRT engine), or null when there is no
     * subtitle track / nothing decodable. Never throws for ordinary IO or
     * container problems — they are logged and reported as null.
     */
    fun extract(context: Context, source: String): String? {
        val src = source.trim()
        if (src.isEmpty()) return null
        val extractor = MediaExtractor()
        return try {
            if (src.startsWith("content:", ignoreCase = true) || src.startsWith("file:", ignoreCase = true)) {
                extractor.setDataSource(context, Uri.parse(src), null)
            } else {
                extractor.setDataSource(src)
            }
            extractTimedCues(extractor)
        } catch (e: Exception) {
            Log.d(TAG, "extract failed for $src: ${e.message}")
            null
        } finally {
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    // ── demux ────────────────────────────────────────────────────────────

    private fun extractTimedCues(extractor: MediaExtractor): String? {
        val picked = pickSubtitleTrack(extractor)
        if (picked == null) {
            Log.d(TAG, "no embedded subtitle track found (${extractor.trackCount} tracks)")
            return null
        }
        val (trackIndex, format) = picked
        val mime = format.getString(MediaFormat.KEY_MIME)?.lowercase(Locale.US).orEmpty()
        extractor.selectTrack(trackIndex)

        val buffer = ByteBuffer.allocate(SAMPLE_BUFFER_BYTES)
        val samples = ArrayList<RawSample>()
        while (samples.size < MAX_CUES * 2) {
            buffer.clear()
            val size = try {
                extractor.readSampleData(buffer, 0)
            } catch (e: Exception) {
                Log.d(TAG, "readSampleData failed at sample ${samples.size}: ${e.message}")
                break
            }
            if (size < 0) break
            val timeUs = extractor.sampleTime
            if (size > 0 && timeUs >= 0) {
                val bytes = ByteArray(size)
                buffer.rewind()
                buffer.get(bytes, 0, size)
                samples.add(RawSample(timeUs, bytes))
            } else if (size == 0 && timeUs >= 0) {
                // Zero-length samples still matter for mov_text gap handling.
                samples.add(RawSample(timeUs, ByteArray(0)))
            }
            if (!extractor.advance()) break
        }
        if (samples.isEmpty()) {
            Log.d(TAG, "subtitle track $trackIndex ($mime) produced no samples")
            return null
        }

        val cues = when (mime) {
            "text/3gpp-tt", "application/x-quicktime-tx3g" -> decodeTx3g(samples)
            else -> decodeTextSamples(samples, mime)
        }.take(MAX_CUES)
        if (cues.isEmpty()) {
            Log.d(TAG, "subtitle track $trackIndex ($mime) decoded to zero cues")
            return null
        }

        val arr = JSONArray()
        for (cue in cues) {
            arr.put(
                JSONObject()
                    .put("start", cue.startMs / 1000.0)
                    .put("end", cue.endMs / 1000.0)
                    .put("text", cue.text)
            )
        }
        val lang = format.getString(MediaFormat.KEY_LANGUAGE).orEmpty()
        Log.d(TAG, "extracted ${cues.size} cues (track=$trackIndex mime=$mime lang=$lang)")
        return arr.toString()
    }

    /**
     * First subtitle track wins, except an English-tagged track is preferred
     * when the container carries several languages.
     */
    private fun pickSubtitleTrack(extractor: MediaExtractor): Pair<Int, MediaFormat>? {
        var fallback: Pair<Int, MediaFormat>? = null
        for (i in 0 until extractor.trackCount) {
            val format = try { extractor.getTrackFormat(i) } catch (_: Exception) { continue }
            val mime = format.getString(MediaFormat.KEY_MIME)?.lowercase(Locale.US) ?: continue
            if (mime !in SUBTITLE_MIMES) continue
            val lang = format.getString(MediaFormat.KEY_LANGUAGE)?.lowercase(Locale.US).orEmpty()
            if (lang.startsWith("en")) return i to format
            if (fallback == null) fallback = i to format
        }
        return fallback
    }

    // ── decoders ─────────────────────────────────────────────────────────

    /**
     * Matroska-muxed SRT (`application/x-subrip`) and WebVTT (`text/vtt`):
     * each sample payload is the cue text itself (VTT payloads may carry a
     * leading settings/header line, stripped here). Sample time is the cue
     * start; the end is approximated from the next cue's start.
     */
    private fun decodeTextSamples(samples: List<RawSample>, mime: String): List<Cue> {
        val cues = ArrayList<Cue>(samples.size)
        for ((index, sample) in samples.withIndex()) {
            if (sample.bytes.isEmpty()) continue
            var text = String(sample.bytes, Charsets.UTF_8).trim { it <= ' ' }
            if (text.isEmpty()) continue
            if (mime == "text/vtt") text = stripVttCueHeader(text)
            text = stripMarkup(text)
            if (text.isBlank()) continue
            val startMs = sample.timeUs / 1000
            val nextStartMs = samples.getOrNull(index + 1)?.timeUs?.div(1000)
            val endMs = if (nextStartMs != null && nextStartMs > startMs) {
                minOf(nextStartMs, startMs + MAX_CUE_HOLD_MS)
            } else {
                startMs + DEFAULT_CUE_MS
            }
            cues.add(Cue(startMs, endMs, text))
        }
        return cues
    }

    /**
     * MP4 mov_text (tx3g): each sample is a 2-byte big-endian text length
     * followed by that many UTF-8 bytes (style boxes may trail; ignored).
     * Empty-text samples are explicit gap markers, so every cue's end time
     * is exact: the timestamp of whichever sample (empty or not) follows it.
     */
    private fun decodeTx3g(samples: List<RawSample>): List<Cue> {
        val cues = ArrayList<Cue>()
        var open: Cue? = null
        for (sample in samples) {
            val timeMs = sample.timeUs / 1000
            val text = readTx3gText(sample.bytes)
            open?.let { prev ->
                val endMs = if (timeMs > prev.startMs) timeMs else prev.startMs + 500L
                cues.add(prev.copy(endMs = endMs))
                open = null
            }
            if (text.isNotBlank()) {
                open = Cue(timeMs, timeMs + DEFAULT_CUE_MS, stripMarkup(text))
            }
        }
        open?.let { cues.add(it) }
        return cues
    }

    private fun readTx3gText(bytes: ByteArray): String {
        if (bytes.size < 2) return ""
        val declared = ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
        val length = declared.coerceAtMost(bytes.size - 2)
        if (length <= 0) return ""
        return String(bytes, 2, length, Charsets.UTF_8).trim { it <= ' ' }
    }

    // ── text cleanup ─────────────────────────────────────────────────────

    /**
     * Some muxers prefix VTT block payloads with header/settings lines
     * ("WEBVTT", "NOTE …", a timing line, or a cue-settings list like
     * `align:start position:10%`). Drop those; keep the dialogue.
     */
    private fun stripVttCueHeader(raw: String): String {
        val lines = raw.replace("\r\n", "\n").split('\n').toMutableList()
        while (lines.isNotEmpty()) {
            val head = lines.first().trim()
            val isHeader = head.isEmpty() ||
                head.startsWith("WEBVTT", ignoreCase = true) ||
                head.startsWith("NOTE", ignoreCase = true) ||
                head.contains("-->") ||
                head.split(' ').all { it.isNotEmpty() && VTT_SETTING.matches(it) }
            if (isHeader) lines.removeAt(0) else break
        }
        return lines.joinToString("\n").trim()
    }

    /**
     * Strip inline markup the same way media_player.html's parseSrt does
     * (it removes every `<…>` tag), plus the handful of HTML entities that
     * show up in subtitle text. Internal newlines are preserved — the SRT
     * engine keeps them too.
     */
    private fun stripMarkup(raw: String): String {
        var text = raw.replace(Regex("<[^>]*>"), "")
        text = text
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
        // Collapse runs of blank lines but keep single line breaks.
        return text.replace(Regex("\n{3,}"), "\n\n").trim()
    }
}
