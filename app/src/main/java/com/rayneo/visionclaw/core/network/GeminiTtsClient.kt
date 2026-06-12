package com.rayneo.visionclaw.core.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Base64

class GeminiTtsClient(
    private val apiKeyProvider: () -> String?,
    private val fallbackApiKeyProvider: () -> String? = { null },
    private val modelProvider: () -> String? = { null },
    private val voiceNameProvider: () -> String? = { null },
    private val languageCodeProvider: () -> String? = { null },
    private val directorNotesProvider: () -> String? = { null },
    private val timeoutSecondsProvider: () -> Int = { 0 }
) {

    companion object {
        private const val TAG = "GeminiTtsClient"
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
        // Canonical Google TTS model (v1beta). The earlier
        // "gemini-3.1-flash-tts-preview" string we'd been using returns
        // a 404 — Google's actual naming convention puts -preview-
        // INSIDE the suffix, not at the end. Verified against
        // generativelanguage.googleapis.com/v1beta/models for current
        // accounts; the companion app's "List TTS Models" button
        // surfaces whatever this user's API key currently has access
        // to if Google rotates the catalogue again.
        private const val DEFAULT_MODEL = "gemini-2.5-flash-preview-tts"
        private const val FALLBACK_MODEL = "gemini-2.5-pro-preview-tts"
        private const val DEFAULT_VOICE = "Kore"
        private const val DEFAULT_LANGUAGE = "en-US"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val MAX_CHARS_PER_CHUNK = 2_400
        private const val DEFAULT_MIME_TYPE = "audio/L16;rate=24000"
    }

    sealed class TtsResult {
        data class Success(
            val audioBytes: ByteArray,
            val mimeType: String,
            val model: String,
            val chunkCount: Int
        ) : TtsResult()

        object ApiKeyMissing : TtsResult()
        data class Error(val message: String) : TtsResult()
    }

    fun segmentTranscriptForPlayback(
        text: String,
        maxChars: Int = MAX_CHARS_PER_CHUNK
    ): List<String> {
        val transcript = text.trim()
        if (transcript.isBlank()) return emptyList()
        return splitTranscript(transcript, maxChars.coerceAtLeast(400))
    }

    suspend fun synthesizeVerbatim(
        text: String,
        label: String? = null
    ): TtsResult = withContext(Dispatchers.IO) {
        val transcript = text.trim()
        if (transcript.isBlank()) {
            return@withContext TtsResult.Error("Nothing to read aloud.")
        }

        val apiKey = resolveApiKey()
            ?: return@withContext TtsResult.ApiKeyMissing
        val requestedModel = modelProvider()?.trim().takeIf { !it.isNullOrBlank() } ?: DEFAULT_MODEL
        val voiceName = voiceNameProvider()?.trim().takeIf { !it.isNullOrBlank() } ?: DEFAULT_VOICE
        val languageCode = languageCodeProvider()?.trim().takeIf { !it.isNullOrBlank() } ?: DEFAULT_LANGUAGE
        val directorNotes = directorNotesProvider()?.trim().orEmpty()
        val chunks = splitTranscript(transcript, MAX_CHARS_PER_CHUNK)
        val audioOut = ByteArrayOutputStream()
        var selectedModel = requestedModel
        var mimeType = DEFAULT_MIME_TYPE

        for ((index, chunk) in chunks.withIndex()) {
            var success = false
            var lastError: String? = null
            for (candidate in modelCandidates(requestedModel)) {
                val response = ActiveNetworkHttp.postJson(
                    url = "$BASE_URL/$candidate:generateContent",
                    jsonBody = buildRequestBody(
                        modelName = candidate,
                        transcript = chunk,
                        label = label,
                        directorNotes = directorNotes,
                        voiceName = voiceName,
                        languageCode = languageCode,
                        chunkIndex = index,
                        chunkCount = chunks.size
                    ).toString(),
                    headers = mapOf(
                        "Content-Type" to "application/json",
                        "x-goog-api-key" to apiKey
                    ),
                    connectTimeoutMs = CONNECT_TIMEOUT_MS,
                    readTimeoutMs = effectiveReadTimeoutMs()
                )

                if (response.code in 200..299) {
                    val audio = extractAudio(response.body)
                    if (audio == null || audio.first.isEmpty()) {
                        lastError = "Gemini TTS returned empty audio"
                        Log.w(TAG, "Empty Gemini TTS audio model=$candidate")
                        continue
                    }
                    selectedModel = candidate
                    mimeType = audio.second.ifBlank { DEFAULT_MIME_TYPE }
                    audioOut.write(audio.first)
                    success = true
                    break
                }

                lastError = "Gemini TTS HTTP ${response.code}"
                Log.w(
                    TAG,
                    "Gemini TTS failed model=$candidate code=${response.code} body=${response.body.take(220)}"
                )
                if (response.code != 404) break
            }

            if (!success) {
                return@withContext TtsResult.Error(lastError ?: "Gemini TTS unavailable")
            }
        }

        val bytes = audioOut.toByteArray()
        if (bytes.isEmpty()) {
            return@withContext TtsResult.Error("Gemini TTS returned empty audio.")
        }

        TtsResult.Success(
            audioBytes = bytes,
            mimeType = mimeType,
            model = selectedModel,
            chunkCount = chunks.size
        )
    }

    private fun resolveApiKey(): String? {
        val primary = apiKeyProvider()?.trim().takeIf { !it.isNullOrBlank() }
        if (primary != null) return primary
        return fallbackApiKeyProvider()?.trim().takeIf { !it.isNullOrBlank() }
    }

    private fun effectiveReadTimeoutMs(): Int {
        val userTimeout = timeoutSecondsProvider()
        return if (userTimeout > 0) userTimeout * 1000 else READ_TIMEOUT_MS
    }

    private fun modelCandidates(requested: String): List<String> =
        listOf(requested.trim(), DEFAULT_MODEL, FALLBACK_MODEL)
            .filter { it.isNotBlank() }
            .distinct()

    private fun buildRequestBody(
        modelName: String,
        transcript: String,
        label: String?,
        directorNotes: String,
        voiceName: String,
        languageCode: String,
        chunkIndex: Int,
        chunkCount: Int
    ): JSONObject {
        return JSONObject()
            .put("model", modelName)
            .put("contents", JSONArray().put(
                JSONObject().put(
                    "parts",
                    JSONArray().put(
                        JSONObject().put(
                            "text",
                            buildPrompt(
                                transcript = transcript,
                                label = label,
                                directorNotes = directorNotes,
                                chunkIndex = chunkIndex,
                                chunkCount = chunkCount
                            )
                        )
                    )
                )
            ))
            .put("generationConfig", JSONObject()
                .put("responseModalities", JSONArray().put("AUDIO"))
                .put("speechConfig", JSONObject()
                    .put("voiceConfig", JSONObject()
                        .put("prebuiltVoiceConfig", JSONObject()
                            .put("voiceName", voiceName)
                        )
                    )
                    .put("languageCode", languageCode)
                )
            )
    }

    private fun buildPrompt(
        transcript: String,
        label: String?,
        directorNotes: String,
        chunkIndex: Int,
        chunkCount: Int
    ): String = buildString {
        append("Read only the TRANSCRIPT section verbatim. ")
        append("Speak every visible word exactly as written and in order. ")
        append("Do not add greetings, transitions, commentary, conclusions, summaries, paraphrases, repairs, or filler. ")
        append("Do not mention missing text, truncation, continuity, segment numbers, or section headers. ")
        append("Stop silently at the final visible character of the TRANSCRIPT.\n\n")
        append("### DIRECTOR_NOTES\n")
        append("Clear, natural pacing. Preserve punctuation and paragraph breaks.\n")
        if (!label.isNullOrBlank()) {
            append("Context: ")
            append(label.trim())
            append('\n')
        }
        if (chunkCount > 1) {
            append("This excerpt is chunk ${chunkIndex + 1} of $chunkCount from a longer document. ")
            append("Read only the words in TRANSCRIPT and then stop silently.\n")
        }
        if (directorNotes.isNotBlank()) {
            append(directorNotes.trim())
            append('\n')
        }
        append("\n### TRANSCRIPT\n")
        append(transcript)
    }

    private fun extractAudio(body: String): Pair<ByteArray, String>? {
        val root = JSONObject(body)
        val candidates = root.optJSONArray("candidates") ?: return null
        val out = ByteArrayOutputStream()
        var mimeType = DEFAULT_MIME_TYPE

        for (i in 0 until candidates.length()) {
            val content = candidates.optJSONObject(i)
                ?.optJSONObject("content")
                ?: continue
            val parts = content.optJSONArray("parts") ?: continue
            for (j in 0 until parts.length()) {
                val inlineData = parts.optJSONObject(j)
                    ?.optJSONObject("inlineData")
                    ?: continue
                val data = inlineData.optString("data", "").trim()
                if (data.isBlank()) continue
                val decoded = runCatching { Base64.getDecoder().decode(data) }.getOrNull() ?: continue
                mimeType = inlineData.optString("mimeType", mimeType).ifBlank { mimeType }
                out.write(decoded)
            }
        }

        val bytes = out.toByteArray()
        return if (bytes.isEmpty()) null else bytes to mimeType
    }

    private fun splitTranscript(text: String, maxChars: Int): List<String> {
        val normalized = text.replace("\r\n", "\n").trim()
        if (normalized.length <= maxChars) return listOf(normalized)

        val chunks = mutableListOf<String>()
        var current = StringBuilder()

        fun flushCurrent() {
            val value = current.toString().trim()
            if (value.isNotBlank()) {
                chunks += value
            }
            current = StringBuilder()
        }

        fun appendPart(part: String) {
            val trimmed = part.trim()
            if (trimmed.isBlank()) return
            if (trimmed.length > maxChars) {
                splitLongBlock(trimmed, maxChars).forEach { appendPart(it) }
                return
            }
            val candidate = if (current.isEmpty()) trimmed else "${current}\n\n$trimmed"
            if (candidate.length > maxChars && current.isNotEmpty()) {
                flushCurrent()
                current.append(trimmed)
            } else {
                if (current.isNotEmpty()) current.append("\n\n")
                current.append(trimmed)
            }
        }

        normalized.split(Regex("\n{2,}")).forEach { appendPart(it) }
        flushCurrent()
        return chunks.ifEmpty { listOf(normalized) }
    }

    private fun splitLongBlock(text: String, maxChars: Int): List<String> {
        val sentenceRegex = Regex("""(?<=[.!?])(?:["')\]]+)?\s+""")
        val sentences = sentenceRegex.split(text)
        if (sentences.size <= 1) return splitHard(text, maxChars)

        val chunks = mutableListOf<String>()
        var current = StringBuilder()
        for (sentence in sentences) {
            val trimmed = sentence.trim()
            if (trimmed.isBlank()) continue
            if (trimmed.length > maxChars) {
                if (current.isNotEmpty()) {
                    chunks += current.toString().trim()
                    current = StringBuilder()
                }
                chunks += splitHard(trimmed, maxChars)
                continue
            }
            val candidate = if (current.isEmpty()) trimmed else "${current} $trimmed"
            if (candidate.length > maxChars && current.isNotEmpty()) {
                chunks += current.toString().trim()
                current = StringBuilder(trimmed)
            } else {
                if (current.isNotEmpty()) current.append(' ')
                current.append(trimmed)
            }
        }
        if (current.isNotEmpty()) {
            chunks += current.toString().trim()
        }
        return chunks
    }

    private fun splitHard(text: String, maxChars: Int): List<String> {
        val chunks = mutableListOf<String>()
        var remaining = text.trim()
        while (remaining.length > maxChars) {
            val splitAt = listOf(
                remaining.lastIndexOf(". ", startIndex = maxChars).takeIf { it > maxChars / 2 }?.plus(1),
                remaining.lastIndexOf("? ", startIndex = maxChars).takeIf { it > maxChars / 2 }?.plus(1),
                remaining.lastIndexOf("! ", startIndex = maxChars).takeIf { it > maxChars / 2 }?.plus(1),
                remaining.lastIndexOf('\n', startIndex = maxChars).takeIf { it > maxChars / 2 },
                remaining.lastIndexOf(' ', startIndex = maxChars).takeIf { it > maxChars / 2 }
            ).firstOrNull { it != null } ?: maxChars
            chunks += remaining.substring(0, splitAt).trim()
            remaining = remaining.substring(splitAt).trim()
        }
        if (remaining.isNotBlank()) chunks += remaining
        return chunks
    }
}
