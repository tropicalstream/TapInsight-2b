package com.rayneo.visionclaw.core.network

import android.content.Context
import android.util.Log
import com.rayneo.visionclaw.core.model.DeviceLocationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class GoogleGeolocationClient(
    private val apiKeyProvider: () -> String?,
    private val context: Context? = null
) {

    companion object {
        private const val TAG = "GoogleGeolocation"
        private const val GEOLOCATION_URL = "https://www.googleapis.com/geolocation/v1/geolocate"
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 12_000
    }

    data class WifiAccessPoint(
        val macAddress: String,
        val signalStrength: Int? = null,
        val channel: Int? = null,
        val ageMs: Int? = null
    )

    sealed class GeolocationResult {
        data class Success(val context: DeviceLocationContext) : GeolocationResult()
        data class Error(val message: String, val code: Int = -1) : GeolocationResult()
        object ApiKeyMissing : GeolocationResult()
    }

    suspend fun locate(
        wifiAccessPoints: List<WifiAccessPoint>,
        considerIp: Boolean
    ): GeolocationResult = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider()?.trim().orEmpty()
        if (apiKey.isBlank()) {
            return@withContext GeolocationResult.ApiKeyMissing
        }

        if (wifiAccessPoints.size < 2 && !considerIp) {
            return@withContext GeolocationResult.Error("Need at least two Wi-Fi access points for triangulation.")
        }

        return@withContext try {
            val request = JSONObject()
                .put("considerIp", considerIp)

            if (wifiAccessPoints.isNotEmpty()) {
                val accessPointsJson = JSONArray()
                wifiAccessPoints.forEach { ap ->
                    accessPointsJson.put(
                        JSONObject().apply {
                            put("macAddress", ap.macAddress)
                            ap.signalStrength?.let { put("signalStrength", it) }
                            ap.channel?.let { put("channel", it) }
                            ap.ageMs?.let { put("age", it) }
                        }
                    )
                }
                request.put("wifiAccessPoints", accessPointsJson)
            }

            val response = ActiveNetworkHttp.postJson(
                url = "$GEOLOCATION_URL?key=$apiKey",
                jsonBody = request.toString(),
                headers = mapOf("Content-Type" to "application/json"),
                connectTimeoutMs = CONNECT_TIMEOUT_MS,
                readTimeoutMs = READ_TIMEOUT_MS
            )
            val code = response.code
            val body = response.body

            if (code !in 200..299) {
                Log.w(TAG, "Geolocation HTTP $code body=${body.take(240)}")
                return@withContext GeolocationResult.Error("Google Geolocation API error ($code)", code)
            }

            val json = JSONObject(body)
            val location = json.optJSONObject("location")
                ?: return@withContext GeolocationResult.Error("Geolocation API returned no location.")
            val latitude = location.optDouble("lat", Double.NaN)
            val longitude = location.optDouble("lng", Double.NaN)
            if (!latitude.isFinite() || !longitude.isFinite()) {
                return@withContext GeolocationResult.Error("Geolocation API returned invalid coordinates.")
            }
            val accuracy = json.optDouble("accuracy", Double.NaN).takeIf { it.isFinite() }?.toFloat()
            val provider = if (wifiAccessPoints.size >= 2) "wifi_geolocation" else "network_geolocation"

            GeolocationResult.Success(
                DeviceLocationContext(
                    latitude = latitude,
                    longitude = longitude,
                    accuracyMeters = accuracy,
                    provider = provider,
                    timestampMs = System.currentTimeMillis()
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Geolocation request failed", e)
            GeolocationResult.Error(e.localizedMessage ?: "Geolocation request failed")
        }
    }
}
