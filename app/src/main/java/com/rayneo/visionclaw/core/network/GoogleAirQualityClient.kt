package com.rayneo.visionclaw.core.network

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Google Air Quality API client for current AQI conditions at the device location.
 *
 * Uses:
 *   POST https://airquality.googleapis.com/v1/currentConditions:lookup?key=API_KEY
 */
class GoogleAirQualityClient(
    private val apiKeyProvider: () -> String?,
    private val context: Context? = null
) {

    companion object {
        private const val TAG = "GoogleAirQuality"
        private const val CURRENT_CONDITIONS_URL =
            "https://airquality.googleapis.com/v1/currentConditions:lookup"
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 15_000
    }

    data class AirQualityIndex(
        val aqi: Int?,
        val label: String,
        val category: String?,
        val displayName: String?,
        val dominantPollutant: String?
    )

    sealed class AirQualityResult {
        data class Success(val index: AirQualityIndex) : AirQualityResult()
        data class Error(val message: String, val code: Int = -1) : AirQualityResult()
        object ApiKeyMissing : AirQualityResult()
    }

    suspend fun fetchCurrentConditions(
        latitude: Double,
        longitude: Double,
        languageCode: String = "en"
    ): AirQualityResult = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider()?.trim().orEmpty()
        if (apiKey.isBlank()) {
            Log.w(TAG, "Maps API key missing for Air Quality API")
            return@withContext AirQualityResult.ApiKeyMissing
        }

        val requestBody = JSONObject()
            .put("location", JSONObject()
                .put("latitude", latitude)
                .put("longitude", longitude)
            )
            .put("languageCode", languageCode)
            .put("universalAqi", true)
            .put("extraComputations", JSONArray().put("LOCAL_AQI"))

        return@withContext try {
            val response = ActiveNetworkHttp.postJson(
                url = "$CURRENT_CONDITIONS_URL?key=$apiKey",
                jsonBody = requestBody.toString(),
                headers = mapOf("Content-Type" to "application/json"),
                connectTimeoutMs = CONNECT_TIMEOUT_MS,
                readTimeoutMs = READ_TIMEOUT_MS
            )
            val code = response.code
            val body = response.body

            if (code == 401 || code == 403) {
                Log.e(TAG, "Air Quality auth error ($code): $body")
                return@withContext AirQualityResult.Error(
                    "Air Quality API key invalid or API not enabled in Google Cloud.",
                    code
                )
            }
            if (code !in 200..299) {
                Log.e(TAG, "Air Quality HTTP $code: $body")
                return@withContext AirQualityResult.Error("Air Quality API error ($code)", code)
            }

            val root = JSONObject(body)
            val indexes = root.optJSONArray("indexes")
            if (indexes == null || indexes.length() == 0) {
                return@withContext AirQualityResult.Error("Air Quality API returned no AQI data.")
            }

            val selected = pickPreferredIndex(indexes)
            val aqi = when {
                selected.has("aqi") -> selected.optInt("aqi")
                else -> selected.optString("aqiDisplay", "").toIntOrNull()
            }
            val category = selected.optString("category", "").trim().takeIf { it.isNotBlank() }
            val displayName = selected.optString("displayName", "").trim().takeIf { it.isNotBlank() }
            val dominantPollutant = selected.optString("dominantPollutant", "").trim()
                .takeIf { it.isNotBlank() }
            val label = when {
                aqi != null && category != null -> "AQI $aqi • $category"
                aqi != null -> "AQI $aqi"
                category != null -> "AQI • $category"
                else -> displayName ?: "AQI unavailable"
            }

            AirQualityResult.Success(
                AirQualityIndex(
                    aqi = aqi,
                    label = label,
                    category = category,
                    displayName = displayName,
                    dominantPollutant = dominantPollutant
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Air Quality request failed", e)
            AirQualityResult.Error(e.localizedMessage ?: "Unknown error")
        }
    }

    private fun pickPreferredIndex(indexes: JSONArray): JSONObject {
        var universal: JSONObject? = null
        for (i in 0 until indexes.length()) {
            val item = indexes.optJSONObject(i) ?: continue
            val code = item.optString("code", "").trim().lowercase()
            if (code.isBlank()) return item
            if (code != "uaqi" && code != "universal_aqi") {
                return item
            }
            if (universal == null) {
                universal = item
            }
        }
        return universal ?: indexes.optJSONObject(0) ?: JSONObject()
    }
}
