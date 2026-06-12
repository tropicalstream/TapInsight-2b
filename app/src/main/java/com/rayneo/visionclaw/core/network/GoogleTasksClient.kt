package com.rayneo.visionclaw.core.network

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * GoogleTasksClient – fetches and manages Google Tasks via the Tasks API v1.
 *
 * Requires OAuth Bearer token (Tasks API does not support API key auth).
 *
 * Error-handling contract:
 *   • No access token → [TasksResult.AuthRequired]
 *   • Network or server errors → [TasksResult.Error] with message
 *   • Success → [TasksResult.Success] with list of tasks
 */
class GoogleTasksClient(
    private val accessTokenProvider: () -> String?,
    private val context: Context? = null
) {

    companion object {
        private const val TAG = "GoogleTasks"
        private const val BASE_URL = "https://tasks.googleapis.com/tasks/v1"
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 15_000
    }

    // ── Data classes ──────────────────────────────────────────────────────

    data class TaskItem(
        val id: String,
        val title: String,
        val notes: String?,
        val due: Date?,
        val status: String,           // "needsAction" or "completed"
        val completed: Date?,
        val position: String?
    )

    data class TaskListInfo(
        val id: String,
        val title: String,
        val updated: String?
    )

    // ── Result types ──────────────────────────────────────────────────────

    sealed class TasksResult {
        data class Success(val tasks: List<TaskItem>) : TasksResult()
        data class Error(val message: String, val code: Int = -1) : TasksResult()
        object AuthRequired : TasksResult()
    }

    sealed class TaskListsResult {
        data class Success(val lists: List<TaskListInfo>) : TaskListsResult()
        data class Error(val message: String) : TaskListsResult()
        object AuthRequired : TaskListsResult()
    }

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Fetch all task lists for the authenticated user.
     */
    suspend fun fetchTaskLists(): TaskListsResult = withContext(Dispatchers.IO) {
        val accessToken = accessTokenProvider()
        if (accessToken.isNullOrBlank()) return@withContext TaskListsResult.AuthRequired

        try {
            val response = ActiveNetworkHttp.get(
                url = "$BASE_URL/users/@me/lists",
                headers = mapOf("Authorization" to "Bearer $accessToken"),
                connectTimeoutMs = CONNECT_TIMEOUT_MS,
                readTimeoutMs = READ_TIMEOUT_MS
            )
            val code = response.code
            if (code in 200..299) {
                val body = response.body
                val json = JSONObject(body)
                val items = json.optJSONArray("items")
                    ?: return@withContext TaskListsResult.Success(emptyList())

                val lists = (0 until items.length()).map { i ->
                    val item = items.getJSONObject(i)
                    TaskListInfo(
                        id = item.optString("id", ""),
                        title = item.optString("title", "(Untitled)"),
                        updated = item.optString("updated", null)
                    )
                }
                Log.d(TAG, "Fetched ${lists.size} task lists")
                TaskListsResult.Success(lists)
            } else {
                val errorBody = response.body
                Log.e(TAG, "TaskLists HTTP $code: $errorBody")
                TaskListsResult.Error("Task lists error ($code)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "TaskLists request failed", e)
            TaskListsResult.Error(e.localizedMessage ?: "Unknown error")
        }
    }

    /**
     * Fetch incomplete tasks from a specific task list.
     *
     * @param taskListId  The task list ID (use "@default" for the user's default list).
     * @param maxResults  Maximum number of tasks to return.
     */
    suspend fun fetchTasks(
        taskListId: String = "@default",
        maxResults: Int = 10
    ): TasksResult = withContext(Dispatchers.IO) {
        val accessToken = accessTokenProvider()
        if (accessToken.isNullOrBlank()) return@withContext TasksResult.AuthRequired

        try {
            val urlStr = "$BASE_URL/lists/$taskListId/tasks" +
                "?maxResults=$maxResults" +
                "&showCompleted=false" +
                "&showHidden=false"

            val response = ActiveNetworkHttp.get(
                url = urlStr,
                headers = mapOf("Authorization" to "Bearer $accessToken"),
                connectTimeoutMs = CONNECT_TIMEOUT_MS,
                readTimeoutMs = READ_TIMEOUT_MS
            )
            val code = response.code
            if (code in 200..299) {
                val body = response.body
                val json = JSONObject(body)
                val items = json.optJSONArray("items")
                    ?: return@withContext TasksResult.Success(emptyList())

                val tasks = (0 until items.length()).mapNotNull { i ->
                    val item = items.getJSONObject(i)
                    val title = item.optString("title", "").trim()
                    if (title.isBlank()) return@mapNotNull null  // skip empty tasks

                    TaskItem(
                        id = item.optString("id", ""),
                        title = title,
                        notes = item.optString("notes", null)?.takeIf { it.isNotBlank() },
                        due = parseRfc3339Date(item.optString("due", "")),
                        status = item.optString("status", "needsAction"),
                        completed = parseRfc3339Date(item.optString("completed", "")),
                        position = item.optString("position", null)
                    )
                }
                Log.d(TAG, "Fetched ${tasks.size} tasks from list $taskListId")
                TasksResult.Success(tasks)
            } else {
                val errorBody = response.body
                Log.e(TAG, "Tasks HTTP $code: $errorBody")
                TasksResult.Error("Tasks error ($code)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Tasks request failed", e)
            TasksResult.Error(e.localizedMessage ?: "Unknown error")
        }
    }

    /**
     * Create a new task in the given list.
     */
    suspend fun createTask(
        taskListId: String = "@default",
        title: String,
        notes: String? = null,
        dueDate: String? = null
    ): TasksResult = withContext(Dispatchers.IO) {
        val accessToken = accessTokenProvider()
        if (accessToken.isNullOrBlank()) return@withContext TasksResult.AuthRequired

        try {
            val taskJson = JSONObject().apply {
                put("title", title)
                if (!notes.isNullOrBlank()) put("notes", notes)
                if (!dueDate.isNullOrBlank()) put("due", dueDate)
            }

            val response = ActiveNetworkHttp.postJson(
                url = "$BASE_URL/lists/$taskListId/tasks",
                jsonBody = taskJson.toString(),
                headers = mapOf(
                    "Authorization" to "Bearer $accessToken",
                    "Content-Type" to "application/json"
                ),
                connectTimeoutMs = CONNECT_TIMEOUT_MS,
                readTimeoutMs = READ_TIMEOUT_MS
            )
            val code = response.code
            if (code in 200..299) {
                val body = response.body
                val item = JSONObject(body)
                val created = TaskItem(
                    id = item.optString("id", ""),
                    title = item.optString("title", title),
                    notes = notes,
                    due = parseRfc3339Date(item.optString("due", "")),
                    status = item.optString("status", "needsAction"),
                    completed = null,
                    position = item.optString("position", null)
                )
                TasksResult.Success(listOf(created))
            } else {
                val errorBody = response.body
                Log.e(TAG, "Create task HTTP $code: $errorBody")
                TasksResult.Error("Failed to create task ($code)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Create task failed", e)
            TasksResult.Error(e.localizedMessage ?: "Unknown error")
        }
    }

    /**
     * Mark a task as completed.
     */
    suspend fun completeTask(
        taskListId: String = "@default",
        taskId: String
    ): TasksResult = withContext(Dispatchers.IO) {
        val accessToken = accessTokenProvider()
        if (accessToken.isNullOrBlank()) return@withContext TasksResult.AuthRequired

        try {
            val patchJson = JSONObject().apply {
                put("status", "completed")
            }

            val response = ActiveNetworkHttp.postJson(
                url = "$BASE_URL/lists/$taskListId/tasks/$taskId",
                jsonBody = patchJson.toString(),
                headers = mapOf(
                    "Authorization" to "Bearer $accessToken",
                    "Content-Type" to "application/json",
                    "X-HTTP-Method-Override" to "PATCH"
                ),
                connectTimeoutMs = CONNECT_TIMEOUT_MS,
                readTimeoutMs = READ_TIMEOUT_MS
            )
            val code = response.code
            if (code in 200..299) {
                TasksResult.Success(emptyList())
            } else {
                val errorBody = response.body
                Log.e(TAG, "Complete task HTTP $code: $errorBody")
                TasksResult.Error("Failed to complete task ($code)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Complete task failed", e)
            TasksResult.Error(e.localizedMessage ?: "Unknown error")
        }
    }

    // ── Date parsing ──────────────────────────────────────────────────────

    private fun parseRfc3339Date(dateStr: String): Date? {
        if (dateStr.isBlank()) return null
        val formats = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd"
        )
        for (fmt in formats) {
            try {
                return SimpleDateFormat(fmt, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.parse(dateStr)
            } catch (_: Exception) { /* try next */ }
        }
        return null
    }

}
