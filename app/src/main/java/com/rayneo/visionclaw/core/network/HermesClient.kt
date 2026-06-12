package com.rayneo.visionclaw.core.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * HermesClient — HTTP / SSE client for NousResearch/hermes-agent's
 * OpenAI-compatible API server (default port 8642).
 *
 * Replaces the old WebSocket-based OpenClawClient for the TapHermes
 * build. Wire:
 *   1. POST  $endpoint/v1/chat/completions
 *      Authorization: Bearer $apiKey
 *      Content-Type:  application/json
 *      X-Hermes-Session-Id: $sessionId
 *   2. Body { "model":"hermes-agent", "stream":true, "messages":[
 *              { "role":"user", "content":[
 *                  { "type":"text", "text":"..." },
 *                  { "type":"image_url", "image_url":{ "url":"data:image/jpeg;base64,…" } }
 *              ] }
 *            ] }
 *   3. Response SSE — newline-delimited "data: {…}" frames, terminator
 *      "data: [DONE]". Chunks shape:
 *        { "choices":[{ "delta":{ "content":"partial text" } }] }
 *
 * Mirrors OpenClawClient's public surface (ClawResult shape, sendMessage,
 * ping, onProgressUpdate, onProgressComplete) so MainActivity's existing
 * HUD-ticker wiring keeps working unchanged — it just gets pointed at
 * this client instead of the old WebSocket one.
 */
class HermesClient(
    private val endpointUrlProvider: () -> String?,
    private val apiKeyProvider: () -> String?,
    private val sessionIdProvider: () -> String = { "main" },
    private val timeoutMsProvider: () -> Int = { 30_000 },
    /** Called on the IO thread with the latest accumulated assistant text after each SSE chunk. */
    var onProgressUpdate: ((String) -> Unit)? = null,
    /**
     * Called once per [sendMessage] when the SSE stream terminates.
     * success = true for a clean `[DONE]`, false for any error path.
     */
    var onProgressComplete: ((success: Boolean) -> Unit)? = null
) {

    companion object {
        private const val TAG = "HermesClient"
        private const val DEFAULT_MODEL = "hermes-agent"
        private const val CHAT_PATH = "/v1/chat/completions"
        private const val MODELS_PATH = "/v1/models"
        private const val SESSION_HEADER = "X-Hermes-Session-Id"
        private const val JPEG_DATA_URL_PREFIX = "data:image/jpeg;base64,"
    }

    /**
     * Same shape as OpenClawClient.ClawResult so existing call-site
     * pattern-matching in MainActivity (and HermesTool below) doesn't
     * have to learn a new type.
     */
    sealed class ClawResult {
        data class Success(
            val text: String,
            val model: String? = null,
            val sessionId: String? = null
        ) : ClawResult()
        data class Error(val message: String, val code: Int = -1) : ClawResult()
        object NotConfigured : ClawResult()
    }

    // ── Public API ───────────────────────────────────────────────────────

    suspend fun sendMessage(
        message: String,
        context: String? = null,
        imageBase64: String? = null
    ): ClawResult = withContext(Dispatchers.IO) {
        val baseUrl = normalizeEndpoint(endpointUrlProvider())
            ?: return@withContext ClawResult.NotConfigured

        val apiKey = apiKeyProvider().orEmpty().trim()
        if (apiKey.isBlank()) {
            return@withContext ClawResult.Error("Hermes API key not configured.")
        }

        val sessionId = sessionIdProvider().ifBlank { "main" }
        val fullMessage = buildString {
            if (!context.isNullOrBlank()) {
                append("[Context from AR glasses: $context]\n\n")
            }
            append(message)
        }

        val body = buildChatCompletionsBody(fullMessage, imageBase64)
        val bodyText = body.toString()
        Log.d(
            TAG,
            "Hermes send: url=$baseUrl session=$sessionId hasImage=${!imageBase64.isNullOrBlank()} msg=${message.take(100)}"
        )
        val request = Request.Builder()
            .url(baseUrl + CHAT_PATH)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Accept", "text/event-stream")
            .addHeader(SESSION_HEADER, sessionId)
            .post(bodyText.toRequestBody("application/json".toMediaType()))
            .build()

        var success = false
        try {
            httpClient(timeoutMsProvider(), streaming = true).newCall(request).execute().use { response ->
                val result = handleResponse(response, sessionId)
                success = result is ClawResult.Success
                return@withContext result
            }
        } catch (e: java.net.ConnectException) {
            Log.e(TAG, "Hermes connect failed", e)
            ClawResult.Error("Cannot connect to Hermes. Is the API server running and the tunnel up?")
        } catch (e: java.net.SocketTimeoutException) {
            Log.e(TAG, "Hermes timeout", e)
            ClawResult.Error("Hermes request timed out. Try again or raise the timeout.")
        } catch (e: Exception) {
            Log.e(TAG, "Hermes failed", e)
            ClawResult.Error(e.localizedMessage ?: "Hermes request failed")
        } finally {
            onProgressComplete?.invoke(success)
        }
    }

    /** Health check via /v1/models — fast, doesn't pollute conversation. */
    suspend fun ping(): ClawResult = withContext(Dispatchers.IO) {
        val baseUrl = normalizeEndpoint(endpointUrlProvider())
            ?: return@withContext ClawResult.NotConfigured
        val apiKey = apiKeyProvider().orEmpty().trim()
        if (apiKey.isBlank()) return@withContext ClawResult.Error("Hermes API key not configured.")

        try {
            httpClient(10_000, streaming = false).newCall(
                Request.Builder()
                    .url(baseUrl + MODELS_PATH)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .get()
                    .build()
            ).execute().use { resp ->
                if (resp.isSuccessful) ClawResult.Success("Hermes reachable")
                else ClawResult.Error("Hermes returned HTTP ${resp.code}", resp.code)
            }
        } catch (e: Exception) {
            ClawResult.Error("Cannot reach Hermes: ${e.localizedMessage}")
        }
    }

    // No-op shims so MainActivity's existing mode-bracket callbacks
    // don't need to be deleted. The May 14 build's bracket UI was
    // OpenClaw-specific (/fast, /think); Hermes uses different commands
    // and the bracket UI is just informational on this branch.
    fun consumeModePrefixForSession(@Suppress("UNUSED_PARAMETER") sessionId: String): String = ""
    fun resetModePrefixForSession(@Suppress("UNUSED_PARAMETER") sessionId: String) {}
    suspend fun fireAfterTurnRestoreIfConfigured() {
        /* no-op for Hermes */
    }

    // ── Internal helpers ─────────────────────────────────────────────────

    private fun buildChatCompletionsBody(userText: String, imageBase64: String?): JSONObject {
        val content = JSONArray()
        content.put(JSONObject().put("type", "text").put("text", userText))
        if (!imageBase64.isNullOrBlank()) {
            val dataUrl = if (imageBase64.startsWith("data:")) imageBase64
                          else JPEG_DATA_URL_PREFIX + imageBase64
            content.put(
                JSONObject()
                    .put("type", "image_url")
                    .put("image_url", JSONObject().put("url", dataUrl))
            )
        }
        return JSONObject()
            .put("model", DEFAULT_MODEL)
            .put("stream", true)
            .put("messages", JSONArray().put(
                JSONObject().put("role", "user").put("content", content)
            ))
    }

    private fun handleResponse(response: Response, sessionId: String): ClawResult {
        if (!response.isSuccessful) {
            val body = runCatching { response.body?.string().orEmpty() }.getOrDefault("")
            val msg = parseErrorMessage(body) ?: "Hermes returned HTTP ${response.code}"
            return ClawResult.Error(msg, response.code)
        }
        val body = response.body ?: return ClawResult.Error("Empty Hermes response", -1)
        val source = body.source()
        val assembled = StringBuilder()
        var modelName: String? = null

        try {
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (line.isBlank() || !line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload == "[DONE]") break
                val chunk = runCatching { JSONObject(payload) }.getOrNull() ?: continue
                if (modelName == null) {
                    chunk.optString("model", "").takeIf { it.isNotBlank() }?.let { modelName = it }
                }
                val choices = chunk.optJSONArray("choices") ?: continue
                if (choices.length() == 0) continue
                val choice = choices.optJSONObject(0) ?: continue
                val delta = choice.optJSONObject("delta")
                val partial = delta?.optString("content", "").orEmpty()
                if (partial.isNotBlank()) {
                    assembled.append(partial)
                    // Forward the ACCUMULATED text (last 200 chars worth
                    // of context) so the HUD ticker scrolls readably
                    // instead of flashing single tokens.
                    runCatching {
                        onProgressUpdate?.invoke(assembled.toString().takeLast(200))
                    }.onFailure { Log.w(TAG, "onProgressUpdate threw: ${it.message}") }
                }
                val finishReason = choice.optString("finish_reason", "")
                    .ifBlank { choice.optString("finishReason", "") }
                    .trim()
                if (finishReason.isNotBlank() && finishReason != "null") {
                    break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "SSE read failed", e)
            return ClawResult.Error(e.localizedMessage ?: "Hermes stream read failed", -1)
        }

        val text = assembled.toString().trim()
        if (text.isEmpty()) return ClawResult.Error("Hermes returned empty completion", -1)
        return ClawResult.Success(text = text, model = modelName, sessionId = sessionId)
    }

    private fun parseErrorMessage(body: String): String? {
        if (body.isBlank()) return null
        return runCatching {
            val json = JSONObject(body)
            val err = json.optJSONObject("error")
            err?.optString("message", "")?.takeIf { it.isNotBlank() }
                ?: json.optString("message", "").takeIf { it.isNotBlank() }
                ?: json.optString("detail", "").takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    /**
     * Normalize user-typed endpoint into a usable base URL.
     *   hermes.example.com         → https://hermes.example.com
     *   hermes.example.com:8642    → http://hermes.example.com:8642
     *   http://192.168.1.10:8642   → unchanged
     *   (blank)                    → null
     */
    private fun normalizeEndpoint(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty().trimEnd('/')
        if (trimmed.isBlank()) return null
        val withScheme = when {
            trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            Regex(""":\d+(?:/|$)""").containsMatchIn(trimmed) -> "http://$trimmed"
            else -> "https://$trimmed"
        }
        return withScheme
    }

    private fun httpClient(timeoutMs: Int, streaming: Boolean): OkHttpClient {
        val configured = if (timeoutMs > 0) timeoutMs.toLong() else 30_000L
        val readTimeoutMs = if (streaming) {
            // Streaming waits on gaps between SSE chunks, so keep this generous.
            // Force HTTP/1.1 below: the failing glasses logs showed the stuck
            // path was OkHttp HTTP/2, which is a poor fit for Cloudflare SSE.
            maxOf(configured, 5 * 60_000L)
        } else {
            configured.coerceAtLeast(10_000L)
        }
        return OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectTimeout(configured.coerceAtLeast(10_000L), TimeUnit.MILLISECONDS)
            .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
