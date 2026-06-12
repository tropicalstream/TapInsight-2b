package com.rayneo.visionclaw.core.tools

import android.content.Context
import android.util.Log
import com.rayneo.visionclaw.core.network.GoogleTasksClient
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AiTapTool for Google Tasks.
 * Actions: query, create, complete
 */
class GoogleTasksTool(
    private val context: Context,
    private val tasksClient: GoogleTasksClient
) : AiTapTool {
    override val name = "google_tasks"

    override suspend fun execute(args: Map<String, String>): Result<String> {
        val action = args["action"] ?: "query"
        Log.d("GoogleTasksTool", "action=$action args=$args")

        return when (action) {
            "query" -> queryTasks(args)
            "create" -> createTask(args)
            "complete" -> completeTask(args)
            else -> Result.failure(Exception("Unknown tasks action: $action. Supported: query, create, complete"))
        }
    }

    private suspend fun queryTasks(args: Map<String, String>): Result<String> {
        val maxResults = args["count"]?.toIntOrNull() ?: 10

        return when (val result = tasksClient.fetchTasks(maxResults = maxResults)) {
            is GoogleTasksClient.TasksResult.Success -> {
                if (result.tasks.isEmpty()) {
                    Result.success("No pending tasks.")
                } else {
                    val dateFormat = SimpleDateFormat("EEE MMM d", Locale.US)
                    val formatted = result.tasks.joinToString("\n") { task ->
                        val dueStr = task.due?.let { " (due ${dateFormat.format(it)})" } ?: ""
                        val notesStr = task.notes?.let { " — $it" } ?: ""
                        "• ${task.title}$dueStr$notesStr"
                    }
                    Result.success("${result.tasks.size} pending task(s):\n$formatted")
                }
            }
            is GoogleTasksClient.TasksResult.AuthRequired ->
                Result.failure(Exception("Tasks not authorized. Complete OAuth setup with Tasks scope in TapInsight companion app."))
            is GoogleTasksClient.TasksResult.Error ->
                Result.failure(Exception("Tasks error: ${result.message}"))
        }
    }

    private suspend fun createTask(args: Map<String, String>): Result<String> {
        val title = args["title"] ?: return Result.failure(Exception("Task title is required"))
        val notes = args["notes"]
        val dueDate = args["due_date"]

        return when (val result = tasksClient.createTask(
            title = title,
            notes = notes,
            dueDate = dueDate
        )) {
            is GoogleTasksClient.TasksResult.Success -> {
                Result.success("Task created: \"$title\"")
            }
            is GoogleTasksClient.TasksResult.AuthRequired ->
                Result.failure(Exception("Tasks not authorized. Complete OAuth setup."))
            is GoogleTasksClient.TasksResult.Error ->
                Result.failure(Exception("Failed to create task: ${result.message}"))
        }
    }

    private suspend fun completeTask(args: Map<String, String>): Result<String> {
        val taskId = args["task_id"] ?: return Result.failure(Exception("task_id is required"))
        val listId = args["list_id"] ?: "@default"

        return when (val result = tasksClient.completeTask(taskListId = listId, taskId = taskId)) {
            is GoogleTasksClient.TasksResult.Success -> {
                Result.success("Task marked as complete.")
            }
            is GoogleTasksClient.TasksResult.AuthRequired ->
                Result.failure(Exception("Tasks not authorized."))
            is GoogleTasksClient.TasksResult.Error ->
                Result.failure(Exception("Failed to complete task: ${result.message}"))
        }
    }
}
