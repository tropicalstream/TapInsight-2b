package com.rayneo.visionclaw.core.notifications

import android.content.Context
import android.util.Log
import com.rayneo.visionclaw.core.network.RelayUrlHelper
import com.rayneo.visionclaw.core.storage.AppPreferences
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Pull-side of the relay notification bridge.
 *
 * The Mac relay (tools/image_relay.py) accepts POST /notify and tries to
 * push the payload to the glasses companion server's /api/notify. When the
 * glasses are unreachable (away from the home LAN), payloads queue on disk;
 * GET /notify/pending returns the queue and clears it. MainActivity's
 * 5-minute notification poll calls [drainBlocking] so queued bells still
 * arrive from any network — the relay's public Cloudflare tunnel is always
 * reachable even when the Mac can't see the glasses' IP.
 *
 * Auth: the relay's --glasses-token is the SAME secret as the companion
 * server's X-Session-Token, so we authenticate with the token the glasses
 * already hold. NotificationCenter dedupes by id, so a race between the
 * relay's own push drain and this pull can't double-ring the bell.
 */
object RelayNotifyInbox {

    private const val TAG = "RelayNotifyInbox"
    private const val CONNECT_TIMEOUT_MS = 4000
    private const val READ_TIMEOUT_MS = 6000

    /**
     * Fetch and post any queued relay notifications. Blocking — call from
     * a background dispatcher. Never throws.
     */
    fun drainBlocking(context: Context) {
        val base = relayBaseUrl(AppPreferences(context).openClawEndpoint) ?: return
        val token = context.getSharedPreferences("visionclaw_prefs", Context.MODE_PRIVATE)
            .getString("companion_session_token", null)?.trim().orEmpty()
        if (token.isBlank()) return
        val url = "$base/notify/pending"
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("X-Session-Token", token)
            }
            val code = conn.responseCode
            if (code != 200) {
                Log.w(TAG, "notify/pending HTTP $code at $url")
                return
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            if (!json.optBoolean("ok", false)) return
            val entries = json.optJSONArray("notifications") ?: return
            var posted = 0
            for (i in 0 until entries.length()) {
                val entry = entries.optJSONObject(i) ?: continue
                val title = entry.optString("title").trim().ifBlank { "Assistant update" }
                val message = entry.optString("message").trim()
                if (message.isBlank()) continue
                val source = when (entry.optString("source").trim().uppercase(Locale.US)) {
                    "CALENDAR" -> NotificationCenter.Source.CALENDAR
                    "TASK" -> NotificationCenter.Source.TASK
                    "HERMES" -> NotificationCenter.Source.HERMES
                    "OPENCLAW" -> NotificationCenter.Source.OPENCLAW
                    else -> NotificationCenter.Source.SYSTEM
                }
                // Deterministic fallback id: the push path receives the same
                // payload, so deriving the id from content keeps the dedupe
                // in NotificationCenter effective across both delivery paths.
                val id = entry.optString("id").trim()
                    .ifBlank { "relay_" + ("$title|$message").hashCode() }
                if (NotificationCenter.post(
                        NotificationCenter.HudNotification(id, source, title, message)
                    )
                ) {
                    posted++
                }
            }
            if (posted > 0) {
                Log.i(TAG, "Drained $posted relay notification(s) from $url")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Relay notify drain failed: ${e.message}")
        } finally {
            conn?.disconnect()
        }
    }

    private fun relayBaseUrl(openClawEndpoint: String?): String? {
        return RelayUrlHelper.baseFromEndpoint(openClawEndpoint, true)
    }
}
