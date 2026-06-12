package com.rayneo.visionclaw.core.tools

import android.content.Context
import android.util.Log
import com.rayneo.visionclaw.core.model.DeviceLocationContext
import com.rayneo.visionclaw.core.network.GoogleDirectionsClient
import com.rayneo.visionclaw.core.network.GooglePlacesClient
import com.rayneo.visionclaw.core.network.OpenMeteoWeatherClient
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * AiTapTool for Google Places — nearby business search.
 *
 * Finds restaurants, cafes, gas stations, pharmacies, etc. near the user's
 * current GPS location. Returns names, ratings, open/closed status.
 */
class GooglePlacesTool(
    private val context: Context,
    private val placesClient: GooglePlacesClient,
    private val locationProvider: () -> DeviceLocationContext?,
    private val directionsClient: GoogleDirectionsClient? = null,
    private val weatherClient: OpenMeteoWeatherClient? = null
) : AiTapTool {
    override val name = "google_places"

    companion object {
        private const val TAG = "GooglePlacesTool"
        private const val DEFAULT_RADIUS = 1500.0
        private const val MAX_RADIUS = 5000.0

        /** Map common natural-language aliases to Google Places types. */
        private val TYPE_ALIASES = mapOf(
            "coffee" to "cafe",
            "coffee shop" to "cafe",
            "food" to "restaurant",
            "eat" to "restaurant",
            "dining" to "restaurant",
            "gas" to "gas_station",
            "fuel" to "gas_station",
            "petrol" to "gas_station",
            "drugs" to "pharmacy",
            "drugstore" to "pharmacy",
            "medicine" to "pharmacy",
            "groceries" to "supermarket",
            "grocery" to "supermarket",
            "market" to "supermarket",
            "atm" to "bank",
            "money" to "bank",
            "hotel" to "lodging",
            "motel" to "lodging",
            "doctor" to "hospital",
            "urgent care" to "hospital",
            "er" to "hospital",
            "gym" to "gym",
            "fitness" to "gym",
            "drinks" to "bar",
            "pub" to "bar",
            "park" to "park",
            "parking" to "parking",
            "ev charging" to "electric_vehicle_charging_station",
            "charger" to "electric_vehicle_charging_station"
        )
    }

    override suspend fun execute(args: Map<String, String>): Result<String> {
        val rawType = args["type"]?.trim()?.lowercase()
        val query = args["query"]?.trim()
        val radiusStr = args["radius"]

        // Resolve place type
        val placeType = if (rawType.isNullOrBlank() && !query.isNullOrBlank()) {
            resolveType(query)
        } else if (!rawType.isNullOrBlank()) {
            resolveType(rawType)
        } else {
            return Result.failure(Exception("Please specify what type of place you're looking for (e.g. restaurant, cafe, gas station)."))
        }

        val radius = radiusStr?.toDoubleOrNull()?.coerceIn(100.0, MAX_RADIUS) ?: DEFAULT_RADIUS

        // Get GPS location
        val location = locationProvider()
        if (location == null) {
            Log.w(TAG, "No GPS location available")
            return Result.failure(Exception(
                "GPS location not available. Make sure location services are enabled on the glasses."
            ))
        }

        // Check if location is stale (older than 10 minutes)
        val ageMs = System.currentTimeMillis() - location.timestampMs
        if (ageMs > 10 * 60 * 1000) {
            Log.w(TAG, "GPS location is ${ageMs / 1000}s old")
        }

        Log.d(TAG, "Searching for '$placeType' near (${location.latitude}, ${location.longitude}), radius=$radius")

        val searchOutcome = searchPlacesWithOpenFallback(
            latitude = location.latitude,
            longitude = location.longitude,
            placeType = placeType,
            query = query,
            baseRadius = radius
        )

        return when (searchOutcome) {
            is PlaceSearchOutcome.ApiKeyMissing ->
                Result.failure(Exception("Google Maps API key not configured. Add it in the TapInsight companion app."))
            is PlaceSearchOutcome.Error ->
                Result.failure(Exception(searchOutcome.message))
            is PlaceSearchOutcome.Success -> {
                if (searchOutcome.places.isEmpty()) {
                    Result.success(
                        "No ${placeType.replace("_", " ")}s found within ${
                            (searchOutcome.searchedRadiusMeters / 1000).let {
                                if (it >= 1) "${it.toInt()}km" else "${searchOutcome.searchedRadiusMeters.toInt()}m"
                            }
                        } of your location."
                    )
                } else {
                    val response = buildPlacesResponse(
                        transcript = query ?: rawType.orEmpty(),
                        placeType = placeType,
                        location = location,
                        places = searchOutcome.places,
                        nearestOpen = searchOutcome.nearestOpen
                    )
                    Result.success(response)
                }
            }
        }
    }

    private sealed class PlaceSearchOutcome {
        data class Success(
            val places: List<GooglePlacesClient.NearbyPlace>,
            val nearestOpen: GooglePlacesClient.NearbyPlace?,
            val searchedRadiusMeters: Double
        ) : PlaceSearchOutcome()
        object ApiKeyMissing : PlaceSearchOutcome()
        data class Error(val message: String) : PlaceSearchOutcome()
    }

    private suspend fun searchPlacesWithOpenFallback(
        latitude: Double,
        longitude: Double,
        placeType: String,
        query: String?,
        baseRadius: Double
    ): PlaceSearchOutcome {
        // Build a clean text query from the user's request
        val queryText = buildSearchTextQuery(placeType, query)

        val searchRadii = linkedSetOf(baseRadius.coerceIn(100.0, MAX_RADIUS)).apply {
            add((baseRadius * 1.75).coerceIn(100.0, MAX_RADIUS))
            add((baseRadius * 2.5).coerceIn(100.0, MAX_RADIUS))
            add(MAX_RADIUS)
        }.toList()

        var nearestPlaces: List<GooglePlacesClient.NearbyPlace> = emptyList()
        var nearestOpen: GooglePlacesClient.NearbyPlace? = null
        var lastRadius = searchRadii.first()
        var nearestError: String? = null
        var openError: String? = null

        for (candidateRadius in searchRadii) {
            lastRadius = candidateRadius

            // Always use text search — let Google's API handle type matching
            // instead of guessing with hardcoded type/cuisine filters.
            if (nearestPlaces.isEmpty()) {
                val result = placesClient.searchText(
                    textQuery = queryText,
                    latitude = latitude,
                    longitude = longitude,
                    radiusMeters = candidateRadius,
                    pageSize = 12,
                    includedType = placeType,
                    strictTypeFiltering = false
                )
                when (result) {
                    is GooglePlacesClient.PlacesResult.Success -> nearestPlaces = result.places
                    is GooglePlacesClient.PlacesResult.ApiKeyMissing -> return PlaceSearchOutcome.ApiKeyMissing
                    is GooglePlacesClient.PlacesResult.Error -> nearestError = result.message
                }
            }

            if (nearestOpen == null) {
                val result = placesClient.searchText(
                    textQuery = queryText,
                    latitude = latitude,
                    longitude = longitude,
                    radiusMeters = candidateRadius,
                    pageSize = 12,
                    includedType = placeType,
                    strictTypeFiltering = false,
                    openNow = true
                )
                when (result) {
                    is GooglePlacesClient.PlacesResult.Success -> {
                        nearestOpen = result.places.firstOrNull { candidate ->
                            candidate.isOpen == true &&
                                (nearestPlaces.firstOrNull()?.let { !samePlace(candidate, it) } ?: true)
                        }
                    }
                    is GooglePlacesClient.PlacesResult.ApiKeyMissing -> return PlaceSearchOutcome.ApiKeyMissing
                    is GooglePlacesClient.PlacesResult.Error -> openError = result.message
                }
            }

            if (nearestPlaces.isNotEmpty() && nearestOpen != null) {
                break
            }
        }

        val mergedPlaces = mergePlaces(
            primary = nearestPlaces,
            secondary = nearestOpen?.let { listOf(it) }.orEmpty()
        )
        if (mergedPlaces.isNotEmpty()) {
            return PlaceSearchOutcome.Success(
                places = mergedPlaces,
                nearestOpen = nearestOpen,
                searchedRadiusMeters = lastRadius
            )
        }

        return PlaceSearchOutcome.Error(
            nearestError ?: openError ?: "Nearby places search failed."
        )
    }

    private fun buildSearchTextQuery(placeType: String, query: String?): String {
        val cleaned = query.orEmpty()
            .replace(Regex("(?i)\\b(find|show|look for|search for|where is|where are|nearest|closest|open|any)\\b"), " ")
            .replace(Regex("(?i)\\b(near me|nearby|around here|close by|around|here|right now)\\b"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        if (cleaned.isBlank()) return placeType.replace('_', ' ')
        return if (cleaned.contains(placeType.replace('_', ' '), ignoreCase = true)) {
            cleaned
        } else {
            "$cleaned ${placeType.replace('_', ' ')}"
        }.trim()
    }

    private suspend fun buildPlacesResponse(
        transcript: String,
        placeType: String,
        location: DeviceLocationContext,
        places: List<GooglePlacesClient.NearbyPlace>,
        nearestOpen: GooglePlacesClient.NearbyPlace?
    ): String {
        val nearest = places.first()
        val distinctNearestOpen = nearestOpen
            ?.takeUnless { samePlace(it, nearest) && nearest.isOpen == false }
            ?: places.firstOrNull { it.isOpen == true && !samePlace(it, nearest) }
        val preferred = distinctNearestOpen ?: places.firstOrNull { it.isOpen == true } ?: nearest
        val routeSummary = buildRouteSummary(location, preferred)

        val header = when {
            nearest.isOpen == false && distinctNearestOpen != null ->
                "Closest nearby ${placeTypeLabel(placeType)} is closed. Nearest open option:"
            preferred.isOpen == true ->
                "Closest open ${placeTypeLabel(placeType)}:"
            else ->
                "Closest nearby ${placeTypeLabel(placeType)} is currently closed:"
        }

        val alternatives = places
            .filterNot { it == preferred }
            .sortedBy { if (it.isOpen == true) 0 else 1 }
            .take(3)
            .mapIndexed { index, place -> formatPlace(index + 2, place) }
        val weatherSummary = buildWeatherSummary(preferred)

        return buildString {
            append(header)
            append('\n')
            append(formatPlace(1, preferred))
            if (routeSummary.isNotBlank()) {
                append('\n')
                append(routeSummary)
            }
            if (weatherSummary.isNotBlank()) {
                append('\n')
                append(weatherSummary)
            }
            if (alternatives.isNotEmpty()) {
                append("\nNearby alternatives:\n")
                append(alternatives.joinToString("\n"))
            }
            append("\nTap this card for map results.")
        }
    }

    private fun mergePlaces(
        primary: List<GooglePlacesClient.NearbyPlace>,
        secondary: List<GooglePlacesClient.NearbyPlace>
    ): List<GooglePlacesClient.NearbyPlace> {
        val merged = LinkedHashMap<String, GooglePlacesClient.NearbyPlace>()
        (primary + secondary).forEach { place ->
            val key = "${place.name.lowercase(Locale.US)}|${place.address.lowercase(Locale.US)}"
            merged.putIfAbsent(key, place)
        }
        return merged.values.toList()
    }

    /** Map user input to a valid Google Places type. */
    private fun resolveType(input: String): String {
        // Direct match to alias
        TYPE_ALIASES[input]?.let { return it }

        // Partial match — check if any alias key is contained in the input
        for ((alias, type) in TYPE_ALIASES) {
            if (input.contains(alias)) return type
        }

        // Already a valid Google type (has underscore or is a known type)
        if (input.contains("_") || input in setOf(
                "restaurant", "cafe", "bar", "bakery", "bank", "pharmacy",
                "hospital", "supermarket", "gas_station", "parking", "park",
                "gym", "lodging", "library", "laundry", "car_wash",
                "car_repair", "dentist", "veterinary_care"
            )) {
            return input
        }

        // Fallback — use as-is and let Google API handle it
        return input
    }

    /** Format a single place result for HUD display. */
    private fun formatPlace(index: Int, place: GooglePlacesClient.NearbyPlace): String {
        val parts = mutableListOf<String>()
        parts.add("$index. ${place.name}")

        // Rating
        if (place.rating != null) {
            val stars = "★${String.format("%.1f", place.rating)}"
            val count = place.ratingCount?.let { " ($it)" } ?: ""
            parts.add("$stars$count")
        }

        // Open/closed status
        when {
            place.isOpen == true -> parts.add("Open Now")
            place.isOpen == false -> parts.add("Closed")
            place.businessStatus == "CLOSED_TEMPORARILY" -> parts.add("Temporarily Closed")
            place.businessStatus == "CLOSED_PERMANENTLY" -> parts.add("Permanently Closed")
        }

        // Short address
        if (place.shortAddress.isNotBlank()) {
            parts.add(place.shortAddress)
        }

        return parts.joinToString(" — ")
    }

    private suspend fun buildRouteSummary(
        location: DeviceLocationContext,
        place: GooglePlacesClient.NearbyPlace
    ): String {
        val client = directionsClient ?: return ""
        val origin = "${location.latitude},${location.longitude}"
        val destination = place.address.ifBlank { place.shortAddress }.ifBlank { place.name }

        val walking = when (val result = client.getDirections(origin, destination, "walking")) {
            is GoogleDirectionsClient.DirectionsResult.Success -> "${result.duration} walk"
            else -> null
        }
        val driving = when (val result = client.getDirections(origin, destination, "driving")) {
            is GoogleDirectionsClient.DirectionsResult.Success -> {
                result.durationInTraffic?.let { "$it drive" } ?: "${result.duration} drive"
            }
            else -> null
        }

        val parts = listOfNotNull(walking, driving)
        return if (parts.isEmpty()) "" else "ETA: ${parts.joinToString(" • ")}"
    }

    private suspend fun buildWeatherSummary(
        place: GooglePlacesClient.NearbyPlace
    ): String {
        val client = weatherClient ?: return ""
        val latitude = place.latitude ?: return ""
        val longitude = place.longitude ?: return ""
        return when (val result = client.fetchCurrentConditions(latitude, longitude)) {
            is OpenMeteoWeatherClient.WeatherResult.Success -> result.summary
            is OpenMeteoWeatherClient.WeatherResult.Error -> ""
        }
    }


    private fun placeTypeLabel(placeType: String): String {
        return placeType.replace('_', ' ')
    }

    private fun samePlace(
        left: GooglePlacesClient.NearbyPlace,
        right: GooglePlacesClient.NearbyPlace
    ): Boolean {
        val leftId = left.id?.trim().orEmpty()
        val rightId = right.id?.trim().orEmpty()
        if (leftId.isNotBlank() && rightId.isNotBlank() && leftId == rightId) {
            return true
        }

        val leftLat = left.latitude
        val leftLng = left.longitude
        val rightLat = right.latitude
        val rightLng = right.longitude
        if (leftLat != null && leftLng != null && rightLat != null && rightLng != null) {
            val meters = distanceMeters(leftLat, leftLng, rightLat, rightLng)
            if (meters <= 45.0 && left.name.equals(right.name, ignoreCase = true)) {
                return true
            }
        }

        return left.name.equals(right.name, ignoreCase = true) &&
            normalizeAddress(left.address).equals(normalizeAddress(right.address), ignoreCase = true)
    }

    private fun normalizeAddress(address: String): String {
        return address.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]"), "")
            .trim()
    }

    private fun distanceMeters(
        startLat: Double,
        startLng: Double,
        endLat: Double,
        endLng: Double
    ): Double {
        val earthRadiusMeters = 6_371_000.0
        val dLat = Math.toRadians(endLat - startLat)
        val dLng = Math.toRadians(endLng - startLng)
        val a = Math.sin(dLat / 2).let { it * it } +
            Math.cos(Math.toRadians(startLat)) *
            Math.cos(Math.toRadians(endLat)) *
            Math.sin(dLng / 2).let { it * it }
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return earthRadiusMeters * c
    }
}
