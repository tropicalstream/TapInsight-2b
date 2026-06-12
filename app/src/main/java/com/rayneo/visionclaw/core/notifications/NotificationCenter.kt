package com.rayneo.visionclaw.core.notifications

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-memory notification center backing the HUD bell.
 *
 * Everything that wants to ring the bell — calendar/task pollers, agent
 * (Hermes / TapClaw) turn completions, the companion `/api/notify` push
 * endpoint, and the relay pull inbox ([RelayNotifyInbox]) — funnels through
 * [post]. Consumers (chat panel bell list, unipanel HUD state bridge)
 * observe [notifications] and [unreadCount] as StateFlows.
 *
 * Dedupe: every posted id is remembered (up to [MAX_REMEMBERED_IDS]) so the
 * 5-minute pollers and the push/pull race can't double-post the same event.
 *
 * Persistence: full state — the visible list, the unread badge count, AND the
 * dedupe memory — is saved to this class's own prefs store on every change and
 * restored by [init] at app start. Persisting the dedupe memory matters:
 * without it the 5-minute pollers would re-post already-seen events after a
 * restart and falsely re-light the badge.
 */
object NotificationCenter {

    private const val TAG = "NotificationCenter"
    private const val MAX_NOTIFICATIONS = 30
    private const val MAX_REMEMBERED_IDS = 400

    private const val PREFS_NAME = "notification_center"
    private const val KEY_LIST = "notifications"
    private const val KEY_UNREAD = "unread_count"
    private const val KEY_POSTED_IDS = "posted_ids"

    private var prefs: SharedPreferences? = null

    /** Where a notification came from — drives the badge color in the HUD list. */
    enum class Source {
        CALENDAR,
        TASK,
        HERMES,
        OPENCLAW,
        SYSTEM
    }

    data class HudNotification(
        val id: String,
        val source: Source,
        val title: String,
        val message: String,
        val timestampMs: Long = System.currentTimeMillis()
    ) {
        /** One-line rendering for the HUD list, e.g. "3:42 PM  Hermes finished — …". */
        fun displayText(): String {
            val time = SimpleDateFormat("h:mm a", Locale.US).format(Date(timestampMs))
            return "$time  $title — $message"
        }

        /** TTS-friendly rendering (no timestamp). */
        fun spokenText(): String {
            return "$title. $message"
        }
    }

    /** Ids of everything ever posted (insertion-ordered so we can drop the oldest). */
    private val postedIds = LinkedHashSet<String>()

    private val _notifications = MutableStateFlow<List<HudNotification>>(emptyList())
    val notifications: StateFlow<List<HudNotification>> = _notifications.asStateFlow()

    private val _unreadCount = MutableStateFlow(0)
    val unreadCount: StateFlow<Int> = _unreadCount.asStateFlow()

    /**
     * Restore persisted state. Call once at app start, before the pollers
     * arm; safe to call again (no-op after the first call).
     */
    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = p
        try {
            val ids = JSONArray(p.getString(KEY_POSTED_IDS, "[]") ?: "[]")
            for (i in 0 until ids.length()) postedIds.add(ids.getString(i))
            val list = JSONArray(p.getString(KEY_LIST, "[]") ?: "[]")
            val restored = ArrayList<HudNotification>(list.length())
            for (i in 0 until list.length()) {
                val o = list.getJSONObject(i)
                restored.add(
                    HudNotification(
                        id = o.getString("id"),
                        source = runCatching { Source.valueOf(o.getString("source")) }
                            .getOrDefault(Source.SYSTEM),
                        title = o.optString("title"),
                        message = o.optString("message"),
                        timestampMs = o.optLong("timestampMs", System.currentTimeMillis())
                    )
                )
            }
            _notifications.value = restored
            _unreadCount.value = p.getInt(KEY_UNREAD, 0)
        } catch (e: Exception) {
            Log.w(TAG, "restore failed: $e")
        }
    }

    private fun persist() {
        val p = prefs ?: return
        try {
            val list = JSONArray()
            for (n in _notifications.value) {
                list.put(
                    JSONObject()
                        .put("id", n.id)
                        .put("source", n.source.name)
                        .put("title", n.title)
                        .put("message", n.message)
                        .put("timestampMs", n.timestampMs)
                )
            }
            val ids = JSONArray()
            for (id in postedIds) ids.put(id)
            p.edit()
                .putString(KEY_LIST, list.toString())
                .putInt(KEY_UNREAD, _unreadCount.value)
                .putString(KEY_POSTED_IDS, ids.toString())
                .apply()
        } catch (e: Exception) {
            Log.w(TAG, "persist failed: $e")
        }
    }

    /**
     * Post a notification. Returns false (and does nothing) when the id is
     * blank or has been seen before; true when the bell should light up.
     */
    @Synchronized
    fun post(notification: HudNotification): Boolean {
        if (notification.id.isBlank()) return false
        if (!postedIds.add(notification.id)) return false
        while (postedIds.size > MAX_REMEMBERED_IDS) {
            val oldest = postedIds.firstOrNull() ?: break
            postedIds.remove(oldest)
        }
        _notifications.value = (listOf(notification) + _notifications.value).take(MAX_NOTIFICATIONS)
        _unreadCount.value = _unreadCount.value + 1
        persist()
        return true
    }

    /** User opened the bell list — clear the unread badge but keep the list. */
    @Synchronized
    fun markAllSeen() {
        _unreadCount.value = 0
        persist()
    }

    /** Wipe the list, badge, and dedupe memory. */
    @Synchronized
    fun clearAll() {
        postedIds.clear()
        _notifications.value = emptyList()
        _unreadCount.value = 0
        persist()
    }
}
