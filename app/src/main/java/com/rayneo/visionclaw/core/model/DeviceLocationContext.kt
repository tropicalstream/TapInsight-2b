package com.rayneo.visionclaw.core.model

/**
 * Lightweight container for the device's GPS/location state.
 * Used by tools and the ViewModel for location-aware queries.
 */
data class DeviceLocationContext(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float? = null,
    val altitudeMeters: Double? = null,
    val speedMps: Float? = null,
    val bearingDeg: Float? = null,
    val provider: String? = null,
    val timestampMs: Long = System.currentTimeMillis()
)
