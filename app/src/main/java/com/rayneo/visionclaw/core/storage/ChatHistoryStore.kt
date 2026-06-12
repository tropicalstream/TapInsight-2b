package com.rayneo.visionclaw.core.storage

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Per-agent rolling chat history surfaced by the HUD's H / O badge overlay.
 *
 * Each Gemini Live turn that was routed through Hermes or OpenClaw is
 * appended as a record:
 *   { ts: epoch-ms, agent: "hermes"|"openclaw",
 *     query: "<user transcript>", response: "<agent reply>",
 *     snippet: "<short preview used by the overlay card>" }
 *
 * Storage is two SharedPreferences keys (one per agent) holding a JSON
 * array. On every append we prune records older than
 * [AppPreferences.hudChatHistoryDays] days so the array can't grow
 * unbounded over weeks of glasses use.
 *
 * Reads return chronological order (oldest first) so the UI can render
 * date-separator headers as it walks the list. The history overlay
 * reverses for newest-first display.
 *
 * Stored in the same `visionclaw_prefs` SharedPreferences that
 * AppPreferences uses, so it survives app restarts without an extra
 * persistence layer.
 */
object ChatHistoryStore {

    /** Which agent owned the turn. Drives the per-agent JSON key. */
    enum class Agent {
        GEMINI, HERMES, OPENCLAW;

        fun prefKey(): String = when (this) {
            GEMINI -> AppPreferences.KEY_CHAT_HISTORY_GEMINI
            HERMES -> AppPreferences.KEY_CHAT_HISTORY_HERMES
            OPENCLAW -> AppPreferences.KEY_CHAT_HISTORY_OPENCLAW
        }
    }

    /**
     * One completed turn. `snippet` is a short preview used by the card
     * list; `query` + `response` are the full strings the card-tap
     * action injects as PREVIOUS CONVERSATION.
     */
    data class Record(
        val ts: Long,
        val agent: Agent,
        val query: String,
        val response: String,
        val snippet: String
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("ts", ts)
            put("agent", agent.name.lowercase())
            put("query", query)
            put("response", response)
            put("snippet", snippet)
        }

        companion object {
            fun fromJson(obj: JSONObject): Record? {
                val ts = obj.optLong("ts", 0L).takeIf { it > 0L } ?: return null
                val agentName = obj.optString("agent", "").lowercase()
                val agent = when (agentName) {
                    "gemini" -> Agent.GEMINI
                    "hermes" -> Agent.HERMES
                    "openclaw", "tapclaw", "claw" -> Agent.OPENCLAW
                    else -> return null
                }
                return Record(
                    ts = ts,
                    agent = agent,
                    query = obj.optString("query", ""),
                    response = obj.optString("response", ""),
                    snippet = obj.optString("snippet", "")
                )
            }
        }
    }

    /**
     * Append a turn to the agent's history, then prune everything older
     * than `retentionDays`. Caller is expected to read
     * [AppPreferences.hudChatHistoryDays] and pass it in (we avoid the
     * dependency here so this object stays a thin storage helper).
     */
    @Synchronized
    fun append(context: Context, record: Record, retentionDays: Int) {
        val prefs = prefs(context)
        val key = record.agent.prefKey()
        val existing = loadArray(prefs, key)
        existing.put(record.toJson())
        val cutoff = System.currentTimeMillis() - retentionDays.coerceAtLeast(1) * 86_400_000L
        val pruned = pruneOlderThan(existing, cutoff)
        prefs.edit().putString(key, pruned.toString()).apply()
    }

    /**
     * Read all records for an agent within the retention window. Returns
     * newest-first so the overlay can render them in display order.
     */
    @Synchronized
    fun readNewestFirst(context: Context, agent: Agent, retentionDays: Int): List<Record> {
        val prefs = prefs(context)
        val arr = loadArray(prefs, agent.prefKey())
        val cutoff = System.currentTimeMillis() - retentionDays.coerceAtLeast(1) * 86_400_000L
        val out = ArrayList<Record>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val rec = Record.fromJson(obj) ?: continue
            if (rec.ts < cutoff) continue
            out.add(rec)
        }
        return out.sortedByDescending { it.ts }
    }

    /** Read all agents merged, newest-first. Used by the "Show all" view in the overlay. */
    @Synchronized
    fun readAllNewestFirst(context: Context, retentionDays: Int): List<Record> {
        return (readNewestFirst(context, Agent.GEMINI, retentionDays) +
                readNewestFirst(context, Agent.HERMES, retentionDays) +
                readNewestFirst(context, Agent.OPENCLAW, retentionDays))
            .sortedByDescending { it.ts }
    }

    /** Wipe everything. Used by the companion app's "Clear history" affordance (if added). */
    @Synchronized
    fun clear(context: Context) {
        prefs(context).edit()
            .remove(AppPreferences.KEY_CHAT_HISTORY_HERMES)
            .remove(AppPreferences.KEY_CHAT_HISTORY_OPENCLAW)
            .apply()
    }

    /**
     * Build a short snippet for the card preview from query + response.
     * The overlay shows ~80 characters per card; pick the first sentence
     * of the response, falling back to the query if the response is empty.
     */
    fun buildSnippet(query: String, response: String, maxLen: Int = 90): String {
        val src = response.trim().ifBlank { query.trim() }
        if (src.isEmpty()) return ""
        // First sentence-ish chunk, then collapse whitespace.
        val firstChunk = src.split(Regex("(?<=[.!?])\\s+"), limit = 2).firstOrNull().orEmpty()
        val collapsed = firstChunk.replace(Regex("\\s+"), " ").trim()
        return if (collapsed.length <= maxLen) collapsed
        else collapsed.substring(0, maxLen - 1).trimEnd() + "…"
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences("visionclaw_prefs", Context.MODE_PRIVATE)

    private fun loadArray(prefs: SharedPreferences, key: String): JSONArray {
        val raw = prefs.getString(key, null) ?: return JSONArray()
        return try {
            JSONArray(raw)
        } catch (_: Exception) {
            JSONArray()
        }
    }

    private fun pruneOlderThan(arr: JSONArray, cutoffMs: Long): JSONArray {
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val ts = obj.optLong("ts", 0L)
            if (ts >= cutoffMs) out.put(obj)
        }
        return out
    }
}
