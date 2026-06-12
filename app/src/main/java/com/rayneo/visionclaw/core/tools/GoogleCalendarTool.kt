package com.rayneo.visionclaw.core.tools

import android.content.Context
import android.util.Log
import com.rayneo.visionclaw.core.network.GoogleCalendarClient
import com.rayneo.visionclaw.core.storage.AppPreferences
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class GoogleCalendarTool(
    private val context: Context,
    private val calendarClient: GoogleCalendarClient
) : AiTapTool {
    override val name = "google_calendar"

    private val prefs by lazy { AppPreferences(context) }

    override suspend fun execute(args: Map<String, String>): Result<String> {
        val action = args["action"] ?: "query"
        val query = args["query"] ?: ""
        // Default 48h so "tomorrow" queries work even late at night
        val hoursAhead = args["hours"]?.toIntOrNull() ?: 48

        Log.d("GoogleCalendarTool", "action=$action query=$query hours=$hoursAhead")

        return when (action) {
            "query" -> fetchEvents(hoursAhead)
            "create" -> createEvent(args)
            else -> Result.failure(Exception("Unknown calendar action: $action. Supported: query, create"))
        }
    }

    private suspend fun createEvent(args: Map<String, String>): Result<String> {
        val title = args["title"] ?: return Result.failure(Exception("Event title is required"))
        val startTime = args["start_time"] ?: return Result.failure(Exception("Start time is required (ISO 8601)"))
        val duration = args["duration_minutes"]?.toIntOrNull() ?: 60
        val calendarId = prefs.calendarId.takeIf { it.isNotBlank() } ?: "primary"

        return when (val result = calendarClient.createEvent(
            calendarId = calendarId,
            title = title,
            startTimeIso = startTime,
            durationMinutes = duration,
            location = args["location"],
            description = args["description"]
        )) {
            is GoogleCalendarClient.CalendarResult.Success -> {
                val event = result.events.firstOrNull()
                val timeFormat = SimpleDateFormat("h:mm a", Locale.US).apply {
                    timeZone = TimeZone.getDefault()
                }
                val startStr = event?.start?.let { timeFormat.format(it) } ?: startTime
                val endStr = event?.end?.let { timeFormat.format(it) } ?: ""
                Result.success("Event created: \"$title\" at $startStr–$endStr")
            }
            is GoogleCalendarClient.CalendarResult.ApiKeyMissing ->
                Result.failure(Exception("OAuth required to create events. Authorize in TapInsight setup."))
            is GoogleCalendarClient.CalendarResult.Error ->
                Result.failure(Exception("Failed to create event: ${result.message}"))
        }
    }

    private suspend fun fetchEvents(hoursAhead: Int): Result<String> {
        // Query ALL enabled calendars, just like the HUD does
        val enabledIds = prefs.enabledCalendarIds
        val calendarIds = if (enabledIds.isEmpty()) {
            listOf(prefs.calendarId.takeIf { it.isNotBlank() } ?: "primary")
        } else {
            enabledIds.toList()
        }

        val allEvents = mutableListOf<GoogleCalendarClient.CalendarEvent>()
        var lastError: String? = null
        var anyApiKeyMissing = false

        for (calId in calendarIds) {
            when (val result = calendarClient.fetchUpcomingEvents(
                calendarId = calId,
                maxResults = 10,
                timeHorizonHours = hoursAhead
            )) {
                is GoogleCalendarClient.CalendarResult.Success -> {
                    allEvents.addAll(result.events)
                }
                is GoogleCalendarClient.CalendarResult.ApiKeyMissing -> {
                    anyApiKeyMissing = true
                }
                is GoogleCalendarClient.CalendarResult.Error -> {
                    Log.w("GoogleCalendarTool", "Calendar error for $calId: ${result.message}")
                    lastError = result.message
                }
            }
        }

        // If no events found from any calendar and we had auth issues, report that
        if (allEvents.isEmpty() && anyApiKeyMissing) {
            return Result.failure(Exception("Calendar not configured. Add OAuth or API key in TapInsight setup."))
        }
        if (allEvents.isEmpty() && lastError != null) {
            return Result.failure(Exception("Calendar error: $lastError"))
        }

        if (allEvents.isEmpty()) {
            return Result.success("No upcoming events in the next $hoursAhead hours.")
        }

        // Sort all events by start time across all calendars
        allEvents.sortBy { it.start?.time ?: Long.MAX_VALUE }

        val timeFormat = SimpleDateFormat("h:mm a", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }
        val dateFormat = SimpleDateFormat("EEE MMM d", Locale.US)
        val formatted = allEvents.joinToString("\n") { event ->
            val time = event.start?.let { timeFormat.format(it) } ?: "all day"
            val date = event.start?.let { dateFormat.format(it) } ?: ""
            val loc = event.location?.let { " @ $it" } ?: ""
            "$date $time — ${event.summary}$loc"
        }
        return Result.success("Upcoming events:\n$formatted")
    }
}
