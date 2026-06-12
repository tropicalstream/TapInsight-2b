package com.rayneo.visionclaw.core.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * GoogleNewsClient – fetches top headlines from the Google News RSS feed.
 *
 * No API key or OAuth needed — public RSS at https://news.google.com/rss
 *
 * Error-handling:
 *   • Network errors → [NewsResult.Error]
 *   • Success → [NewsResult.Success] with list of headlines
 */
class GoogleNewsClient {

    companion object {
        private const val TAG = "GoogleNews"
        private const val RSS_URL = "https://news.google.com/rss"
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 15_000
    }

    // ── Data classes ──────────────────────────────────────────────────────

    data class NewsHeadline(
        val title: String,
        val link: String?,
        val source: String?,
        val pubDate: Date?
    )

    // ── Result types ──────────────────────────────────────────────────────

    sealed class NewsResult {
        data class Success(val headlines: List<NewsHeadline>) : NewsResult()
        data class Error(val message: String) : NewsResult()
    }

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Fetch top headlines from Google News RSS.
     *
     * @param maxResults Maximum headlines to return (default 5).
     */
    suspend fun fetchHeadlines(maxResults: Int = 5): NewsResult = withContext(Dispatchers.IO) {
        try {
            val conn = (URL(RSS_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("User-Agent", "TapInsight/1.0")
            }

            val code = conn.responseCode
            if (code !in 200..299) {
                return@withContext NewsResult.Error("News RSS HTTP $code")
            }

            val factory = DocumentBuilderFactory.newInstance()
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(conn.inputStream)
            doc.documentElement.normalize()

            val items = doc.getElementsByTagName("item")
            val headlines = mutableListOf<NewsHeadline>()

            val dateFormats = listOf(
                SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US),
                SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US),
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            )

            for (i in 0 until minOf(items.length, maxResults)) {
                val item = items.item(i) as Element
                val title = getElementText(item, "title")?.trim() ?: continue
                val link = getElementText(item, "link")?.trim()
                val source = getElementText(item, "source")?.trim()
                val pubDateStr = getElementText(item, "pubDate")?.trim()

                val pubDate = pubDateStr?.let { dateStr ->
                    dateFormats.firstNotNullOfOrNull { fmt ->
                        try { fmt.parse(dateStr) } catch (_: Exception) { null }
                    }
                }

                headlines.add(NewsHeadline(
                    title = title,
                    link = link,
                    source = source,
                    pubDate = pubDate
                ))
            }

            Log.d(TAG, "Fetched ${headlines.size} headlines")
            NewsResult.Success(headlines)
        } catch (e: Exception) {
            Log.e(TAG, "News fetch failed", e)
            NewsResult.Error(e.localizedMessage ?: "Unknown error")
        }
    }

    private fun getElementText(parent: Element, tagName: String): String? {
        val nodes = parent.getElementsByTagName(tagName)
        return if (nodes.length > 0) nodes.item(0)?.textContent else null
    }
}
