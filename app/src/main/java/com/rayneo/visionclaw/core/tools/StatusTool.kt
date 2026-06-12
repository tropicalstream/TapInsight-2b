package com.rayneo.visionclaw.core.tools

import android.content.Context
import android.util.Log
import com.rayneo.visionclaw.core.model.OpenClawStatusService
import com.rayneo.visionclaw.core.network.GoogleCalendarClient
import com.rayneo.visionclaw.core.storage.AppPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * "Give me a brief" status tool.
 *
 * Triggered by Gemini when the user asks for a brief. Produces a
 * single spoken-ready summary with three parts:
 *   1) The most recent TapClaw / OpenClaw gateway heartbeat + task label.
 *   2) Any Google Calendar events currently in progress (start ≤ now < end).
 *   3) The next Google Calendar event scheduled after the current time.
 *
 * The tool reads gateway state from [OpenClawStatusService] (a process-wide
 * singleton that MainActivity keeps up to date) and queries the calendar
 * via the shared [GoogleCalendarClient]. Calendar failures are non-fatal —
 * we still return the TapClaw portion so the user hears something useful.
 */
class StatusTool(
    private val context: Context,
    private val calendarClient: GoogleCalendarClient
) : AiTapTool {

    override val name = "status_briefing"

    private val prefs by lazy { AppPreferences(context) }

    override suspend fun execute(args: Map<String, String>): Result<String> {
        Log.d(TAG, "status_briefing invoked args=$args")

        val tapClawLine = buildTapClawLine()
        val calendarLines = buildCalendarLines()

        val full = buildString {
            append("TapClaw status: ")
            append(tapClawLine)
            append('\n')
            append(calendarLines)
        }
        return Result.success(full.trimEnd())
    }

    // ── TapClaw portion ──────────────────────────────────────────────

    private fun buildTapClawLine(): String {
        val updatedAt = OpenClawStatusService.lastUpdatedWallClockMs
        if (updatedAt == 0L) {
            return "no gateway activity yet this session."
        }
        val task = OpenClawStatusService.lastTaskLabel?.takeIf { it.isNotBlank() }
        val heartbeat = OpenClawStatusService.lastHeartbeat?.takeIf { it.isNotBlank() }
        val conn = OpenClawStatusService.connectionLabel
        val healthy = OpenClawStatusService.gatewayHealthy
        val healthWord = if (healthy) "healthy" else "unreachable"
        val ageSeconds = ((System.currentTimeMillis() - updatedAt) / 1000L).coerceAtLeast(0L)
        val ageStr = formatAge(ageSeconds)

        val body = when {
            task != null && heartbeat != null && task != heartbeat ->
                "$conn ($healthWord). Last task \"$task\" — heartbeat: $heartbeat"
            task != null -> "$conn ($healthWord). Last task: $task"
            heartbeat != null -> "$conn ($healthWord). Last heartbeat: $heartbeat"
            else -> "$conn ($healthWord). No task running."
        }
        return "$body (updated ${ageStr} ago)."
    }

    // ── Calendar portion ─────────────────────────────────────────────

    private suspend fun buildCalendarLines(): String {
        val enabledIds = prefs.enabledCalendarIds
        val calendarIds = if (enabledIds.isEmpty()) {
            listOf(prefs.calendarId.takeIf { it.isNotBlank() } ?: "primary")
        } else {
            enabledIds.toList()
        }

        val allEvents = mutableListOf<GoogleCalendarClient.CalendarEvent>()
        var anyApiKeyMissing = false
        var lastError: String? = null

        // Look ~12h ahead — enough to include long in-progress events and
        // catch the next one without pulling a whole day of noise.
        for (calId in calendarIds) {
            when (val result = calendarClient.fetchUpcomingEvents(
                calendarId = calId,
                maxResults = 10,
                timeHorizonHours = 12
            )) {
                is GoogleCalendarClient.CalendarResult.Success -> allEvents.addAll(result.events)
                is GoogleCalendarClient.CalendarResult.ApiKeyMissing -> anyApiKeyMissing = true
                is GoogleCalendarClient.CalendarResult.Error -> {
                    Log.w(TAG, "Calendar error for $calId: ${result.message}")
                    lastError = result.message
                }
            }
        }

        if (allEvents.isEmpty()) {
            return when {
                anyApiKeyMissing -> "Calendar: not configured (OAuth or API key missing)."
                lastError != null -> "Calendar: unavailable ($lastError)."
                else -> "Calendar: nothing on the schedule right now."
            }
        }

        allEvents.sortBy { it.start?.time ?: Long.MAX_VALUE }

        val now = System.currentTimeMillis()

        // Events "happening now": started at/before now and end (if known) after now.
        val inProgress = allEvents.filter { ev ->
            val startMs = ev.start?.time ?: return@filter false
            val endMs = ev.end?.time
            startMs <= now && (endMs == null || endMs > now)
        }

        // Next event strictly after now (the first one whose start is in the future).
        val next = allEvents.firstOrNull { ev ->
            val startMs = ev.start?.time ?: return@firstOrNull false
            startMs > now
        }

        val timeFormat = SimpleDateFormat("h:mm a", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }
        val dayFormat = SimpleDateFormat("EEE MMM d", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }
        val today = dayFormat.format(Date(now))

        val sb = StringBuilder()
        if (inProgress.isEmpty()) {
            sb.append("Happening now: nothing on the calendar.")
        } else {
            sb.append("Happening now: ")
            sb.append(inProgress.joinToString("; ") { describe(it, timeFormat, dayFormat, today, includeEnd = true) })
            sb.append('.')
        }
        sb.append('\n')

        if (next == null) {
            sb.append("Next up: nothing scheduled in the next 12 hours.")
        } else {
            sb.append("Next up: ")
            sb.append(describe(next, timeFormat, dayFormat, today, includeEnd = false))
            val untilMs = (next.start?.time ?: now) - now
            val untilStr = formatDuration(untilMs)
            if (untilStr.isNotEmpty()) {
                sb.append(" (in $untilStr)")
            }
            sb.append('.')
        }
        return sb.toString()
    }

    private fun describe(
        event: GoogleCalendarClient.CalendarEvent,
        timeFormat: SimpleDateFormat,
        dayFormat: SimpleDateFormat,
        today: String,
        includeEnd: Boolean
    ): String {
        val summary = event.summary.ifBlank { "Untitled event" }
        val start = event.start
        val end = event.end
        val dayStr = start?.let { dayFormat.format(it) }
        val startStr = start?.let { timeFormat.format(it) } ?: "all day"
        val endStr = if (includeEnd && end != null) " until ${timeFormat.format(end)}" else ""
        val location = event.location?.takeIf { it.isNotBlank() }?.let { " at $it" } ?: ""
        val dayPrefix = if (dayStr != null && dayStr != today) "$dayStr " else ""
        return "\"$summary\" ${dayPrefix}$startStr$endStr$location".trim()
    }

    private fun formatAge(seconds: Long): String = when {
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> "${seconds / 60}m"
        seconds < 86400 -> "${seconds / 3600}h"
        else -> "${seconds / 86400}d"
    }

    private fun formatDuration(ms: Long): String {
        if (ms <= 0) return ""
        val totalMinutes = ms / 60_000L
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return when {
            hours == 0L && minutes == 0L -> "less than a minute"
            hours == 0L -> "$minutes min"
            minutes == 0L -> "$hours hr"
            else -> "$hours hr $minutes min"
        }
    }

    companion object {
        private const val TAG = "StatusTool"
    }
}
