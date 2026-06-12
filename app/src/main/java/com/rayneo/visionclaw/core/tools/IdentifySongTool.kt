package com.rayneo.visionclaw.core.tools

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.TapLink.app.unipanel.NowPlayingBridge
import com.rayneo.visionclaw.core.storage.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * `identify_song` — answers "what song is this?" while TapRadio is playing.
 *
 * Two layers:
 *   1. AudD acoustic match (optional): if a token is configured, sample the
 *      live stream at request time so "what song is this?" answers the current
 *      audio rather than old station metadata.
 *   2. ICY metadata fallback (free): if no acoustic match is available, use
 *      the live player's current `StreamTitle='Artist - Title'` metadata, but
 *      only when it belongs to the currently-started stream.
 *
 * Returns a spoken-friendly answer for Gemini to read back.
 */
class IdentifySongTool(
    context: Context
) : AiTapTool {

    override val name = "identify_song"

    private val prefs = AppPreferences(context)

    companion object {
        private const val TAG = "IdentifySongTool"
        private const val AUDD_ENDPOINT = "https://api.audd.io/"
        private val http = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    override suspend fun execute(args: Map<String, String>): Result<String> {
        val streamUrl = NowPlayingBridge.streamUrl?.trim().orEmpty()
        val station = NowPlayingBridge.stationName?.trim().orEmpty()
        val playing = NowPlayingBridge.isPlaying
        val token = prefs.auddApiToken.trim()

        Log.d(
            TAG,
            "execute: playing=$playing station='${station.take(48)}' " +
                "hasStream=${streamUrl.isNotBlank()} hasAuddToken=${token.isNotBlank()} " +
                "track='${NowPlayingBridge.trackTitle?.take(80).orEmpty()}'"
        )

        if (!playing || streamUrl.isBlank()) {
            return Result.success(
                "Nothing is playing on TapRadio right now, so there's no song to identify. " +
                    "Start a station first."
            )
        }

        // 1) Optional acoustic match via AudD. When configured, this should win:
        //    it samples the live stream at request time, while station metadata
        //    can lag or stay stuck on the previous track.
        if (token.isNotBlank()) {
            val match = withContext(Dispatchers.IO) { recognizeViaAudD(token, streamUrl) }
            if (match != null) {
                NowPlayingBridge.updateTrack(match.asIcyTitle())
                // Feed the confirmed ID straight into the dim-screen lyric
                // engine, anchored MID-SONG: AudD's timecode says where in the
                // track the sample matched, and the recognition round-trip
                // (time elapsed since the sample was captured) is added on
                // top. If ICY metadata later names the same song, the engine
                // keeps this more accurate anchor rather than restarting
                // (handled inside onManualSongId).
                runCatching {
                    val roundTripMs =
                        if (match.sampledAtUptimeMs > 0L) {
                            SystemClock.uptimeMillis() - match.sampledAtUptimeMs
                        } else {
                            0L
                        }
                    com.TapLinkX3.app.DimCaptionEngine.onManualSongId(
                        match.artist,
                        match.title,
                        match.offsetIntoSongMs + roundTripMs
                    )
                }
                val where = if (station.isNotBlank()) " on $station" else ""
                return Result.success("That's ${match.spokenLabel()}$where.")
            }
            Log.d(TAG, "AudD returned no usable match; falling back to current-stream metadata")
        } else {
            Log.d(TAG, "AudD skipped: no token configured in companion settings")
        }

        // 2) Free metadata fallback: live ICY StreamTitle from the player. Only
        //    trust it if it belongs to this stream and is not ancient; otherwise
        //    we risk reading a previous song as the current one.
        val icyTitle = NowPlayingBridge.trackTitle?.trim()
        val icyAgeMs = System.currentTimeMillis() - NowPlayingBridge.trackUpdatedAtMs
        val icyBelongsToCurrentStream =
            NowPlayingBridge.trackUpdatedAtMs >= NowPlayingBridge.streamStartedAtMs &&
                NowPlayingBridge.trackUpdatedAtMs > 0L
        if (!icyTitle.isNullOrBlank() && icyBelongsToCurrentStream && icyAgeMs <= 30 * 60 * 1000L) {
            val where = if (station.isNotBlank()) " on $station" else ""
            return Result.success("The station metadata says that's \"$icyTitle\"$where.")
        }

        // Nothing worked — be honest about why.
        val stationLabel = if (station.isNotBlank()) "$station" else "this station"
        return Result.success(
            if (token.isBlank()) {
                "$stationLabel isn't broadcasting song info, and no AudD key is configured for acoustic " +
                    "matching, so I can't name this track. You can add an AudD token in settings to enable that."
            } else {
                "I couldn't identify the current track — $stationLabel isn't broadcasting song info and the " +
                    "acoustic match came back empty."
            }
        )
    }

    /**
     * AudD recognition of the live stream URL. Returns the current acoustic
     * match, or null.
     * Best-effort: any failure (quota, network, no match) returns null so the
     * caller can fall through to an honest "couldn't identify" message.
     */
    private fun recognizeViaAudD(token: String, streamUrl: String): SongMatch? {
        return try {
            // Anchor for the lyric handoff: the sample's audio is "now" at
            // capture time, so the recognition round-trip measured from here
            // is added to AudD's in-track timecode by the caller.
            val sampledAt = SystemClock.uptimeMillis()
            val sample = sampleStreamAudio(streamUrl)
            if (sample.isNotEmpty()) {
                recognizeViaAudDFile(token, sample)?.let {
                    return it.copy(sampledAtUptimeMs = sampledAt)
                }
            } else {
                Log.w(TAG, "AudD sample skipped: no bytes captured from stream")
            }
            recognizeViaAudDUrl(token, streamUrl)?.copy(sampledAtUptimeMs = sampledAt)
        } catch (e: Exception) {
            Log.w(TAG, "AudD recognition failed: ${e.message}")
            null
        }
    }

    /**
     * Capture a short raw audio sample from the same stream ExoPlayer is
     * playing. Some stations reject AudD's server-side URL fetch as "invalid
     * audio", but uploading the bytes we can read locally gives AudD an actual
     * fingerprintable sample.
     */
    private fun sampleStreamAudio(streamUrl: String): ByteArray {
        val startedAt = System.currentTimeMillis()
        val request = Request.Builder()
            .url(streamUrl)
            .header("User-Agent", "TapInsight/1.0")
            .header("Icy-MetaData", "0")
            .build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "AudD sample HTTP ${resp.code} from ${redactedUrlHost(streamUrl)}")
                return ByteArray(0)
            }
            val source = resp.body?.source() ?: return ByteArray(0)
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            val maxBytes = 512 * 1024
            val maxMs = 10_000L
            while (out.size() < maxBytes && System.currentTimeMillis() - startedAt < maxMs) {
                val read = source.read(buffer)
                if (read <= 0L) break
                out.write(buffer, 0, read.toInt())
            }
            val bytes = out.toByteArray()
            Log.d(
                TAG,
                "AudD sample: host=${redactedUrlHost(streamUrl)} http=${resp.code} " +
                    "contentType='${resp.body?.contentType()}' bytes=${bytes.size} " +
                    "elapsedMs=${System.currentTimeMillis() - startedAt}"
            )
            return bytes
        }
    }

    private fun recognizeViaAudDFile(token: String, audioBytes: ByteArray): SongMatch? {
        return try {
            val startedAt = System.currentTimeMillis()
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("api_token", token)
                .addFormDataPart("return", "apple_music,spotify")
                .addFormDataPart(
                    "file",
                    "tapradio-sample.mp3",
                    audioBytes.toRequestBody("audio/mpeg".toMediaType())
                )
                .build()
            val request = Request.Builder().url(AUDD_ENDPOINT).post(body).build()
            http.newCall(request).execute().use { resp ->
                val elapsedMs = System.currentTimeMillis() - startedAt
                val responseText = resp.body?.string().orEmpty()
                Log.d(TAG, "AudD file response: http=${resp.code} elapsedMs=$elapsedMs bytes=${responseText.length}")
                parseAudDResponse(resp.code, responseText)
            }
        } catch (e: Exception) {
            Log.w(TAG, "AudD file recognition failed: ${e.message}")
            null
        }
    }

    private fun recognizeViaAudDUrl(token: String, streamUrl: String): SongMatch? {
        return try {
            val startedAt = System.currentTimeMillis()
            Log.d(TAG, "AudD URL request: urlHost=${redactedUrlHost(streamUrl)}")
            val body = FormBody.Builder()
                .add("api_token", token)
                .add("url", streamUrl)
                .add("return", "apple_music,spotify")
                .build()
            val request = Request.Builder().url(AUDD_ENDPOINT).post(body).build()
            http.newCall(request).execute().use { resp ->
                val elapsedMs = System.currentTimeMillis() - startedAt
                val responseText = resp.body?.string().orEmpty()
                Log.d(TAG, "AudD URL response: http=${resp.code} elapsedMs=$elapsedMs bytes=${responseText.length}")
                parseAudDResponse(resp.code, responseText)
            }
        } catch (e: Exception) {
            Log.w(TAG, "AudD URL recognition failed: ${e.message}")
            null
        }
    }

    private fun parseAudDResponse(httpCode: Int, responseText: String): SongMatch? {
        if (httpCode !in 200..299) {
            Log.w(TAG, "AudD HTTP $httpCode: ${responseText.take(240)}")
            return null
        }
        val json = JSONObject(responseText)
        val status = json.optString("status")
        if (status != "success") {
            Log.w(TAG, "AudD status=$status error='${json.optString("error").take(160)}'")
            return null
        }
        val result = json.optJSONObject("result")
        if (result == null) {
            Log.d(TAG, "AudD success with no result")
            return null
        }
        val title = result.optString("title").trim()
        val artist = result.optString("artist").trim()
        val offsetMs = parseTimecodeMs(result.optString("timecode"))
        Log.d(TAG, "AudD match: title='${title.take(80)}' artist='${artist.take(80)}' offsetMs=$offsetMs")
        return when {
            title.isNotBlank() && artist.isNotBlank() -> SongMatch(title, artist, offsetMs)
            title.isNotBlank() -> SongMatch(title, null, offsetMs)
            else -> null
        }
    }

    /**
     * AudD's `timecode` is a "mm:ss" (occasionally "h:mm:ss") string saying
     * where IN THE TRACK the sample matched. Parse to millis; 0 when absent
     * or malformed — the lyric engine treats 0 as "anchor at track start".
     */
    private fun parseTimecodeMs(tc: String?): Long {
        val cleaned = tc?.trim()?.takeUnless { it.isBlank() } ?: return 0L
        val parts = cleaned.split(':')
        return try {
            when (parts.size) {
                2 -> (parts[0].toLong() * 60 + parts[1].toLong()) * 1000
                3 -> ((parts[0].toLong() * 60 + parts[1].toLong()) * 60 + parts[2].toLong()) * 1000
                else -> 0L
            }
        } catch (e: Exception) {
            0L
        }
    }

    private fun redactedUrlHost(url: String): String {
        return runCatching {
            java.net.URI(url).host ?: "invalid-url"
        }.getOrElse { "invalid-url" }
    }

    private data class SongMatch(
        val title: String,
        val artist: String?,
        /** Where in the TRACK the sample matched (AudD `timecode`), in ms. */
        val offsetIntoSongMs: Long = 0L,
        /** uptimeMillis() when the stream sample capture began — lets the
         *  caller add the recognition round-trip onto the lyric anchor. */
        val sampledAtUptimeMs: Long = 0L
    ) {
        fun spokenLabel(): String =
            if (!artist.isNullOrBlank()) "\"$title\" by $artist" else "\"$title\""

        fun asIcyTitle(): String =
            if (!artist.isNullOrBlank()) "$artist - $title" else title
    }
}
