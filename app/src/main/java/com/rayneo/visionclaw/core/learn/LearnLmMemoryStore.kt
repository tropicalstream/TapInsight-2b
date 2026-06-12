package com.rayneo.visionclaw.core.learn

import android.content.Context
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

class LearnLmMemoryStore(context: Context) {

    companion object {
        private const val TAG = "LearnLmMemoryStore"
        private const val ROOT_DIR_NAME = "learnlm"
        private const val INDEX_FILE_NAME = "index.json"
        private const val MAX_INDEX_ITEMS = 1   // Keep only the current problem
        private const val MAX_CONTEXT_LESSONS = 1
        private const val MAX_CONTEXT_CARDS = 4
        private const val LESSON_EXCERPT_MAX_CHARS = 1200
        private val CONTINUATION_PATTERNS = listOf(
            Regex("(?i)^\\s*(?:continue|keep going|go on|next step|what should i try next)\\s*(?:on|with)?\\s*(?:the\\s+)?(?:previous|same)?\\s*(?:problem|lesson|topic)?\\s*$"),
            Regex("(?i)^\\s*(?:continue|pick up)\\s+(?:where we left off|from before|the previous problem|the last lesson)\\s*$"),
            Regex("(?i)^\\s*(?:help me with|teach me)\\s+(?:the next step|the next part|the same problem)\\s*$")
        )
    }

    data class LessonRef(
        val topic: String,
        val query: String,
        val folderPath: String,
        val timestampMs: Long,
        val summary: String,
        val lessonExcerpt: String?,
        val referenceImagePath: String?
    )

    data class MemoryContext(
        val topicLabel: String,
        val priorLessons: List<LessonRef>,
        val relatedCards: List<String>,
        val isContinuation: Boolean
    )

    private data class IndexEntry(
        val topic: String,
        val query: String,
        val folderPath: String,
        val timestampMs: Long,
        val summary: String,
        val keywords: Set<String>,
        val referenceImagePath: String?
    )

    private val rootDir = File(context.filesDir, ROOT_DIR_NAME)
    private val indexFile = File(rootDir, INDEX_FILE_NAME)
    private val appContext = context.applicationContext

    init {
        if (!rootDir.exists()) {
            rootDir.mkdirs()
        }
        migrateToSingleProblemStorage()
    }

    /**
     * One-time migration: trim existing archive down to just the most recent
     * lesson and delete all older lesson folders from disk.  Runs once then
     * sets a SharedPreferences flag so it never repeats.
     */
    private fun migrateToSingleProblemStorage() {
        val prefs = appContext.getSharedPreferences("learnlm_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("migrated_single_problem", false)) return
        runCatching {
            val entries = readIndexEntries().sortedByDescending { it.timestampMs }
            val keep = entries.take(MAX_INDEX_ITEMS)            // latest only
            val evict = entries.drop(MAX_INDEX_ITEMS)
            for (old in evict) {
                val dir = File(rootDir, old.folderPath)
                if (dir.exists() && dir.isDirectory) {
                    dir.deleteRecursively()
                    Log.d(TAG, "Migration purged: ${old.folderPath}")
                    val parent = dir.parentFile
                    if (parent != null && parent != rootDir && parent.isDirectory) {
                        val remaining = parent.listFiles()
                        if (remaining == null || remaining.isEmpty()) parent.delete()
                    }
                }
            }
            // Rewrite index with only the kept entry
            val json = JSONArray()
            keep.forEach { entry ->
                json.put(
                    JSONObject()
                        .put("topic", entry.topic)
                        .put("query", entry.query)
                        .put("folderPath", entry.folderPath)
                        .put("timestampMs", entry.timestampMs)
                        .put("summary", entry.summary)
                        .put("keywords", JSONArray().apply { entry.keywords.forEach { put(it) } })
                        .put("referenceImagePath", entry.referenceImagePath ?: JSONObject.NULL)
                )
            }
            indexFile.writeText(json.toString(2))
            Log.d(TAG, "Migration complete — kept ${keep.size} lesson(s), purged ${evict.size}")
        }.onFailure { error ->
            Log.w(TAG, "Migration failed", error)
        }
        prefs.edit().putBoolean("migrated_single_problem", true).apply()
    }

    fun normalizeQuery(query: String): String {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return trimmed
        val strippedPrefix = trimmed
            .replaceFirst(Regex("(?i)^\\s*learn\\s*l\\.?m\\.?\\b[:\\-\\s]*"), "")
            .trim()
        return strippedPrefix.ifBlank { "continue on the previous problem" }
    }

    fun isContinuationQuery(query: String): Boolean {
        val normalized = normalizeQuery(query)
        val lower = normalized.lowercase(Locale.US)
        if (CONTINUATION_PATTERNS.any { it.matches(normalized) }) return true
        return listOf(
            "continue",
            "resume",
            "pick up",
            "where we left off",
            "from before",
            "previous problem",
            "last problem",
            "same problem",
            "previous lesson",
            "last lesson",
            "same lesson",
            "previous topic",
            "last topic",
            "same topic"
        ).any { lower.contains(it) }
    }

    fun buildContext(query: String, recentCards: List<String>): MemoryContext {
        val normalizedQuery = normalizeQuery(query)
        val continuation = isContinuationQuery(normalizedQuery)
        val allEntries = readIndexEntries().sortedByDescending { it.timestampMs }
        val queryTokens = tokenize(normalizedQuery + " " + deriveTopicLabel(normalizedQuery))

        val selectedEntries = if (continuation) {
            val matchedEntries = allEntries
                .map { entry -> entry to scoreEntry(entry, queryTokens) }
                .filter { (_, score) -> score > 0 }
                .sortedWith(compareByDescending<Pair<IndexEntry, Int>> { it.second }.thenByDescending { it.first.timestampMs })
                .map { it.first }
                .take(MAX_CONTEXT_LESSONS)

            if (matchedEntries.isNotEmpty()) {
                matchedEntries
            } else {
                allEntries.take(MAX_CONTEXT_LESSONS)
            }
        } else {
            allEntries
                .map { entry -> entry to scoreEntry(entry, queryTokens) }
                .filter { (_, score) -> score > 0 }
                .sortedWith(compareByDescending<Pair<IndexEntry, Int>> { it.second }.thenByDescending { it.first.timestampMs })
                .take(MAX_CONTEXT_LESSONS)
                .map { it.first }
        }

        val lessons = selectedEntries.map { entry ->
            val sessionJson = loadSessionJson(entry.folderPath)
            val resolvedQuery = sessionJson
                ?.optString("query")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: entry.query
            LessonRef(
                topic = entry.topic,
                query = resolvedQuery,
                folderPath = entry.folderPath,
                timestampMs = entry.timestampMs,
                summary = entry.summary,
                lessonExcerpt = loadLessonExcerpt(entry.folderPath),
                referenceImagePath = entry.referenceImagePath
            )
        }

        val relatedCards = if (continuation) {
            recentCards
                .asReversed()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .take(MAX_CONTEXT_CARDS)
        } else {
            recentCards
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .map { card -> card to scoreText(card, queryTokens) }
                .filter { (_, score) -> score > 0 }
                .sortedByDescending { it.second }
                .map { it.first }
                .distinct()
                .take(MAX_CONTEXT_CARDS)
        }

        val topicLabel = if (continuation && lessons.isNotEmpty()) {
            lessons.first().topic
        } else {
            deriveTopicLabel(normalizedQuery)
        }

        return MemoryContext(
            topicLabel = topicLabel,
            priorLessons = lessons,
            relatedCards = relatedCards,
            isContinuation = continuation
        )
    }

    fun latestReferenceImageBase64(memoryContext: MemoryContext): String? {
        val lessonWithImage = memoryContext.priorLessons.firstOrNull { !it.referenceImagePath.isNullOrBlank() } ?: return null
        return loadReferenceImageBase64(lessonWithImage.referenceImagePath)
    }

    fun latestReferenceImagePath(memoryContext: MemoryContext): String? {
        return memoryContext.priorLessons.firstOrNull { !it.referenceImagePath.isNullOrBlank() }?.referenceImagePath
    }

    fun summarizeContext(query: String, recentCards: List<String>): String {
        val memoryContext = buildContext(query, recentCards)
        return buildString {
            append("Topic: ")
            append(memoryContext.topicLabel)
            append('\n')
            append("Continuation: ")
            append(if (memoryContext.isContinuation) "yes" else "no")
            if (memoryContext.priorLessons.isNotEmpty()) {
                append("\nRecent lessons:")
                memoryContext.priorLessons.forEach { lesson ->
                    append("\n- ")
                    append(lesson.topic)
                    append(" | request: ")
                    append(lesson.query)
                    append(" | summary: ")
                    append(lesson.summary)
                    if (!lesson.referenceImagePath.isNullOrBlank()) {
                        append(" (image saved)")
                    }
                }
            }
            if (memoryContext.relatedCards.isNotEmpty()) {
                append("\nRelated cards:")
                memoryContext.relatedCards.forEach { card ->
                    append("\n- ")
                    append(card.replace('\n', ' ').trim())
                }
            }
            if (memoryContext.priorLessons.isEmpty() && memoryContext.relatedCards.isEmpty()) {
                append("\nNo prior tutor context found.")
            }
        }
    }

    fun saveLesson(
        topic: String,
        query: String,
        response: String,
        relatedCards: List<String>,
        referenceImageBase64: String? = null
    ) {
        runCatching {
            val now = System.currentTimeMillis()
            val dateDir = File(rootDir, SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(now)))
            if (!dateDir.exists()) dateDir.mkdirs()

            val normalizedQuery = normalizeQuery(query)
            val topicLabel = if (topic.isNotBlank()) topic else deriveTopicLabel(normalizedQuery)
            val folderName = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(now)) + "_" + slugify(topicLabel)
            val sessionDir = File(dateDir, folderName)
            sessionDir.mkdirs()

            val imageFileName = saveReferenceImageIfPresent(sessionDir, referenceImageBase64)
            val summary = summarize(response)
            val sessionJson = JSONObject()
                .put("topic", topicLabel)
                .put("timestampMs", now)
                .put("query", normalizedQuery)
                .put("summary", summary)
                .put("relatedCards", JSONArray().apply {
                    relatedCards.take(MAX_CONTEXT_CARDS).forEach { put(it) }
                })
                .put(
                    "files",
                    JSONObject()
                        .put("lesson", "lesson.md")
                        .apply {
                            if (imageFileName != null) {
                                put("referenceImage", imageFileName)
                            }
                        }
                )
            File(sessionDir, "session.json").writeText(sessionJson.toString(2))

            val markdown = buildString {
                append("# ")
                append(topicLabel)
                append("\n\n")
                append("- Saved: ")
                append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(now)))
                append("\n")
                if (imageFileName != null) {
                    append("- Reference image: ")
                    append(imageFileName)
                    append("\n")
                }
                append("\n## User request\n")
                append(normalizedQuery)
                append("\n\n## Tutor response\n")
                append(response.trim())
                if (relatedCards.isNotEmpty()) {
                    append("\n\n## Related chat cards\n")
                    relatedCards.take(MAX_CONTEXT_CARDS).forEach { card ->
                        append("- ")
                        append(card.trim().replace('\n', ' '))
                        append('\n')
                    }
                }
            }
            File(sessionDir, "lesson.md").writeText(markdown)

            val relativeFolder = File(dateDir.name, folderName).path
            updateIndex(
                IndexEntry(
                    topic = topicLabel,
                    query = normalizedQuery,
                    folderPath = relativeFolder,
                    timestampMs = now,
                    summary = summary,
                    keywords = tokenize(topicLabel + " " + normalizedQuery + " " + summary),
                    referenceImagePath = imageFileName?.let { File(relativeFolder, it).path }
                )
            )
        }.onFailure { error ->
            Log.w(TAG, "Failed to save LearnLM lesson", error)
        }
    }

    private fun saveReferenceImageIfPresent(sessionDir: File, referenceImageBase64: String?): String? {
        val payload = referenceImageBase64?.trim().orEmpty()
        if (payload.isBlank()) return null
        return runCatching {
            val bytes = Base64.decode(payload, Base64.DEFAULT)
            val name = "reference.jpg"
            File(sessionDir, name).writeBytes(bytes)
            name
        }.getOrElse { error ->
            Log.w(TAG, "Failed to save LearnLM reference image", error)
            null
        }
    }

    private fun loadReferenceImageBase64(relativePath: String?): String? {
        val safePath = relativePath?.trim().orEmpty()
        if (safePath.isBlank()) return null
        return runCatching {
            val file = File(rootDir, safePath)
            if (!file.exists() || !file.isFile) return@runCatching null
            Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
        }.getOrElse { error ->
            Log.w(TAG, "Failed to load LearnLM reference image", error)
            null
        }
    }

    private fun updateIndex(newEntry: IndexEntry) {
        val current = readIndexEntries().toMutableList()
        current.add(0, newEntry)
        val all = current
            .distinctBy { it.folderPath }
            .sortedByDescending { it.timestampMs }

        // Keep only the latest problem; delete older lesson folders from disk
        val trimmed = all.take(MAX_INDEX_ITEMS)
        val evicted = all.drop(MAX_INDEX_ITEMS)
        for (old in evicted) {
            purgeSessionFolder(old.folderPath)
        }

        val json = JSONArray()
        trimmed.forEach { entry ->
            json.put(
                JSONObject()
                    .put("topic", entry.topic)
                    .put("query", entry.query)
                    .put("folderPath", entry.folderPath)
                    .put("timestampMs", entry.timestampMs)
                    .put("summary", entry.summary)
                    .put("keywords", JSONArray().apply { entry.keywords.forEach { put(it) } })
                    .put("referenceImagePath", entry.referenceImagePath ?: JSONObject.NULL)
            )
        }
        indexFile.writeText(json.toString(2))
    }

    /** Delete a session folder and its empty parent date-directory. */
    private fun purgeSessionFolder(folderPath: String) {
        runCatching {
            val sessionDir = File(rootDir, folderPath)
            if (sessionDir.exists() && sessionDir.isDirectory) {
                sessionDir.deleteRecursively()
                Log.d(TAG, "Purged old lesson folder: $folderPath")
                // Remove the parent date folder if now empty
                val parent = sessionDir.parentFile
                if (parent != null && parent != rootDir && parent.isDirectory) {
                    val remaining = parent.listFiles()
                    if (remaining == null || remaining.isEmpty()) {
                        parent.delete()
                        Log.d(TAG, "Removed empty date folder: ${parent.name}")
                    }
                }
            }
        }.onFailure { error ->
            Log.w(TAG, "Failed to purge old lesson folder: $folderPath", error)
        }
    }

    /** Wipe all stored lessons and reset the index. Call externally for a full cleanup. */
    fun purgeAllLessons() {
        runCatching {
            rootDir.listFiles()?.forEach { child ->
                if (child.isDirectory) {
                    child.deleteRecursively()
                    Log.d(TAG, "Purged: ${child.name}")
                }
            }
            indexFile.writeText("[]")
            Log.d(TAG, "All LearnLM lessons purged, index reset")
        }.onFailure { error ->
            Log.w(TAG, "Failed to purge all LearnLM lessons", error)
        }
    }

    private fun readIndexEntries(): List<IndexEntry> {
        if (!indexFile.exists()) return emptyList()
        return runCatching {
            val raw = indexFile.readText()
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val keywords = buildSet {
                        val arr = item.optJSONArray("keywords") ?: JSONArray()
                        for (j in 0 until arr.length()) {
                            val token = arr.optString(j).trim()
                            if (token.isNotBlank()) add(token)
                        }
                    }
                    val imagePath = item.optString("referenceImagePath").trim().ifBlank { null }
                    add(
                        IndexEntry(
                            topic = item.optString("topic").trim(),
                            query = item.optString("query").trim(),
                            folderPath = item.optString("folderPath").trim(),
                            timestampMs = item.optLong("timestampMs"),
                            summary = item.optString("summary").trim(),
                            keywords = keywords,
                            referenceImagePath = imagePath
                        )
                    )
                }
            }.filter { it.topic.isNotBlank() && it.folderPath.isNotBlank() }
        }.getOrElse { error ->
            Log.w(TAG, "Failed to read LearnLM index", error)
            emptyList()
        }
    }

    private fun loadSessionJson(folderPath: String): JSONObject? {
        val sessionFile = File(File(rootDir, folderPath), "session.json")
        if (!sessionFile.exists() || !sessionFile.isFile) return null
        return runCatching {
            JSONObject(sessionFile.readText())
        }.getOrElse { error ->
            Log.w(TAG, "Failed to read LearnLM session metadata", error)
            null
        }
    }

    private fun loadLessonExcerpt(folderPath: String): String? {
        val lessonFile = File(File(rootDir, folderPath), "lesson.md")
        if (!lessonFile.exists() || !lessonFile.isFile) return null
        return runCatching {
            lessonFile.readText()
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .joinToString(" ")
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(LESSON_EXCERPT_MAX_CHARS)
                .ifBlank { null }
        }.getOrElse { error ->
            Log.w(TAG, "Failed to read LearnLM lesson excerpt", error)
            null
        }
    }

    private fun deriveTopicLabel(query: String): String {
        val normalized = normalizeQuery(query)
        if (isContinuationQuery(normalized)) return "Continuing lesson"
        val cleaned = normalized
            .trim()
            .removePrefix("help me learn")
            .removePrefix("Teach me")
            .removePrefix("teach me")
            .removePrefix("show me how to")
            .removePrefix("walk me through")
            .removePrefix("help me understand")
            .removePrefix("help me study")
            .removePrefix("how do i")
            .removePrefix("how can i")
            .removePrefix("how to")
            .trim(' ', '.', '?', '!')

        if (cleaned.isBlank()) return normalized.take(80)
        return cleaned
            .split(Regex("\\s+"))
            .take(8)
            .joinToString(" ")
            .trim()
            .ifBlank { normalized.take(80) }
    }

    private fun slugify(text: String): String {
        val base = text.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
        return base.ifBlank { "lesson" }.take(48)
    }

    private fun summarize(text: String): String {
        val compact = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return compact.take(220)
    }

    private fun tokenize(text: String): Set<String> {
        val stopWords = setOf(
            "the", "and", "for", "that", "with", "from", "this", "your", "about", "into",
            "have", "will", "just", "them", "they", "what", "when", "where", "which", "while",
            "learn", "teach", "help", "show", "walk", "through", "how", "into", "then", "continue",
            "previous", "problem", "lesson", "topic", "same", "next"
        )
        return text.lowercase(Locale.US)
            .split(Regex("[^a-z0-9]+"))
            .map { it.trim() }
            .filter { it.length >= 3 && it !in stopWords }
            .toSet()
    }

    private fun scoreEntry(entry: IndexEntry, queryTokens: Set<String>): Int {
        if (queryTokens.isEmpty()) return 0
        val overlap = entry.keywords.intersect(queryTokens).size
        if (overlap == 0) return 0
        val recencyBoost = max(1, ((System.currentTimeMillis() - entry.timestampMs) / (1000L * 60L * 60L * 24L)).toInt().let { 8 - it })
        return overlap * 10 + recencyBoost
    }

    private fun scoreText(text: String, queryTokens: Set<String>): Int {
        if (queryTokens.isEmpty()) return 0
        val tokens = tokenize(text)
        val overlap = tokens.intersect(queryTokens).size
        return if (overlap == 0) 0 else overlap * 10
    }
}
