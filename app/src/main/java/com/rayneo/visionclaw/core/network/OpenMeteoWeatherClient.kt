package com.rayneo.visionclaw.core.network

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Lightweight current-conditions client used for proactive place answers.
 *
 * This is intentionally read-only and keyless so nearby-place responses can
 * add useful walking context without requiring extra companion setup.
 */
class OpenMeteoWeatherClient(
    private val context: Context? = null
) {

    companion object {
        private const val TAG = "OpenMeteoWeather"
        private const val BASE_URL = "https://api.open-meteo.com/v1/forecast"
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 15_000
    }

    sealed class WeatherResult {
        data class Success(val summary: String) : WeatherResult()
        data class Error(val message: String) : WeatherResult()
    }

    data class ForecastSummary(
        val currentSummary: String,
        val currentF: Int?,
        val highF: Int?,
        val lowF: Int?
    )

    sealed class ForecastResult {
        data class Success(val forecast: ForecastSummary) : ForecastResult()
        data class Error(val message: String) : ForecastResult()
    }

    suspend fun fetchCurrentConditions(
        latitude: Double,
        longitude: Double
    ): WeatherResult = withContext(Dispatchers.IO) {
        return@withContext try {
            val url = buildUrl(latitude, longitude)
            val response = ActiveNetworkHttp.get(
                url = url,
                connectTimeoutMs = CONNECT_TIMEOUT_MS,
                readTimeoutMs = READ_TIMEOUT_MS
            )
            val code = response.code
            val body = response.body

            if (code !in 200..299) {
                Log.w(TAG, "Weather HTTP $code: $body")
                return@withContext WeatherResult.Error("Weather API error ($code)")
            }

            val current = JSONObject(body).optJSONObject("current")
                ?: return@withContext WeatherResult.Error("Weather API returned no current conditions.")

            val temperature = current.optDouble("temperature_2m").takeIf { !it.isNaN() }
            val apparent = current.optDouble("apparent_temperature").takeIf { !it.isNaN() }
            val wind = current.optDouble("wind_speed_10m").takeIf { !it.isNaN() }
            val codeValue = current.optInt("weather_code", Int.MIN_VALUE)
                .takeIf { it != Int.MIN_VALUE }
            val label = weatherCodeLabel(codeValue)

            val summary = buildString {
                append("Weather: ")
                if (temperature != null) {
                    append("${temperature.toInt()}°F")
                } else {
                    append("Current conditions")
                }
                if (!label.isNullOrBlank()) {
                    append(" • ")
                    append(label)
                }
                if (apparent != null && temperature != null && kotlin.math.abs(apparent - temperature) >= 2.0) {
                    append(" • feels ${apparent.toInt()}°F")
                }
                if (wind != null && wind >= 6.0) {
                    append(" • wind ${wind.toInt()} mph")
                }
            }

            WeatherResult.Success(summary)
        } catch (e: Exception) {
            Log.w(TAG, "Weather lookup failed: ${e.message}")
            WeatherResult.Error(e.localizedMessage ?: "Weather unavailable")
        }
    }

    suspend fun fetchTodayForecast(
        latitude: Double,
        longitude: Double
    ): ForecastResult = withContext(Dispatchers.IO) {
        return@withContext try {
            val url = buildForecastUrl(latitude, longitude)
            val response = ActiveNetworkHttp.get(
                url = url,
                connectTimeoutMs = CONNECT_TIMEOUT_MS,
                readTimeoutMs = READ_TIMEOUT_MS
            )
            val code = response.code
            val body = response.body

            if (code !in 200..299) {
                Log.w(TAG, "Forecast HTTP $code: $body")
                return@withContext ForecastResult.Error("Weather API error ($code)")
            }

            val root = JSONObject(body)
            val current = root.optJSONObject("current")
                ?: return@withContext ForecastResult.Error("Weather API returned no current conditions.")
            val daily = root.optJSONObject("daily")
                ?: return@withContext ForecastResult.Error("Weather API returned no daily forecast.")

            val temperature = current.optDouble("temperature_2m").takeIf { !it.isNaN() }
            val apparent = current.optDouble("apparent_temperature").takeIf { !it.isNaN() }
            val wind = current.optDouble("wind_speed_10m").takeIf { !it.isNaN() }
            val codeValue = current.optInt("weather_code", Int.MIN_VALUE)
                .takeIf { it != Int.MIN_VALUE }
            val label = weatherCodeLabel(codeValue)

            val highArray = daily.optJSONArray("temperature_2m_max")
            val lowArray = daily.optJSONArray("temperature_2m_min")
            val highF = highArray?.optDouble(0)?.takeIf { !it.isNaN() }?.toInt()
            val lowF = lowArray?.optDouble(0)?.takeIf { !it.isNaN() }?.toInt()

            val summary = buildString {
                append("Weather: ")
                if (temperature != null) {
                    append("${temperature.toInt()}F now")
                } else {
                    append("Current conditions")
                }
                if (highF != null && lowF != null) {
                    append(" • H/L ${highF}F/${lowF}F")
                }
                if (!label.isNullOrBlank()) {
                    append(" • ")
                    append(label)
                }
                if (apparent != null && temperature != null && kotlin.math.abs(apparent - temperature) >= 2.0) {
                    append(" • feels ${apparent.toInt()}F")
                }
                if (wind != null && wind >= 6.0) {
                    append(" • wind ${wind.toInt()} mph")
                }
            }

            ForecastResult.Success(
                ForecastSummary(
                    currentSummary = summary,
                    currentF = temperature?.toInt(),
                    highF = highF,
                    lowF = lowF
                )
            )
        } catch (e: Exception) {
            Log.w(TAG, "Forecast lookup failed: ${e.message}")
            ForecastResult.Error(e.localizedMessage ?: "Forecast unavailable")
        }
    }

    private fun buildUrl(latitude: Double, longitude: Double): String {
        val lat = URLEncoder.encode(latitude.toString(), "UTF-8")
        val lng = URLEncoder.encode(longitude.toString(), "UTF-8")
        val current = URLEncoder.encode(
            "temperature_2m,apparent_temperature,weather_code,wind_speed_10m",
            "UTF-8"
        )
        return "$BASE_URL?latitude=$lat&longitude=$lng&current=$current&temperature_unit=fahrenheit&windspeed_unit=mph&timezone=auto"
    }

    private fun buildForecastUrl(latitude: Double, longitude: Double): String {
        val lat = URLEncoder.encode(latitude.toString(), "UTF-8")
        val lng = URLEncoder.encode(longitude.toString(), "UTF-8")
        val current = URLEncoder.encode(
            "temperature_2m,apparent_temperature,weather_code,wind_speed_10m",
            "UTF-8"
        )
        val daily = URLEncoder.encode(
            "temperature_2m_max,temperature_2m_min",
            "UTF-8"
        )
        return "$BASE_URL?latitude=$lat&longitude=$lng&current=$current&daily=$daily&temperature_unit=fahrenheit&windspeed_unit=mph&timezone=auto"
    }

    private fun weatherCodeLabel(code: Int?): String? {
        return when (code) {
            null -> null
            0 -> "clear"
            1 -> "mostly clear"
            2 -> "partly cloudy"
            3 -> "overcast"
            45, 48 -> "foggy"
            51, 53, 55 -> "drizzle"
            56, 57 -> "freezing drizzle"
            61, 63, 65 -> "rain"
            66, 67 -> "freezing rain"
            71, 73, 75, 77 -> "snow"
            80, 81, 82 -> "rain showers"
            85, 86 -> "snow showers"
            95 -> "thunderstorms"
            96, 99 -> "thunderstorms with hail"
            else -> null
        }
    }
}
