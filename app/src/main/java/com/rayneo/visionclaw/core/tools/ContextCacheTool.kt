package com.rayneo.visionclaw.core.tools

import android.content.Context
import android.util.Log
import com.rayneo.visionclaw.core.learn.LearnLmMemoryStore
import com.rayneo.visionclaw.core.storage.db.ChatDatabase

class ContextCacheTool(private val context: Context) : AiTapTool {
    override val name = "get_context"

    override suspend fun execute(args: Map<String, String>): Result<String> {
        val query = args["query"]?.trim().orEmpty()
        val timeRange = args["time_range"] ?: "last_30_minutes"

        Log.d("ContextCacheTool", "query=$query timeRange=$timeRange")

        val dao = ChatDatabase.getInstance(context).chatMessageDao()
        val recentCards = dao.getAllMessages()
            .takeLast(12)
            .map { it.text }
            .filter { it.isNotBlank() }

        val store = LearnLmMemoryStore(context)
        val normalizedQuery = if (query.isBlank()) "continue on the previous problem" else query
        val summary = store.summarizeContext(normalizedQuery, recentCards)
        return Result.success(summary)
    }
}
