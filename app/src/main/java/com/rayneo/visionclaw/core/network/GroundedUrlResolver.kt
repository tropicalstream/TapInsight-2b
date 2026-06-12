package com.rayneo.visionclaw.core.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Turns possibly-hallucinated link-list URLs into real, on-topic ones.
 *
 * Why: when the Gemini Live assistant builds a "send a list of <topic>" card
 * (send_link_list) or the research "links" mode emits sources, the TITLES are
 * right but the URLs are often retyped from the model's memory — plausibly
 * shaped but pointing at the wrong thing (e.g. a valid Goodreads URL for the
 * wrong book). Opening those verbatim is the bug this resolver fixes.
 *
 * Layered resolution per entry (stops at the first that succeeds):
 *   L1  — the URL is a Google grounding redirect (the model DID ground it, the
 *         URL is just opaque): follow it to the real publisher URL.
 *   A   — otherwise re-ground the entry's title via a fresh Gemini googleSearch
 *         and take the top real result (resolving its redirect). We do NOT
 *         trust a from-memory URL even when it loads, because a reachable page
 *         can still be the WRONG item.
 *   B   — if nothing can be confirmed, return an on-topic search-results page
 *         (YouTube search for video, Google search otherwise) — never wrong,
 *         costs one extra tap.
 *
 * A from-memory URL is never returned verbatim.
 */
object GroundedUrlResolver {
    private const val TAG = "GroundedUrlResolver"
    private const val GOOGLE_BASE_URL =
        "https://generativelanguage.googleapis.com/v1beta/models"

    // Cheap/fast models for the single-URL grounding lookup, with fallbacks
    // for tenants where the first isn't enabled.
    private val GROUNDING_MODELS = listOf(
        "gemini-2.5-flash",
        "gemini-flash-latest",
        "gemini-2.0-flash"
    )

    private val VIDEO_TYPES = setOf("video", "videos")

    // ── Resolution caches ───────────────────────────────────────────────
    // The two expensive operations here are (a) the per-title googleSearch
    // grounding call and (b) following a redirect over the network. Both are
    // effectively idempotent for a given input within a session, yet the same
    // link lists get re-opened repeatedly, re-running every lookup from
    // scratch. Caching successful results turns "up to ~8 extra Gemini calls
    // per list, every open" into "once per distinct title." Only SUCCESSES are
    // cached, so a transient network failure isn't pinned. Maps are capped and
    // cleared wholesale when they grow large — cheap and avoids unbounded RAM.
    private const val CACHE_MAX = 256
    private val groundedCache = ConcurrentHashMap<String, String>()
    private val redirectCache = ConcurrentHashMap<String, String>()

    private fun groundedCacheKey(title: String, type: String): String =
        type.trim().lowercase(Locale.US) + "|" + title.trim().lowercase(Locale.US)

    private fun ConcurrentHashMap<String, String>.putCapped(key: String, value: String) {
        if (size >= CACHE_MAX) clear()
        put(key, value)
    }

    /** Clear cached resolutions (e.g. on explicit user refresh). */
    fun clearCaches() {
        groundedCache.clear()
        redirectCache.clear()
    }

    /** True for Google's opaque grounding-redirect URLs. */
    fun isGroundingRedirect(url: String): Boolean {
        val u = url.lowercase(Locale.US)
        return u.contains("vertexaisearch.cloud.google.com") ||
            u.contains("/grounding-api-redirect/")
    }

    /** Follow a (grounding) redirect to its final publisher URL. */
    fun resolveRedirect(url: String): String {
        redirectCache[url]?.let { return it }
        val resolved = ActiveNetworkHttp.resolveFinalUrl(url)?.takeIf { it.isNotBlank() } ?: url
        // Only cache a genuine resolution (i.e. we actually followed it to a
        // different, non-redirect URL). Caching the unchanged input or a still-
        // opaque redirect would just pin a failed follow.
        if (resolved != url && !isGroundingRedirect(resolved)) {
            redirectCache.putCapped(url, resolved)
        }
        return resolved
    }

    /** On-topic search-results page that can never mis-target (Fallback B). */
    fun searchPageUrl(title: String, type: String): String {
        val q = URLEncoder.encode(title.trim(), "UTF-8").replace("%20", "+")
        return if (type.trim().lowercase(Locale.US) in VIDEO_TYPES) {
            "https://www.youtube.com/results?search_query=$q"
        } else {
            "https://www.google.com/search?q=$q"
        }
    }

    /**
     * Re-ground a single title via Gemini googleSearch; returns the resolved
     * top source URL, or null if grounding produced nothing.
     */
    suspend fun groundedTopUrl(
        geminiApiKey: String?,
        title: String,
        type: String
    ): String? = withContext(Dispatchers.IO) {
        val key = geminiApiKey?.trim().orEmpty()
        if (key.isBlank() || title.isBlank()) return@withContext null
        val cacheKey = groundedCacheKey(title, type)
        groundedCache[cacheKey]?.let { return@withContext it }
        val prompt =
            "Find the single best, real, currently-working source URL for this " +
                "${type.ifBlank { "web" }} item: \"$title\". " +
                "Use the googleSearch tool. Prefer the canonical/official page. " +
                "Return only the URL."
        val body = JSONObject()
            .put(
                "contents",
                JSONArray().put(
                    JSONObject().put("role", "user").put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", prompt))
                    )
                )
            )
            .put("tools", JSONArray().put(JSONObject().put("googleSearch", JSONObject())))
            .put(
                "generationConfig",
                JSONObject().put("temperature", 0.0).put("maxOutputTokens", 256)
            )
        for (model in GROUNDING_MODELS) {
            val resp = try {
                ActiveNetworkHttp.postJson(
                    url = "$GOOGLE_BASE_URL/$model:generateContent?key=$key",
                    jsonBody = body.toString(),
                    headers = mapOf("Content-Type" to "application/json")
                )
            } catch (e: Exception) {
                Log.w(TAG, "groundedTopUrl POST failed model=$model: ${e.message}")
                null
            } ?: continue
            if (resp.code !in 200..299) {
                Log.w(TAG, "groundedTopUrl model=$model HTTP ${resp.code}")
                if (resp.code == 404) continue else break
            }
            val uri = firstGroundingUri(resp.body)
            if (!uri.isNullOrBlank()) {
                val resolved = resolveRedirect(uri)
                // Only accept a REAL publisher URL. If the redirect couldn't be
                // followed (expired / blocked) it stays a vertexaisearch URL,
                // which 404s in the browser — treat that as "no result" so the
                // caller falls through to the on-topic search page instead.
                if (resolved.isNotBlank() && !isGroundingRedirect(resolved)) {
                    groundedCache.putCapped(cacheKey, resolved)
                    return@withContext resolved
                }
            }
        }
        null
    }

    /** Pull the first groundingChunks[].web.uri out of a generateContent body. */
    private fun firstGroundingUri(body: String): String? {
        return try {
            val candidates = JSONObject(body).optJSONArray("candidates") ?: return null
            for (i in 0 until candidates.length()) {
                val gm = candidates.optJSONObject(i)
                    ?.optJSONObject("groundingMetadata") ?: continue
                val chunks = gm.optJSONArray("groundingChunks") ?: continue
                for (j in 0 until chunks.length()) {
                    val web = chunks.optJSONObject(j)?.optJSONObject("web") ?: continue
                    val uri = web.optString("uri").trim()
                    if (uri.isNotBlank()) return uri
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "firstGroundingUri parse failed: ${e.message}")
            null
        }
    }

    /**
     * Resolve one list entry to a real, on-topic URL using the layered policy
     * above. [candidateUrl] is whatever the model supplied (blank / memory /
     * grounding redirect). Never returns a from-memory URL verbatim.
     */
    suspend fun resolveEntry(
        geminiApiKey: String?,
        title: String,
        type: String,
        candidateUrl: String
    ): String = withContext(Dispatchers.IO) {
        val candidate = candidateUrl.trim()
        // L1 — model already grounded it; the URL is just opaque. Resolve it,
        // but ONLY use the result if it became a real publisher URL. If the
        // redirect couldn't be followed (expired / blocked) it stays a
        // vertexaisearch URL that 404s in the browser — fall through to the
        // next layer instead of opening the dead redirect.
        if (candidate.isNotBlank() && isGroundingRedirect(candidate)) {
            val resolved = resolveRedirect(candidate)
            if (resolved.isNotBlank() && !isGroundingRedirect(resolved)) {
                return@withContext resolved
            }
        }
        // A — re-ground the title (don't trust a from-memory URL even if reachable).
        groundedTopUrl(geminiApiKey, title, type)?.let { return@withContext it }
        // B — guaranteed on-topic search page (never 404s).
        searchPageUrl(title, type)
    }
}
