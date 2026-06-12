package com.rayneo.visionclaw.core.tools

import android.content.Context
import com.rayneo.visionclaw.core.model.DeviceLocationContext
import com.rayneo.visionclaw.core.network.GoogleAirQualityClient

class GoogleAirQualityTool(
    private val context: Context,
    private val airQualityClient: GoogleAirQualityClient,
    private val locationProvider: () -> DeviceLocationContext?
) : AiTapTool {
    override val name: String = "google_air_quality"

    override suspend fun execute(args: Map<String, String>): Result<String> {
        val location = locationProvider()
            ?: return Result.failure(
                IllegalStateException("Current location is unavailable. Enable location on the glasses and try again.")
            )

        return when (
            val result = airQualityClient.fetchCurrentConditions(
                latitude = location.latitude,
                longitude = location.longitude
            )
        ) {
            is GoogleAirQualityClient.AirQualityResult.Success -> {
                val index = result.index
                val details = buildList {
                    add(index.label)
                    index.dominantPollutant?.let { add("Dominant pollutant: $it") }
                }.joinToString(". ")
                Result.success(details)
            }
            is GoogleAirQualityClient.AirQualityResult.ApiKeyMissing ->
                Result.failure(IllegalStateException("Google Maps API key is missing for Air Quality."))
            is GoogleAirQualityClient.AirQualityResult.Error ->
                Result.failure(IllegalStateException(result.message))
        }
    }
}
