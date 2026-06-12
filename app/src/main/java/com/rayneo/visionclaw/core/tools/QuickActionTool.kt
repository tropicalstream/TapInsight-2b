package com.rayneo.visionclaw.core.tools

import android.content.Context
import android.util.Log
import com.rayneo.visionclaw.core.storage.AppPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * QuickActionTool — user-defined voice macros that trigger sequences of tools.
 *
 * Users configure custom phrases in the companion app that map to tool chains.
 * When the user speaks their custom phrase, this tool orchestrates the sequence.
 *
 * Built-in quick actions:
 *   "good morning"    → daily briefing + weather + calendar
 *   "leaving work"    → traffic home + ETA
 *   "meeting mode"    → mute notifications summary
 *   "bedtime"         → next alarm + weather tomorrow
 *
 * Users can also define custom macros in the companion app.
 */
class QuickActionTool(
    private val context: Context,
    private val toolDispatcher: ToolDispatcher
) : AiTapTool {

    override val name = "quick_action"

    companion object {
        private const val TAG = "QuickActionTool"

        // Built-in quick actions with their tool chains
        val BUILTIN_ACTIONS = mapOf(
            "good morning" to QuickAction(
                name = "Good Morning",
                description = "Start your day with a briefing",
                steps = listOf(
                    ActionStep("daily_briefing", mapOf("detail" to "brief")),
                )
            ),
            "leaving work" to QuickAction(
                name = "Leaving Work",
                description = "Get traffic and ETA home",
                steps = listOf(
                    ActionStep("google_routes", mapOf("origin" to "current", "destination" to "home"))
                )
            ),
            "heading home" to QuickAction(
                name = "Heading Home",
                description = "Traffic and directions home",
                steps = listOf(
                    ActionStep("google_routes", mapOf("origin" to "current", "destination" to "home"))
                )
            ),
            "meeting mode" to QuickAction(
                name = "Meeting Mode",
                description = "Summarize upcoming + mute",
                steps = listOf(
                    ActionStep("google_calendar", mapOf("action" to "upcoming", "count" to "3"))
                )
            ),
            "what's nearby" to QuickAction(
                name = "What's Nearby",
                description = "Find interesting places around you",
                steps = listOf(
                    ActionStep("google_places", mapOf("type" to "restaurant", "radius" to "1000"))
                )
            )
        )
    }

    data class QuickAction(
        val name: String,
        val description: String,
        val steps: List<ActionStep>
    )

    data class ActionStep(
        val toolName: String,
        val args: Map<String, String>
    )

    override suspend fun execute(args: Map<String, String>): Result<String> {
        val actionName = args["action"]?.trim()?.lowercase().orEmpty()
        val query = args["query"]?.trim().orEmpty()

        Log.d(TAG, "QuickAction: action=$actionName query=${query.take(80)}")

        // List available actions
        if (actionName == "list" || actionName == "help") {
            return Result.success(listActions())
        }

        // Find matching action
        val action = findAction(actionName.ifBlank { query })
            ?: return Result.failure(Exception(
                "No quick action found for '$actionName'. Say 'list quick actions' to see available ones."
            ))

        Log.d(TAG, "Executing quick action: ${action.name} (${action.steps.size} steps)")

        // Execute each step in the chain
        val results = mutableListOf<String>()
        for ((index, step) in action.steps.withIndex()) {
            Log.d(TAG, "Step ${index + 1}/${action.steps.size}: ${step.toolName}")
            val argsJson = JSONObject(step.args as Map<*, *>).toString()
            val result = toolDispatcher.dispatch(step.toolName, argsJson)
            result.onSuccess { text ->
                results.add("— ${step.toolName} —\n$text")
            }.onFailure { err ->
                results.add("— ${step.toolName} —\n⚠ ${err.message}")
            }
        }

        val combined = buildString {
            append("⚡ Quick Action: ${action.name}\n\n")
            append(results.joinToString("\n\n"))
        }

        return Result.success(combined)
    }

    private fun findAction(input: String): QuickAction? {
        if (input.isBlank()) return null
        val lower = input.lowercase()

        // Exact match on built-in
        BUILTIN_ACTIONS[lower]?.let { return it }

        // Fuzzy match on built-in action names
        for ((key, action) in BUILTIN_ACTIONS) {
            if (lower.contains(key) || key.contains(lower)) return action
        }

        // Check user-defined actions from preferences
        val prefs = AppPreferences(context)
        val customActions = loadCustomActions(prefs)
        for (action in customActions) {
            if (action.name.lowercase() == lower ||
                lower.contains(action.name.lowercase()) ||
                action.name.lowercase().contains(lower)) {
                return action
            }
        }

        return null
    }

    private fun listActions(): String {
        val prefs = AppPreferences(context)
        val customActions = loadCustomActions(prefs)

        return buildString {
            append("⚡ Available Quick Actions:\n\n")
            append("Built-in:\n")
            for ((trigger, action) in BUILTIN_ACTIONS) {
                append("• \"$trigger\" — ${action.description}\n")
            }
            if (customActions.isNotEmpty()) {
                append("\nCustom:\n")
                for (action in customActions) {
                    append("• \"${action.name}\" — ${action.description}\n")
                }
            }
            append("\nSay any trigger phrase to activate, or create custom actions in the companion app.")
        }
    }

    private fun loadCustomActions(prefs: AppPreferences): List<QuickAction> {
        val json = prefs.quickActionsJson
        if (json.isBlank()) return emptyList()

        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val name = obj.optString("name", "").ifBlank { return@mapNotNull null }
                val desc = obj.optString("description", "Custom action")
                val stepsArr = obj.optJSONArray("steps") ?: return@mapNotNull null
                val steps = (0 until stepsArr.length()).mapNotNull { j ->
                    val stepObj = stepsArr.optJSONObject(j) ?: return@mapNotNull null
                    val toolName = stepObj.optString("tool", "").ifBlank { return@mapNotNull null }
                    val argsObj = stepObj.optJSONObject("args") ?: JSONObject()
                    val argsMap = mutableMapOf<String, String>()
                    for (key in argsObj.keys()) {
                        argsMap[key] = argsObj.optString(key, "")
                    }
                    ActionStep(toolName, argsMap)
                }
                if (steps.isNotEmpty()) QuickAction(name, desc, steps) else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse custom quick actions: ${e.message}")
            emptyList()
        }
    }
}
