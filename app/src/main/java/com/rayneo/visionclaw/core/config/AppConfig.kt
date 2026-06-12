package com.rayneo.visionclaw.core.config

import android.content.Context
import android.util.Log
import org.json.JSONObject

data class AppConfig(
    val gatewaySettings: GatewaySettings = GatewaySettings(),
    val apiKeys: ApiKeys = ApiKeys(),
    val mouseSettings: MouseSettings = MouseSettings(),
    val debugServerSettings: DebugServerSettings = DebugServerSettings()
) {
    data class GatewaySettings(
        val url: String = "http://openclaw.ai",
        val port: String = "8080",
        val gatewayToken: String = "",
        val toolPath: String = "/v1/chat/completions",
        val timeoutMs: Int = 15000
    ) {
        fun baseUrl(): String {
            val cleanUrl = url.trim().trimEnd('/')
            val cleanPort = port.trim()
            if (cleanPort.isBlank()) return cleanUrl
            return if (cleanUrl.contains(":$cleanPort")) cleanUrl else "$cleanUrl:$cleanPort"
        }

        fun toolEndpoint(): String {
            val path = toolPath.trim().ifBlank { "/v1/chat/completions" }
            val normalizedPath = if (path.startsWith("/")) path else "/$path"
            return "${baseUrl().trimEnd('/')}$normalizedPath"
        }
    }

    data class ApiKeys(
        val geminiModel: String = "gemini-2.5-flash",
        val geminiKey: String = "YOUR_KEY_HERE"
    )

    data class MouseSettings(
        val sensitivity: Double = 0.5,
        val acceleration: Boolean = true
    )

    data class DebugServerSettings(
        val enabled: Boolean = true,
        val host: String = "0.0.0.0",
        val port: Int = 19110,
        val token: String = "",
        val allowedHosts: List<String> = listOf("0.0.0.0")
    ) {
        fun normalizedAllowedHosts(): Set<String> {
            return allowedHosts
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .toSet()
        }
    }

    companion object {
        private const val TAG = "AppConfig"

        fun load(context: Context, assetName: String = "config.json"): AppConfig {
            return runCatching {
                val raw = context.assets.open(assetName).bufferedReader(Charsets.UTF_8).use { it.readText() }
                parse(raw)
            }.getOrElse { error ->
                Log.w(TAG, "Failed loading $assetName; using defaults: ${error.message}")
                AppConfig()
            }
        }

        private fun parse(raw: String): AppConfig {
            val root = JSONObject(raw)

            val gateway = root.optJSONObject("gateway_settings") ?: JSONObject()
            val keys = root.optJSONObject("api_keys") ?: JSONObject()
            val mouse = root.optJSONObject("mouse_settings") ?: JSONObject()
            val debug = root.optJSONObject("debug_server") ?: JSONObject()
            val allowedHosts = mutableListOf<String>()
            val allowedHostsArray = debug.optJSONArray("allowed_hosts")
            if (allowedHostsArray != null) {
                for (i in 0 until allowedHostsArray.length()) {
                    val value = allowedHostsArray.optString(i, "").trim()
                    if (value.isNotBlank()) {
                        allowedHosts += value
                    }
                }
            }
            if (allowedHosts.isEmpty()) {
                val singleAllowedHost = debug.optString("allowed_host", "").trim()
                if (singleAllowedHost.isNotBlank()) {
                    allowedHosts += singleAllowedHost
                }
            }

            return AppConfig(
                gatewaySettings = GatewaySettings(
                    url = gateway.optString("url", "http://openclaw.ai"),
                    port = gateway.optString("port", "8080"),
                    gatewayToken = gateway.optString(
                        "gatewayToken",
                        gateway.optString("gateway_token", gateway.optString("token", ""))
                    ),
                    toolPath = gateway.optString(
                        "tool_path",
                        gateway.optString("toolPath", "/v1/chat/completions")
                    ),
                    timeoutMs = gateway.optInt("timeout_ms", 15000).coerceAtLeast(1000)
                ),
                apiKeys = ApiKeys(
                    geminiModel = keys.optString("gemini_model", "gemini-2.5-flash"),
                    geminiKey = keys.optString("gemini_key", "YOUR_KEY_HERE")
                ),
                mouseSettings = MouseSettings(
                    sensitivity = mouse.optDouble("sensitivity", 0.5),
                    acceleration = mouse.optBoolean("acceleration", true)
                ),
                debugServerSettings = DebugServerSettings(
                    enabled = debug.optBoolean("enabled", true),
                    host = debug.optString("host", "0.0.0.0").ifBlank { "0.0.0.0" },
                    port = debug.optInt("port", 19110).coerceIn(1, 65535),
                    token = debug.optString(
                        "token",
                        debug.optString("debugToken", "")
                    ).trim(),
                    allowedHosts = if (allowedHosts.isEmpty()) {
                        listOf("0.0.0.0")
                    } else {
                        allowedHosts
                    }
                )
            )
        }
    }
}
