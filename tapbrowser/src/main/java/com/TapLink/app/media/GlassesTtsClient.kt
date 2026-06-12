package com.TapLink.app.media

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.CRC32

/**
 * On-glasses Gemini 3.1 TTS client used by [MediaLibraryBridge.speakText] to
 * synthesize text into audio that the WebView can play through an `<audio>`
 * element. RayNeo's WebView doesn't expose the Web Speech API, so without
 * this client the on-glasses media-player TTS button has nothing to call.
 *
 * Architecture:
 *   1. The bridge passes the chunk text to [synthesize].
 *   2. We POST to generativelanguage.googleapis.com with the
 *      gemini-3.1-flash-tts-preview model (mirrors the companion-side
 *      GeminiTtsClient defaults so voices are consistent across surfaces).
 *   3. The response carries base64-encoded L16 PCM. We wrap it in a WAV
 *      header so the browser can decode it directly without Web Audio API
 *      tricks, then drop the bytes into [TtsCacheStore].
 *   4. The bridge returns `{audioUrl: "https://appassets.../tts/<id>.wav"}`
 *      and the WebView plays it. [MediaFileInterceptor] handles `/tts/...`
 *      by reading from the same in-memory cache.
 *
 * This file is a deliberately trimmed port of the companion app's
 * `GeminiTtsClient` — uses `HttpURLConnection` instead of OkHttp /
 * `ActiveNetworkHttp` so the tapbrowser library module doesn't need a
 * downstream dependency on the app module's network layer.
 */
class GlassesTtsClient(
    private val apiKeyProvider: () -> String?,
    private val voiceNameProvider: () -> String? = { null },
    private val languageCodeProvider: () -> String? = { null }
) {

    companion object {
        private const val TAG = "GlassesTtsClient"
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
        // See GeminiTtsClient for the same comment — the earlier
        // gemini-3.1-flash-tts-preview returns 404; Google's canonical
        // TTS model name uses -preview-tts as the trailing suffix.
        private const val DEFAULT_MODEL = "gemini-2.5-flash-preview-tts"
        private const val FALLBACK_MODEL = "gemini-2.5-pro-preview-tts"
        private const val DEFAULT_VOICE = "Kore"
        private const val DEFAULT_LANGUAGE = "en-US"
        private const val DEFAULT_SAMPLE_RATE = 24_000
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val MAX_CHARS = 2_400
    }

    sealed class SynthesisResult {
        /** Successful synthesis. Audio bytes already wrapped as WAV. */
        data class Success(
            val wavBytes: ByteArray,
            val mimeType: String = "audio/wav",
            val model: String,
            val sampleRate: Int
        ) : SynthesisResult()

        /** No usable Gemini key configured. UI should hint at companion-app setup. */
        object ApiKeyMissing : SynthesisResult()

        data class Error(val message: String) : SynthesisResult()
    }

    /**
     * Synthesize a single chunk. Caller is responsible for chunking long text;
     * we cap chunks at [MAX_CHARS] and refuse anything larger so the request
     * never times out at the API edge.
     */
    fun synthesize(text: String, voiceHint: String?): SynthesisResult {
        val transcript = text.trim()
        if (transcript.isBlank()) return SynthesisResult.Error("Nothing to speak.")
        if (transcript.length > MAX_CHARS) {
            return SynthesisResult.Error("Chunk too long (${transcript.length} chars); split before calling.")
        }

        val apiKey = apiKeyProvider()?.trim().takeIf { !it.isNullOrBlank() }
            ?: return SynthesisResult.ApiKeyMissing
        val voice = (voiceHint?.trim().takeIf { !it.isNullOrBlank() }
            ?: voiceNameProvider()?.trim().takeIf { !it.isNullOrBlank() }
            ?: DEFAULT_VOICE)
        val language = (languageCodeProvider()?.trim().takeIf { !it.isNullOrBlank() }
            ?: DEFAULT_LANGUAGE)

        // Prefer the 3.1 TTS preview: its prebuilt voices are noticeably
        // warmer than the 2.5 preview, and now that our prompt matches the
        // director-notes shape Google's guardrail is happy. 2.5 stays as a
        // fallback for when 3.1 is region-blocked or returns a hard error.
        //
        // First-chunk latency is the single most noticeable thing to the user
        // (they sit staring at "Preparing audio…" waiting for playback). So we
        // deliberately keep the retry budget tight:
        //
        //   * ONE call per model with the normal prompt. No transient-error
        //     retry loop here — model fallback below already covers transient
        //     flakiness on the happy path, and a re-try on the same model
        //     typically hits the same region/endpoint and fails the same way
        //     anyway. Matches the companion-side `GeminiTtsClient` pattern.
        //   * The specific "Model tried to generate text, but it should only
        //     be used for TTS" 400 is a prompt-guardrail error, most often
        //     triggered by short chunks dominated by identifier-like tokens
        //     (codec names, file extensions, acronyms). For that we retry
        //     the same model once with a reinforced prompt that frames the
        //     chunk as a continuation of narration — this reliably gets us
        //     past the guardrail without adding a second full-API latency
        //     hop on the plain-text happy path.
        //
        // Worst case here is 4 HTTP calls (3.1 normal + 3.1 reinforced + 2.5
        // normal + 2.5 reinforced). Typical case on clean text is 1 call.
        val candidates = listOf(DEFAULT_MODEL, FALLBACK_MODEL)
        var lastError: String? = null
        for (model in candidates) {
            val first = callOnce(apiKey, model, transcript, voice, language, reinforced = false)
            when (first) {
                is SynthesisResult.Success -> return first
                is SynthesisResult.ApiKeyMissing -> return first
                is SynthesisResult.Error -> {
                    lastError = first.message
                    Log.w(TAG, "Gemini TTS failed model=$model: ${first.message}")
                    // Guardrail-specific retry: reinforce the prompt and try
                    // the same model once more before we give up on it.
                    if (isGuardrailError(first.message)) {
                        val retry = callOnce(apiKey, model, transcript, voice, language, reinforced = true)
                        when (retry) {
                            is SynthesisResult.Success -> return retry
                            is SynthesisResult.ApiKeyMissing -> return retry
                            is SynthesisResult.Error -> {
                                lastError = retry.message
                                Log.w(TAG, "Gemini TTS reinforced retry failed model=$model: ${retry.message}")
                            }
                        }
                    }
                    // Any other failure (5xx, 4xx, network): fall straight through
                    // to the next model rather than burning another 3-5s on a
                    // same-model retry that almost never succeeds.
                }
            }
        }
        return SynthesisResult.Error(lastError ?: "Gemini TTS unavailable.")
    }

    private fun callOnce(
        apiKey: String,
        model: String,
        transcript: String,
        voice: String,
        language: String,
        reinforced: Boolean
    ): SynthesisResult {
        val url = URL("$BASE_URL/$model:generateContent")
        val body = buildRequestBody(transcript, voice, language, reinforced).toString()
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            doInput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("x-goog-api-key", apiKey)
        }

        return try {
            conn.outputStream.use { os ->
                OutputStreamWriter(os, Charsets.UTF_8).use { it.write(body) }
            }
            val code = conn.responseCode
            val responseText = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
                ?: ""

            if (code !in 200..299) {
                return SynthesisResult.Error("HTTP $code: ${summarizeApiError(responseText)}")
            }
            val (pcm, sampleRate) = extractPcm(responseText)
                ?: return SynthesisResult.Error("Empty audio in Gemini response.")
            val wav = wrapPcmAsWav(pcm, sampleRate)
            SynthesisResult.Success(wav, "audio/wav", model, sampleRate)
        } catch (e: Exception) {
            SynthesisResult.Error(e.message ?: e.javaClass.simpleName)
        } finally {
            try { conn.disconnect() } catch (_: Exception) {}
        }
    }

    private fun buildRequestBody(
        transcript: String,
        voice: String,
        language: String,
        reinforced: Boolean
    ): JSONObject {
        // Mirror the companion-side GeminiTtsClient prompt convention. The
        // gemini-*-tts-preview models are sensitive to prompt framing: a
        // minimalist prompt sometimes trips the "Model tried to generate text,
        // but it should only be used for TTS" guardrail (HTTP 400). The
        // director-notes + TRANSCRIPT section scheme below is the shape
        // Google's own examples use and produces reliably audio-only output.
        //
        // The reinforced variant (used after a guardrail 400) adds an extra
        // framing sentence up front that presents the chunk as the next
        // segment of an in-progress narration — this gets chunks dominated
        // by identifier-like tokens (codec names, file extensions, acronyms)
        // past the guardrail without changing what the model actually speaks.
        val prompt = buildString {
            if (reinforced) {
                append("You are voicing a document that is being read aloud to a human listener. ")
                append("The TRANSCRIPT below is the next passage in that reading. ")
                append("Treat every visible token as a word to be spoken — ")
                append("including file-format names, codec identifiers, acronyms, punctuation, ")
                append("and anything that looks like a list — and speak them exactly as a newsreader ")
                append("would if they were reading this paragraph to someone.\n\n")
            }
            append("Read only the TRANSCRIPT section verbatim. ")
            append("Speak every visible word exactly as written and in order. ")
            append("Do not add greetings, transitions, commentary, conclusions, summaries, paraphrases, repairs, or filler. ")
            append("Do not mention missing text, truncation, continuity, segment numbers, or section headers. ")
            append("Stop silently at the final visible character of the TRANSCRIPT.\n\n")
            append("### DIRECTOR_NOTES\n")
            append("Clear, natural pacing. Preserve punctuation and paragraph breaks.\n")
            append("\n### TRANSCRIPT\n")
            append(transcript)
        }
        return JSONObject()
            .put("contents", JSONArray().put(
                JSONObject().put(
                    "parts",
                    JSONArray().put(JSONObject().put("text", prompt))
                )
            ))
            .put("generationConfig", JSONObject()
                .put("responseModalities", JSONArray().put("AUDIO"))
                .put("speechConfig", JSONObject()
                    .put("voiceConfig", JSONObject()
                        .put("prebuiltVoiceConfig", JSONObject()
                            .put("voiceName", voice)
                        )
                    )
                    .put("languageCode", language)
                )
            )
    }

    /**
     * Pull the human-readable message out of Google's error envelope
     * (`{"error":{"code":500,"message":"Internal error encountered.","status":"INTERNAL"}}`)
     * so the toast the user sees is "Internal error encountered." instead
     * of a wall of nested JSON.
     */
    private fun summarizeApiError(body: String): String {
        return try {
            val msg = JSONObject(body)
                .optJSONObject("error")
                ?.optString("message")
                ?.trim()
                .orEmpty()
            if (msg.isNotBlank()) msg else body.take(220)
        } catch (_: Exception) {
            body.take(220)
        }
    }

    /**
     * Detect the specific prompt-guardrail 400 so the caller can retry
     * with reinforced framing instead of surfacing the error to the user.
     * The message Google returns is stable enough that a substring check
     * is reliable — we look at two orthogonal fragments to be resilient
     * to minor wording changes.
     */
    private fun isGuardrailError(message: String): Boolean {
        val m = message.lowercase()
        if (!m.startsWith("http 400")) return false
        return "tried to generate text" in m ||
            "should only be used for tts" in m ||
            "only generate audio" in m
    }

    private fun extractPcm(body: String): Pair<ByteArray, Int>? {
        val root = JSONObject(body)
        val candidates = root.optJSONArray("candidates") ?: return null
        val out = ByteArrayOutputStream()
        var sampleRate = DEFAULT_SAMPLE_RATE
        for (i in 0 until candidates.length()) {
            val parts = candidates.optJSONObject(i)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
                ?: continue
            for (j in 0 until parts.length()) {
                val inlineData = parts.optJSONObject(j)?.optJSONObject("inlineData") ?: continue
                val data = inlineData.optString("data", "").trim()
                if (data.isBlank()) continue
                val decoded = runCatching { Base64.getDecoder().decode(data) }.getOrNull() ?: continue
                val mimeType = inlineData.optString("mimeType", "")
                // Mime type looks like "audio/L16;rate=24000"
                Regex("""rate=(\d+)""").find(mimeType)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let {
                    sampleRate = it
                }
                out.write(decoded)
            }
        }
        val bytes = out.toByteArray()
        return if (bytes.isEmpty()) null else bytes to sampleRate
    }

    /**
     * Wrap raw L16 PCM (signed 16-bit little-endian, mono) as a self-contained
     * RIFF/WAV stream so the WebView's `<audio>` element can decode it directly.
     * This costs ~44 bytes of header but spares us a Web Audio API round-trip
     * on the JS side.
     */
    private fun wrapPcmAsWav(pcm: ByteArray, sampleRate: Int): ByteArray {
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataSize = pcm.size
        val totalSize = 36 + dataSize
        val buf = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray(Charsets.US_ASCII))
        buf.putInt(totalSize)
        buf.put("WAVE".toByteArray(Charsets.US_ASCII))
        buf.put("fmt ".toByteArray(Charsets.US_ASCII))
        buf.putInt(16)               // fmt chunk size
        buf.putShort(1)              // PCM format
        buf.putShort(channels.toShort())
        buf.putInt(sampleRate)
        buf.putInt(byteRate)
        buf.putShort(blockAlign.toShort())
        buf.putShort(bitsPerSample.toShort())
        buf.put("data".toByteArray(Charsets.US_ASCII))
        buf.putInt(dataSize)
        buf.put(pcm)
        return buf.array()
    }
}

/**
 * Tiny in-memory WAV cache shared by [MediaLibraryBridge] (which writes new
 * entries) and [MediaFileInterceptor] (which reads them when the WebView
 * requests `/tts/<id>.wav`). Bounded to keep the Heap from filling up after
 * a long reading session.
 *
 * We don't persist these — TTS chunks are produced at most a few times per
 * session and re-synthesizing on demand is fine.
 */
object TtsCacheStore {
    private const val MAX_ENTRIES = 24
    private const val TAG = "TtsCacheStore"

    private val store = ConcurrentHashMap<String, ByteArray>()
    private val order = java.util.ArrayDeque<String>()
    private val lock = Any()

    fun put(bytes: ByteArray): String {
        val crc = CRC32().apply { update(bytes) }.value.toString(16)
        val id = "${crc}_${UUID.randomUUID().toString().take(8)}"
        synchronized(lock) {
            store[id] = bytes
            order.addLast(id)
            while (order.size > MAX_ENTRIES) {
                val oldest = order.pollFirst() ?: break
                store.remove(oldest)
            }
        }
        Log.d(TAG, "cached ${bytes.size}B as $id (count=${order.size})")
        return id
    }

    fun get(id: String): ByteArray? = store[id]

    fun clear() {
        synchronized(lock) {
            store.clear()
            order.clear()
        }
    }
}

/** Used by Application/Activity to resolve the configured Gemini API key
 *  out of SharedPreferences ("visionclaw_prefs" / "gemini_api_key"). */
fun resolveGlassesGeminiKey(context: Context): String? {
    val prefs = context.getSharedPreferences("visionclaw_prefs", Context.MODE_PRIVATE)
    return prefs.getString("gemini_api_key", "")?.trim().takeIf { !it.isNullOrBlank() }
}
