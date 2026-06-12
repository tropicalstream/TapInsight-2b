package com.rayneo.visionclaw.core.network

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder

/**
 * GoogleDirectionsClient – fetches directions from Google Directions API.
 *
 * Requires a Google Maps API key with the Directions API enabled.
 */
class GoogleDirectionsClient(
    private val apiKeyProvider: () -> String?,
    private val context: Context? = null
) {

    companion object {
        private const val TAG = "GoogleDirections"
        private const val BASE_URL =
            "https://maps.googleapis.com/maps/api/directions/json"
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 15_000
    }

    // ── Result types ─────────────────────────────────────────────────────

    sealed class DirectionsResult {
        data class Success(
            val summary: String,
            val distance: String,
            val duration: String,
            val durationInTraffic: String?,
            val startAddress: String,
            val endAddress: String,
            val steps: List<String>
        ) : DirectionsResult()

        data class Error(val message: String, val code: Int = -1) : DirectionsResult()
        object ApiKeyMissing : DirectionsResult()
    }

    // ── Public API ───────────────────────────────────────────────────────

    /**
     * Get directions between two locations.
     *
     * @param origin      Starting point (address, place name, or "lat,lng").
     * @param destination Ending point.
     * @param mode        Travel mode: driving, walking, transit, bicycling.
     */
    suspend fun getDirections(
        origin: String,
        destination: String,
        mode: String = "driving"
    ): DirectionsResult = withContext(Dispatchers.IO) {

        val apiKey = apiKeyProvider()
        if (apiKey.isNullOrBlank()) {
            Log.w(TAG, "Google Maps API key is missing")
            return@withContext DirectionsResult.ApiKeyMissing
        }

        try {
            val urlStr = "$BASE_URL" +
                    "?origin=${URLEncoder.encode(origin, "UTF-8")}" +
                    "&destination=${URLEncoder.encode(destination, "UTF-8")}" +
                    "&mode=${mode.lowercase()}" +
                    "&departure_time=now" +
                    "&key=$apiKey"

            val response = ActiveNetworkHttp.get(
                url = urlStr,
                connectTimeoutMs = CONNECT_TIMEOUT_MS,
                readTimeoutMs = READ_TIMEOUT_MS
            )
            val responseCode = response.code
            val body = response.body

            if (responseCode !in 200..299) {
                Log.e(TAG, "Directions HTTP $responseCode: $body")
                return@withContext DirectionsResult.Error("Directions API error ($responseCode)", responseCode)
            }

            val json = JSONObject(body)
            val status = json.optString("status", "UNKNOWN_ERROR")

            if (status != "OK") {
                val errorMsg = when (status) {
                    "NOT_FOUND" -> "Could not find one of the locations."
                    "ZERO_RESULTS" -> "No route found between those locations."
                    "OVER_DAILY_LIMIT", "OVER_QUERY_LIMIT" -> "API rate limit reached. Try again later."
                    "REQUEST_DENIED" -> "Request denied. Check your API key and enabled APIs."
                    "INVALID_REQUEST" -> "Invalid request. Check origin and destination."
                    else -> "Directions error: $status"
                }
                Log.w(TAG, "Directions status: $status")
                return@withContext DirectionsResult.Error(errorMsg)
            }

            val routes = json.optJSONArray("routes")
            if (routes == null || routes.length() == 0) {
                return@withContext DirectionsResult.Error("No routes found.")
            }

            val route = routes.getJSONObject(0)
            val legs = route.optJSONArray("legs")
            if (legs == null || legs.length() == 0) {
                return@withContext DirectionsResult.Error("No route legs found.")
            }

            val leg = legs.getJSONObject(0)
            val distance = leg.optJSONObject("distance")?.optString("text", "unknown") ?: "unknown"
            val duration = leg.optJSONObject("duration")?.optString("text", "unknown") ?: "unknown"
            val durationInTraffic = leg.optJSONObject("duration_in_traffic")?.optString("text")
            val startAddr = leg.optString("start_address", origin)
            val endAddr = leg.optString("end_address", destination)
            val summary = route.optString("summary", "")

            // Parse step-by-step directions (strip HTML tags)
            val stepsArray = leg.optJSONArray("steps")
            val steps = if (stepsArray != null) {
                (0 until stepsArray.length()).map { i ->
                    val step = stepsArray.getJSONObject(i)
                    val instruction = step.optString("html_instructions", "")
                        .replace(Regex("<[^>]*>"), " ")
                        .replace(Regex("\\s+"), " ")
                        .trim()
                    val stepDist = step.optJSONObject("distance")?.optString("text", "") ?: ""
                    if (stepDist.isNotBlank()) "$instruction ($stepDist)" else instruction
                }
            } else emptyList()

            Log.d(TAG, "Directions: $distance, $duration (traffic: $durationInTraffic)")

            DirectionsResult.Success(
                summary = summary,
                distance = distance,
                duration = duration,
                durationInTraffic = durationInTraffic,
                startAddress = startAddr,
                endAddress = endAddr,
                steps = steps
            )
        } catch (e: Exception) {
            Log.e(TAG, "Directions request failed", e)
            DirectionsResult.Error(e.localizedMessage ?: "Unknown error")
        }
    }
}
