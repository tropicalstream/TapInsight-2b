package com.rayneo.visionclaw.core.network

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.rayneo.visionclaw.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.json.JSONObject
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

class OpenClawPairingClient(
    private val context: Context,
    private val prefs: SharedPreferences
) {
    companion object {
        private const val TAG = "OpenClawPairing"
        private const val PREF_DEVICE_ID = "openclaw_pair_device_id"
        private const val PREF_PUBLIC_KEY = "openclaw_pair_public_key"
        private const val PREF_PRIVATE_KEY = "openclaw_pair_private_key"
        private const val PREF_DEVICE_TOKEN = "openclaw_pair_device_token"
        private const val PREF_DEVICE_TOKEN_GATEWAY = "openclaw_pair_device_token_gateway"
        private const val CLIENT_ID = "openclaw-android"
        // OpenClaw QR/setup codes create *node* pairing requests, not operator/UI sessions.
        // Using operator/ui here makes the gateway reject a fresh bootstrap token as invalid.
        private const val CLIENT_MODE = "node"
        private const val CLIENT_PLATFORM = "android"
        private const val CLIENT_DEVICE_FAMILY = "RayNeo X3 Pro"
        private const val CLIENT_INSTANCE_ID = "rayneo-x3"
        private const val ROLE = "node"
        private val REQUESTED_SCOPES = emptyList<String>()
    }

    data class DebugInfo(
        val endpoint: String,
        val gatewayWsUrl: String,
        val deviceId: String,
        val clientId: String,
        val clientMode: String,
        val platform: String,
        val deviceFamily: String,
        val role: String,
        val scopes: List<String>,
        val bootstrapTokenSuffix: String,
        val payloadVersion: String,
        val gatewayErrorCode: String? = null,
        val gatewayErrorDetails: String? = null,
        val closeCode: Int? = null,
        val closeReason: String? = null,
        val lastServerFrame: String? = null
    )

    sealed class PairingResult {
        data class PendingApproval(
            val endpoint: String,
            val requestId: String,
            val deviceId: String,
            val debug: DebugInfo
        ) : PairingResult()

        data class Approved(
            val endpoint: String,
            val deviceId: String,
            val hasDeviceToken: Boolean,
            val debug: DebugInfo
        ) : PairingResult()

        data class Failed(
            val message: String,
            val endpoint: String? = null,
            val debug: DebugInfo? = null
        ) : PairingResult()
    }

    private data class SetupPayload(
        val url: String,
        val bootstrapToken: String
    )

    private data class DeviceIdentity(
        val deviceId: String,
        val publicKey: String,
        val privateKey: String
    )

    private val base64UrlEncoder = Base64.getUrlEncoder().withoutPadding()
    private val base64UrlDecoder = Base64.getUrlDecoder()
    private val wsClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    suspend fun startOrCheckPairing(setupCode: String, fallbackEndpoint: String? = null): PairingResult = withContext(Dispatchers.IO) {
        val payload = decodeSetupCode(setupCode)
            ?: fallbackEndpoint
                ?.takeIf { it.isNotBlank() }
                ?.let { SetupPayload(it, setupCode.trim()) }
            ?: return@withContext PairingResult.Failed(
                message = "This OpenClaw setup code could not be decoded. Generate a fresh QR and try again."
            )
        val endpoint = normalizeGatewayUrl(payload.url)
            ?: return@withContext PairingResult.Failed(
                message = "This OpenClaw setup code did not include a valid gateway URL."
            )
        val gatewayWsUrl = normalizeGatewayWsUrl(payload.url)
            ?: return@withContext PairingResult.Failed(
                message = "This OpenClaw setup code did not include a valid WebSocket URL.",
                endpoint = endpoint
            )
        if (payload.bootstrapToken.isBlank()) {
            return@withContext PairingResult.Failed(
                message = "This OpenClaw setup code did not include a bootstrap token.",
                endpoint = endpoint
            )
        }

        val identity = loadOrCreateIdentity()
        performPairingHandshake(
            gatewayWsUrl = gatewayWsUrl,
            endpoint = endpoint,
            bootstrapToken = payload.bootstrapToken,
            identity = identity
        )
    }

    private suspend fun performPairingHandshake(
        gatewayWsUrl: String,
        endpoint: String,
        bootstrapToken: String,
        identity: DeviceIdentity
    ): PairingResult = suspendCancellableCoroutine { continuation ->
        var socket: WebSocket? = null
        var connectRequestId: String? = null
        var finished = false
        var lastServerFrame: String? = null
        var lastGatewayErrorCode: String? = null
        var lastGatewayErrorDetails: String? = null
        var lastCloseCode: Int? = null
        var lastCloseReason: String? = null

        fun currentDebug(): DebugInfo = DebugInfo(
            endpoint = endpoint,
            gatewayWsUrl = gatewayWsUrl,
            deviceId = identity.deviceId,
            clientId = CLIENT_ID,
            clientMode = CLIENT_MODE,
            platform = CLIENT_PLATFORM,
            deviceFamily = CLIENT_DEVICE_FAMILY,
            role = ROLE,
            scopes = REQUESTED_SCOPES,
            bootstrapTokenSuffix = bootstrapToken.takeLast(6),
            payloadVersion = "v3",
            gatewayErrorCode = lastGatewayErrorCode,
            gatewayErrorDetails = lastGatewayErrorDetails,
            closeCode = lastCloseCode,
            closeReason = lastCloseReason,
            lastServerFrame = lastServerFrame
        )

        fun finish(result: PairingResult) {
            if (finished) return
            finished = true
            continuation.resume(result)
            socket?.close(1000, "done")
        }

        val request = Request.Builder().url(gatewayWsUrl).build()
        socket = wsClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "OpenClaw pairing socket opened for $gatewayWsUrl")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    lastServerFrame = text.take(1200)
                    val root = JSONObject(text)
                    val type = root.optString("type", "")
                    val event = root.optString("event", "")

                    if (type.equals("event", ignoreCase = true) &&
                        event.equals("connect.challenge", ignoreCase = true)
                    ) {
                        if (!connectRequestId.isNullOrBlank()) return
                        val nonce = root.optJSONObject("payload")?.optString("nonce", "").orEmpty()
                        val connectId = "pair-" + System.currentTimeMillis()
                        connectRequestId = connectId
                        val sent = webSocket.send(
                            buildConnectFrame(
                                requestId = connectId,
                                bootstrapToken = bootstrapToken,
                                identity = identity,
                                nonce = nonce
                            ).toString()
                        )
                        if (!sent) {
                            finish(
                                PairingResult.Failed(
                                    message = "Failed to send OpenClaw pairing request.",
                                    endpoint = endpoint,
                                    debug = currentDebug()
                                )
                            )
                        }
                        return
                    }

                    if (type.equals("res", ignoreCase = true)) {
                        val responseId = root.optString("id", "")
                        if (!connectRequestId.isNullOrBlank() && responseId == connectRequestId) {
                            val ok = root.optBoolean("ok", false)
                            if (ok) {
                                val payload = root.optJSONObject("payload")
                                val deviceToken = payload
                                    ?.optJSONObject("auth")
                                    ?.optString("deviceToken", "")
                                    .orEmpty()
                                if (deviceToken.isNotBlank()) {
                                    prefs.edit()
                                        .putString(PREF_DEVICE_TOKEN, deviceToken)
                                        .putString(PREF_DEVICE_TOKEN_GATEWAY, endpoint)
                                        .apply()
                                }
                                finish(
                                    PairingResult.Approved(
                                        endpoint = endpoint,
                                        deviceId = identity.deviceId,
                                        hasDeviceToken = deviceToken.isNotBlank(),
                                        debug = currentDebug()
                                    )
                                )
                                return
                            }

                            val error = root.optJSONObject("error")
                            lastGatewayErrorCode = error?.optString("code", "")?.takeIf { it.isNotBlank() }
                            lastGatewayErrorDetails = error?.opt("details")?.toString()
                            val message = error?.optString("message", "Pairing failed.")
                                ?.takeIf { it.isNotBlank() }
                                ?: "Pairing failed."
                            val details = error?.optJSONObject("details")
                            val code = details?.optString("code", "").orEmpty()
                            val requestId = details?.optString("requestId", "").orEmpty()
                            when (code) {
                                "PAIRING_REQUIRED" -> finish(
                                    PairingResult.PendingApproval(
                                        endpoint = endpoint,
                                        requestId = requestId,
                                        deviceId = identity.deviceId,
                                        debug = currentDebug()
                                    )
                                )
                                "AUTH_BOOTSTRAP_TOKEN_INVALID" -> finish(
                                    PairingResult.Failed(
                                        message = "This OpenClaw setup code is invalid or expired. Generate a fresh QR and try again.",
                                        endpoint = endpoint,
                                        debug = currentDebug()
                                    )
                                )
                                else -> finish(
                                    PairingResult.Failed(
                                        message = message,
                                        endpoint = endpoint,
                                        debug = currentDebug()
                                    )
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse OpenClaw pairing message", e)
                    finish(
                        PairingResult.Failed(
                            message = "OpenClaw pairing failed while parsing the server response: ${e.message}",
                            endpoint = endpoint,
                            debug = currentDebug()
                        )
                    )
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val message = t.message ?: "unknown error"
                Log.e(TAG, "OpenClaw pairing socket failure", t)
                finish(
                    PairingResult.Failed(
                        message = "Could not start OpenClaw pairing from the glasses: $message",
                        endpoint = endpoint,
                        debug = currentDebug()
                    )
                )
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                lastCloseCode = code
                lastCloseReason = reason
                if (!finished) {
                    finish(
                        PairingResult.Failed(
                            message = "OpenClaw closed the pairing connection before it completed ($code: $reason).",
                            endpoint = endpoint,
                            debug = currentDebug()
                        )
                    )
                }
            }
        })

        continuation.invokeOnCancellation {
            socket?.close(1000, "cancelled")
        }
    }

    private fun buildConnectFrame(
        requestId: String,
        bootstrapToken: String,
        identity: DeviceIdentity,
        nonce: String
    ): JSONObject {
        val signedAtMs = System.currentTimeMillis()
        val payload = buildDeviceAuthPayloadV3(
            deviceId = identity.deviceId,
            clientId = CLIENT_ID,
            clientMode = CLIENT_MODE,
            role = ROLE,
            scopes = REQUESTED_SCOPES,
            signedAtMs = signedAtMs,
            token = bootstrapToken,
            nonce = nonce,
            platform = CLIENT_PLATFORM,
            deviceFamily = CLIENT_DEVICE_FAMILY
        )
        val signature = signPayload(identity.privateKey, payload)

        return JSONObject()
            .put("type", "req")
            .put("id", requestId)
            .put("method", "connect")
            .put(
                "params",
                JSONObject()
                    .put("minProtocol", 3)
                    .put("maxProtocol", 3)
                    .put(
                        "client",
                        JSONObject()
                            .put("id", CLIENT_ID)
                            .put("version", BuildConfig.VERSION_NAME)
                            .put("platform", CLIENT_PLATFORM)
                            .put("deviceFamily", CLIENT_DEVICE_FAMILY)
                            .put("mode", CLIENT_MODE)
                            .put("instanceId", CLIENT_INSTANCE_ID)
                    )
                    .put("role", ROLE)
                    .put("scopes", org.json.JSONArray(REQUESTED_SCOPES))
                    .put(
                        "device",
                        JSONObject()
                            .put("id", identity.deviceId)
                            .put("publicKey", identity.publicKey)
                            .put("signature", signature)
                            .put("signedAt", signedAtMs)
                            .put("nonce", nonce)
                    )
                    .put("caps", org.json.JSONArray())
                    .put("auth", JSONObject().put("bootstrapToken", bootstrapToken))
                    .put("userAgent", "TapInsight/${BuildConfig.VERSION_NAME}")
                    .put("locale", Locale.getDefault().toLanguageTag())
            )
    }

    private fun loadOrCreateIdentity(): DeviceIdentity {
        val existingDeviceId = prefs.getString(PREF_DEVICE_ID, null)
        val existingPublicKey = prefs.getString(PREF_PUBLIC_KEY, null)
        val existingPrivateKey = prefs.getString(PREF_PRIVATE_KEY, null)
        if (!existingDeviceId.isNullOrBlank() && !existingPublicKey.isNullOrBlank() && !existingPrivateKey.isNullOrBlank()) {
            return DeviceIdentity(existingDeviceId, existingPublicKey, existingPrivateKey)
        }

        val generator = Ed25519KeyPairGenerator()
        generator.init(Ed25519KeyGenerationParameters(SecureRandom()))
        val keyPair = generator.generateKeyPair()
        val privateKeyRaw = (keyPair.private as Ed25519PrivateKeyParameters).encoded
        val publicKeyRaw = (keyPair.public as Ed25519PublicKeyParameters).encoded
        val publicKey = base64UrlEncode(publicKeyRaw)
        val privateKey = base64UrlEncode(privateKeyRaw)
        val deviceId = sha256Hex(publicKeyRaw)
        prefs.edit()
            .putString(PREF_DEVICE_ID, deviceId)
            .putString(PREF_PUBLIC_KEY, publicKey)
            .putString(PREF_PRIVATE_KEY, privateKey)
            .apply()
        return DeviceIdentity(deviceId, publicKey, privateKey)
    }

    private fun signPayload(privateKeyBase64Url: String, payload: String): String {
        val privateKeyBytes = base64UrlDecode(privateKeyBase64Url)
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(privateKeyBytes, 0))
        val payloadBytes = payload.toByteArray(Charsets.UTF_8)
        signer.update(payloadBytes, 0, payloadBytes.size)
        return base64UrlEncode(signer.generateSignature())
    }

    private fun buildDeviceAuthPayloadV3(
        deviceId: String,
        clientId: String,
        clientMode: String,
        role: String,
        scopes: List<String>,
        signedAtMs: Long,
        token: String,
        nonce: String,
        platform: String,
        deviceFamily: String
    ): String {
        val normalizedPlatform = normalizeDeviceMetadataForAuth(platform)
        val normalizedDeviceFamily = normalizeDeviceMetadataForAuth(deviceFamily)
        return listOf(
            "v3",
            deviceId,
            clientId,
            clientMode,
            role,
            scopes.joinToString(","),
            signedAtMs.toString(),
            token,
            nonce,
            normalizedPlatform,
            normalizedDeviceFamily
        ).joinToString("|")
    }

    private fun normalizeDeviceMetadataForAuth(value: String?): String {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isEmpty()) return ""
        val out = StringBuilder(trimmed.length)
        for (ch in trimmed) {
            out.append(if (ch in 'A'..'Z') (ch.code + 32).toChar() else ch)
        }
        return out.toString()
    }

    private fun decodeSetupCode(setupCode: String): SetupPayload? {
        return try {
            val normalized = setupCode.trim().replace('-', '+').replace('_', '/')
            val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
            val json = String(Base64.getDecoder().decode(padded), Charsets.UTF_8)
            val parsed = JSONObject(json)
            val url = parsed.optString("url", "").trim()
            val bootstrapToken = parsed.optString("bootstrapToken", "").trim()
            if (url.isBlank() || bootstrapToken.isBlank()) null else SetupPayload(url, bootstrapToken)
        } catch (e: Exception) {
            null
        }
    }

    private fun normalizeGatewayWsUrl(raw: String): String? {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isBlank()) return null
        return when {
            trimmed.startsWith("ws://") || trimmed.startsWith("wss://") -> trimmed
            trimmed.startsWith("http://") -> "ws://${trimmed.removePrefix("http://")}"
            trimmed.startsWith("https://") -> "wss://${trimmed.removePrefix("https://")}"
            else -> "ws://$trimmed"
        }
    }

    private fun normalizeGatewayUrl(raw: String): String? {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isBlank()) return null
        var normalized = when {
            trimmed.startsWith("ws://") -> "http://${trimmed.removePrefix("ws://")}"
            trimmed.startsWith("wss://") -> "https://${trimmed.removePrefix("wss://")}"
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            else -> "http://$trimmed"
        }
        if (!normalized.matches(Regex("https?://[^/]+:\\d+(?:/.*)?"))) {
            val schemeEnd = normalized.indexOf("://") + 3
            val hostAndPath = normalized.substring(schemeEnd)
            val host = hostAndPath.substringBefore('/')
            if (!host.contains(':')) {
                normalized = normalized.substring(0, schemeEnd) + host + ":18789" + hostAndPath.removePrefix(host)
            }
        }
        return normalized
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return BigInteger(1, digest).toString(16).padStart(64, '0')
    }

    private fun base64UrlEncode(bytes: ByteArray): String = base64UrlEncoder.encodeToString(bytes)

    private fun base64UrlDecode(value: String): ByteArray = base64UrlDecoder.decode(value)
}
