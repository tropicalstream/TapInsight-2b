package com.rayneo.visionclaw.core.tools

import android.content.Context
import android.util.Log
import com.rayneo.visionclaw.core.model.DeviceLocationContext
import com.rayneo.visionclaw.core.network.GoogleDirectionsClient
import com.rayneo.visionclaw.core.network.GooglePlacesClient
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * AiTapTool for "Ask Maps" — a unified map intelligence tool that combines:
 *
 * 1. **Place exploration** — AI-generated summaries via Places API (New) generativeSummary
 * 2. **Landmark-aware directions** — Step-by-step with nearby landmark context
 * 3. **3D map visualization** — Opens ar_nav.html with 3D mode for photorealistic rendering
 *
 * This tool is designed for the AR HUD and provides concise, voice-friendly responses
 * with optional deep-dive via the 3D AR navigation viewer.
 */
class AskMapsTool(
    private val context: Context,
    private val placesClient: GooglePlacesClient,
    private val directionsClient: GoogleDirectionsClient,
    private val locationProvider: () -> DeviceLocationContext?,
    private val apiKeyProvider: () -> String?
) : AiTapTool {
    override val name = "ask_maps"

    companion object {
        private const val TAG = "AskMapsTool"
    }

    override suspend fun execute(args: Map<String, String>): Result<String> {
        val action = args["action"]?.trim()?.lowercase() ?: "explore"
        val query = args["query"]?.trim().orEmpty()
        val placeId = args["place_id"]?.trim()
        val destination = args["destination"]?.trim()

        Log.d(TAG, "ask_maps: action=$action query=$query placeId=$placeId destination=$destination")

        return when (action) {
            "explore", "about", "tell_me_about" -> handleExplore(query, placeId)
            "navigate_3d", "navigate" -> handleNavigate3D(destination ?: query)
            "show_3d", "preview_3d", "view_3d", "show_3d_map", "photorealistic_view" ->
                handleShow3D(destination ?: query)
            "show_keyword_pins", "show_pins_3d", "keyword_pins", "places_3d" ->
                handleKeywordPins3D(destination ?: query)
            "fly_over", "flyover", "orbit", "cinematic_view", "aerial_view" ->
                handleFlyOver(destination ?: query)
            "landmark_directions" -> handleLandmarkDirections(destination ?: query)
            "nearby_landmarks" -> handleNearbyLandmarks(query)
            else -> handleExplore(query, placeId)
        }
    }

    /**
     * Explore a place — returns AI-generated summary, rating, status, and a 3D view link.
     */
    private suspend fun handleExplore(query: String, placeId: String?): Result<String> {
        if (query.isBlank() && placeId.isNullOrBlank()) {
            return Result.failure(Exception("Please specify a place to explore."))
        }

        val location = locationProvider()

        // If we have a direct place ID, fetch details directly
        if (!placeId.isNullOrBlank()) {
            return fetchPlaceDetailsById(placeId)
        }

        // Landmark queries ("Space Needle", "Eiffel Tower") should NOT be biased by
        // the user's current location — otherwise a user in SF asking about the
        // Seattle Space Needle gets framed somewhere between the two.
        // Use a very large radius (continent-scale) for famous landmarks,
        // and a tighter radius only for ambiguous local queries.
        val isLandmark = isLandmarkLikeQuery(query)
        val lat = location?.latitude ?: 37.7749
        val lng = location?.longitude ?: -122.4194
        val searchRadius = if (isLandmark) 50_000.0 else 10_000.0

        val searchResult = placesClient.textSearchWithSummary(
            textQuery = query,
            latitude = lat,
            longitude = lng,
            radiusMeters = searchRadius,
            pageSize = 3
        )

        return when (searchResult) {
            is GooglePlacesClient.PlacesResult.Success -> {
                if (searchResult.places.isEmpty()) {
                    return Result.success("No places found matching '$query' near your location.")
                }

                val topPlace = searchResult.places.first()
                // Fetch full details with generativeSummary
                val plId = topPlace.id
                if (!plId.isNullOrBlank()) {
                    val detailsResult = fetchPlaceDetailsById(plId)
                    if (detailsResult.isSuccess) return detailsResult
                }

                // Fallback: return basic info
                Result.success(buildBasicPlaceResponse(topPlace, location))
            }
            is GooglePlacesClient.PlacesResult.ApiKeyMissing ->
                Result.failure(Exception("Google Maps API key not configured."))
            is GooglePlacesClient.PlacesResult.Error ->
                Result.failure(Exception(searchResult.message))
        }
    }

    private suspend fun fetchPlaceDetailsById(placeId: String): Result<String> {
        return when (val result = placesClient.getPlaceDetails(placeId)) {
            is GooglePlacesClient.PlaceDetailsResult.Success -> {
                val d = result.details
                val response = buildString {
                    append("📍 ${d.name}\n")
                    if (!d.address.isNullOrBlank()) append("Address: ${d.address}\n")

                    // AI-generated summary (from Gemini via Places API)
                    val summary = d.generativeSummary ?: d.editorialSummary
                    if (!summary.isNullOrBlank()) {
                        append("About: $summary\n")
                    }

                    if (d.rating != null) {
                        val stars = "★${"%.1f".format(d.rating)}"
                        val count = d.ratingCount?.let { " ($it reviews)" } ?: ""
                        append("Rating: $stars$count\n")
                    }

                    when (d.isOpen) {
                        true -> append("Status: Open Now\n")
                        false -> append("Status: Currently Closed\n")
                        null -> {}
                    }

                    if (!d.phoneNumber.isNullOrBlank()) append("Phone: ${d.phoneNumber}\n")
                    if (!d.websiteUri.isNullOrBlank()) append("Web: ${d.websiteUri}\n")
                    if (d.latitude != null && d.longitude != null) {
                        append("3D navigation available. Tap this card to open it.")
                    }
                }
                Result.success(response)
            }
            is GooglePlacesClient.PlaceDetailsResult.ApiKeyMissing ->
                Result.failure(Exception("Google Maps API key not configured."))
            is GooglePlacesClient.PlaceDetailsResult.Error ->
                Result.failure(Exception(result.message))
        }
    }

    /**
     * Launch 3D AR navigation to a destination.
     * Returns route summary text AND an open_taplink: URL that opens ar_nav.html
     * with the user's current coordinates and destination pre-filled.
     */
    private suspend fun handleNavigate3D(destination: String): Result<String> {
        if (destination.isBlank()) {
            return Result.failure(Exception("Please specify a destination for 3D navigation."))
        }
        if (isKeywordMapQuery(destination)) {
            return handleKeywordPins3D(destination, routeToFirst = true)
        }

        val location = locationProvider()
        val origin = if (location != null) "${location.latitude},${location.longitude}" else "current"

        // Get route info for summary
        val routeInfo = when (val result = directionsClient.getDirections(origin, destination, "driving")) {
            is GoogleDirectionsClient.DirectionsResult.Success -> {
                val traffic = result.durationInTraffic?.let { " ($it with traffic)" } ?: ""
                "${result.distance}, ${result.duration}$traffic via ${result.summary}"
            }
            else -> null
        }

        // Also get walking time for short distances
        val walkInfo = when (val result = directionsClient.getDirections(origin, destination, "walking")) {
            is GoogleDirectionsClient.DirectionsResult.Success -> "${result.duration} walk"
            else -> null
        }

        // Build the ar_nav.html URL with all required parameters
        val enc = { s: String -> URLEncoder.encode(s, StandardCharsets.UTF_8.name()) }
        val apiKey = apiKeyProvider().orEmpty()
        val arNavParams = buildString {
            append("dest=${enc(destination)}")
            append("&gkey=${enc(apiKey)}")
            if (location != null) {
                append("&lat=${location.latitude}")
                append("&lng=${location.longitude}")
                append("&origin_locked=1")
            }
            append("&search=${enc(destination)}")
        }
        val arNavUrl = "file:///android_asset/ar_nav.html?$arNavParams"

        return Result.success(buildString {
            append("3D Navigation to: $destination\n")
            if (routeInfo != null) append("Driving: $routeInfo\n")
            if (walkInfo != null) append("Walking: $walkInfo\n")
            append("Opening 3D AR navigation view.\n")
            append("open_taplink:$arNavUrl")
        })
    }

    /**
     * Get directions with landmark context for each step.
     * Enriches standard turn-by-turn with nearby landmarks visible from AR glasses.
     */
    private suspend fun handleLandmarkDirections(destination: String): Result<String> {
        if (destination.isBlank()) {
            return Result.failure(Exception("Please specify a destination."))
        }

        val location = locationProvider()
            ?: return Result.failure(Exception("GPS not available. Enable location services."))

        val origin = "${location.latitude},${location.longitude}"

        val result = directionsClient.getDirections(origin, destination, "driving")
        if (result !is GoogleDirectionsClient.DirectionsResult.Success) {
            return Result.failure(Exception("Could not get directions to $destination"))
        }

        // Build landmark-enhanced directions
        val response = buildString {
            append("🧭 Landmark Directions to: $destination\n")
            append("${result.distance} — ${result.duration}")
            result.durationInTraffic?.let { append(" ($it in traffic)") }
            append("\n\n")

            result.steps.forEachIndexed { index, step ->
                append("${index + 1}. $step\n")
            }

            append("\nFor 3D view with landmarks, say 'navigate 3D to $destination'")
        }

        return Result.success(response)
    }

    /**
     * Find notable landmarks near the user's current location.
     */
    private suspend fun handleNearbyLandmarks(query: String): Result<String> {
        val location = locationProvider()
            ?: return Result.failure(Exception("GPS not available."))

        val searchQuery = if (query.isNotBlank()) "$query landmarks" else "landmarks points of interest"

        val result = placesClient.textSearchWithSummary(
            textQuery = searchQuery,
            latitude = location.latitude,
            longitude = location.longitude,
            radiusMeters = 2000.0,
            pageSize = 5
        )

        return when (result) {
            is GooglePlacesClient.PlacesResult.Success -> {
                if (result.places.isEmpty()) {
                    return Result.success("No notable landmarks found nearby.")
                }
                val response = buildString {
                    append("🏛️ Nearby Landmarks:\n")
                    result.places.forEachIndexed { index, place ->
                        append("${index + 1}. ${place.name}")
                        if (place.rating != null) append(" ★${"%.1f".format(place.rating)}")
                        if (!place.shortAddress.isNullOrBlank()) append(" — ${place.shortAddress}")
                        append("\n")
                    }
                }
                Result.success(response)
            }
            is GooglePlacesClient.PlacesResult.ApiKeyMissing ->
                Result.failure(Exception("Google Maps API key not configured."))
            is GooglePlacesClient.PlacesResult.Error ->
                Result.failure(Exception(result.message))
        }
    }

    private fun buildBasicPlaceResponse(
        place: GooglePlacesClient.NearbyPlace,
        location: DeviceLocationContext?
    ): String {
        return buildString {
            append("📍 ${place.name}\n")
            if (place.address.isNotBlank()) append("Address: ${place.address}\n")
            if (place.rating != null) {
                append("Rating: ★${"%.1f".format(place.rating)}")
                place.ratingCount?.let { append(" ($it reviews)") }
                append("\n")
            }
            when (place.isOpen) {
                true -> append("Status: Open Now\n")
                false -> append("Status: Currently Closed\n")
                null -> {}
            }
            append("Tap this card to open navigation.")
        }
    }

    /**
     * Open a 3D photorealistic view centered on a landmark — NO driving route.
     * Fixes the bug where "show me a 3D map of the Space Needle" (from SF)
     * framed the midpoint of a 1,300 km driving route and showed countryside.
     *
     * This mode passes `mode=landmark` to ar_nav.html which skips route fetching
     * and frames the destination at an evocative tilt/range close enough to
     * read the building geometry.
     */
    private suspend fun handleShow3D(query: String): Result<String> {
        if (query.isBlank()) {
            return Result.failure(Exception("Please specify a place to show in 3D."))
        }
        if (isKeywordMapQuery(query)) {
            return handleKeywordPins3D(query)
        }
        val enc = { s: String -> URLEncoder.encode(s, StandardCharsets.UTF_8.name()) }
        val apiKey = apiKeyProvider().orEmpty()
        val location = locationProvider()
        val params = buildString {
            append("dest=${enc(query)}")
            append("&gkey=${enc(apiKey)}")
            append("&mode=landmark")
            append("&search=${enc(query)}")
            // Pass user location only as a fallback biasing signal — ar_nav.html
            // won't draw a route in landmark mode, but it may use the bias to
            // disambiguate between locally-similar names. For very famous places
            // we use regionCode heuristics on the JS side instead.
            if (location != null) {
                append("&lat=${location.latitude}")
                append("&lng=${location.longitude}")
            }
        }
        val url = "file:///android_asset/ar_nav.html?$params"
        return Result.success(buildString {
            append("3D View: $query\n")
            append("Opening photorealistic 3D.\n")
            append("open_taplink:$url")
        })
    }

    /**
     * Show a local search query as explicit 3D pushpins. This covers prompts such
     * as "show pizza restaurants in Oakland in the 3D map" where the user wants a
     * field of results, not a single landmark-centered preview.
     */
    private suspend fun handleKeywordPins3D(query: String, routeToFirst: Boolean = false): Result<String> {
        if (query.isBlank()) {
            return Result.failure(Exception("Please specify what to show in the 3D map."))
        }

        val location = locationProvider()
        val lat = location?.latitude ?: 37.7749
        val lng = location?.longitude ?: -122.4194
        val result = placesClient.searchText(
            textQuery = query,
            latitude = lat,
            longitude = lng,
            radiusMeters = 50_000.0,
            pageSize = 12
        )

        return when (result) {
            is GooglePlacesClient.PlacesResult.Success -> {
                val places = result.places.filter { it.latitude != null && it.longitude != null }
                if (places.isEmpty()) {
                    return Result.success("No mappable places found for '$query'. Try a more specific city or place type.")
                }

                val enc = { s: String -> URLEncoder.encode(s, StandardCharsets.UTF_8.name()) }
                val apiKey = apiKeyProvider().orEmpty()
                val pinsJson = buildPinsJson(places)
                val locationsJson = buildLocationsJson(places)
                val firstPlace = places.first()
                val firstDestination = firstPlace.address
                    .ifBlank { firstPlace.shortAddress }
                    .ifBlank { firstPlace.name }
                val destinationParam = if (routeToFirst) firstDestination else query
                val url = buildString {
                    append("file:///android_asset/ar_nav.html?")
                    append("dest=${enc(destinationParam)}")
                    append("&gkey=${enc(apiKey)}")
                    append("&mode=${if (routeToFirst) "route" else "landmark"}")
                    append("&search=${enc(query)}")
                    append("&pins=${enc(pinsJson)}")
                    append("&locations=${enc(locationsJson)}")
                    append("&selected=0")
                    if (location != null) {
                        append("&lat=${location.latitude}")
                        append("&lng=${location.longitude}")
                        if (routeToFirst) append("&origin_locked=1")
                    }
                }

                Result.success(buildString {
                    append(if (routeToFirst) "3D Navigation: $query\n" else "3D Map: $query\n")
                    if (routeToFirst) {
                        append("Routing to ${firstPlace.name}. ")
                    }
                    append("Showing ${places.size} requested POI${if (places.size == 1) "" else "s"} as pushpins. ")
                    append("Only requested POIs are listed; parking is added in navigation mode. ")
                    append("The pins will disappear automatically when you zoom close so they don't block Street View.\n")
                    append("open_taplink:$url")
                })
            }
            is GooglePlacesClient.PlacesResult.ApiKeyMissing ->
                Result.failure(Exception("Google Maps API key not configured."))
            is GooglePlacesClient.PlacesResult.Error ->
                Result.failure(Exception(result.message))
        }
    }

    private fun buildPinsJson(places: List<GooglePlacesClient.NearbyPlace>): String {
        return JSONArray().apply {
            places.forEach { place ->
                put(JSONObject().apply {
                    put("name", place.name)
                    put("address", place.shortAddress.ifBlank { place.address })
                    put("lat", place.latitude)
                    put("lng", place.longitude)
                    put("type", place.types.firstOrNull().orEmpty())
                })
            }
        }.toString()
    }

    private fun buildLocationsJson(places: List<GooglePlacesClient.NearbyPlace>): String {
        return JSONArray().apply {
            places.forEach { place ->
                put(JSONObject().apply {
                    put("name", place.name)
                    put("address", place.address.ifBlank { place.shortAddress })
                    put("lat", place.latitude)
                    put("lng", place.longitude)
                })
            }
        }.toString()
    }

    private fun isKeywordMapQuery(query: String): Boolean {
        val q = query.lowercase()
        if (isLandmarkLikeQuery(query)) return false
        val placeWords = listOf(
            "restaurant", "restaurants", "pizza", "cafe", "cafes", "coffee",
            "bar", "bars", "bakery", "bakeries", "store", "stores", "shop",
            "shops", "hotel", "hotels", "gas", "pharmacy", "parking", "gym",
            "hospital", "clinic", "sushi", "tacos", "food", "places",
            "business", "businesses", "poi", "pois", "point of interest",
            "points of interest"
        )
        return placeWords.any { q.contains(it) }
    }

    /**
     * Orbit / fly-over a landmark using Google Maps 3D flyCameraAround.
     * The viewer centers on the place, tilts the camera, and rotates the heading
     * through 360° over a configurable duration.
     */
    private fun handleFlyOver(query: String): Result<String> {
        if (query.isBlank()) {
            return Result.failure(Exception("Please specify a place to fly over."))
        }
        val enc = { s: String -> URLEncoder.encode(s, StandardCharsets.UTF_8.name()) }
        val apiKey = apiKeyProvider().orEmpty()
        val location = locationProvider()
        val params = buildString {
            append("dest=${enc(query)}")
            append("&gkey=${enc(apiKey)}")
            append("&mode=flyover")
            append("&search=${enc(query)}")
            if (location != null) {
                append("&lat=${location.latitude}")
                append("&lng=${location.longitude}")
            }
        }
        val url = "file:///android_asset/ar_nav.html?$params"
        return Result.success(buildString {
            append("Fly-Over: $query\n")
            append("Cinematic 3D orbit starting.\n")
            append("open_taplink:$url")
        })
    }

    /**
     * Heuristic: does the query name a famous landmark (as opposed to a generic
     * local business)? Used to decide whether to bias Places search by the user's
     * current location. Returns true for queries containing landmark-class nouns
     * (tower, bridge, palace, etc.) or well-known proper noun tokens.
     */
    private fun isLandmarkLikeQuery(query: String): Boolean {
        if (query.isBlank()) return false
        val q = query.lowercase()
        // Landmark class nouns
        val landmarkWords = listOf(
            "tower", "bridge", "cathedral", "basilica", "palace", "castle",
            "monument", "memorial", "statue", "pyramid", "pyramids", "temple",
            "shrine", "mosque", "museum", "opera house", "stadium", "arena",
            "plaza", "square", "fountain", "falls", "canyon", "park", "forest",
            "needle", "arch", "gate", "wall", "tomb", "lighthouse", "capitol",
            "observatory", "pier", "wharf", "harbor", "harbour", "valley",
            "peak", "mountain", "mount", "volcano", "island", "bay", "colosseum",
            "pantheon", "parthenon", "acropolis"
        )
        if (landmarkWords.any { q.contains(it) }) return true
        // Well-known proper-noun tokens that almost always refer to famous places
        val famousTokens = listOf(
            "eiffel", "louvre", "vatican", "sistine", "kremlin", "buckingham",
            "versailles", "notre dame", "taj mahal", "machu picchu", "angkor",
            "great wall", "grand canyon", "niagara", "uluru", "stonehenge",
            "big ben", "times square", "times square", "central park",
            "golden gate", "space needle", "empire state", "chrysler",
            "hollywood sign", "mount fuji", "mt fuji", "mount rushmore",
            "mt rushmore", "statue of liberty", "lincoln memorial",
            "washington monument", "sydney opera", "burj khalifa", "burj al arab",
            "petronas", "sagrada familia", "alhambra", "neuschwanstein",
            "yosemite", "yellowstone"
        )
        if (famousTokens.any { q.contains(it) }) return true
        return false
    }
}
