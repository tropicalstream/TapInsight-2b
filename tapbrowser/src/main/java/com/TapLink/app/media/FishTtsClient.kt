package com.TapLink.app.media

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * On-glasses Fish.audio TTS client. Mirrors [GlassesTtsClient]'s contract so
 * [MediaLibraryBridge.speakText] can swap engines transparently — same
 * `SynthesisResult` shape, same WAV output, same caching strategy.
 *
 * Architecture:
 *   1. Bridge passes a chunk to [synthesize].
 *   2. We POST to api.fish.audio/v1/tts with `format: "wav"` so we get a
 *      ready-to-play RIFF stream back. Fish.audio supports wav natively
 *      so we don't need the PCM-wrap dance the Gemini client does.
 *   3. Bytes go straight into [TtsCacheStore]; the bridge returns the
 *      virtual /tts/<id>.wav URL and the WebView's <audio> element plays
 *      it via [MediaFileInterceptor].
 *
 * Implementation choices:
 *   * `HttpURLConnection` instead of OkHttp so the tapbrowser library
 *     module avoids a hard dependency on the app module's network layer
 *     (matching [GlassesTtsClient]).
 *   * The Fish API returns raw audio bytes when synthesis succeeds and a
 *     JSON error envelope otherwise. We branch on Content-Type, falling
 *     back to a code-based check if the header is missing.
 *   * Authentication via `Authorization: Bearer <key>` per Fish.audio
 *     docs. The `model` field is sent as a request HEADER (not body),
 *     which is unusual but matches how the Fish v1 API was designed.
 */
class FishTtsClient(
    private val configProvider: () -> FishTtsConfig?
) {

    companion object {
        private const val TAG = "FishTtsClient"
        private const val FISH_TTS_URL = "https://api.fish.audio/v1/tts"
        private const val DEFAULT_MODEL = "s2-pro"
        // MP3 by default — wav is uncompressed (~960 KB for a 30-word
        // chunk, several MB for a 150-word chunk) which dominates the
        // transfer time and decode-start latency on the WebView's
        // <audio> element. MP3 is ~12× smaller for the same audio and
        // the WebView decodes it natively. The companion UI's default
        // is also "mp3" — this value is only used when the user has
        // never explicitly picked a format.
        private const val DEFAULT_FORMAT = "mp3"
        private const val DEFAULT_LATENCY = "balanced"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 60_000
        // Fish handles long inputs natively; cap is purely for our own
        // chunk pipeline so a runaway request can't pin a worker thread.
        // Raised from 4_000 to 12_000 so the steady-state 150-word
        // chunks (typical ~900 chars, but with safety headroom for
        // dialogue-heavy or long-word chunks) fit comfortably. Larger
        // chunks reduce handoff frequency, which is the main cause of
        // perceived "freezing every 10 seconds" on cloud TTS.
        private const val MAX_CHARS = 12_000
    }

    /** Same shape as [GlassesTtsClient.SynthesisResult] so the bridge can
     *  treat both engines identically. */
    sealed class SynthesisResult {
        data class Success(
            val wavBytes: ByteArray,
            val mimeType: String,
            val model: String,
            val sampleRate: Int
        ) : SynthesisResult()

        /** Returned when the active config has no key, no voice id, or
         *  fish_tts is not the active engine. Lets the caller fall back
         *  to Gemini without surfacing a confusing error. */
        object NotConfigured : SynthesisResult()

        data class Error(val message: String) : SynthesisResult()
    }

    /**
     * Synthesize one chunk. Returns immediately with [NotConfigured] if
     * the config provider returns null OR the resolved config doesn't
     * have everything Fish needs to actually synthesize.
     */
    fun synthesize(text: String): SynthesisResult {
        val transcript = text.trim()
        if (transcript.isBlank()) return SynthesisResult.Error("Nothing to speak.")
        if (transcript.length > MAX_CHARS) {
            return SynthesisResult.Error("Chunk too long (${transcript.length} chars); split before calling.")
        }

        val cfg = configProvider() ?: return SynthesisResult.NotConfigured
        if (cfg.apiKey.isBlank()) return SynthesisResult.NotConfigured
        // No reference voice id is allowed (Fish has a default voice), but
        // the whole point of integrating Fish is for the user to pick a
        // specific voice — surface a clear error if they enabled Fish but
        // never finished setup.
        if (cfg.activeVoiceId.isBlank()) {
            return SynthesisResult.Error(
                "No Fish.audio voice selected. Open the companion app's Readout Voice section and pick or save a voice."
            )
        }

        val model = cfg.model.ifBlank { DEFAULT_MODEL }
        val format = cfg.format.ifBlank { DEFAULT_FORMAT }
        val latency = cfg.latency.ifBlank { DEFAULT_LATENCY }

        val payload = JSONObject()
            .put("text", transcript)
            .put("reference_id", cfg.activeVoiceId)
            .put("format", format)
            .put("latency", latency)
            .put("normalize", cfg.normalize)
        // Fish.audio v1 /tts spec: speed and volume live inside a
        // `prosody` object, not at the top level. Sending them flat
        // (the previous version) caused them to be silently ignored,
        // which is why slider changes in the companion app had no
        // audible effect. Build the prosody object only if the user
        // actually changed something away from defaults — otherwise
        // omit it entirely so Fish uses its trained defaults.
        val prosody = JSONObject()
        if (cfg.speed > 0f && cfg.speed != 1.0f) prosody.put("speed", cfg.speed.toDouble())
        if (cfg.volume != 0f) prosody.put("volume", cfg.volume.toDouble())
        if (prosody.length() > 0) payload.put("prosody", prosody)
        // chunk_length tells Fish how to size audio segments inside the
        // returned stream. Per the docs, smaller values reduce TTFA
        // (time-to-first-audio) at the cost of more boundaries inside
        // the MP3. We pass 200 chars (≈ a sentence and a half) which
        // balances first-byte latency vs. internal coherence — Fish
        // streams this as one continuous audio file so we don't see
        // the boundaries on our side, just lower start latency.
        payload.put("chunk_length", 200)

        val conn = (URL(FISH_TTS_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doInput = true
            doOutput = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Authorization", "Bearer ${cfg.apiKey}")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("model", model)
            setRequestProperty("User-Agent", "TapInsight-Glasses/1.0 (+fish-tts)")
        }

        return try {
            conn.outputStream.use { os ->
                OutputStreamWriter(os, Charsets.UTF_8).use { it.write(payload.toString()) }
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                val errBody = conn.errorStream
                    ?.bufferedReader(Charsets.UTF_8)
                    ?.use { it.readText() } ?: ""
                Log.w(TAG, "Fish TTS failed code=$code body=${errBody.take(220)}")
                return SynthesisResult.Error(
                    "Fish.audio HTTP $code: ${summarizeError(errBody)}"
                )
            }
            val ct = conn.contentType ?: ""
            // If Fish ever returned a JSON envelope on the success path
            // (some endpoints wrap audio in base64), bail with a clear
            // error rather than dumping JSON into the WAV cache.
            if (ct.contains("application/json", ignoreCase = true)) {
                val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                return SynthesisResult.Error(
                    "Fish.audio returned JSON instead of audio: ${body.take(160)}"
                )
            }
            val audioBytes = conn.inputStream.use { it.readBytes() }
            if (audioBytes.isEmpty()) {
                return SynthesisResult.Error("Fish.audio returned empty audio.")
            }
            // Sample rate isn't returned in the response body (it's encoded
            // inside the WAV header). Pass 0 so the bridge knows the JS side
            // shouldn't trust it and should let the <audio> element discover
            // the rate from the file itself.
            SynthesisResult.Success(
                wavBytes = audioBytes,
                mimeType = mimeForFormat(format),
                model = "fish:$model",
                sampleRate = 0
            )
        } catch (e: Exception) {
            Log.e(TAG, "Fish TTS exception", e)
            SynthesisResult.Error(e.message ?: e.javaClass.simpleName)
        } finally {
            try { conn.disconnect() } catch (_: Exception) {}
        }
    }

    private fun mimeForFormat(format: String): String = when (format.lowercase()) {
        "mp3" -> "audio/mpeg"
        "opus" -> "audio/opus"
        else -> "audio/wav"
    }

    /** Best-effort prettifier for Fish error envelopes. */
    private fun summarizeError(body: String): String {
        if (body.isBlank()) return "(empty)"
        return try {
            val root = JSONObject(body)
            root.optString("message").trim().takeIf { it.isNotBlank() }
                ?: root.optString("detail").trim().takeIf { it.isNotBlank() }
                ?: body.take(220)
        } catch (_: Exception) {
            body.take(220)
        }
    }
}

/**
 * Resolved snapshot of the Fish-related SharedPreferences. Treated as an
 * immutable value object — the provider lambda re-reads on every call so
 * the user can flip settings in the companion app without restarting the
 * bridge.
 */
data class FishTtsConfig(
    val apiKey: String,
    val activeVoiceId: String,
    val activeVoiceName: String,
    val model: String,
    val format: String,
    val latency: String,
    val speed: Float,
    val volume: Float,
    val normalize: Boolean,
    val dramatize: Boolean
)

/**
 * Companion to [resolveGlassesGeminiKey] — pulls the Fish config out of
 * "visionclaw_prefs" on every call. Returns null if the readout engine is
 * not "fish", which lets [FishTtsClient.synthesize] short-circuit to
 * [FishTtsClient.SynthesisResult.NotConfigured] cleanly.
 *
 * `nullIfNotActiveEngine` lets callers (like the companion server's
 * preview endpoint, or routing logic that wants to know "is fish set up
 * at all") fetch the config without forcing readout_engine == "fish".
 */
fun resolveGlassesFishConfig(
    context: Context,
    nullIfNotActiveEngine: Boolean = true
): FishTtsConfig? {
    val prefs = context.getSharedPreferences("visionclaw_prefs", Context.MODE_PRIVATE)
    val engine = prefs.getString("readout_engine", "gemini")?.trim().orEmpty()
    if (nullIfNotActiveEngine && engine != "fish") return null
    val key = prefs.getString("fish_api_key", "")?.trim().orEmpty()
    val voiceId = prefs.getString("fish_active_voice_id", "")?.trim().orEmpty()
    val voiceName = prefs.getString("fish_active_voice_name", "")?.trim().orEmpty()
    return FishTtsConfig(
        apiKey = key,
        activeVoiceId = voiceId,
        activeVoiceName = voiceName,
        model = prefs.getString("fish_model", "")?.trim().orEmpty(),
        format = prefs.getString("fish_format", "")?.trim().orEmpty(),
        latency = prefs.getString("fish_latency", "")?.trim().orEmpty(),
        speed = prefs.getFloat("fish_speed", 1.0f),
        volume = prefs.getFloat("fish_volume", 0.0f),
        normalize = prefs.getBoolean("fish_normalize", true),
        dramatize = prefs.getBoolean("fish_dramatize", false)
    )
}

/** True if the user has both a Fish key and a picked voice — useful for
 *  the "should I route through Fish?" decision in [MediaLibraryBridge]. */
fun isFishReadoutReady(context: Context): Boolean {
    val cfg = resolveGlassesFishConfig(context, nullIfNotActiveEngine = false) ?: return false
    return cfg.apiKey.isNotBlank() && cfg.activeVoiceId.isNotBlank()
}

/** Reads `readout_engine` directly. Used to decide whether to attempt
 *  Fish synthesis before falling back to Gemini. */
fun isFishEngineActive(context: Context): Boolean {
    val prefs = context.getSharedPreferences("visionclaw_prefs", Context.MODE_PRIVATE)
    return (prefs.getString("readout_engine", "gemini") ?: "gemini").trim() == "fish"
}
