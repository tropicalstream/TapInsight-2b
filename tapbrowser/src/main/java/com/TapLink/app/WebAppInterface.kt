package com.TapLinkX3.app

import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.annotation.Keep
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

@Keep
class WebAppInterface(private val context: Context, private val webView: WebView) {
    private val client =
            OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .writeTimeout(30, TimeUnit.SECONDS)
                    .build()
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    @Keep
    fun ping(): String {
        return "pong"
    }

    /**
     * Fetch podcast episodes for a given iTunes collection ID.
     * Called by podcasts.html when the user taps a podcast row — looks up
     * the podcast's RSS feed URL via the iTunes Lookup API, downloads
     * the RSS feed, parses out the most recent episodes, and calls back
     * into JS via `onPodcastEpisodesLoaded(collectionId, jsonStr)` or
     * `onPodcastEpisodesError(collectionId, message)`.
     *
     * Runs entirely off the main thread so the WebView stays responsive.
     */
    @JavascriptInterface
    @Keep
    fun fetchPodcastEpisodes(collectionId: String, limit: Int) {
        DebugLog.d("WebAppInterface", "fetchPodcastEpisodes id=$collectionId limit=$limit")
        if (collectionId.isBlank()) {
            callJs("onPodcastEpisodesError", collectionId, "Missing collection ID")
            return
        }
        val maxEpisodes = if (limit > 0 && limit <= 100) limit else 20
        Thread {
            try {
                // 1) Look up podcast by ID via iTunes to get feedUrl
                val lookupReq = Request.Builder()
                    .url("https://itunes.apple.com/lookup?id=$collectionId")
                    .header("User-Agent", "TapInsight/1.1.2")
                    .build()
                val feedUrl: String = client.newCall(lookupReq).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        callJs("onPodcastEpisodesError", collectionId,
                            "iTunes lookup failed: ${resp.code}")
                        return@Thread
                    }
                    val body = resp.body?.string().orEmpty()
                    val json = JSONObject(body)
                    val results = json.optJSONArray("results")
                    if (results == null || results.length() == 0) {
                        callJs("onPodcastEpisodesError", collectionId,
                            "No podcast found for ID $collectionId")
                        return@Thread
                    }
                    val first = results.optJSONObject(0) ?: run {
                        callJs("onPodcastEpisodesError", collectionId, "Empty lookup result")
                        return@Thread
                    }
                    first.optString("feedUrl", "").trim().also { url ->
                        if (url.isBlank()) {
                            callJs("onPodcastEpisodesError", collectionId,
                                "Podcast has no RSS feed")
                            return@Thread
                        }
                    }
                }

                // 2) Fetch RSS feed
                val feedReq = Request.Builder()
                    .url(feedUrl)
                    .header("User-Agent", "TapInsight/1.1.2")
                    .build()
                val xml: String = client.newCall(feedReq).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        callJs("onPodcastEpisodesError", collectionId,
                            "RSS feed fetch failed: ${resp.code}")
                        return@Thread
                    }
                    resp.body?.string().orEmpty()
                }

                // 3) Parse episodes
                val episodes = parseEpisodes(xml, maxEpisodes)
                if (episodes.length() == 0) {
                    callJs("onPodcastEpisodesError", collectionId,
                        "No episodes found in feed")
                    return@Thread
                }

                callJs("onPodcastEpisodesLoaded", collectionId, episodes.toString())
            } catch (e: Exception) {
                DebugLog.e("WebAppInterface", "fetchPodcastEpisodes failed", e)
                callJs("onPodcastEpisodesError", collectionId, "Error: ${e.message ?: "unknown"}")
            }
        }.start()
    }

    /**
     * Extract up to [max] episodes from an RSS feed XML string.
     * Returns a JSONArray of { title, audioUrl, pubDate, duration }.
     * Uses simple substring scanning to avoid pulling in an XML library.
     */
    private fun parseEpisodes(xml: String, max: Int): org.json.JSONArray {
        val out = org.json.JSONArray()
        var cursor = 0
        var count = 0
        while (count < max) {
            val itemStart = xml.indexOf("<item>", cursor).let { a ->
                if (a >= 0) a else xml.indexOf("<item ", cursor)
            }
            if (itemStart < 0) break
            val itemEnd = xml.indexOf("</item>", itemStart)
            if (itemEnd < 0) break
            val item = xml.substring(itemStart, itemEnd)
            cursor = itemEnd + "</item>".length

            // Enclosure URL
            val encIdx = item.indexOf("<enclosure")
            val audioUrl = if (encIdx >= 0) {
                val encEnd = item.indexOf(">", encIdx)
                if (encEnd >= 0) {
                    val encTag = item.substring(encIdx, encEnd + 1)
                    val m = Regex("""url\s*=\s*["']([^"']+)["']""").find(encTag)
                    m?.groupValues?.getOrNull(1)?.trim().orEmpty()
                } else ""
            } else ""

            if (audioUrl.isBlank()) continue  // skip items without audio

            val title = extractTag(item, "title")
            val pubDate = extractTag(item, "pubDate")
            // iTunes duration can be <itunes:duration>…</itunes:duration>
            val duration = extractTag(item, "itunes:duration")

            out.put(JSONObject()
                .put("title", title)
                .put("audioUrl", audioUrl)
                .put("pubDate", pubDate)
                .put("duration", duration))
            count++
        }
        return out
    }

    private fun extractTag(xml: String, tag: String): String {
        val open = "<$tag>"
        val close = "</$tag>"
        val start = xml.indexOf(open)
        if (start < 0) return ""
        val end = xml.indexOf(close, start)
        if (end < 0) return ""
        var content = xml.substring(start + open.length, end).trim()
        if (content.startsWith("<![CDATA[")) {
            content = content.removePrefix("<![CDATA[").removeSuffix("]]>").trim()
        }
        return content
    }

    /** Invoke a JS callback with two string args on the main thread. */
    private fun callJs(fn: String, a: String, b: String) {
        mainHandler.post {
            val escA = escapeJsString(a)
            val escB = escapeJsString(b)
            webView.evaluateJavascript("$fn('$escA','$escB')", null)
        }
    }

    private fun escapeJsString(s: String): String =
        s.replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "\\r")

    @JavascriptInterface
    @Keep
    fun chatWithGroq(message: String, historyJson: String) {
        DebugLog.d("WebAppInterface", "chatWithGroq called: $message")
        Thread {
                    try {
                        val prefs =
                                context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                        val apiKey = prefs.getString("groq_api_key", null)

                        if (apiKey.isNullOrBlank()) {
                            postResponse("Error: API Key not found. Please set it in Settings.")
                            return@Thread
                        }

                        val history =
                                try {
                                    org.json.JSONArray(historyJson)
                                } catch (e: Exception) {
                                    org.json.JSONArray()
                                }

                        val messages = org.json.JSONArray()
                        // Add system prompt
                        val systemMsg = JSONObject()
                        systemMsg.put("role", "system")

                        var systemContent =
                                """You are TapLink AI, integrated into the TapLink X3 dashboard.
Respond clearly and concisely, prioritize practical help, and avoid unnecessary sections.
Do not include a "How this was determined" section unless explicitly requested.
Do not include internal reasoning traces or chain-of-thought."""
                        val activity = findMainActivity(context)
                        val location = activity?.getLastLocation()
                        if (location != null) {
                            systemContent +=
                                    "\nCurrent Location: ${location.first}, ${location.second}"
                        }

                        systemMsg.put("content", systemContent)
                        messages.put(systemMsg)

                        // Add history
                        for (i in 0 until history.length()) {
                            messages.put(history.get(i))
                        }

                        // Add current user message
                        val userMsg = JSONObject()
                        userMsg.put("role", "user")
                        userMsg.put("content", message)
                        messages.put(userMsg)

                        val jsonBody = JSONObject()
                        jsonBody.put("model", "llama3-70b-8192")
                        jsonBody.put("messages", messages)

                        val requestBody =
                                jsonBody.toString()
                                        .toRequestBody(
                                                "application/json; charset=utf-8".toMediaType()
                                        )

                        val request =
                                Request.Builder()
                                        .url("https://api.groq.com/openai/v1/chat/completions")
                                        .addHeader("Authorization", "Bearer $apiKey")
                                        .post(requestBody)
                                        .build()

                        client.newCall(request).execute().use { response ->
                            if (!response.isSuccessful) {
                                postResponse("Error: ${response.code} - ${response.message}")
                                return@use
                            }

                            val responseBody = response.body?.string()
                            if (responseBody != null) {
                                val json = JSONObject(responseBody)
                                val choices = json.optJSONArray("choices")
                                if (choices != null && choices.length() > 0) {
                                    val content =
                                            choices.getJSONObject(0)
                                                    .getJSONObject("message")
                                                    .getString("content")
                                    postResponse(content)
                                } else {
                                    postResponse("Error: No response from AI.")
                                }
                            } else {
                                postResponse("Error: Empty response body.")
                            }
                        }
                    } catch (e: Exception) {
                        DebugLog.e("GroqChat", "Chat failed", e)
                        postResponse("Error: ${e.message}")
                    }
                }
                .start()
    }

    private fun postResponse(text: String) {
        mainHandler.post {
            // Escape single quotes and backslashes for JS string
            val escapedText = text.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n")

            // Log.d("WebAppInterface", "Posting response to WebView: $escapedText")
            webView.evaluateJavascript("receiveGroqResponse('$escapedText')", null)
        }
    }

    private fun findMainActivity(context: Context): MainActivity? {
        var ctx = context
        while (ctx is ContextWrapper) {
            if (ctx is MainActivity) return ctx
            ctx = ctx.baseContext
        }
        return ctx as? MainActivity
    }
}
