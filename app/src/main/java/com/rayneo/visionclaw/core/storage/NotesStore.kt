package com.rayneo.visionclaw.core.storage

import android.content.Context
import android.util.Log
import com.TapLink.app.media.MediaLibraryService
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * On-glasses voice notes. Notes are the source of truth ON the glasses, stored
 * as a markdown file in the Media Library Text/ folder (so they also show up in
 * the on-glasses library UI and are reachable by the companion API). Hermes /
 * OpenClaw find them because [readRecent] injects their content into an agent
 * query when the user asks about their notes — no flaky reverse-fetch needed.
 *
 * Append-only, timestamped, newest at the bottom. Self-contained: a failure
 * here never throws into the caller.
 */
object NotesStore {

    private const val TAG = "NotesStore"
    /** Library-relative path agents are told about. */
    const val NOTES_RELATIVE_PATH = "Text/Notes.md"
    private val TS = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    private fun notesFile(context: Context): File {
        val lib = MediaLibraryService(context).apply { ensureBootstrap() }
        val dir = File(lib.mediaRoot, MediaLibraryService.DEFAULT_TEXT_DIR)
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "Notes.md")
    }

    /** Append a timestamped note. Returns the library-relative path, or null. */
    @Synchronized
    fun appendNote(context: Context, text: String): String? {
        val body = text.trim()
        if (body.isBlank()) return null
        return try {
            val f = notesFile(context)
            if (!f.exists()) f.writeText("# Notes\n\n", Charsets.UTF_8)
            f.appendText("- [${TS.format(Date())}] $body\n", Charsets.UTF_8)
            Log.i(TAG, "note appended (${body.length} chars) -> $NOTES_RELATIVE_PATH")
            NOTES_RELATIVE_PATH
        } catch (e: Exception) {
            Log.w(TAG, "appendNote failed: ${e.message}")
            null
        }
    }

    /** Recent notes (tail), for injecting into an agent query. Null if none. */
    fun readRecent(context: Context, maxChars: Int = 4000): String? {
        return try {
            val f = notesFile(context)
            if (!f.exists()) return null
            val all = f.readText(Charsets.UTF_8).trim()
            when {
                all.isBlank() -> null
                all.length <= maxChars -> all
                else -> all.takeLast(maxChars)
            }
        } catch (e: Exception) {
            Log.w(TAG, "readRecent failed: ${e.message}")
            null
        }
    }
}
