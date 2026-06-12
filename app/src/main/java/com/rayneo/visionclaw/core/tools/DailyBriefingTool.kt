package com.rayneo.visionclaw.core.tools

import android.content.Context
import android.util.Log
import com.rayneo.visionclaw.core.model.DeviceLocationContext
import com.rayneo.visionclaw.core.network.GoogleAirQualityClient
import com.rayneo.visionclaw.core.network.GoogleCalendarClient
import com.rayneo.visionclaw.core.network.GoogleDirectionsClient
import com.rayneo.visionclaw.core.network.GooglePlacesClient
import com.rayneo.visionclaw.core.network.OpenMeteoWeatherClient
import com.rayneo.visionclaw.core.network.ResearchRouter
import com.rayneo.visionclaw.core.storage.AppPreferences
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class DailyBriefingTool(
    private val context: Context,
    private val calendarClient: GoogleCalendarClient,
    private val directionsClient: GoogleDirectionsClient,
    private val placesClient: GooglePlacesClient,
    private val airQualityClient: GoogleAirQualityClient?,
    private val weatherClient: OpenMeteoWeatherClient?,
    private val researchRouter: ResearchRouter,
    private val locationProvider: () -> DeviceLocationContext?
) : AiTapTool {

    override val name: String = "daily_briefing"

    companion object {
        private const val TAG = "DailyBriefingTool"
        private const val CALENDAR_HORIZON_HOURS = 72
        private const val CALENDAR_FETCH_LIMIT = 20
        private const val SEARCH_RADIUS_METERS = 2500.0

        private val PUBLIC_EVENT_TOPICS = listOf(
            "Artificial Intelligence",
            "Retro Computers",
            "Marxist Analysis",
            "Pedagogy",
            "Ethnic Studies"
        )

        private const val PUBLIC_EVENT_SYSTEM_PROMPT =
            "You produce concise, accurate Bay Area event recommendations for RayNeo AR glasses. " +
                "Return strict JSON only, no markdown, no commentary, no code fences."
    }

    private val prefs by lazy { AppPreferences(context) }
    private val dateHeaderFormat = SimpleDateFormat("EEEE, MMM d", Locale.US).apply {
        timeZone = TimeZone.getDefault()
    }
    private val eventTimeFormat = SimpleDateFormat("EEE h:mm a", Locale.US).apply {
        timeZone = TimeZone.getDefault()
    }

    override suspend fun execute(args: Map<String, String>): Result<String> = coroutineScope {
        try {
            val location = locationProvider()
            val now = Date()
            val upcomingEvents = fetchUpcomingEvents()
            val calendarInsights = buildCalendarEventInsights(upcomingEvents, location)
            val calendarInsightMap = calendarInsights.associateBy { it.event.id.ifBlank { "${it.event.summary}:${it.event.start?.time ?: 0L}" } }
            val nextThree = upcomingEvents.take(3)
            val closestThree = calendarInsights.take(3)
            val publicEventResponse = researchPublicEvents(now, location, nextThree)
            val enrichedPublicEvents = buildPublicEventInsights(publicEventResponse.events, location).take(3)
            val briefing = buildBriefing(
                now = now,
                nextEvents = nextThree,
                nextEventInsightMap = calendarInsightMap,
                closestEvents = closestThree,
                publicEvents = enrichedPublicEvents
            )
            Result.success(briefing)
        } catch (t: Throwable) {
            Log.e(TAG, "Daily briefing failed", t)
            Result.failure(IllegalStateException("Daily briefing unavailable right now."))
        }
    }

    private suspend fun fetchUpcomingEvents(): List<GoogleCalendarClient.CalendarEvent> {
        val calendarIds = prefs.enabledCalendarIds
            .takeIf { it.isNotEmpty() }
            ?.toList()
            ?: listOf(prefs.calendarId.takeIf { it.isNotBlank() } ?: "primary")

        val allEvents = mutableListOf<GoogleCalendarClient.CalendarEvent>()
        calendarIds.forEach { calendarId ->
            when (val result = calendarClient.fetchUpcomingEvents(
                calendarId = calendarId,
                maxResults = CALENDAR_FETCH_LIMIT,
                timeHorizonHours = CALENDAR_HORIZON_HOURS
            )) {
                is GoogleCalendarClient.CalendarResult.Success -> allEvents += result.events
                is GoogleCalendarClient.CalendarResult.Error ->
                    Log.w(TAG, "Calendar fetch failed for $calendarId: ${result.message}")
                is GoogleCalendarClient.CalendarResult.ApiKeyMissing ->
                    Log.w(TAG, "Calendar fetch missing auth for $calendarId")
            }
        }

        return allEvents
            .filter { event ->
                event.start?.after(Date(System.currentTimeMillis() - 60_000)) ?: true
            }
            .distinctBy { "${it.id}:${it.summary}:${it.start?.time ?: 0L}" }
            .sortedBy { it.start?.time ?: Long.MAX_VALUE }
    }

    private suspend fun buildCalendarEventInsights(
        upcomingEvents: List<GoogleCalendarClient.CalendarEvent>,
        location: DeviceLocationContext?
    ): List<CalendarEventInsight> = coroutineScope {
        if (location == null) return@coroutineScope emptyList()
        val origin = "${location.latitude},${location.longitude}"
        val candidates = upcomingEvents
            .filter { !it.location.isNullOrBlank() }

        candidates.map { event ->
            async {
                val destination = event.location ?: return@async null
                val resolvedPlace = resolvePlace(destination)
                val driveSuccess = resolveDirections(origin, destination, "driving", resolvedPlace)
                    ?: return@async null
                val walkSuccess = resolveDirections(origin, destination, "walking", resolvedPlace)
                val venue = enrichVenue(
                    searchQuery = destination,
                    resolvedPlace = resolvedPlace,
                    driving = driveSuccess,
                    walking = walkSuccess
                )
                CalendarEventInsight(
                    event = event,
                    driving = driveSuccess,
                    walking = walkSuccess,
                    venue = venue
                )
            }
        }.awaitAll()
            .filterNotNull()
            .sortedWith(
                compareBy<CalendarEventInsight> {
                    parseDurationMinutes(it.driving.durationInTraffic ?: it.driving.duration) ?: Int.MAX_VALUE
                }.thenBy { it.event.start?.time ?: Long.MAX_VALUE }
            )
    }

    private suspend fun buildPublicEventInsights(
        publicEvents: List<PublicEventRecommendation>,
        location: DeviceLocationContext?
    ): List<PublicEventInsight> = coroutineScope {
        if (publicEvents.isEmpty()) return@coroutineScope emptyList()
        val origin = location?.let { "${it.latitude},${it.longitude}" }

        publicEvents.map { event ->
            async {
                val resolvedPlace = if (event.location.isNotBlank()) {
                    resolvePlace(buildString {
                        append(event.title)
                        append(' ')
                        append(event.location)
                    }.trim())
                } else {
                    null
                }
                val driving = if (!origin.isNullOrBlank() && event.location.isNotBlank()) {
                    resolveDirections(origin, event.location, "driving", resolvedPlace)
                } else {
                    null
                }
                val walking = if (!origin.isNullOrBlank() && event.location.isNotBlank()) {
                    resolveDirections(origin, event.location, "walking", resolvedPlace)
                } else {
                    null
                }
                val venue = enrichVenue(
                    searchQuery = buildString {
                        append(event.title)
                        if (event.location.isNotBlank()) {
                            append(' ')
                            append(event.location)
                        }
                    }.trim(),
                    resolvedPlace = resolvedPlace,
                    driving = driving,
                    walking = walking
                )
                PublicEventInsight(event = event, driving = driving, walking = walking, venue = venue)
            }
        }.awaitAll()
    }

    private suspend fun enrichVenue(
        searchQuery: String,
        resolvedPlace: GooglePlacesClient.NearbyPlace?,
        driving: GoogleDirectionsClient.DirectionsResult.Success?,
        walking: GoogleDirectionsClient.DirectionsResult.Success?
    ): VenueInsight {
        val latitude = resolvedPlace?.latitude
        val longitude = resolvedPlace?.longitude

        val weather = if (weatherClient != null && latitude != null && longitude != null) {
            when (val result = weatherClient.fetchTodayForecast(latitude, longitude)) {
                is OpenMeteoWeatherClient.ForecastResult.Success -> result.forecast
                is OpenMeteoWeatherClient.ForecastResult.Error -> null
            }
        } else {
            null
        }

        val airQuality = if (airQualityClient != null && latitude != null && longitude != null) {
            when (val result = airQualityClient.fetchCurrentConditions(latitude, longitude)) {
                is GoogleAirQualityClient.AirQualityResult.Success -> result.index
                is GoogleAirQualityClient.AirQualityResult.Error -> null
                is GoogleAirQualityClient.AirQualityResult.ApiKeyMissing -> null
            }
        } else {
            null
        }

        val parkingStatus = if (latitude != null && longitude != null) {
            lookupParkingStatus(searchQuery, latitude, longitude)
        } else {
            null
        }

        return VenueInsight(
            resolvedPlace = resolvedPlace,
            driving = driving,
            walking = walking,
            weather = weather,
            airQuality = airQuality,
            parkingStatus = parkingStatus
        )
    }

    private suspend fun resolvePlace(searchQuery: String): GooglePlacesClient.NearbyPlace? {
        val location = locationProvider()
        val latitude = location?.latitude ?: return null
        val longitude = location.longitude
        return when (
            val result = placesClient.searchText(
                textQuery = searchQuery,
                latitude = latitude,
                longitude = longitude,
                radiusMeters = SEARCH_RADIUS_METERS,
                pageSize = 1
            )
        ) {
            is GooglePlacesClient.PlacesResult.Success -> result.places.firstOrNull()
            is GooglePlacesClient.PlacesResult.Error -> null
            is GooglePlacesClient.PlacesResult.ApiKeyMissing -> null
        }
    }

    private suspend fun resolveDirections(
        origin: String,
        destinationText: String,
        mode: String,
        resolvedPlace: GooglePlacesClient.NearbyPlace?
    ): GoogleDirectionsClient.DirectionsResult.Success? {
        val direct = directionsClient.getDirections(origin, destinationText, mode)
            as? GoogleDirectionsClient.DirectionsResult.Success
        if (direct != null) return direct
        val fallbackDestination = resolvedPlace?.address
            ?.takeIf { it.isNotBlank() && !it.equals(destinationText, ignoreCase = true) }
            ?: resolvedPlace?.shortAddress?.takeIf { it.isNotBlank() && !it.equals(destinationText, ignoreCase = true) }
            ?: return null
        return directionsClient.getDirections(origin, fallbackDestination, mode)
            as? GoogleDirectionsClient.DirectionsResult.Success
    }

    private suspend fun lookupParkingStatus(
        locationText: String,
        latitude: Double,
        longitude: Double
    ): String? {
        val freeResult = placesClient.searchText(
            textQuery = "free parking near $locationText",
            latitude = latitude,
            longitude = longitude,
            radiusMeters = SEARCH_RADIUS_METERS,
            pageSize = 3,
            includedType = "parking"
        )
        val freePlaces = (freeResult as? GooglePlacesClient.PlacesResult.Success)?.places.orEmpty()
        if (freePlaces.isNotEmpty()) return "free"

        val paidResult = placesClient.searchText(
            textQuery = "parking near $locationText",
            latitude = latitude,
            longitude = longitude,
            radiusMeters = SEARCH_RADIUS_METERS,
            pageSize = 3,
            includedType = "parking"
        )
        val paidPlaces = (paidResult as? GooglePlacesClient.PlacesResult.Success)?.places.orEmpty()
        return if (paidPlaces.isNotEmpty()) "paid" else null
    }

    private fun eventInsightKey(event: GoogleCalendarClient.CalendarEvent): String {
        return event.id.ifBlank { "${event.summary}:${event.start?.time ?: 0L}" }
    }

    private fun formatEventStatus(venue: VenueInsight): String {
        val traffic = venue.driving?.let { "traffic ${trafficColor(it)}" } ?: "traffic n/a"
        val temp = venue.weather?.currentF?.let { "${it}F" } ?: "temp n/a"
        val parking = venue.parkingStatus?.let { "parking $it" } ?: "parking n/a"
        return listOf(traffic, temp, parking).joinToString(" • ")
    }

    private fun trafficColor(driving: GoogleDirectionsClient.DirectionsResult.Success): String {
        return when (trafficGrade(driving)) {
            "Heavy" -> "red"
            "Moderate" -> "yellow"
            else -> "green"
        }
    }

    private suspend fun researchPublicEvents(
        now: Date,
        location: DeviceLocationContext?,
        nextEvents: List<GoogleCalendarClient.CalendarEvent>
    ): PublicEventResponse {
        val calendarContext = if (nextEvents.isEmpty()) {
            "No upcoming personal calendar events found."
        } else {
            nextEvents.joinToString("; ") { event ->
                "${formatEventHeadline(event)}${event.location?.let { " @ $it" } ?: ""}"
            }
        }
        val today = dateHeaderFormat.format(now)
        val tomorrow = dateHeaderFormat.format(
            Calendar.getInstance().apply { time = now; add(Calendar.DAY_OF_YEAR, 1) }.time
        )
        val locationLine = location?.let {
            "Current device location: ${"%.5f".format(Locale.US, it.latitude)}, ${"%.5f".format(Locale.US, it.longitude)}."
        } ?: "Current device location unavailable."

        val prompt = """
            Find exactly 3 public Bay Area events happening today or tomorrow that best match these themes:
            ${PUBLIC_EVENT_TOPICS.joinToString(", ")}.

            Use this calendar context to prefer complementary events and avoid obvious conflicts:
            $calendarContext

            Time window:
            - Today: $today
            - Tomorrow: $tomorrow

            $locationLine

            Return strict JSON only with this schema:
            {
              "events": [
                {
                  "title": "string",
                  "date": "string",
                  "time": "string",
                  "location": "string",
                  "topic": "string",
                  "whyRelevant": "string"
                }
              ]
            }

            Rules:
            - Only Bay Area events.
            - Prefer public lectures, meetups, library/museum/community events, workshops, teach-ins, screenings, and open gatherings.
            - Keep whyRelevant to one sentence.
            - If you have fewer than 3 strong matches, return the best available options anyway.
        """.trimIndent()

        return when (val result = researchRouter.runPrompt(prompt, PUBLIC_EVENT_SYSTEM_PROMPT)) {
            is ResearchRouter.ResearchResult.Success ->
                parsePublicEventResponse(result.text).let { response ->
                    response.copy(events = response.events.take(3))
                }
            is ResearchRouter.ResearchResult.ApiKeyMissing -> PublicEventResponse(emptyList(), null)
            is ResearchRouter.ResearchResult.Error -> PublicEventResponse(emptyList(), null)
        }
    }

    private fun parsePublicEventResponse(raw: String): PublicEventResponse {
        val cleaned = raw.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val jsonCandidate = when {
            cleaned.startsWith("{") -> cleaned
            cleaned.contains('{') && cleaned.contains('}') ->
                cleaned.substring(cleaned.indexOf('{'), cleaned.lastIndexOf('}') + 1)
            else -> return PublicEventResponse(emptyList(), null)
        }
        return runCatching {
            val root = JSONObject(jsonCandidate)
            val items = root.optJSONArray("events") ?: JSONArray()
            val events = (0 until items.length()).mapNotNull { index ->
                val item = items.optJSONObject(index) ?: return@mapNotNull null
                val title = item.optString("title").trim()
                val location = item.optString("location").trim()
                if (title.isBlank()) return@mapNotNull null
                PublicEventRecommendation(
                    title = title,
                    date = item.optString("date").trim(),
                    time = item.optString("time").trim(),
                    location = location,
                    topic = item.optString("topic").trim(),
                    whyRelevant = item.optString("whyRelevant").trim()
                )
            }
            PublicEventResponse(
                events = events,
                bonusSentence = root.optString("bonus_sentence").trim().takeIf { it.isNotBlank() }
            )
        }.getOrElse {
            Log.w(TAG, "Unable to parse public event JSON: ${it.message}")
            PublicEventResponse(emptyList(), null)
        }
    }

    private fun buildBriefing(
        now: Date,
        nextEvents: List<GoogleCalendarClient.CalendarEvent>,
        nextEventInsightMap: Map<String, CalendarEventInsight>,
        closestEvents: List<CalendarEventInsight>,
        publicEvents: List<PublicEventInsight>
    ): String {
        val closestLines = closestEvents.mapIndexed { index, insight ->
            val line = buildString {
                append("${index + 1}. ")
                append(formatEventHeadline(insight.event))
                insight.event.location?.takeIf { it.isNotBlank() }?.let {
                    append(" (")
                    append(it)
                    append(")")
                }
                append(" • ")
                append(formatEventStatus(insight.venue))
            }
            line
        }

        val publicLines = publicEvents.mapIndexed { index, insight ->
            val line = buildString {
                append("${index + 1}. ")
                append(insight.event.title)
                if (insight.event.date.isNotBlank() || insight.event.time.isNotBlank()) {
                    append(" — ")
                    append(listOf(insight.event.date, insight.event.time).filter { it.isNotBlank() }.joinToString(" "))
                }
                if (insight.event.location.isNotBlank()) {
                    append(" (")
                    append(insight.event.location)
                    append(")")
                }
                if (insight.event.topic.isNotBlank()) {
                    append(" • ")
                    append(insight.event.topic)
                }
                if (!insight.event.whyRelevant.isNullOrBlank()) {
                    append(" • ")
                    append(insight.event.whyRelevant)
                }
                append(" • ")
                append(formatEventStatus(insight.venue))
            }
            line
        }

        return buildString {
            append("Ultimate daily brief for ")
            append(dateHeaderFormat.format(now))
            append('\n')
            append('\n')
            append("Next 3 calendar events:\n")
            if (nextEvents.isEmpty()) {
                append("- No upcoming calendar events found.\n")
            } else {
                nextEvents.forEachIndexed { index, event ->
                    append("${index + 1}. ${formatEventHeadline(event)}")
                    event.location?.takeIf { it.isNotBlank() }?.let {
                        append(" (")
                        append(it)
                        append(")")
                    }
                    nextEventInsightMap[eventInsightKey(event)]?.let { insight ->
                        append(" • ")
                        append(formatEventStatus(insight.venue))
                    }
                    append('\n')
                }
            }
            append('\n')
            append("Closest next 3 calendar events from your current location:\n")
            if (closestLines.isEmpty()) {
                append("- No upcoming calendar events with usable locations.\n")
            } else {
                append(closestLines.joinToString("\n"))
                append('\n')
            }
            append('\n')
            append("Top 3 public Bay Area events for today or tomorrow:\n")
            if (publicLines.isEmpty()) {
                append("- No strong public event matches found right now.\n")
            } else {
                append(publicLines.joinToString("\n"))
                append('\n')
            }
        }
    }

    private fun trafficGrade(driving: GoogleDirectionsClient.DirectionsResult.Success): String {
        val base = parseDurationMinutes(driving.duration) ?: return "Good"
        val live = parseDurationMinutes(driving.durationInTraffic ?: driving.duration) ?: return "Good"
        if (base <= 0) return "Good"
        val ratio = live.toDouble() / base.toDouble()
        return when {
            ratio >= 1.35 -> "Heavy"
            ratio >= 1.12 -> "Moderate"
            else -> "Good"
        }
    }

    private fun parseDurationMinutes(text: String?): Int? {
        val value = text?.trim().orEmpty()
        if (value.isBlank()) return null
        var total = 0
        Regex("(\\d+)\\s*day").findAll(value).forEach { total += it.groupValues[1].toInt() * 24 * 60 }
        Regex("(\\d+)\\s*hour").findAll(value).forEach { total += it.groupValues[1].toInt() * 60 }
        Regex("(\\d+)\\s*min").findAll(value).forEach { total += it.groupValues[1].toInt() }
        return total.takeIf { it > 0 }
    }

    private fun formatEventHeadline(event: GoogleCalendarClient.CalendarEvent): String {
        val timeLabel = event.start?.let { eventTimeFormat.format(it) } ?: "All day"
        return "$timeLabel — ${event.summary}"
    }

    private data class VenueInsight(
        val resolvedPlace: GooglePlacesClient.NearbyPlace?,
        val driving: GoogleDirectionsClient.DirectionsResult.Success?,
        val walking: GoogleDirectionsClient.DirectionsResult.Success?,
        val weather: OpenMeteoWeatherClient.ForecastSummary?,
        val airQuality: GoogleAirQualityClient.AirQualityIndex?,
        val parkingStatus: String?
    )

    private data class CalendarEventInsight(
        val event: GoogleCalendarClient.CalendarEvent,
        val driving: GoogleDirectionsClient.DirectionsResult.Success,
        val walking: GoogleDirectionsClient.DirectionsResult.Success?,
        val venue: VenueInsight
    )

    private data class PublicEventRecommendation(
        val title: String,
        val date: String,
        val time: String,
        val location: String,
        val topic: String,
        val whyRelevant: String
    )

    private data class PublicEventResponse(
        val events: List<PublicEventRecommendation>,
        val bonusSentence: String?
    )

    private data class PublicEventInsight(
        val event: PublicEventRecommendation,
        val driving: GoogleDirectionsClient.DirectionsResult.Success?,
        val walking: GoogleDirectionsClient.DirectionsResult.Success?,
        val venue: VenueInsight
    )

}
