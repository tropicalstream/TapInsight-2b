package com.rayneo.visionclaw.core.storage

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.abs

class ReadableArtifactStore(context: Context) {

    enum class ArtifactKind(
        val key: String,
        val baseFileName: String
    ) {
        RESEARCH_REPORT("research_report", "latest_research_report"),
        TAPCLAW_RESULT("tapclaw_result", "latest_tapclaw_result");

        companion object {
            fun fromKey(key: String?): ArtifactKind? =
                values().firstOrNull { it.key == key }
        }
    }

    data class ReadableArtifact(
        val kind: ArtifactKind,
        val title: String,
        val text: String,
        val sourceLabel: String?,
        val createdAtMs: Long,
        val textFile: File,
        val id: String? = null,
        val topic: String? = null,
        val unread: Boolean = false
    )

    private data class ResearchIndexEntry(
        val id: String,
        val title: String,
        val topic: String?,
        val normalizedTopic: String,
        val sourceLabel: String?,
        val createdAtMs: Long,
        val textFileName: String,
        val unread: Boolean
    )

    companion object {
        private const val RESEARCH_INDEX_FILE = "research_report_index.json"
        private const val RESEARCH_DEDUPE_WINDOW_MS = 5 * 60 * 1000L
    }

    private val dir = File(context.filesDir, "readable_artifacts").apply { mkdirs() }

    fun saveLatest(
        kind: ArtifactKind,
        title: String,
        text: String,
        sourceLabel: String? = null,
        createdAtMs: Long = System.currentTimeMillis(),
        id: String? = null,
        topic: String? = null,
        unread: Boolean = false
    ): ReadableArtifact? {
        val safeText = text.trim()
        if (safeText.isBlank()) return null

        val txt = textFile(kind)
        val meta = metadataFile(kind)
        txt.writeText(safeText, Charsets.UTF_8)
        meta.writeText(
            JSONObject()
                .put("kind", kind.key)
                .put("id", id ?: JSONObject.NULL)
                .put("title", title.trim().ifBlank { defaultTitle(kind) })
                .put("topic", topic?.trim()?.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
                .put("unread", unread)
                .put("sourceLabel", sourceLabel ?: JSONObject.NULL)
                .put("createdAtMs", createdAtMs)
                .put("textFileName", txt.name)
                .toString(),
            Charsets.UTF_8
        )

        return loadLatest(kind)
    }

    fun saveResearchReport(
        topic: String,
        title: String,
        text: String,
        sourceLabel: String? = null,
        createdAtMs: Long = System.currentTimeMillis()
    ): ReadableArtifact? {
        val safeText = text.trim()
        val safeTopic = topic.trim().ifBlank { title.trim() }.ifBlank { "research" }
        val normalizedTopic = normalizeTopicQuery(safeTopic)
        if (safeText.isBlank()) return null

        val entries = loadResearchIndexEntries().toMutableList()
        val duplicate = entries.firstOrNull { entry ->
            val sameWindow = abs(createdAtMs - entry.createdAtMs) <= RESEARCH_DEDUPE_WINDOW_MS
            if (!sameWindow) return@firstOrNull false
            if (entry.normalizedTopic != normalizedTopic) return@firstOrNull false
            val entryFile = File(dir, entry.textFileName)
            entryFile.exists() && entryFile.readText(Charsets.UTF_8).trim() == safeText
        }

        val artifact = if (duplicate != null) {
            val updatedEntries = entries.map { entry ->
                when (entry.id) {
                    duplicate.id -> entry.copy(
                        title = title.trim().ifBlank { entry.title },
                        topic = safeTopic,
                        normalizedTopic = normalizedTopic,
                        sourceLabel = sourceLabel ?: entry.sourceLabel,
                        unread = true
                    )

                    else -> entry.copy(unread = false)
                }
            }
            writeResearchIndexEntries(updatedEntries)
            saveLatest(
                kind = ArtifactKind.RESEARCH_REPORT,
                title = title,
                text = safeText,
                sourceLabel = sourceLabel,
                createdAtMs = duplicate.createdAtMs,
                id = duplicate.id,
                topic = safeTopic,
                unread = true
            )
            loadResearchReportById(duplicate.id)
        } else {
            val slug = slugify(if (normalizedTopic.isNotBlank()) safeTopic else title)
            val id = "research_${createdAtMs}_$slug"
            val archiveTextFile = File(dir, "research_report_${createdAtMs}_$slug.txt")
            archiveTextFile.writeText(safeText, Charsets.UTF_8)
            val newEntry = ResearchIndexEntry(
                id = id,
                title = title.trim().ifBlank { defaultTitle(ArtifactKind.RESEARCH_REPORT) },
                topic = safeTopic,
                normalizedTopic = normalizedTopic,
                sourceLabel = sourceLabel,
                createdAtMs = createdAtMs,
                textFileName = archiveTextFile.name,
                unread = true
            )
            val updatedEntries = buildList {
                add(newEntry)
                addAll(entries.map { it.copy(unread = false) })
            }
            writeResearchIndexEntries(updatedEntries)
            saveLatest(
                kind = ArtifactKind.RESEARCH_REPORT,
                title = newEntry.title,
                text = safeText,
                sourceLabel = sourceLabel,
                createdAtMs = createdAtMs,
                id = id,
                topic = safeTopic,
                unread = true
            )
            loadResearchReportById(id)
        }

        return artifact
    }

    fun loadLatest(kind: ArtifactKind): ReadableArtifact? {
        val meta = metadataFile(kind)
        if (!meta.exists()) return null

        return runCatching {
            val root = JSONObject(meta.readText(Charsets.UTF_8))
            val actualKind = ArtifactKind.fromKey(root.optString("kind")) ?: kind
            val textName = root.optString("textFileName", textFile(actualKind).name)
            val txt = File(dir, textName)
            if (!txt.exists()) return null
            val text = txt.readText(Charsets.UTF_8).trim()
            if (text.isBlank()) return null
            ReadableArtifact(
                kind = actualKind,
                title = root.optString("title", defaultTitle(actualKind)).ifBlank {
                    defaultTitle(actualKind)
                },
                text = text,
                sourceLabel = root.optString("sourceLabel", "").trim().ifBlank { null },
                createdAtMs = root.optLong("createdAtMs", txt.lastModified()),
                textFile = txt,
                id = root.optString("id", "").trim().ifBlank { null },
                topic = root.optString("topic", "").trim().ifBlank { null },
                unread = root.optBoolean("unread", false)
            )
        }.getOrNull()
    }

    fun loadLatestAny(vararg kinds: ArtifactKind): ReadableArtifact? {
        return kinds.mapNotNull { loadLatest(it) }.maxByOrNull { it.createdAtMs }
    }

    fun loadLatestResearchReport(): ReadableArtifact? {
        val latestEntry = loadResearchIndexEntries().maxByOrNull { it.createdAtMs }
        return latestEntry?.let { loadResearchReportById(it.id) }
            ?: loadLatest(ArtifactKind.RESEARCH_REPORT)
    }

    fun loadLatestUnreadResearchReport(): ReadableArtifact? {
        val latestUnread = loadResearchIndexEntries()
            .filter { it.unread }
            .maxByOrNull { it.createdAtMs }
        return latestUnread?.let { loadResearchReportById(it.id) }
            ?: loadLatest(ArtifactKind.RESEARCH_REPORT)?.takeIf { it.unread }
    }

    fun hasUnreadResearchReport(): Boolean =
        loadResearchIndexEntries().any { it.unread } ||
            (loadResearchIndexEntries().isEmpty() && loadLatest(ArtifactKind.RESEARCH_REPORT)?.unread == true)

    fun markResearchReportRead(id: String): Boolean {
        val entries = loadResearchIndexEntries()
        if (entries.none { it.id == id && it.unread }) return false
        val updated = entries.map { entry ->
            if (entry.id == id) entry.copy(unread = false) else entry
        }
        writeResearchIndexEntries(updated)
        val latest = loadLatest(ArtifactKind.RESEARCH_REPORT)
        if (latest?.id == id && latest.unread) {
            saveLatest(
                kind = ArtifactKind.RESEARCH_REPORT,
                title = latest.title,
                text = latest.text,
                sourceLabel = latest.sourceLabel,
                createdAtMs = latest.createdAtMs,
                id = latest.id,
                topic = latest.topic,
                unread = false
            )
        }
        return true
    }

    fun markLatestRead(kind: ArtifactKind): Boolean {
        val latest = loadLatest(kind) ?: return false
        if (!latest.unread) return false
        saveLatest(
            kind = kind,
            title = latest.title,
            text = latest.text,
            sourceLabel = latest.sourceLabel,
            createdAtMs = latest.createdAtMs,
            id = latest.id,
            topic = latest.topic,
            unread = false
        )
        return true
    }

    fun hasUnreadLatest(kind: ArtifactKind): Boolean =
        loadLatest(kind)?.unread == true

    fun findResearchReportByTopic(query: String): ReadableArtifact? {
        val normalizedQuery = normalizeTopicQuery(query)
        if (normalizedQuery.isBlank()) return loadLatestResearchReport()

        val matches = loadResearchIndexEntries()
            .map { entry -> entry to researchMatchScore(entry, normalizedQuery) }
            .filter { it.second > 0 }
            .sortedWith(
                compareByDescending<Pair<ResearchIndexEntry, Int>> { it.second }
                    .thenByDescending { it.first.createdAtMs }
            )
        val best = matches.firstOrNull()?.first ?: return null
        return loadResearchReportById(best.id)
    }

    private fun loadResearchReportById(id: String): ReadableArtifact? {
        val entry = loadResearchIndexEntries().firstOrNull { it.id == id } ?: return null
        val file = File(dir, entry.textFileName)
        if (!file.exists()) return null
        val text = file.readText(Charsets.UTF_8).trim()
        if (text.isBlank()) return null
        return ReadableArtifact(
            kind = ArtifactKind.RESEARCH_REPORT,
            id = entry.id,
            title = entry.title.ifBlank { defaultTitle(ArtifactKind.RESEARCH_REPORT) },
            topic = entry.topic,
            text = text,
            sourceLabel = entry.sourceLabel,
            createdAtMs = entry.createdAtMs,
            textFile = file,
            unread = entry.unread
        )
    }

    private fun loadResearchIndexEntries(): List<ResearchIndexEntry> {
        val file = File(dir, RESEARCH_INDEX_FILE)
        if (!file.exists()) return emptyList()
        return runCatching {
            val root = JSONArray(file.readText(Charsets.UTF_8))
            buildList {
                for (i in 0 until root.length()) {
                    val obj = root.optJSONObject(i) ?: continue
                    val id = obj.optString("id", "").trim()
                    val textFileName = obj.optString("textFileName", "").trim()
                    if (id.isBlank() || textFileName.isBlank()) continue
                    add(
                        ResearchIndexEntry(
                            id = id,
                            title = obj.optString("title", defaultTitle(ArtifactKind.RESEARCH_REPORT)).ifBlank {
                                defaultTitle(ArtifactKind.RESEARCH_REPORT)
                            },
                            topic = obj.optString("topic", "").trim().ifBlank { null },
                            normalizedTopic = obj.optString("normalizedTopic", "").trim(),
                            sourceLabel = obj.optString("sourceLabel", "").trim().ifBlank { null },
                            createdAtMs = obj.optLong("createdAtMs", 0L),
                            textFileName = textFileName,
                            unread = obj.optBoolean("unread", false)
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun writeResearchIndexEntries(entries: List<ResearchIndexEntry>) {
        val file = File(dir, RESEARCH_INDEX_FILE)
        val json = JSONArray()
        entries
            .sortedByDescending { it.createdAtMs }
            .forEach { entry ->
                json.put(
                    JSONObject()
                        .put("id", entry.id)
                        .put("title", entry.title)
                        .put("topic", entry.topic ?: JSONObject.NULL)
                        .put("normalizedTopic", entry.normalizedTopic)
                        .put("sourceLabel", entry.sourceLabel ?: JSONObject.NULL)
                        .put("createdAtMs", entry.createdAtMs)
                        .put("textFileName", entry.textFileName)
                        .put("unread", entry.unread)
                )
            }
        file.writeText(json.toString(), Charsets.UTF_8)
    }

    private fun metadataFile(kind: ArtifactKind): File =
        File(dir, "${kind.baseFileName}.json")

    private fun textFile(kind: ArtifactKind): File =
        File(dir, "${kind.baseFileName}.txt")

    private fun defaultTitle(kind: ArtifactKind): String =
        when (kind) {
            ArtifactKind.RESEARCH_REPORT -> "Research report"
            ArtifactKind.TAPCLAW_RESULT -> "TapClaw result"
        }

    private fun normalizeTopicQuery(value: String): String {
        return value
            .trim()
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun slugify(value: String): String {
        val normalized = normalizeTopicQuery(value)
        return normalized.replace(' ', '_').ifBlank { "report" }
    }

    private fun researchMatchScore(entry: ResearchIndexEntry, normalizedQuery: String): Int {
        val normalizedTitle = normalizeTopicQuery(entry.title)
        val tokens = normalizedQuery.split(' ').filter { it.isNotBlank() }
        return when {
            entry.normalizedTopic == normalizedQuery -> 400
            normalizedTitle == normalizedQuery -> 360
            entry.normalizedTopic.contains(normalizedQuery) -> 320
            normalizedTitle.contains(normalizedQuery) -> 280
            tokens.isNotEmpty() && tokens.all {
                entry.normalizedTopic.contains(it) || normalizedTitle.contains(it)
            } -> 220
            tokens.isNotEmpty() && tokens.any {
                entry.normalizedTopic.contains(it) || normalizedTitle.contains(it)
            } -> 120
            else -> 0
        }
    }
}
