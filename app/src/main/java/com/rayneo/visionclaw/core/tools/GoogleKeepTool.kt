package com.rayneo.visionclaw.core.tools

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Note-taking tool that stores notes locally on the device.
 *
 * Google Keep API is restricted and not available to most developers,
 * so this stores notes in SharedPreferences. Notes are accessible
 * from the companion app and persist across restarts.
 *
 * Actions: create, append, list
 */
class GoogleKeepTool(private val context: Context) : AiTapTool {
    override val name = "google_keep"

    companion object {
        private const val TAG = "GoogleKeepTool"
        private const val PREFS_NAME = "tapinsight_notes"
        private const val KEY_NOTES = "notes_json"
        private const val MAX_NOTES = 100
    }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    override suspend fun execute(args: Map<String, String>): Result<String> {
        val action = args["action"] ?: "create"
        val title = args["title"] ?: "TapInsight Note"
        val content = args["content"] ?: ""

        Log.d(TAG, "action=$action title=$title contentLen=${content.length}")

        return when (action) {
            "create" -> createNote(title, content)
            "append" -> appendToNote(title, content)
            "list" -> listNotes()
            else -> Result.failure(Exception("Unknown action: $action. Supported: create, append, list"))
        }
    }

    private fun createNote(title: String, content: String): Result<String> {
        val notes = loadNotes()
        val note = JSONObject().apply {
            put("id", System.currentTimeMillis().toString())
            put("title", title)
            put("content", content)
            put("created_at", System.currentTimeMillis())
            put("updated_at", System.currentTimeMillis())
        }
        notes.put(note)

        // Trim old notes if over limit
        while (notes.length() > MAX_NOTES) {
            notes.remove(0)
        }

        saveNotes(notes)
        Log.i(TAG, "Created note: $title")
        return Result.success("Note created: \"$title\". Note: This is stored locally on your glasses (Google Keep API is not available). You can view your notes by asking me to list them.")
    }

    private fun appendToNote(title: String, content: String): Result<String> {
        val notes = loadNotes()
        var found = false

        for (i in (notes.length() - 1) downTo 0) {
            val note = notes.getJSONObject(i)
            if (note.optString("title", "").equals(title, ignoreCase = true)) {
                val existing = note.optString("content", "")
                note.put("content", if (existing.isBlank()) content else "$existing\n$content")
                note.put("updated_at", System.currentTimeMillis())
                found = true
                break
            }
        }

        if (!found) {
            // Create new note if not found
            return createNote(title, content)
        }

        saveNotes(notes)
        Log.i(TAG, "Appended to note: $title")
        return Result.success("Appended to note: \"$title\"")
    }

    private fun listNotes(): Result<String> {
        val notes = loadNotes()
        if (notes.length() == 0) {
            return Result.success("No notes saved yet.")
        }

        val sb = StringBuilder("Your notes (${notes.length()}):\n")
        for (i in (notes.length() - 1) downTo maxOf(0, notes.length() - 10)) {
            val note = notes.getJSONObject(i)
            val title = note.optString("title", "Untitled")
            val content = note.optString("content", "")
            val preview = if (content.length > 80) content.take(80) + "..." else content
            sb.append("• $title")
            if (preview.isNotBlank()) sb.append(": $preview")
            sb.append("\n")
        }
        if (notes.length() > 10) {
            sb.append("...and ${notes.length() - 10} more")
        }
        return Result.success(sb.toString().trim())
    }

    private fun loadNotes(): JSONArray {
        val raw = prefs.getString(KEY_NOTES, "[]") ?: "[]"
        return try {
            JSONArray(raw)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse notes", e)
            JSONArray()
        }
    }

    private fun saveNotes(notes: JSONArray) {
        prefs.edit().putString(KEY_NOTES, notes.toString()).apply()
    }
}
