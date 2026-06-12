package com.rayneo.visionclaw.core.tools

import android.content.Context
import android.util.Log
import com.rayneo.visionclaw.BuildConfig
import com.rayneo.visionclaw.core.storage.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Voice-triggered "what does this say?" tool. Captures whatever the
 * on-glasses TapBrowser WebView is currently showing, sends the
 * image + the user's question to Gemini's standard `generateContent`
 * endpoint, and returns the answer text for the chat-card pipeline
 * to render + speak via TTS.
 *
 * Why a separate tool (not the Live multimodal stream): the Live
 * session is busy carrying camera frames + mic audio. Reusing it
 * for a one-shot screenshot would race the camera stream. The REST
 * endpoint is cheap (one HTTP request per ask), self-contained, and
 * lets us reuse the existing tool-result → chat-card → TTS path
 * the assistant already has wired.
 *
 * Args (Gemini-side function schema):
 *   • `question` (string, required) — the user's spoken question
 *     after the wake phrase ("what does this say", "summarize this
 *     page", "translate this", etc.). The router strips the wake
 *     phrase before populating this arg, so the LLM gets a clean
 *     question.
 *
 * Returns: the answer text. On any error (no frame available,
 * Gemini API failure, no key), returns a Result.failure with a
 * human-friendly message — the dispatcher surfaces that to the
 * chat card instead of pretending the lookup succeeded.
 */
class BrowserVisionTool(
    private val context: Context,
    private val frameProvider: () -> String? = { null },
    /** Provides the Gemini API key. Defaults to the user-saved
     *  visionclaw_prefs value, falling back to BuildConfig. */
    private val apiKeyProvider: () -> String? = {
        val prefs = AppPreferences(context)
        prefs.geminiApiKey.trim().takeIf { it.isNotBlank() }
            ?: BuildConfig.GEMINI_API_KEY.trim().takeIf { it.isNotBlank() }
    },
    /** Override for tests; production callers leave it at the
     *  default and use [DEFAULT_MODEL]. */
    private val modelOverride: String? = null
) : AiTapTool {

    override val name = "browser_vision"

    companion object {
        private const val TAG = "BrowserVisionTool"

        /** gemini-2.5-flash is fast, multimodal, and cheap.
         *  Accurate enough at OCR for typical web page text. */
        private const val DEFAULT_MODEL = "gemini-2.5-flash"

        private const val API_BASE =
            "https://generativelanguage.googleapis.com/v1beta/models"

        /** Hard cap on response length so spoken answers don't run
         *  the user out of patience. The chat card can re-tap for
         *  long-form, but spoken-first defaults to ~3 sentences. */
        private const val MAX_OUTPUT_TOKENS = 600

        /** The system instruction that tells Gemini we're feeding it
         *  the browser's current viewport, and that the user wants a
         *  short, spoken-friendly answer. */
        private const val SYSTEM_INSTRUCTION =
            "You are TapInsight's vision assistant on AR glasses. The attached image is " +
                "what the user is currently looking at: when their glasses camera is on it " +
                "is the LIVE camera view of the real world in front of them (e.g. a book " +
                "page, sign, label, or object); otherwise it is a screenshot of the web " +
                "page in their browser. Answer the user's question about that image in " +
                "plain, spoken-friendly English. Keep responses concise (2-3 sentences) " +
                "unless the user explicitly asks for detail. If you can't see what they're " +
                "asking about, say so plainly — don't make things up."

        private val http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(45, TimeUnit.SECONDS)
            .build()
    }

    override suspend fun execute(args: Map<String, String>): Result<String> {
        val question = args["question"]?.trim()
            ?: args["query"]?.trim()
            ?: args["prompt"]?.trim()
            ?: ""
        if (question.isBlank()) {
            return Result.failure(
                IllegalArgumentException(
                    "Browser vision needs a question — say 'look at this' or 'what does this say' followed by what you want to know."
                )
            )
        }

        val apiKey = apiKeyProvider()
        if (apiKey.isNullOrBlank()) {
            return Result.failure(
                IllegalStateException(
                    "No Gemini API key configured. Set it in the companion app's settings."
                )
            )
        }

        val frameBase64 = frameProvider()?.trim()
        if (frameBase64.isNullOrBlank()) {
            return Result.failure(
                IllegalStateException(
                    "I can't see the browser right now — make sure the browser tab is open and try again."
                )
            )
        }

        Log.d(TAG, "execute: question='${question.take(80)}' frameBytes=${frameBase64.length}")

        return withContext(Dispatchers.IO) {
            try {
                val responseText = callGemini(apiKey, frameBase64, question)
                Result.success(responseText)
            } catch (e: Exception) {
                Log.w(TAG, "Gemini call failed: ${e.message}")
                Result.failure(
                    RuntimeException(
                        "Couldn't read the page: ${e.message ?: "unknown error"}"
                    )
                )
            }
        }
    }

    private fun callGemini(apiKey: String, frameBase64: String, question: String): String {
        val model = modelOverride ?: DEFAULT_MODEL
        val url = "$API_BASE/$model:generateContent?key=$apiKey"

        // Build the request body. Gemini's REST schema uses
        // `contents[].parts[]` with each part being either text or
        // inline_data (binary attachments base64-encoded). The system
        // instruction lives at the top level on v1beta.
        val payload = JSONObject().apply {
            put("systemInstruction", JSONObject().put(
                "parts", JSONArray().put(
                    JSONObject().put("text", SYSTEM_INSTRUCTION)
                )
            ))
            put("contents", JSONArray().put(
                JSONObject()
                    .put("role", "user")
                    .put("parts", JSONArray()
                        .put(JSONObject().put(
                            "inlineData", JSONObject()
                                .put("mimeType", "image/jpeg")
                                .put("data", frameBase64)
                        ))
                        .put(JSONObject().put("text", question))
                    )
            ))
            put("generationConfig", JSONObject()
                .put("maxOutputTokens", MAX_OUTPUT_TOKENS)
                .put("temperature", 0.4)
            )
        }

        val request = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        http.newCall(request).execute().use { resp ->
            val bodyText = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                Log.w(TAG, "Gemini ${resp.code}: ${bodyText.take(300)}")
                throw RuntimeException("Gemini API ${resp.code}")
            }
            return parseAnswer(bodyText)
        }
    }

    /**
     * Extract the assistant's reply from a Gemini `generateContent`
     * response. The interesting structure is:
     *
     *   candidates[0].content.parts[].text
     *
     * which we join across parts in case the model chunks its
     * answer. Returns the joined text, trimmed.
     */
    private fun parseAnswer(jsonText: String): String {
        val root = JSONObject(jsonText)
        val candidates = root.optJSONArray("candidates")
            ?: throw RuntimeException("Gemini response had no candidates")
        if (candidates.length() == 0) {
            // Some safety blocks land here with promptFeedback.blockReason.
            val pf = root.optJSONObject("promptFeedback")
            val reason = pf?.optString("blockReason")
            throw RuntimeException(
                if (reason.isNullOrBlank()) "Gemini returned an empty answer"
                else "Gemini declined to answer (${reason})"
            )
        }
        val first = candidates.getJSONObject(0)
        val content = first.optJSONObject("content")
            ?: throw RuntimeException("Gemini candidate had no content")
        val parts = content.optJSONArray("parts") ?: JSONArray()
        val builder = StringBuilder()
        for (i in 0 until parts.length()) {
            val p = parts.optJSONObject(i) ?: continue
            val text = p.optString("text").trim()
            if (text.isNotBlank()) {
                if (builder.isNotEmpty()) builder.append(' ')
                builder.append(text)
            }
        }
        val out = builder.toString().trim()
        if (out.isBlank()) throw RuntimeException("Gemini returned an empty answer")
        return out
    }
}
