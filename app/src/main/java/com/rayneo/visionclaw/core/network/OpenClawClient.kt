package com.rayneo.visionclaw.core.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * OpenClawClient — WebSocket client for the OpenClaw Gateway.
 *
 * OpenClaw is a personal AI assistant that runs on the user's own devices/server.
 * The Gateway exposes a WebSocket endpoint on port 18789 (configurable).
 *
 * Authentication requires Ed25519 device identity (matching OpenClawPairingClient).
 * The connect frame includes a signed payload with the gateway token and challenge nonce.
 *
 * Protocol:
 *   1. Connect via WebSocket to ws://host:port
 *   2. Server sends connect.challenge with nonce
 *   3. Client responds with signed connect frame (device identity + token)
 *   4. Server acknowledges (ok: true)
 *   5. Client sends agent call: { method: "agent", params: { message, idempotencyKey, agentId } }
 *   6. Server sends result with payloads[].text
 */
class OpenClawClient(
    private val gatewayUrlProvider: () -> String?,
    private val fallbackGatewayUrlProvider: () -> String? = { null },
    private val gatewayTokenProvider: () -> String?,
    private val deviceIdProvider: () -> String? = { null },
    private val publicKeyProvider: () -> String? = { null },
    private val privateKeyProvider: () -> String? = { null },
    private val sessionIdProvider: () -> String = { "main" },
    private val timeoutMsProvider: () -> Int = { 30_000 },
    // ── Mode brackets ──────────────────────────────────────────────
    // Prefix-on-first-message: "off" / "fast" toggles "/fast" line,
    // think level "" / "low" / "medium" / "high" toggles "/think
    // <level>". Both prepended (newline-separated) ahead of the
    // user's message on the first send for each sessionId.
    private val fastModeProvider: () -> String = { "off" },
    private val thinkLevelProvider: () -> String = { "" },
    // Same shape, sent fire-and-forget AFTER each agent completion
    // so the session returns to whatever profile the user wants the
    // bare TapClaw to have between turns.
    private val afterFastModeProvider: () -> String = { "off" },
    private val afterThinkLevelProvider: () -> String = { "" },
    /** Called on the IO thread with streaming progress text from OpenClaw delta events. */
    var onProgressUpdate: ((String) -> Unit)? = null,
    /**
     * Called on the IO thread exactly ONCE per [sendMessage] call, when the
     * agent run reaches a terminal state. `success = true` for a normal
     * completion (final chat or agent phase=complete/done/finished),
     * `success = false` for any error/timeout/auth-failure/close path.
     * Intended for the UI layer to speak "TapClaw finished" + clear stream
     * active indicators, without having to race lifecycle events itself.
     */
    var onProgressComplete: ((success: Boolean) -> Unit)? = null
) {

    companion object {
        private const val TAG = "OpenClawClient"
        private const val DEFAULT_PORT = 18789
        private const val IMAGE_RELAY_PORT = 18790
        private const val FRAME_FILENAME = "camera_frame.jpg"
        private const val CLIENT_ID = "openclaw-android"
        private const val CLIENT_MODE = "node"
        private const val CLIENT_PLATFORM = "android"
        private const val CLIENT_DEVICE_FAMILY = "RayNeo X3 Pro"
        private const val CLIENT_INSTANCE_ID = "rayneo-x3"
        private const val ROLE = "operator"
    }

    private val base64UrlEncoder = Base64.getUrlEncoder().withoutPadding()
    private val base64UrlDecoder = Base64.getUrlDecoder()

    // ── Mode bracket session tracking ───────────────────────────────
    // Records which sessionIds have already received their first-turn
    // mode prefix this process lifetime. Reset behavior: app restarts
    // automatically reset (the map is process-local). Users can also
    // change session ID in the companion app to force a re-send.
    private val sessionsWithPrefixSent = java.util.Collections.synchronizedSet(HashSet<String>())

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
        val wsCandidates = resolveWsCandidates()
        if (wsCandidates.isEmpty()) return@withContext ClawResult.NotConfigured
        val token = gatewayTokenProvider()
        if (token.isNullOrBlank()) {
            Log.w(TAG, "OpenClaw gateway token is not set")
            return@withContext ClawResult.Error("TapClaw gateway token not configured. Add it in TapInsight setup.")
        }

        val agentId = sessionIdProvider().ifBlank { "main" }
        val idempotencyKey = UUID.randomUUID().toString()
        // Per-session mode prefix: only fires for the FIRST message in
        // each agentId session this process. Subsequent messages reuse
        // the mode TapClaw is already in. consumeModePrefixForSession
        // returns "" when no prefix is configured OR the session has
        // already received it.
        val modePrefix = consumeModePrefixForSession(agentId)
        val fullMessage = buildString {
            if (modePrefix.isNotBlank()) {
                append(modePrefix)
                append("\n\n")
            }
            if (!context.isNullOrBlank()) {
                append("[Context from AR glasses: $context]\n\n")
            }
            append(message)
        }

        var lastError: ClawResult.Error? = null
        val hasImage = !imageBase64.isNullOrBlank()

        for ((index, wsUrl) in wsCandidates.withIndex()) {
            Log.d(
                TAG,
                "Sending to TapClaw via WebSocket: agent=$agentId url=$wsUrl hasImage=$hasImage msg=${message.take(100)}"
            )

            // If we have a camera image, upload it to the image relay running on the
            // same host as the OpenClaw gateway. The relay saves it directly to
            // OpenClaw's workspace (~/.openclaw/workspace/camera_frame.jpg).
            // The agent can then read the file from its workspace.
            var imageDelivered = false
            if (hasImage) {
                com.rayneo.visionclaw.core.session.CaptureFeedback.captured()
                val relayUrl = buildRelayUrl(wsUrl)
                imageDelivered = uploadToRelay(imageBase64!!, relayUrl)
                if (imageDelivered) {
                    com.rayneo.visionclaw.core.session.CaptureFeedback.delivered("your workspace")
                }
            }

            val finalMessage = if (imageDelivered) {
                "$fullMessage\n\n[A camera image from the user's AR glasses has been saved to your workspace as $FRAME_FILENAME — please open and analyze this image file to answer the user's question.]"
            } else {
                fullMessage
            }

            val result = try {
                sendViaWebSocket(wsUrl, token, agentId, finalMessage, idempotencyKey, null)
            } catch (e: java.net.ConnectException) {
                Log.e(TAG, "Cannot connect to TapClaw at $wsUrl", e)
                ClawResult.Error("Cannot connect to TapClaw. Make sure it's running and accessible.")
            } catch (e: java.net.SocketTimeoutException) {
                Log.e(TAG, "TapClaw request timed out at $wsUrl", e)
                ClawResult.Error("TapClaw request timed out. Try again or increase the timeout.")
            } catch (e: Exception) {
                Log.e(TAG, "TapClaw request failed at $wsUrl", e)
                ClawResult.Error(e.localizedMessage ?: "TapClaw request failed")
            }

            when (result) {
                is ClawResult.Success -> return@withContext result
                is ClawResult.Error -> {
                    lastError = result
                    val hasAlternate = index < wsCandidates.lastIndex
                    if (hasAlternate && shouldRetryWithAlternateEndpoint(result.message)) {
                        Log.w(TAG, "Primary TapClaw endpoint failed, trying alternate endpoint next: ${result.message}")
                        continue
                    }
                    return@withContext result
                }
                is ClawResult.NotConfigured -> Unit
            }
        }

        lastError ?: ClawResult.Error("Cannot connect to TapClaw. Make sure it's running and accessible.")
    }

    suspend fun ping(): ClawResult = withContext(Dispatchers.IO) {
        val wsCandidates = resolveWsCandidates()
        if (wsCandidates.isEmpty()) return@withContext ClawResult.NotConfigured
        val token = gatewayTokenProvider()

        var lastError = "TapClaw health check failed"
        for (wsUrl in wsCandidates) {
            try {
                val result = callMethod(wsUrl, token, "health", JSONObject())
                if (result != null) {
                    return@withContext ClawResult.Success(text = "TapClaw is reachable", sessionId = null)
                }
                lastError = "TapClaw health check failed"
            } catch (e: Exception) {
                Log.e(TAG, "TapClaw ping failed at $wsUrl", e)
                lastError = "Cannot reach TapClaw: ${e.localizedMessage}"
            }
        }
        ClawResult.Error(lastError)
    }

    // ── Connect frame builder ───────────────────────────────────────────

    /**
     * Build the connect frame, including device identity + Ed25519 signature
     * if device keys are available, or token-only auth as fallback.
     */
    private fun buildConnectFrame(requestId: String, token: String, nonce: String): JSONObject {
        val deviceId = deviceIdProvider()
        val publicKey = publicKeyProvider()
        val privateKey = privateKeyProvider()
        val hasDeviceIdentity = !deviceId.isNullOrBlank() && !publicKey.isNullOrBlank() && !privateKey.isNullOrBlank()

        val signedAtMs = System.currentTimeMillis()

        val params = JSONObject().apply {
            put("minProtocol", 3)
            put("maxProtocol", 3)
            put("client", JSONObject().apply {
                put("id", CLIENT_ID)
                put("version", "1.1.2")
                put("platform", CLIENT_PLATFORM)
                put("deviceFamily", CLIENT_DEVICE_FAMILY)
                put("mode", CLIENT_MODE)
                put("instanceId", CLIENT_INSTANCE_ID)
            })
            put("role", ROLE)
            put("scopes", JSONArray().apply {
                put("operator.read")
                put("operator.write")
            })
            put("caps", JSONArray())
            put("userAgent", "TapInsight/1.1.2")
            put("locale", Locale.getDefault().toLanguageTag())

            // Auth block
            put("auth", JSONObject().apply {
                put("token", token)
            })

            // Device identity with Ed25519 signature (required by gateway)
            if (hasDeviceIdentity) {
                val payload = buildAuthPayloadV3(
                    deviceId = deviceId!!,
                    signedAtMs = signedAtMs,
                    token = token,
                    nonce = nonce
                )
                val signature = signPayload(privateKey!!, payload)
                put("device", JSONObject().apply {
                    put("id", deviceId)
                    put("publicKey", publicKey)
                    put("signature", signature)
                    put("signedAt", signedAtMs)
                    put("nonce", nonce)
                })
                Log.d(TAG, "Connect frame includes device identity: ${deviceId.take(16)}...")
            } else {
                Log.w(TAG, "No device identity available — token-only auth (may be rejected)")
            }
        }

        return JSONObject().apply {
            put("type", "req")
            put("id", requestId)
            put("method", "connect")
            put("params", params)
        }
    }

    /**
     * Build the v3 auth payload string for signing.
     * Must match OpenClawPairingClient.buildDeviceAuthPayloadV3 format exactly.
     */
    private fun buildAuthPayloadV3(
        deviceId: String,
        signedAtMs: Long,
        token: String,
        nonce: String
    ): String {
        val normalizedPlatform = CLIENT_PLATFORM.trim().lowercase()
        val normalizedDeviceFamily = CLIENT_DEVICE_FAMILY.trim().lowercase()
        return listOf(
            "v3",
            deviceId,
            CLIENT_ID,
            CLIENT_MODE,
            ROLE,
            "operator.read,operator.write", // scopes
            signedAtMs.toString(),
            token,
            nonce,
            normalizedPlatform,
            normalizedDeviceFamily
        ).joinToString("|")
    }

    private fun signPayload(privateKeyBase64Url: String, payload: String): String {
        val privateKeyBytes = base64UrlDecoder.decode(privateKeyBase64Url)
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(privateKeyBytes, 0))
        val payloadBytes = payload.toByteArray(Charsets.UTF_8)
        signer.update(payloadBytes, 0, payloadBytes.size)
        return base64UrlEncoder.encodeToString(signer.generateSignature())
    }

    // ── WebSocket transport ─────────────────────────────────────────────

    private suspend fun sendViaWebSocket(
        wsUrl: String,
        token: String,
        agentId: String,
        message: String,
        idempotencyKey: String,
        @Suppress("UNUSED_PARAMETER") imageBase64: String? = null
    ): ClawResult = suspendCancellableCoroutine { cont ->
        val timeoutMs = timeoutMsProvider().toLong()

        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .pingInterval(15, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder().url(wsUrl).build()

        var resumed = false
        var connected = false
        var acceptedRunId: String? = null
        val connectRequestId = "conn-" + System.currentTimeMillis()
        val agentRequestId = "agent-" + System.currentTimeMillis()
        val collectedTexts = mutableListOf<String>()

        // One-shot completion signal for UI (heartbeat ticker cleanup + TTS).
        // Fired at most once per sendViaWebSocket call, immediately before each
        // cont.resume. `success` distinguishes a normal final response from
        // any error / timeout / auth-failure / close path.
        var completeFired = false
        val fireComplete: (Boolean) -> Unit = { success ->
            if (!completeFired) {
                completeFired = true
                try {
                    onProgressComplete?.invoke(success)
                } catch (e: Exception) {
                    Log.w(TAG, "onProgressComplete handler threw: ${e.message}")
                }
            }
        }

        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket opened to TapClaw gateway, waiting for challenge...")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "WS message: ${text.take(500)}")

                try {
                    val json = JSONObject(text)
                    val type = json.optString("type", "")
                    val event = json.optString("event", "")

                    when {
                        // Server sends connect challenge — respond with signed auth
                        type == "event" && event == "connect.challenge" -> {
                            val nonce = json.optJSONObject("payload")?.optString("nonce", "") ?: ""
                            Log.d(TAG, "Received connect challenge, nonce=${nonce.take(20)}...")

                            val connectFrame = buildConnectFrame(connectRequestId, token, nonce)
                            webSocket.send(connectFrame.toString())
                            Log.d(TAG, "Sent signed connect frame")
                        }

                        // Connect response — check if auth succeeded
                        type == "res" && json.optString("id", "") == connectRequestId -> {
                            if (json.optBoolean("ok", false)) {
                                Log.d(TAG, "Gateway auth succeeded, sending agent call")
                                connected = true

                                // Always use text-only agent method.
                                // Images are delivered to OpenClaw's workspace via the
                                // image relay (tools/image_relay.py) before we get here.
                                // The message text tells the agent to read camera_frame.jpg.
                                val agentParams = JSONObject().apply {
                                    put("message", message)
                                    put("idempotencyKey", idempotencyKey)
                                    put("agentId", agentId)
                                }
                                val agentCall = JSONObject().apply {
                                    put("type", "req")
                                    put("id", agentRequestId)
                                    put("method", "agent")
                                    put("params", agentParams)
                                }
                                webSocket.send(agentCall.toString())
                                Log.d(TAG, "Sent agent call: agent=$agentId key=$idempotencyKey")
                            } else {
                                val error = json.optJSONObject("error")
                                val errorMsg = error?.optString("message", "Authentication failed")
                                    ?: "Authentication failed"
                                Log.e(TAG, "Gateway auth failed: $errorMsg")
                                if (!resumed) {
                                    resumed = true
                                    fireComplete(false)
                                    cont.resume(ClawResult.Error("TapClaw auth failed: $errorMsg"))
                                    webSocket.close(1000, "auth failed")
                                }
                            }
                        }

                        // Response to our agent/chat.send call — may be "accepted" ack or final result
                        type == "res" && json.optString("id", "") == agentRequestId -> {
                            if (json.optBoolean("ok", false)) {
                                val result = json.optJSONObject("payload")
                                val status = result?.optString("status", "")
                                val runId = result?.optString("runId", null)
                                    ?: result?.optString("id", null)

                                // Gateway sends "accepted" (agent method) or "ok" (chat.send)
                                // first, then the real result streams as chat/agent events.
                                if (status == "accepted" || (runId != null && status != "error")) {
                                    acceptedRunId = runId ?: idempotencyKey
                                    Log.d(TAG, "Call accepted (runId=$acceptedRunId), waiting for chat events...")
                                    return@onMessage
                                }

                                val payloads = result?.optJSONArray("payloads")
                                val responseText = extractPayloadText(payloads)
                                val model = result?.optJSONObject("meta")
                                    ?.optJSONObject("agentMeta")
                                    ?.optString("model", null)
                                val sessionId = result?.optJSONObject("meta")
                                    ?.optJSONObject("agentMeta")
                                    ?.optString("sessionId", null)

                                Log.d(TAG, "TapClaw final response: ${responseText.take(200)}")
                                if (!resumed) {
                                    resumed = true
                                    if (responseText.isNotBlank()) {
                                        fireComplete(true)
                                        cont.resume(ClawResult.Success(
                                            text = responseText,
                                            model = model,
                                            sessionId = sessionId
                                        ))
                                    } else {
                                        fireComplete(false)
                                        cont.resume(ClawResult.Error("TapClaw returned an empty response."))
                                    }
                                    webSocket.close(1000, "done")
                                }
                            } else {
                                val error = json.optJSONObject("error")
                                val errorMsg = error?.optString("message", "Agent call failed")
                                    ?: "Agent call failed"
                                Log.e(TAG, "Agent call failed: $errorMsg")
                                if (!resumed) {
                                    resumed = true
                                    fireComplete(false)
                                    cont.resume(ClawResult.Error("TapClaw: $errorMsg"))
                                    webSocket.close(1000, "done")
                                }
                            }
                        }

                        // Alternative result format
                        json.has("result") && !json.has("id") -> {
                            val result = json.optJSONObject("result")
                            val payloads = result?.optJSONArray("payloads")
                            val responseText = extractPayloadText(payloads)
                            val model = result?.optJSONObject("meta")
                                ?.optJSONObject("agentMeta")
                                ?.optString("model", null)
                            val sessionId = result?.optJSONObject("meta")
                                ?.optJSONObject("agentMeta")
                                ?.optString("sessionId", null)

                            if (!resumed) {
                                resumed = true
                                if (responseText.isNotBlank()) {
                                    fireComplete(true)
                                    cont.resume(ClawResult.Success(text = responseText, model = model, sessionId = sessionId))
                                } else {
                                    fireComplete(false)
                                    cont.resume(ClawResult.Error("TapClaw returned an empty response."))
                                }
                                webSocket.close(1000, "done")
                            }
                        }

                        // Error
                        type == "error" || (json.has("error") && !json.has("id")) -> {
                            val errorMsg = json.optString("error",
                                json.optJSONObject("error")?.optString("message", "Unknown gateway error")
                                    ?: "Unknown gateway error")
                            Log.e(TAG, "Gateway error: $errorMsg")
                            if (!resumed) {
                                resumed = true
                                fireComplete(false)
                                cont.resume(ClawResult.Error(errorMsg))
                                webSocket.close(1000, "done")
                            }
                        }

                        // Agent lifecycle events (error, completion)
                        type == "event" && event == "agent" && acceptedRunId != null -> {
                            val payload = json.optJSONObject("payload")
                            val data = payload?.optJSONObject("data")
                            val phase = data?.optString("phase", "")
                            val stream = payload?.optString("stream", "")

                            when {
                                // Agent error (e.g., rate limit)
                                phase == "error" && !resumed -> {
                                    val errorMsg = data?.optString("error", "Agent error")
                                        ?: "Agent error"
                                    Log.e(TAG, "Agent lifecycle error: $errorMsg")
                                    resumed = true
                                    fireComplete(false)
                                    cont.resume(ClawResult.Error("TapClaw: $errorMsg"))
                                    webSocket.close(1000, "done")
                                }
                                // Agent completed with result
                                phase == "complete" || phase == "done" || phase == "finished" -> {
                                    val result = data?.optString("result", null)
                                        ?: data?.optString("text", null)
                                    val payloads = data?.optJSONArray("payloads")
                                    val responseText = when {
                                        payloads != null -> extractPayloadText(payloads)
                                        !result.isNullOrBlank() -> result
                                        else -> ""
                                    }
                                    if (responseText.isNotBlank() && !resumed) {
                                        Log.d(TAG, "TapClaw agent complete: ${responseText.take(200)}")
                                        resumed = true
                                        fireComplete(true)
                                        cont.resume(ClawResult.Success(text = responseText))
                                        webSocket.close(1000, "done")
                                    }
                                }
                                else -> Log.d(TAG, "Agent lifecycle: stream=$stream phase=$phase")
                            }
                        }

                        // Chat event — contains the agent's text response
                        // Events arrive with state="delta" (streaming chunks) then state="final" (complete).
                        // We only act on "final" to get the full response text.
                        type == "event" && event == "chat" && acceptedRunId != null && !resumed -> {
                            val payload = json.optJSONObject("payload")
                            val state = payload?.optString("state", "")

                            if (state == "delta") {
                                // Streaming chunk — notify progress listener, don't resolve yet
                                val deltaText = extractChatMessageText(payload)
                                if (deltaText.isNotBlank()) {
                                    onProgressUpdate?.invoke(deltaText)
                                }
                                Log.d(TAG, "Chat delta (streaming, waiting for final)")
                            } else if (state == "final" || state == "complete" || state == "done") {
                                // Final response — extract text from message.content[0].text
                                val responseText = extractChatMessageText(payload)

                                if (responseText.isNotBlank()) {
                                    Log.d(TAG, "TapClaw chat final: ${responseText.take(200)}")
                                    val model = payload?.optJSONObject("meta")
                                        ?.optJSONObject("agentMeta")
                                        ?.optString("model", null)
                                    val sessionId = payload?.optJSONObject("meta")
                                        ?.optJSONObject("agentMeta")
                                        ?.optString("sessionId", null)
                                    resumed = true
                                    fireComplete(true)
                                    cont.resume(ClawResult.Success(text = responseText, model = model, sessionId = sessionId))
                                    webSocket.close(1000, "done")
                                } else {
                                    Log.w(TAG, "Chat final event but no text extracted: ${payload?.toString()?.take(300)}")
                                    collectedTexts.add(payload?.toString() ?: "")
                                }
                            } else {
                                // Unknown state — log for debugging
                                Log.d(TAG, "Chat event state=$state: ${payload?.toString()?.take(300)}")
                                collectedTexts.add(payload?.toString() ?: "")
                            }
                        }

                        // Other events after accepted (presence, health, etc.) — skip
                        type == "event" && acceptedRunId != null && !resumed -> {
                            Log.d(TAG, "Skipping event after accepted: event=$event")
                        }

                        // Streaming partial
                        json.has("payloads") -> {
                            val payloads = json.optJSONArray("payloads")
                            val partialText = extractPayloadText(payloads)
                            if (partialText.isNotBlank()) collectedTexts.add(partialText)
                        }

                        else -> Log.d(TAG, "Unhandled WS message type=$type event=$event")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse WS message: ${e.message}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}", t)
                if (!resumed) {
                    resumed = true
                    val msg = when {
                        t is java.net.ConnectException -> "Cannot connect to TapClaw. Is it running?"
                        t is java.net.SocketTimeoutException -> "TapClaw connection timed out."
                        else -> "TapClaw connection failed: ${t.localizedMessage}"
                    }
                    fireComplete(false)
                    cont.resume(ClawResult.Error(msg))
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: code=$code reason=$reason")
                if (!resumed && collectedTexts.isNotEmpty()) {
                    resumed = true
                    fireComplete(true)
                    cont.resume(ClawResult.Success(text = collectedTexts.joinToString("\n")))
                } else if (!resumed) {
                    resumed = true
                    val msg = if (code == 1008) "TapClaw auth failed: $reason"
                             else "TapClaw closed connection: $reason"
                    fireComplete(false)
                    cont.resume(ClawResult.Error(msg))
                }
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: code=$code")
                if (!resumed) {
                    resumed = true
                    fireComplete(false)
                    cont.resume(ClawResult.Error("TapClaw connection closed unexpectedly"))
                }
            }
        })

        cont.invokeOnCancellation {
            Log.d(TAG, "Coroutine cancelled, closing WebSocket")
            ws.cancel()
        }
    }

    /**
     * Simple one-shot RPC call (e.g. health) with challenge-response auth.
     */
    private suspend fun callMethod(
        wsUrl: String,
        token: String?,
        method: String,
        params: JSONObject
    ): JSONObject? = suspendCancellableCoroutine { cont ->
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder().url(wsUrl).build()
        var resumed = false
        val connectId = "hc-" + System.currentTimeMillis()
        val callId = "call-" + System.currentTimeMillis()

        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "Health check WS opened, waiting for challenge...")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    val type = json.optString("type", "")
                    val event = json.optString("event", "")

                    when {
                        type == "event" && event == "connect.challenge" -> {
                            if (!token.isNullOrBlank()) {
                                val nonce = json.optJSONObject("payload")?.optString("nonce", "") ?: ""
                                val connectFrame = buildConnectFrame(connectId, token, nonce)
                                webSocket.send(connectFrame.toString())
                            }
                        }

                        type == "res" && json.optString("id", "") == connectId -> {
                            if (json.optBoolean("ok", false)) {
                                val callMsg = JSONObject().apply {
                                    put("type", "req")
                                    put("id", callId)
                                    put("method", method)
                                    put("params", params)
                                }
                                webSocket.send(callMsg.toString())
                            } else {
                                if (!resumed) { resumed = true; cont.resume(null) }
                                webSocket.close(1000, "auth failed")
                            }
                        }

                        type == "res" && json.optString("id", "") == callId -> {
                            if (!resumed) {
                                resumed = true
                                cont.resume(json)
                                webSocket.close(1000, "done")
                            }
                        }
                    }
                } catch (_: Exception) {}
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!resumed) { resumed = true; cont.resume(null) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (!resumed) { resumed = true; cont.resume(null) }
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!resumed) { resumed = true; cont.resume(null) }
            }
        })

        cont.invokeOnCancellation { ws.cancel() }
    }

    // ── Image relay ─────────────────────────────────────────────────────

    /**
     * POST the camera frame to the image relay service running on the OpenClaw host.
     * The relay saves it to ~/.openclaw/workspace/camera_frame.jpg so the agent
     * can read it from its workspace. This bypasses the gateway's attachment stripping.
     *
     * The relay (tools/image_relay.py) is auto-started via macOS launchd.
     */
    private fun uploadToRelay(imageBase64: String, relayUrl: String): Boolean {
        try {
            val imageBytes = Base64.getDecoder().decode(imageBase64)
            Log.d(TAG, "Uploading ${imageBytes.size / 1024}KB frame to relay at $relayUrl")

            val httpClient = OkHttpClient.Builder()
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(3, TimeUnit.SECONDS)
                .writeTimeout(5, TimeUnit.SECONDS)
                .build()

            val request = Request.Builder()
                .url(relayUrl)
                .post(imageBytes.toRequestBody("image/jpeg".toMediaType()))
                .build()

            val response = httpClient.newCall(request).execute()
            val body = response.body?.string()

            if (response.isSuccessful) {
                Log.d(TAG, "Frame uploaded to relay OK: $body")
                return true
            } else {
                Log.e(TAG, "Relay HTTP ${response.code}: $body")
                return false
            }
        } catch (e: java.net.ConnectException) {
            Log.w(TAG, "Image relay not reachable at $relayUrl — TapClaw vision relay may not be running on the Mac")
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Relay upload failed", e)
            return false
        }
    }

    /** Extract the host (IP or domain) from a WebSocket URL. */
    private fun extractHost(wsUrl: String): String {
        return wsUrl
            .removePrefix("wss://").removePrefix("ws://")
            .removePrefix("https://").removePrefix("http://")
            .split(":")[0].split("/")[0]
    }

    /**
     * Build the relay URL for uploading camera frames.
     *
     * If the gateway is a local IP (e.g. ws://192.168.1.50:18789), use the
     * relay on the same host: http://192.168.1.50:18790/frame
     *
     * If the gateway is a remote domain (e.g. wss://tapclaw.example.com),
     * the relay is assumed to be tunneled at relay.<basedomain>/frame.
     * For example: wss://tapclaw.example.com → https://relay.example.com/frame
     */
    private fun buildRelayUrl(wsUrl: String): String {
        // Shared RelayUrlHelper logic (LAN IP → http://<ip>:18790/frame,
        // remote domain → https://relay.<basedomain>/frame). The fallback
        // arm only fires when no host at all could be extracted.
        return RelayUrlHelper.frameUrlFromEndpoint(wsUrl)
            ?: "http://${extractHost(wsUrl)}:$IMAGE_RELAY_PORT/frame"
    }

    // ── Private helpers ──────────────────────────────────────────────────

    private fun normalizeWsUrl(raw: String?): String? {
        val rawValue = raw?.trim().orEmpty()
        if (rawValue.isBlank()) return null

        var url = rawValue.trimEnd('/')
        url = when {
            url.startsWith("ws://") || url.startsWith("wss://") -> url
            url.startsWith("https://") -> url.replace("https://", "wss://")
            url.startsWith("http://") -> url.replace("http://", "ws://")
            else -> "ws://$url"
        }

        val schemeEnd = url.indexOf("://") + 3
        val hostPart = url.substring(schemeEnd)
        val isSecure = url.startsWith("wss://")
        val looksLikeDomain = hostPart.contains('.') &&
            !hostPart.matches(Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+.*"))
        if (!hostPart.contains(':') && !(isSecure && looksLikeDomain)) {
            url = url.substring(0, schemeEnd) + hostPart + ":$DEFAULT_PORT"
        }
        return url
    }

    // ── Mode bracket helpers ────────────────────────────────────────

    /**
     * Build a slash-command prefix string from the current
     * fastMode + thinkLevel pref values. Returns "" when neither
     * is configured. Format is one slash command per line so
     * TapClaw parses each command independently.
     *
     * Examples:
     *   fast="off",  think=""        → ""
     *   fast="fast", think=""        → "/fast"
     *   fast="off",  think="low"     → "/think low"
     *   fast="fast", think="medium"  → "/fast\n/think medium"
     */
    private fun buildModePrefix(fastMode: String, thinkLevel: String): String = buildString {
        val cleanFast = fastMode.trim().lowercase()
        val cleanThink = thinkLevel.trim().lowercase()
        if (cleanFast == "fast") {
            append("/fast")
        }
        if (cleanThink == "low" || cleanThink == "medium" || cleanThink == "high") {
            if (isNotEmpty()) append('\n')
            append("/think ")
            append(cleanThink)
        }
    }

    /**
     * Returns the mode prefix to prepend to the very first message of
     * [sessionId]'s session. Subsequent messages in the same session
     * return "". The "session" boundary is process-lifetime: an app
     * restart re-enables the prefix, as does the user switching
     * session IDs in the companion app.
     *
     * Public so MainActivity can also reset on demand if needed
     * (e.g., when the user explicitly toggles the mode dropdown
     * mid-session and wants the new value applied immediately).
     */
    fun consumeModePrefixForSession(sessionId: String): String {
        val key = sessionId.ifBlank { "main" }
        if (sessionsWithPrefixSent.contains(key)) return ""
        val prefix = buildModePrefix(fastModeProvider(), thinkLevelProvider())
        // Mark as consumed even when prefix is empty — flipping the
        // prefs mid-session won't re-fire the prefix. That's the
        // desired semantics: bracketing is "set the profile once at
        // start of session, restore at the end."
        sessionsWithPrefixSent.add(key)
        return prefix
    }

    /** Force the prefix to be re-sent on the next message for [sessionId]. */
    fun resetModePrefixForSession(sessionId: String) {
        sessionsWithPrefixSent.remove(sessionId.ifBlank { "main" })
    }

    /**
     * After a successful agent turn completes, optionally send a
     * fire-and-forget slash-command message that restores the bare
     * TapClaw to a chosen profile (typically the user's normal
     * default). Called by MainActivity from onProgressComplete.
     *
     * Shape: a single agent message containing only the slash
     * commands — no user text. TapClaw's first token sees the
     * commands and applies them, then the empty body ends the turn.
     *
     * Errors are logged + swallowed; the after-turn restore is best-
     * effort and never blocks the user.
     */
    suspend fun fireAfterTurnRestoreIfConfigured() {
        val prefix = buildModePrefix(afterFastModeProvider(), afterThinkLevelProvider())
        if (prefix.isBlank()) return
        try {
            withContext(Dispatchers.IO) {
                val wsCandidates = resolveWsCandidates()
                if (wsCandidates.isEmpty()) return@withContext
                val token = gatewayTokenProvider() ?: return@withContext
                val agentId = sessionIdProvider().ifBlank { "main" }
                val idempotencyKey = UUID.randomUUID().toString()
                Log.d(TAG, "After-turn mode restore: sending '$prefix' to session=$agentId")
                runCatching {
                    sendViaWebSocket(
                        wsCandidates.first(), token, agentId,
                        prefix, idempotencyKey, null
                    )
                }.onFailure {
                    Log.w(TAG, "After-turn mode restore failed (ignored): ${it.message}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "After-turn mode restore threw (ignored): ${e.message}")
        }
    }

    private fun resolveWsCandidates(): List<String> {
        return listOf(
            gatewayUrlProvider(),
            fallbackGatewayUrlProvider()
        ).mapNotNull { normalizeWsUrl(it) }
            .distinct()
    }

    private fun shouldRetryWithAlternateEndpoint(message: String): Boolean {
        val lower = message.lowercase()
        return lower.contains("cannot connect") ||
            lower.contains("timed out") ||
            lower.contains("connection failed") ||
            lower.contains("closed unexpectedly") ||
            lower.contains("unreachable") ||
            lower.contains("failed to connect") ||
            lower.contains("no route")
    }

    /**
     * Extract text from a chat event payload.
     * The gateway sends: payload.message = {"role":"assistant","content":[{"type":"text","text":"..."}]}
     * We need to drill into message.content[0].text to get the actual response.
     * Falls back to other possible locations if the nested structure isn't found.
     */
    private fun extractChatMessageText(payload: JSONObject?): String {
        if (payload == null) return ""

        // Primary path: message.content[].text (OpenClaw chat format)
        val message = payload.optJSONObject("message")
        if (message != null) {
            val contentArray = message.optJSONArray("content")
            if (contentArray != null && contentArray.length() > 0) {
                val parts = mutableListOf<String>()
                for (i in 0 until contentArray.length()) {
                    val block = contentArray.optJSONObject(i) ?: continue
                    val blockType = block.optString("type", "")
                    if (blockType == "text" || blockType.isBlank()) {
                        val text = block.optString("text", "").trim()
                        if (text.isNotBlank()) parts.add(text)
                    }
                }
                if (parts.isNotEmpty()) return parts.joinToString("\n")
            }
            // message might have a direct text field
            val directText = message.optString("text", "").trim()
            if (directText.isNotBlank()) return directText
        }

        // Fallback: payload.text or payload.content directly
        val text = payload.optString("text", "").trim()
        if (text.isNotBlank()) return text

        val content = payload.optString("content", "").trim()
        if (content.isNotBlank()) return content

        // Fallback: payload.payloads array
        val payloads = payload.optJSONArray("payloads")
        if (payloads != null) return extractPayloadText(payloads)

        // Fallback: payload.data.text
        val data = payload.optJSONObject("data")
        val dataText = data?.optString("text", "")?.trim() ?: ""
        if (dataText.isNotBlank()) return dataText

        return ""
    }

    private fun extractPayloadText(payloads: JSONArray?): String {
        if (payloads == null || payloads.length() == 0) return ""
        val parts = mutableListOf<String>()
        for (i in 0 until payloads.length()) {
            val payload = payloads.optJSONObject(i) ?: continue
            val text = payload.optString("text", "").trim()
            if (text.isNotBlank()) parts.add(text)
        }
        return parts.joinToString("\n")
    }
}
