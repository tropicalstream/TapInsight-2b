package com.rayneo.visionclaw.core.network

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * GoogleCalendarClient – fetches upcoming events from a public or
 * API-key-authenticated Google Calendar.
 *
 * Error-handling contract:
 *   • Missing / blank API key → [CalendarResult.ApiKeyMissing] (no crash).
 *   • Network or server errors → [CalendarResult.Error] with message.
 *   • Success → [CalendarResult.Success] with a list of [CalendarEvent].
 */
class GoogleCalendarClient(
    private val apiKeyProvider: () -> String?,
    private val accessTokenProvider: () -> String? = { null },
    private val context: Context? = null
) {

    companion object {
        private const val TAG = "GoogleCalendar"
        private val API_ROOTS = listOf(
            "https://www.googleapis.com/calendar/v3",
        )
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 15_000
    }

    // ── Result types ─────────────────────────────────────────────────────

    data class CalendarEvent(
        val id: String,
        val summary: String,
        val start: Date?,
        val end: Date?,
        val location: String?,
        val description: String?
    )

    data class CalendarListEntry(
        val id: String,
        val summary: String,
        val primary: Boolean,
        val backgroundColor: String?,
        val accessRole: String?
    )

    sealed class CalendarResult {
        data class Success(val events: List<CalendarEvent>) : CalendarResult()
        data class Error(val message: String, val code: Int = -1) : CalendarResult()
        object ApiKeyMissing : CalendarResult()
    }

    sealed class CalendarListResult {
        data class Success(val calendars: List<CalendarListEntry>) : CalendarListResult()
        data class Error(val message: String) : CalendarListResult()
        object AuthRequired : CalendarListResult()
    }

    // ── Public API ───────────────────────────────────────────────────────

    /**
     * Fetch all calendars visible to the authenticated user.
     * Requires OAuth — API keys can only access public calendars by ID.
     */
    suspend fun fetchCalendarList(): CalendarListResult = withContext(Dispatchers.IO) {
        val accessToken = accessTokenProvider()
        if (accessToken.isNullOrBlank()) {
            return@withContext CalendarListResult.AuthRequired
        }

        try {
            var lastError: CalendarListResult.Error? = null
            API_ROOTS.forEachIndexed { index, apiRoot ->
                val urlStr = "$apiRoot/users/me/calendarList"
                try {
                    Log.d(TAG, "CalendarList request via $apiRoot")
                    val response = ActiveNetworkHttp.get(
                        url = urlStr,
                        headers = mapOf("Authorization" to "Bearer $accessToken"),
                        connectTimeoutMs = CONNECT_TIMEOUT_MS,
                        readTimeoutMs = READ_TIMEOUT_MS
                    )
                    val responseCode = response.code
                    if (responseCode in 200..299) {
                        val body = response.body

                        val json = JSONObject(body)
                        val items = json.optJSONArray("items")
                            ?: return@withContext CalendarListResult.Success(emptyList())

                        val calendars = (0 until items.length()).map { i ->
                            val item = items.getJSONObject(i)
                            CalendarListEntry(
                                id = item.optString("id", ""),
                                summary = item.optString("summary", "(No name)"),
                                primary = item.optBoolean("primary", false),
                                backgroundColor = item.optString("backgroundColor").takeIf { it.isNotBlank() },
                                accessRole = item.optString("accessRole").takeIf { it.isNotBlank() }
                            )
                        }

                        Log.d(TAG, "Fetched ${calendars.size} calendars via $apiRoot")
                        return@withContext CalendarListResult.Success(calendars)
                    }

                    val errorBody = response.body
                    Log.e(TAG, "CalendarList HTTP $responseCode via $apiRoot: $errorBody")
                    lastError = CalendarListResult.Error("Calendar list error ($responseCode)")
                    if (responseCode == 404 && index < API_ROOTS.lastIndex) {
                        Log.w(TAG, "CalendarList retrying alternate API root after 404: $apiRoot")
                        return@forEachIndexed
                    }
                    return@withContext lastError as CalendarListResult.Error
                } catch (e: UnknownHostException) {
                    Log.w(TAG, "CalendarList host resolution failed for $apiRoot: ${e.message}")
                    lastError = CalendarListResult.Error(e.localizedMessage ?: "Unable to resolve calendar host")
                    if (index == API_ROOTS.lastIndex) {
                        return@withContext lastError as CalendarListResult.Error
                    }
                }
            }
            lastError ?: CalendarListResult.Error("Calendar list unavailable")
        } catch (e: Exception) {
            Log.e(TAG, "CalendarList request failed", e)
            CalendarListResult.Error(e.localizedMessage ?: "Unknown error")
        }
    }

    /**
     * Fetch upcoming events from the given calendar.
     *
     * @param calendarId  Calendar ID (default: "primary").
     * @param maxResults  Maximum events to return.
     * @param timeHorizonHours How far ahead to look (default: 24h).
     */
    suspend fun fetchUpcomingEvents(
        calendarId: String = "primary",
        maxResults: Int = 10,
        timeHorizonHours: Int = 24
    ): CalendarResult = withContext(Dispatchers.IO) {

        // ── 1. Resolve auth ────────────────────────────────────────
        val accessToken = accessTokenProvider()
        val apiKey = apiKeyProvider()
        if (accessToken.isNullOrBlank() && apiKey.isNullOrBlank()) {
            Log.w(TAG, "No OAuth token or API key available for Calendar")
            return@withContext CalendarResult.ApiKeyMissing
        }

        try {
            // ── 2. Build time window ─────────────────────────────────
            val isoFormat = SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US
            ).apply { timeZone = TimeZone.getTimeZone("UTC") }

            val now = Date()
            val horizon = Calendar.getInstance().apply {
                time = now
                add(Calendar.HOUR_OF_DAY, timeHorizonHours)
            }.time

            val timeMin = URLEncoder.encode(isoFormat.format(now), "UTF-8")
            val timeMax = URLEncoder.encode(isoFormat.format(horizon), "UTF-8")
            val encodedId = URLEncoder.encode(calendarId, "UTF-8")

            // ── 3. Execute HTTP request ──────────────────────────────
            val useOAuth = !accessToken.isNullOrBlank()
            var lastError: CalendarResult.Error? = null
            API_ROOTS.forEachIndexed { index, apiRoot ->
                val urlStr = buildString {
                    append("$apiRoot/calendars/$encodedId/events")
                    append("?timeMin=$timeMin")
                    append("&timeMax=$timeMax")
                    append("&maxResults=$maxResults")
                    append("&singleEvents=true")
                    append("&orderBy=startTime")
                    if (!useOAuth) append("&key=$apiKey")
                }

                try {
                    Log.d(TAG, "Calendar events request via $apiRoot calendarId=$calendarId useOAuth=$useOAuth")
                    val headers = if (useOAuth) {
                        mapOf("Authorization" to "Bearer $accessToken")
                    } else {
                        emptyMap()
                    }
                    val response = ActiveNetworkHttp.get(
                        url = urlStr,
                        headers = headers,
                        connectTimeoutMs = CONNECT_TIMEOUT_MS,
                        readTimeoutMs = READ_TIMEOUT_MS
                    )
                    val responseCode = response.code

                    if (responseCode in 200..299) {
                        val body = response.body

                        val json = JSONObject(body)
                        val items = json.optJSONArray("items") ?: return@withContext CalendarResult.Success(emptyList())

                        val events = (0 until items.length()).map { i ->
                            val item = items.getJSONObject(i)
                            val startObj = item.optJSONObject("start")
                            val endObj = item.optJSONObject("end")

                            CalendarEvent(
                                id = item.optString("id", ""),
                                summary = item.optString("summary", "(No title)"),
                                start = parseEventTime(startObj),
                                end = parseEventTime(endObj),
                                location = item.optString("location").takeIf { it.isNotBlank() },
                                description = item.optString("description").takeIf { it.isNotBlank() }
                            )
                        }

                        Log.d(TAG, "Fetched ${events.size} events via $apiRoot")
                        return@withContext CalendarResult.Success(events)
                    }

                    val errorBody = response.body
                    Log.e(TAG, "Calendar HTTP $responseCode via $apiRoot: $errorBody")

                    if (responseCode == 401 && useOAuth) {
                        return@withContext CalendarResult.Error(
                            "OAuth token expired. Re-authorize in TapInsight setup.", 401
                        )
                    }

                    if (responseCode == 400 || responseCode == 403) {
                        val lower = errorBody.lowercase()
                        if (lower.contains("api key") || lower.contains("apikey")
                            || lower.contains("forbidden") || lower.contains("invalid key")) {
                            return@withContext CalendarResult.ApiKeyMissing
                        }
                        if (lower.contains("accessnotconfigured") ||
                            lower.contains("service_disabled") ||
                            lower.contains("calendar api has not been used in project") ||
                            lower.contains("calendar-json.googleapis.com")) {
                            return@withContext CalendarResult.Error(
                                "Google Calendar API is disabled in your Google Cloud project. Enable Calendar API, then retry.",
                                responseCode
                            )
                        }
                    }

                    lastError = CalendarResult.Error("Calendar error ($responseCode)", responseCode)
                    if (responseCode == 404 && index < API_ROOTS.lastIndex) {
                        Log.w(TAG, "Retrying alternate Calendar API root after 404: $apiRoot")
                        return@forEachIndexed
                    }
                    return@withContext lastError as CalendarResult.Error
                } catch (e: UnknownHostException) {
                    Log.w(TAG, "Calendar host resolution failed for $apiRoot: ${e.message}")
                    lastError = CalendarResult.Error(e.localizedMessage ?: "Unable to resolve calendar host")
                    if (index == API_ROOTS.lastIndex) {
                        return@withContext lastError as CalendarResult.Error
                    }
                }
            }
            lastError ?: CalendarResult.Error("Calendar unavailable")
        } catch (e: Exception) {
            Log.e(TAG, "Calendar request failed", e)
            CalendarResult.Error(e.localizedMessage ?: "Unknown error")
        }
    }

    /**
     * Create a new event on the given calendar.
     * Requires OAuth (write access) — API keys are read-only.
     */
    suspend fun createEvent(
        calendarId: String = "primary",
        title: String,
        startTimeIso: String,
        durationMinutes: Int = 60,
        location: String? = null,
        description: String? = null
    ): CalendarResult = withContext(Dispatchers.IO) {

        val accessToken = accessTokenProvider()
        if (accessToken.isNullOrBlank()) {
            return@withContext CalendarResult.Error(
                "OAuth required to create events. Authorize in TapInsight setup."
            )
        }

        try {
            val encodedId = URLEncoder.encode(calendarId, "UTF-8")
            // Parse start time and compute end time
            val startDate = try {
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).parse(
                    startTimeIso.replace("Z", "").substringBefore("+").substringBefore("-")
                )
            } catch (e: Exception) {
                // Try other formats
                try {
                    SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US).parse(startTimeIso)
                } catch (e2: Exception) {
                    return@withContext CalendarResult.Error("Invalid start time format: $startTimeIso")
                }
            }

            val endDate = Calendar.getInstance().apply {
                time = startDate!!
                add(Calendar.MINUTE, durationMinutes)
            }.time

            val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).apply {
                timeZone = TimeZone.getDefault()
            }

            val eventBody = JSONObject().apply {
                put("summary", title)
                put("start", JSONObject().put("dateTime", isoFormat.format(startDate)))
                put("end", JSONObject().put("dateTime", isoFormat.format(endDate)))
                if (!location.isNullOrBlank()) put("location", location)
                if (!description.isNullOrBlank()) put("description", description)
            }

            var lastError: CalendarResult.Error? = null
            API_ROOTS.forEachIndexed { index, apiRoot ->
                val urlStr = "$apiRoot/calendars/$encodedId/events"
                try {
                    Log.d(TAG, "Create event request via $apiRoot calendarId=$calendarId")
                    val response = ActiveNetworkHttp.postJson(
                        url = urlStr,
                        jsonBody = eventBody.toString(),
                        headers = mapOf(
                            "Authorization" to "Bearer $accessToken",
                            "Content-Type" to "application/json"
                        ),
                        connectTimeoutMs = CONNECT_TIMEOUT_MS,
                        readTimeoutMs = READ_TIMEOUT_MS
                    )
                    val responseCode = response.code
                    if (responseCode in 200..299) {
                        val body = response.body
                        val json = JSONObject(body)
                        val event = CalendarEvent(
                            id = json.optString("id", ""),
                            summary = json.optString("summary", title),
                            start = startDate,
                            end = endDate,
                            location = location,
                            description = description
                        )
                        Log.i(TAG, "Created event via $apiRoot: ${event.summary}")
                        return@withContext CalendarResult.Success(listOf(event))
                    }

                    val errorBody = response.body
                    Log.e(TAG, "Create event HTTP $responseCode via $apiRoot: $errorBody")
                    lastError = CalendarResult.Error("Failed to create event ($responseCode)", responseCode)
                    if (responseCode == 404 && index < API_ROOTS.lastIndex) {
                        Log.w(TAG, "Retrying alternate Calendar API root for createEvent after 404: $apiRoot")
                        return@forEachIndexed
                    }
                    return@withContext lastError as CalendarResult.Error
                } catch (e: UnknownHostException) {
                    Log.w(TAG, "Create event host resolution failed for $apiRoot: ${e.message}")
                    lastError = CalendarResult.Error(e.localizedMessage ?: "Unable to resolve calendar host")
                    if (index == API_ROOTS.lastIndex) {
                        return@withContext lastError as CalendarResult.Error
                    }
                }
            }
            lastError ?: CalendarResult.Error("Calendar unavailable")
        } catch (e: Exception) {
            Log.e(TAG, "Create event failed", e)
            CalendarResult.Error(e.localizedMessage ?: "Unknown error")
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun parseEventTime(obj: JSONObject?): Date? {
        if (obj == null) return null
        val dateTimeStr = obj.optString("dateTime", "")
        if (dateTimeStr.isNotBlank()) {
            // Google returns RFC3339 timestamps that may or may not carry
            // fractional seconds, and the offset is either "Z" or "±hh:mm".
            // The ISO-8601 'XXX' token handles both "Z" and "-07:00"; we try
            // the millis and no-millis variants. If parsing fails we return
            // null — but callers MUST treat a null start as "not a confirmed
            // upcoming event" so a mis-parsed (and possibly in-progress) event
            // can't masquerade as the next one on the HUD.
            for (pattern in arrayOf(
                "yyyy-MM-dd'T'HH:mm:ssXXX",
                "yyyy-MM-dd'T'HH:mm:ss.SSSXXX"
            )) {
                try {
                    return SimpleDateFormat(pattern, Locale.US).parse(dateTimeStr)
                } catch (_: Exception) {
                    // try the next pattern
                }
            }
            Log.w(TAG, "Failed to parse event dateTime: $dateTimeStr")
            return null
        }
        val dateStr = obj.optString("date", "")
        if (dateStr.isNotBlank()) {
            // All-day event: date only, interpreted in the device's local zone
            // (midnight). An all-day event for *today* therefore has a start in
            // the past, so the HUD will treat it as in-progress, not upcoming.
            return try {
                SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(dateStr)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse event date: $dateStr ($e)")
                null
            }
        }
        return null
    }
}
