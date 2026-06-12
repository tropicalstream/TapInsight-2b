package com.rayneo.visionclaw.core.tools

import android.content.Context
import android.util.Log
import com.rayneo.visionclaw.core.network.GroundedUrlResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

/**
 * Renders a tappable list of arbitrary URLs (articles, audio, videos, PDFs,
 * generic web pages — anything that isn't a YouTube playback request) on the
 * user's AR glasses inside TapBrowser.
 *
 * Built as the visible-card path for "find me links / URLs / articles"
 * style queries that previously suffered from one of two bad outcomes:
 *   • Gemini cached titles via send_video_list display='cache' and never
 *     wrote URLs into the chat card → user heard "I have N links" but
 *     saw nothing.
 *   • Gemini emitted URLs in its spoken response → URLs got read aloud
 *     character by character ("h-t-t-p-s-c-o-l-o-n-slash-slash…").
 *
 * With this tool, Gemini speaks only the titles + a short offer
 * ("want me to send these as a list?"), the user confirms, and the list
 * page opens in TapBrowser. URLs never travel through TTS, but they are
 * fully visible and tappable.
 *
 * Mirror of [SendVideoListTool]'s shape: tool returns a `taplink://` URL
 * pointing at a bundled HTML asset (`link_list.html`) with the list
 * payload encoded into the `?data=` query parameter. MainActivity's
 * autoOpenUrl interceptor recognises the prefix and forwards directly to
 * TapBrowser without round-tripping the URL back through Gemini (which
 * tends to mangle long encoded query strings).
 */
class SendLinkListTool(
    private val context: Context,
    /** Gemini API key for the grounded re-search used to repair URLs. */
    private val geminiApiKeyProvider: () -> String? = { null }
) : AiTapTool {
    override val name = "send_link_list"

    override suspend fun execute(args: Map<String, String>): Result<String> {
        val topicArg = args["topic"].orEmpty().trim()
        val titleArg = args["title"].orEmpty().trim().ifBlank { topicArg }
        val rawLinks = args["links"].orEmpty().trim()

        if (rawLinks.isBlank()) {
            return Result.failure(
                IllegalArgumentException("send_link_list requires a 'links' JSON array.")
            )
        }

        // Gemini occasionally wraps its JSON in code fences. Strip them so a
        // formatting hiccup doesn't reject an otherwise valid call.
        val cleaned = rawLinks
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```")
            .trim()

        val linksArray = try {
            JSONArray(cleaned)
        } catch (e: Exception) {
            Log.w("SendLinkListTool", "links arg was not a JSON array: ${e.message}")
            return Result.failure(
                IllegalArgumentException(
                    "send_link_list 'links' must be a JSON array of {title, url, type, summary} objects."
                )
            )
        }

        if (linksArray.length() == 0) {
            return Result.failure(
                IllegalArgumentException("send_link_list needs at least one entry in 'links'.")
            )
        }

        // Parse the raw entries first (title / type / summary + the model's
        // candidate URL). We DON'T trust the candidate URL — it's frequently
        // a from-memory hallucination that's plausibly shaped but points at
        // the wrong item (e.g. a valid Goodreads URL for the wrong book).
        // URL resolution happens in parallel below.
        val rawEntries = mutableListOf<JSONObject>()
        for (i in 0 until linksArray.length()) {
            val obj = linksArray.optJSONObject(i) ?: continue
            val title = obj.optString("title").trim()
            if (title.isBlank()) continue
            val type = obj.optString("type").trim().lowercase(Locale.US).ifBlank { "web" }
            val summary = obj.optString("summary").trim()
            val candidateUrl = obj.optString("url").trim()
            rawEntries.add(
                JSONObject()
                    .put("title", title)
                    .put("type", type)
                    .put("summary", summary)
                    .put("cand", candidateUrl)
            )
        }

        if (rawEntries.isEmpty()) {
            return Result.failure(
                IllegalArgumentException("send_link_list needs at least one entry with a title.")
            )
        }

        // Resolve every URL to a real, on-topic one IN PARALLEL before showing
        // the list (resolve-before-show): a grounding-redirect URL is followed
        // to its real publisher page; a from-memory URL is re-grounded via a
        // fresh search and replaced with the top real result; if nothing can
        // be confirmed we fall back to an on-topic search page. See
        // GroundedUrlResolver. A from-memory URL is never opened verbatim.
        val apiKey = geminiApiKeyProvider()
        val resolvedUrls: List<String> = coroutineScope {
            rawEntries.map { e ->
                async(Dispatchers.IO) {
                    val t = e.optString("title")
                    val ty = e.optString("type")
                    val cand = e.optString("cand")
                    runCatching {
                        GroundedUrlResolver.resolveEntry(apiKey, t, ty, cand)
                    }.getOrElse { GroundedUrlResolver.searchPageUrl(t, ty) }
                }
            }.awaitAll()
        }

        val normalized = JSONArray()
        for (idx in rawEntries.indices) {
            val e = rawEntries[idx]
            val t = e.optString("title")
            val ty = e.optString("type")
            val url = resolvedUrls.getOrNull(idx)
                ?.takeIf { it.isNotBlank() }
                ?: GroundedUrlResolver.searchPageUrl(t, ty)
            normalized.put(
                JSONObject()
                    .put("title", t)
                    .put("url", url)
                    .put("type", ty)
                    .put("summary", e.optString("summary"))
            )
        }

        if (normalized.length() == 0) {
            return Result.failure(
                IllegalArgumentException(
                    "send_link_list couldn't read any valid {title} entries."
                )
            )
        }

        val payload = JSONObject()
            .put("title", titleArg.ifBlank { "Links" })
            .put("topic", topicArg)
            .put("links", normalized)
            .toString()

        val encoded = URLEncoder.encode(payload, "UTF-8")
        val url = "file:///android_asset/link_list.html?data=$encoded"
        // Log each entry on its own line so logcat's per-line truncation
        // doesn't hide what Gemini actually emitted. The data param itself
        // is too long to log in one line; this gives us a full audit trail
        // of {type, title, url} for every entry rendered.
        Log.d(
            "SendLinkListTool",
            "Rendering link list topic='$topicArg' count=${normalized.length()}"
        )
        for (i in 0 until normalized.length()) {
            val entry = normalized.optJSONObject(i) ?: continue
            Log.d(
                "SendLinkListTool",
                "  entry[$i] type=${entry.optString("type")} " +
                    "title='${entry.optString("title").take(80)}' " +
                    "url='${entry.optString("url")}'"
            )
        }
        return Result.success("taplink://$url")
    }
}
