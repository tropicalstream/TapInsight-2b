package com.rayneo.visionclaw.core.network

import java.util.Locale

/**
 * Derives the image-relay base URL from a configured agent endpoint.
 *
 * The Mac-side relay (tools/image_relay.py) listens on port 18790 locally
 * and is typically published through a Cloudflare tunnel at a domain the
 * operator owns (set TAPINSIGHT_RELAY_BASE in ~/.gradle/gradle.properties,
 * e.g. "https://relay.example.com"). Agent endpoints (Hermes / OpenClaw)
 * are configured as full URLs; this helper maps whatever host they point
 * at to the matching relay base:
 *
 *  • localhost / 127.0.0.1 / any raw IP  → http://<ip>:18790
 *    (or the public tunnel when [TAPINSIGHT_RELAY_BASE] is preferred —
 *    used by code paths that must work away from the home LAN)
 *  • the operator's relay host / domain  → [TAPINSIGHT_RELAY_BASE]
 *  • relay.<anything>                    → https://relay.<anything>
 *  • any other domain                    → https://relay.<base-domain>
 */
object RelayUrlHelper {

    /** Operator's public relay base (no trailing slash). Blank when the
     *  build wasn't configured with one — every special case below then
     *  falls through to the generic host-derivation rules. */
    val TAPINSIGHT_RELAY_BASE: String =
        com.rayneo.visionclaw.BuildConfig.DEFAULT_RELAY_BASE.trim().trimEnd('/')

    /** Hostname of [TAPINSIGHT_RELAY_BASE] ("relay.example.com"), or null. */
    private val publicRelayHost: String? by lazy {
        extractHost(TAPINSIGHT_RELAY_BASE)?.lowercase(Locale.US)
    }

    /** Apex of the operator's relay host ("example.com"), or null. */
    private val publicBaseDomain: String? by lazy {
        publicRelayHost?.split(".")?.filter { it.isNotBlank() }?.let { parts ->
            if (parts.size > 2) parts.drop(1).joinToString(".") else parts.joinToString(".")
        }
    }

    /** Port image_relay.py listens on when reached directly over the LAN. */
    private const val RELAY_PORT = 18790

    /**
     * First resolvable relay base across several candidate endpoints.
     * Prefers an https base (the tunnel) over plain-http LAN bases; when
     * [preferTapInsightPublicForLocal] is set and only LAN bases resolved,
     * falls back to [TAPINSIGHT_RELAY_BASE] so the URL stays reachable
     * from any network.
     */
    fun baseFromEndpoints(
        vararg endpoints: String?,
        preferTapInsightPublicForLocal: Boolean = false
    ): String? {
        val candidates = endpoints.mapNotNull { baseFromEndpoint(it, false) }
        candidates.firstOrNull { it.startsWith("https://", ignoreCase = true) }?.let { return it }
        if (preferTapInsightPublicForLocal &&
            TAPINSIGHT_RELAY_BASE.isNotBlank() &&
            candidates.any { it.startsWith("http://") }
        ) {
            return TAPINSIGHT_RELAY_BASE
        }
        return candidates.firstOrNull()
    }

    /** Relay base URL for a single endpoint, or null when no host is found. */
    fun baseFromEndpoint(endpoint: String?, preferTapInsightPublicForLocal: Boolean = false): String? {
        val host = extractHost(endpoint) ?: return null
        val lowerHost = host.lowercase(Locale.US)
        val isIp = Regex("\\d+\\.\\d+\\.\\d+\\.\\d+").matches(lowerHost)
        val isLocal = lowerHost == "localhost" || lowerHost == "127.0.0.1" || isIp
        if (isLocal) {
            if (preferTapInsightPublicForLocal && TAPINSIGHT_RELAY_BASE.isNotBlank()) {
                return TAPINSIGHT_RELAY_BASE
            }
            return "http://$lowerHost:$RELAY_PORT"
        }
        if (publicRelayHost != null && lowerHost == publicRelayHost) {
            return TAPINSIGHT_RELAY_BASE
        }
        if (lowerHost.startsWith("relay.")) {
            return "https://$lowerHost"
        }
        val apex = publicBaseDomain
        if (apex != null && (lowerHost == apex || lowerHost.endsWith(".$apex"))) {
            return TAPINSIGHT_RELAY_BASE
        }
        val parts = lowerHost.split(".").filter { it.isNotBlank() }
        if (parts.isEmpty()) return null
        val baseDomain = if (parts.size > 2) parts.drop(1).joinToString(".") else parts.joinToString(".")
        return "https://relay.$baseDomain"
    }

    /** Camera-frame upload/download URL ("<base>/frame") for an endpoint. */
    fun frameUrlFromEndpoint(endpoint: String?): String? {
        return baseFromEndpoint(endpoint)?.let { "$it/frame" }
    }

    /**
     * Pull the bare hostname out of an endpoint string — tolerates full
     * URLs, scheme-less host:port strings, userinfo, paths, queries.
     */
    private fun extractHost(endpoint: String?): String? {
        val trimmed = (endpoint?.trim() ?: "").trimEnd('/').takeIf { it.isNotBlank() } ?: return null
        val withoutScheme = Regex("://([^/?#]+)").find(trimmed)?.groupValues?.getOrNull(1)
            ?: trimmed.substringBefore('/').substringBefore('?').substringBefore('#')
        return withoutScheme.substringBefore('@').substringBefore(':').trim()
            .takeIf { it.isNotBlank() }
    }
}
