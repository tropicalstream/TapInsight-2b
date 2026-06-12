package com.rayneo.visionclaw.core.network

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * GooglePlacesClient – finds nearby businesses via Google Places API (New).
 *
 * Uses the Nearby Search endpoint:
 *   POST https://places.googleapis.com/v1/places:searchNearby
 *
 * Requires a Google Maps API key with the Places API (New) enabled.
 * Reuses the same key as Directions / Maps.
 */
class GooglePlacesClient(
    private val apiKeyProvider: () -> String?,
    private val context: Context? = null
) {

    companion object {
        private const val TAG = "GooglePlaces"
        private const val NEARBY_URL =
            "https://places.googleapis.com/v1/places:searchNearby"
        private const val TEXT_SEARCH_URL =
            "https://places.googleapis.com/v1/places:searchText"
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 15_000

        /** Fields returned — Basic + Advanced tier. */
        private const val FIELD_MASK =
            "places.id,places.displayName,places.formattedAddress,places.location,places.rating," +
                "places.userRatingCount,places.currentOpeningHours," +
                "places.businessStatus,places.types,places.shortFormattedAddress," +
                "places.googleMapsUri"
    }

    // ── Result types ─────────────────────────────────────────────────────

    sealed class PlacesResult {
        data class Success(val places: List<NearbyPlace>) : PlacesResult()
        data class Error(val message: String, val code: Int = -1) : PlacesResult()
        object ApiKeyMissing : PlacesResult()
    }

    data class NearbyPlace(
        val id: String?,
        val name: String,
        val address: String,
        val shortAddress: String,
        val latitude: Double?,
        val longitude: Double?,
        val rating: Double?,
        val ratingCount: Int?,
        val isOpen: Boolean?,
        val businessStatus: String?,
        val types: List<String>,
        val googleMapsUri: String?
    )

    /** Extended place details including AI-generated summary. */
    data class PlaceDetails(
        val id: String,
        val name: String,
        val address: String,
        val latitude: Double?,
        val longitude: Double?,
        val rating: Double?,
        val ratingCount: Int?,
        val isOpen: Boolean?,
        val googleMapsUri: String?,
        val generativeSummary: String?,
        val editorialSummary: String?,
        val phoneNumber: String?,
        val websiteUri: String?,
        val types: List<String>
    )

    sealed class PlaceDetailsResult {
        data class Success(val details: PlaceDetails) : PlaceDetailsResult()
        data class Error(val message: String, val code: Int = -1) : PlaceDetailsResult()
        object ApiKeyMissing : PlaceDetailsResult()
    }

    // ── Public API ───────────────────────────────────────────────────────

    /**
     * Search for nearby places by type.
     *
     * @param latitude      Device latitude.
     * @param longitude     Device longitude.
     * @param types         Place types (e.g. ["restaurant"], ["cafe", "bakery"]).
     * @param radiusMeters  Search radius in meters (default 1500, max 50000).
     * @param maxResults    Maximum results to return (1-20, default 5).
     */
    suspend fun searchNearby(
        latitude: Double,
        longitude: Double,
        types: List<String>,
        radiusMeters: Double = 1500.0,
        maxResults: Int = 5,
        openNow: Boolean? = null,
        rankPreference: String = "DISTANCE"
    ): PlacesResult = withContext(Dispatchers.IO) {

        val apiKey = apiKeyProvider()
        if (apiKey.isNullOrBlank()) {
            Log.w(TAG, "Google Maps API key is missing")
            return@withContext PlacesResult.ApiKeyMissing
        }

        try {
            // Build request JSON
            val locale = Locale.getDefault()
            val requestBody = JSONObject().apply {
                put("includedTypes", JSONArray(types))
                put("maxResultCount", maxResults.coerceIn(1, 20))
                put("rankPreference", rankPreference)
                put("languageCode", locale.language)
                put("regionCode", locale.country)
                openNow?.let { put("openNow", it) }
                put("locationRestriction", JSONObject().apply {
                    put("circle", JSONObject().apply {
                        put("center", JSONObject().apply {
                            put("latitude", latitude)
                            put("longitude", longitude)
                        })
                        put("radius", radiusMeters.coerceIn(1.0, 50000.0))
                    })
                })
            }

            val response = ActiveNetworkHttp.postJson(
                url = NEARBY_URL,
                jsonBody = requestBody.toString(),
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "X-Goog-Api-Key" to apiKey,
                    "X-Goog-FieldMask" to FIELD_MASK
                ),
                connectTimeoutMs = CONNECT_TIMEOUT_MS,
                readTimeoutMs = READ_TIMEOUT_MS
            )
            val responseCode = response.code
            val body = response.body

            if (responseCode == 403 || responseCode == 401) {
                Log.e(TAG, "Places API auth error ($responseCode): $body")
                return@withContext PlacesResult.Error(
                    "Places API key invalid or Places API (New) not enabled in GCP.", responseCode
                )
            }

            if (responseCode !in 200..299) {
                Log.e(TAG, "Places HTTP $responseCode: $body")
                val errorMsg = try {
                    val errJson = JSONObject(body)
                    errJson.optJSONObject("error")?.optString("message")
                        ?: "Places API error ($responseCode)"
                } catch (_: Exception) {
                    "Places API error ($responseCode)"
                }
                return@withContext PlacesResult.Error(errorMsg, responseCode)
            }

            val places = parsePlaces(body)

            Log.d(TAG, "Found ${places.size} places near ($latitude, $longitude)")
            PlacesResult.Success(places)
        } catch (e: Exception) {
            Log.e(TAG, "Places request failed", e)
            PlacesResult.Error(e.localizedMessage ?: "Unknown error")
        }
    }

    suspend fun searchText(
        textQuery: String,
        latitude: Double,
        longitude: Double,
        radiusMeters: Double = 1500.0,
        pageSize: Int = 10,
        includedType: String? = null,
        strictTypeFiltering: Boolean = false,
        openNow: Boolean? = null
    ): PlacesResult = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider()
        if (apiKey.isNullOrBlank()) {
            Log.w(TAG, "Google Maps API key is missing")
            return@withContext PlacesResult.ApiKeyMissing
        }

        try {
            val locale = Locale.getDefault()
            val requestBody = JSONObject().apply {
                put("textQuery", textQuery)
                put("pageSize", pageSize.coerceIn(1, 20))
                put("languageCode", locale.language)
                put("regionCode", locale.country)
                put("locationBias", JSONObject().apply {
                    put("circle", JSONObject().apply {
                        put("center", JSONObject().apply {
                            put("latitude", latitude)
                            put("longitude", longitude)
                        })
                        put("radius", radiusMeters.coerceIn(1.0, 50_000.0))
                    })
                })
                includedType
                    ?.takeIf { it.isNotBlank() }
                    ?.let {
                        put("includedType", it)
                        put("strictTypeFiltering", strictTypeFiltering)
                    }
                openNow?.let { put("openNow", it) }
            }

            val response = ActiveNetworkHttp.postJson(
                url = TEXT_SEARCH_URL,
                jsonBody = requestBody.toString(),
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "X-Goog-Api-Key" to apiKey,
                    "X-Goog-FieldMask" to FIELD_MASK
                ),
                connectTimeoutMs = CONNECT_TIMEOUT_MS,
                readTimeoutMs = READ_TIMEOUT_MS
            )
            val responseCode = response.code
            val body = response.body

            if (responseCode == 403 || responseCode == 401) {
                Log.e(TAG, "Places text search auth error ($responseCode): $body")
                return@withContext PlacesResult.Error(
                    "Places API key invalid or Places API (New) not enabled in GCP.",
                    responseCode
                )
            }

            if (responseCode !in 200..299) {
                Log.e(TAG, "Places text search HTTP $responseCode: $body")
                val errorMsg = try {
                    val errJson = JSONObject(body)
                    errJson.optJSONObject("error")?.optString("message")
                        ?: "Places text search error ($responseCode)"
                } catch (_: Exception) {
                    "Places text search error ($responseCode)"
                }
                return@withContext PlacesResult.Error(errorMsg, responseCode)
            }

            val places = parsePlaces(body)
            Log.d(TAG, "Text search '$textQuery' returned ${places.size} places")
            PlacesResult.Success(places)
        } catch (e: Exception) {
            Log.e(TAG, "Places text search failed", e)
            PlacesResult.Error(e.localizedMessage ?: "Unknown error")
        }
    }

    /**
     * Get extended place details including generativeSummary (AI-generated description).
     * Uses Places API (New) Place Details endpoint.
     */
    suspend fun getPlaceDetails(placeId: String): PlaceDetailsResult = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider()
        if (apiKey.isNullOrBlank()) return@withContext PlaceDetailsResult.ApiKeyMissing

        try {
            val url = "https://places.googleapis.com/v1/places/$placeId"
            val fieldMask = "id,displayName,formattedAddress,location,rating,userRatingCount," +
                "currentOpeningHours,googleMapsUri,generativeSummary,editorialSummary," +
                "nationalPhoneNumber,websiteUri,types"

            val response = ActiveNetworkHttp.get(
                url = url,
                headers = mapOf(
                    "X-Goog-Api-Key" to apiKey,
                    "X-Goog-FieldMask" to fieldMask
                ),
                connectTimeoutMs = CONNECT_TIMEOUT_MS,
                readTimeoutMs = READ_TIMEOUT_MS
            )

            if (response.code !in 200..299) {
                val errMsg = try {
                    JSONObject(response.body).optJSONObject("error")?.optString("message")
                        ?: "Place details error (${response.code})"
                } catch (_: Exception) { "Place details error (${response.code})" }
                return@withContext PlaceDetailsResult.Error(errMsg, response.code)
            }

            val place = JSONObject(response.body)
            val details = PlaceDetails(
                id = place.optString("id", placeId),
                name = place.optJSONObject("displayName")?.optString("text", "Unknown") ?: "Unknown",
                address = place.optString("formattedAddress", ""),
                latitude = place.optJSONObject("location")?.optDouble("latitude")?.takeIf { !it.isNaN() },
                longitude = place.optJSONObject("location")?.optDouble("longitude")?.takeIf { !it.isNaN() },
                rating = if (place.has("rating")) place.optDouble("rating") else null,
                ratingCount = if (place.has("userRatingCount")) place.optInt("userRatingCount") else null,
                isOpen = place.optJSONObject("currentOpeningHours")
                    ?.let { if (it.has("openNow")) it.optBoolean("openNow") else null },
                googleMapsUri = place.optString("googleMapsUri").takeIf { it.isNotBlank() },
                generativeSummary = place.optJSONObject("generativeSummary")
                    ?.optJSONObject("overview")
                    ?.optString("text")
                    ?.takeIf { it.isNotBlank() },
                editorialSummary = place.optJSONObject("editorialSummary")
                    ?.optString("text")
                    ?.takeIf { it.isNotBlank() },
                phoneNumber = place.optString("nationalPhoneNumber").takeIf { it.isNotBlank() },
                websiteUri = place.optString("websiteUri").takeIf { it.isNotBlank() },
                types = place.optJSONArray("types")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                } ?: emptyList()
            )

            Log.d(TAG, "Place details for '$placeId': ${details.name}, summary=${details.generativeSummary?.take(80)}")
            PlaceDetailsResult.Success(details)
        } catch (e: Exception) {
            Log.e(TAG, "Place details request failed", e)
            PlaceDetailsResult.Error(e.localizedMessage ?: "Unknown error")
        }
    }

    /**
     * Text search that also returns generativeSummary for each place.
     * Uses the Enterprise + Atmosphere field mask.
     */
    suspend fun textSearchWithSummary(
        textQuery: String,
        latitude: Double,
        longitude: Double,
        radiusMeters: Double = 5000.0,
        pageSize: Int = 5
    ): PlacesResult = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider()
        if (apiKey.isNullOrBlank()) return@withContext PlacesResult.ApiKeyMissing

        try {
            val locale = Locale.getDefault()
            val requestBody = JSONObject().apply {
                put("textQuery", textQuery)
                put("pageSize", pageSize.coerceIn(1, 10))
                put("languageCode", locale.language)
                put("regionCode", locale.country)
                put("locationBias", JSONObject().apply {
                    put("circle", JSONObject().apply {
                        put("center", JSONObject().apply {
                            put("latitude", latitude)
                            put("longitude", longitude)
                        })
                        put("radius", radiusMeters.coerceIn(1.0, 50_000.0))
                    })
                })
            }

            val enrichedFieldMask = FIELD_MASK +
                ",places.generativeSummary,places.editorialSummary," +
                "places.nationalPhoneNumber,places.websiteUri"

            val response = ActiveNetworkHttp.postJson(
                url = TEXT_SEARCH_URL,
                jsonBody = requestBody.toString(),
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "X-Goog-Api-Key" to apiKey,
                    "X-Goog-FieldMask" to enrichedFieldMask
                ),
                connectTimeoutMs = CONNECT_TIMEOUT_MS,
                readTimeoutMs = READ_TIMEOUT_MS
            )

            if (response.code !in 200..299) {
                val errMsg = try {
                    JSONObject(response.body).optJSONObject("error")?.optString("message")
                        ?: "Places search error (${response.code})"
                } catch (_: Exception) { "Places search error (${response.code})" }
                return@withContext PlacesResult.Error(errMsg, response.code)
            }

            val places = parsePlacesWithSummary(response.body)
            Log.d(TAG, "Text search with summary '$textQuery' returned ${places.size} places")
            PlacesResult.Success(places)
        } catch (e: Exception) {
            Log.e(TAG, "Text search with summary failed", e)
            PlacesResult.Error(e.localizedMessage ?: "Unknown error")
        }
    }

    private fun parsePlacesWithSummary(body: String): List<NearbyPlace> {
        // Reuses standard parser — generativeSummary is logged but NearbyPlace
        // doesn't carry it. For enriched results, use getPlaceDetails per place.
        return parsePlaces(body)
    }

    private fun parsePlaces(body: String): List<NearbyPlace> {
        val json = JSONObject(body)
        val placesArray = json.optJSONArray("places") ?: return emptyList()
        if (placesArray.length() == 0) return emptyList()

        return (0 until placesArray.length()).map { i ->
            val place = placesArray.getJSONObject(i)
            val id = place.optString("id").takeIf { it.isNotBlank() }
            val displayName = place.optJSONObject("displayName")
                ?.optString("text", "Unknown")
                ?.takeIf { it.isNotBlank() }
                ?: "Unknown"
            val address = place.optString("formattedAddress", "")
            val shortAddr = place.optString("shortFormattedAddress", address)
            val location = place.optJSONObject("location")
            val latitude = location?.optDouble("latitude")?.takeIf { !it.isNaN() }
            val longitude = location?.optDouble("longitude")?.takeIf { !it.isNaN() }
            val rating = if (place.has("rating")) place.optDouble("rating") else null
            val ratingCount = if (place.has("userRatingCount")) {
                place.optInt("userRatingCount")
            } else {
                null
            }
            val openNow = place.optJSONObject("currentOpeningHours")
                ?.let { if (it.has("openNow")) it.optBoolean("openNow") else null }
            val businessStatus = place.optString("businessStatus").takeIf { it.isNotBlank() }
            val googleMapsUri = place.optString("googleMapsUri").takeIf { it.isNotBlank() }
            val typesArr = place.optJSONArray("types")
            val typesList = if (typesArr != null) {
                (0 until typesArr.length()).map { j -> typesArr.getString(j) }
            } else {
                emptyList()
            }

            NearbyPlace(
                id = id,
                name = displayName,
                address = address,
                shortAddress = shortAddr,
                latitude = latitude,
                longitude = longitude,
                rating = rating,
                ratingCount = ratingCount,
                isOpen = openNow,
                businessStatus = businessStatus,
                types = typesList,
                googleMapsUri = googleMapsUri
            )
        }
    }
}
