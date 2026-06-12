package com.rayneo.visionclaw.core.tools

import android.content.Context
import android.util.Log
import com.rayneo.visionclaw.core.network.GoogleNewsClient
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * AiTapTool for Google News headlines.
 * Action: query → fetch top headlines from Google News RSS.
 */
class GoogleNewsTool(
    private val context: Context,
    private val newsClient: GoogleNewsClient
) : AiTapTool {
    override val name = "google_news"

    override suspend fun execute(args: Map<String, String>): Result<String> {
        val action = args["action"] ?: "query"
        val count = args["count"]?.toIntOrNull() ?: 5
        Log.d("GoogleNewsTool", "action=$action count=$count")

        return when (action) {
            "query" -> fetchNews(count)
            else -> Result.failure(Exception("Unknown news action: $action. Supported: query"))
        }
    }

    private suspend fun fetchNews(count: Int): Result<String> {
        return when (val result = newsClient.fetchHeadlines(maxResults = count)) {
            is GoogleNewsClient.NewsResult.Success -> {
                if (result.headlines.isEmpty()) {
                    Result.success("No news headlines available.")
                } else {
                    val timeFormat = SimpleDateFormat("h:mm a", Locale.US)
                    val formatted = result.headlines.mapIndexed { idx, headline ->
                        val sourceStr = headline.source?.let { " ($it)" } ?: ""
                        val timeStr = headline.pubDate?.let { " — ${timeFormat.format(it)}" } ?: ""
                        "${idx + 1}) ${headline.title}$sourceStr$timeStr"
                    }.joinToString("\n")
                    Result.success("Top ${result.headlines.size} headlines:\n$formatted")
                }
            }
            is GoogleNewsClient.NewsResult.Error ->
                Result.failure(Exception("News error: ${result.message}"))
        }
    }
}
