package com.rayneo.visionclaw.core.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.rayneo.visionclaw.core.network.ActiveNetworkHttp
import com.rayneo.visionclaw.core.network.GoogleGeolocationClient
import com.rayneo.visionclaw.core.model.DeviceLocationContext
import com.rayneo.visionclaw.core.storage.AppPreferences
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Resolves the device's best available location without depending on activity-only state.
 */
class DeviceLocationResolver(context: Context) {
    companion object {
        private const val TAG = "DeviceLocationResolver"
        private const val DEFAULT_MAX_AGE_MS = 10 * 60 * 1000L
        private const val DEFAULT_TIMEOUT_MS = 4_500L
        private const val PRECISE_MAX_AGE_MS = 2 * 60 * 1000L
        private const val PRECISE_TIMEOUT_MS = 6_500L
        private const val FRESH_LOCATION_MAX_AGE_MS = 2 * 60 * 1000L
        private const val FRESH_LOCATION_MAX_ACCURACY_METERS = 150f
        private const val USABLE_LOCATION_MAX_ACCURACY_METERS = 500f
        private const val PRECISE_LOCATION_MAX_ACCURACY_METERS = 250f
        private const val MAX_WIFI_ACCESS_POINTS = 12
        private const val PHONE_BRIDGE_MAX_AGE_MS = 15_000L  // Reduced from 60s: stale GPS on AR glasses is dangerous
        private const val PHONE_BRIDGE_PRECISE_MAX_AGE_MS = 10_000L  // Reduced from 30s for navigation accuracy
    }

    private val appContext = context.applicationContext
    private val locationManager: LocationManager? = appContext.getSystemService(LocationManager::class.java)
    private val wifiManager: WifiManager? = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val preferences = AppPreferences(appContext)
    private val geolocationClient = GoogleGeolocationClient(
        apiKeyProvider = { preferences.googleMapsApiKey.trim().takeIf { it.isNotBlank() } },
        context = appContext
    )

    @Volatile private var cached: DeviceLocationContext? = null

    fun peekCached(
        maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
        maxAccuracyMeters: Float = USABLE_LOCATION_MAX_ACCURACY_METERS,
        allowApproximate: Boolean = true
    ): DeviceLocationContext? {
        peekPhoneBridgeContext(
            maxAgeMs = minOf(maxAgeMs, if (allowApproximate) PHONE_BRIDGE_MAX_AGE_MS else PHONE_BRIDGE_PRECISE_MAX_AGE_MS),
            maxAccuracyMeters = maxAccuracyMeters,
            allowApproximate = allowApproximate
        )?.let { return it }

        val snapshot = cached ?: return null
        val ageMs = System.currentTimeMillis() - snapshot.timestampMs
        return snapshot.takeIf {
            ageMs <= maxAgeMs && isAcceptableContext(
                context = it,
                maxAccuracyMeters = maxAccuracyMeters,
                allowApproximate = allowApproximate,
                maxAgeMs = maxAgeMs
            )
        }
    }

    fun resolveBlocking(
        maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        requirePrecise: Boolean = false,
        allowApproximateFallback: Boolean = true
    ): DeviceLocationContext? = runBlocking {
        resolve(
            maxAgeMs = maxAgeMs,
            timeoutMs = timeoutMs,
            requirePrecise = requirePrecise,
            allowApproximateFallback = allowApproximateFallback
        )
    }

    suspend fun resolve(
        maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        requirePrecise: Boolean = false,
        allowApproximateFallback: Boolean = true
    ): DeviceLocationContext? {
        val maxAccuracyMeters = if (requirePrecise) {
            PRECISE_LOCATION_MAX_ACCURACY_METERS
        } else {
            USABLE_LOCATION_MAX_ACCURACY_METERS
        }
        peekPhoneBridgeContext(
            maxAgeMs = minOf(maxAgeMs, if (requirePrecise) PHONE_BRIDGE_PRECISE_MAX_AGE_MS else PHONE_BRIDGE_MAX_AGE_MS),
            maxAccuracyMeters = maxAccuracyMeters,
            allowApproximate = !requirePrecise
        )?.let { return it }

        peekCached(
            maxAgeMs = maxAgeMs,
            maxAccuracyMeters = maxAccuracyMeters,
            allowApproximate = !requirePrecise
        )?.let { return it }

        val hasFine = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        val hasCoarse = hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (!hasFine && !hasCoarse) return null

        val manager = locationManager
        val providers = manager?.let { buildProviders(hasFine, hasCoarse, it) }.orEmpty()

        var bestLastKnown: Location? = null
        providers.forEach { provider ->
            val candidate = runCatching { manager?.getLastKnownLocation(provider) }.getOrNull() ?: return@forEach
            bestLastKnown = selectBetter(bestLastKnown, candidate)
        }

        val freshLastKnown = bestLastKnown?.takeIf { isFreshAndPrecise(it) }
        if (freshLastKnown != null) {
            return remember(freshLastKnown)
        }

        var bestCurrent: Location? = null
        for (provider in providers) {
            val current = manager?.let { requestCurrentLocation(it, provider, timeoutMs) }
            if (current != null) {
                bestCurrent = selectBetter(bestCurrent, current)
            }
        }

        val preferredCurrent = bestCurrent?.takeIf {
            if (requirePrecise) isPrecise(it) else isUsable(it)
        }
        if (preferredCurrent != null) {
            return remember(preferredCurrent)
        }

        requestWifiTriangulatedLocation(considerIp = false)?.takeIf {
            isAcceptableContext(
                context = it,
                maxAccuracyMeters = maxAccuracyMeters,
                allowApproximate = false,
                maxAgeMs = maxAgeMs
            )
        }?.let { triangulated ->
            return remember(triangulated)
        }

        bestLastKnown?.takeIf {
            if (requirePrecise) isPrecise(it) else isUsable(it)
        }?.let { return remember(it) }

        if (allowApproximateFallback && !requirePrecise) {
            requestWifiTriangulatedLocation(considerIp = true)?.let { return remember(it) }
        }

        if (!allowApproximateFallback || requirePrecise) return null

        return requestApproximateNetworkLocation()?.let(::remember)
    }

    fun resolveNavigationBlocking(timeoutMs: Long = PRECISE_TIMEOUT_MS): DeviceLocationContext? {
        return resolveBlocking(
            maxAgeMs = PRECISE_MAX_AGE_MS,
            timeoutMs = timeoutMs,
            requirePrecise = true,
            allowApproximateFallback = false
        )
    }

    private fun buildProviders(
        hasFine: Boolean,
        hasCoarse: Boolean,
        manager: LocationManager
    ): List<String> {
        val providers = mutableListOf<String>()
        if ((hasFine || hasCoarse) && isProviderUsable(manager, LocationManager.FUSED_PROVIDER)) {
            providers += LocationManager.FUSED_PROVIDER
        }
        if (hasFine && isProviderUsable(manager, LocationManager.GPS_PROVIDER)) {
            providers += LocationManager.GPS_PROVIDER
        }
        if (hasCoarse && isProviderUsable(manager, LocationManager.NETWORK_PROVIDER)) {
            providers += LocationManager.NETWORK_PROVIDER
        }
        if (hasCoarse && isProviderUsable(manager, LocationManager.PASSIVE_PROVIDER)) {
            providers += LocationManager.PASSIVE_PROVIDER
        }
        return providers.distinct()
    }

    private fun isProviderUsable(manager: LocationManager, provider: String): Boolean {
        return runCatching { manager.allProviders.contains(provider) && manager.isProviderEnabled(provider) }
            .getOrElse { false }
    }

    private suspend fun requestCurrentLocation(
        manager: LocationManager,
        provider: String,
        timeoutMs: Long
    ): Location? {
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val signal = CancellationSignal()
                        continuation.invokeOnCancellation { signal.cancel() }
                        manager.getCurrentLocation(
                            provider,
                            signal,
                            ContextCompat.getMainExecutor(appContext)
                        ) { location ->
                            if (continuation.isActive) continuation.resume(location)
                        }
                    } else {
                        val listener = object : LocationListener {
                            override fun onLocationChanged(location: Location) {
                                manager.removeUpdates(this)
                                if (continuation.isActive) continuation.resume(location)
                            }

                            @Deprecated("Deprecated in Java")
                            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
                            override fun onProviderEnabled(provider: String) = Unit
                            override fun onProviderDisabled(provider: String) = Unit
                        }
                        continuation.invokeOnCancellation { manager.removeUpdates(listener) }
                        manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                    }
                } catch (security: SecurityException) {
                    Log.w(TAG, "Location permission lost for $provider: ${security.message}")
                    if (continuation.isActive) continuation.resume(null)
                } catch (error: Throwable) {
                    Log.w(TAG, "Current location request failed for $provider: ${error.message}")
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        }
    }

    private fun remember(location: Location): DeviceLocationContext {
        return remember(location.toDeviceLocationContext())
    }

    private fun remember(context: DeviceLocationContext): DeviceLocationContext {
        val previous = cached
        cached = if (shouldPreferLocation(context, previous)) context else previous
        return cached ?: context
    }

    private fun Location.toDeviceLocationContext(): DeviceLocationContext {
        return DeviceLocationContext(
            latitude = latitude,
            longitude = longitude,
            accuracyMeters = if (hasAccuracy()) accuracy else null,
            altitudeMeters = if (hasAltitude()) altitude else null,
            speedMps = if (hasSpeed()) speed else null,
            bearingDeg = if (hasBearing()) bearing else null,
            provider = provider,
            timestampMs = safeTime()
        )
    }

    private fun selectBetter(current: Location?, candidate: Location): Location {
        if (current == null) return candidate
        val timeDelta = candidate.safeTime() - current.safeTime()
        val candidateAccuracy = if (candidate.hasAccuracy()) candidate.accuracy else Float.MAX_VALUE
        val currentAccuracy = if (current.hasAccuracy()) current.accuracy else Float.MAX_VALUE
        return when {
            timeDelta > 120_000L -> candidate
            timeDelta < -120_000L -> current
            candidate.provider == LocationManager.GPS_PROVIDER && current.provider != LocationManager.GPS_PROVIDER &&
                candidateAccuracy <= currentAccuracy + 50f -> candidate
            candidate.provider == LocationManager.FUSED_PROVIDER && current.provider == LocationManager.NETWORK_PROVIDER &&
                candidateAccuracy <= currentAccuracy + 50f -> candidate
            candidateAccuracy + 10f < currentAccuracy -> candidate
            timeDelta > 0L && candidateAccuracy <= currentAccuracy + 50f -> candidate
            else -> current
        }
    }

    private fun Location.safeTime(): Long = time.takeIf { it > 0L } ?: System.currentTimeMillis()

    private fun isFreshAndPrecise(location: Location): Boolean {
        val ageMs = System.currentTimeMillis() - location.safeTime()
        val accuracy = if (location.hasAccuracy()) location.accuracy else Float.MAX_VALUE
        return ageMs <= FRESH_LOCATION_MAX_AGE_MS && accuracy <= FRESH_LOCATION_MAX_ACCURACY_METERS
    }

    private fun isUsable(location: Location): Boolean {
        val ageMs = System.currentTimeMillis() - location.safeTime()
        val accuracy = if (location.hasAccuracy()) location.accuracy else Float.MAX_VALUE
        return ageMs <= DEFAULT_MAX_AGE_MS && accuracy <= USABLE_LOCATION_MAX_ACCURACY_METERS
    }

    private fun isPrecise(location: Location): Boolean {
        val ageMs = System.currentTimeMillis() - location.safeTime()
        val accuracy = if (location.hasAccuracy()) location.accuracy else Float.MAX_VALUE
        return ageMs <= PRECISE_MAX_AGE_MS && accuracy <= PRECISE_LOCATION_MAX_ACCURACY_METERS
    }

    private fun shouldPreferLocation(
        candidate: DeviceLocationContext,
        current: DeviceLocationContext?
    ): Boolean {
        if (current == null) return true
        val timeDelta = candidate.timestampMs - current.timestampMs
        val candidateAccuracy = candidate.accuracyMeters ?: Float.MAX_VALUE
        val currentAccuracy = current.accuracyMeters ?: Float.MAX_VALUE
        val currentIsApproximate = current.provider == "ip_geolocation"
        val candidateIsTriangulated = candidate.provider == "wifi_geolocation" || candidate.provider == "network_geolocation"

        return when {
            currentIsApproximate && candidate.provider != "ip_geolocation" -> true
            timeDelta > 120_000L -> true
            timeDelta < -120_000L -> false
            candidate.provider == LocationManager.GPS_PROVIDER && current.provider != LocationManager.GPS_PROVIDER &&
                candidateAccuracy <= currentAccuracy + 25f -> true
            candidate.provider == LocationManager.FUSED_PROVIDER && current.provider == LocationManager.NETWORK_PROVIDER &&
                candidateAccuracy <= currentAccuracy + 25f -> true
            candidateAccuracy + 25f < currentAccuracy -> true
            candidateIsTriangulated && currentAccuracy > 1_000f && candidateAccuracy <= 250f -> true
            timeDelta > 0L && candidateAccuracy <= currentAccuracy + 50f -> true
            else -> false
        }
    }

    private fun isAcceptableContext(
        context: DeviceLocationContext,
        maxAccuracyMeters: Float,
        allowApproximate: Boolean,
        maxAgeMs: Long
    ): Boolean {
        val ageMs = System.currentTimeMillis() - context.timestampMs
        val provider = context.provider.orEmpty()
        val accuracy = context.accuracyMeters ?: Float.MAX_VALUE
        if (ageMs > maxAgeMs) return false
        if (!allowApproximate && provider == "ip_geolocation") return false
        return accuracy <= maxAccuracyMeters
    }

    private fun peekPhoneBridgeContext(
        maxAgeMs: Long,
        maxAccuracyMeters: Float,
        allowApproximate: Boolean
    ): DeviceLocationContext? {
        if (!preferences.phoneLocationBridgeEnabled) return null
        val context = preferences.getPhoneLocationBridgeContext() ?: return null
        return context.takeIf {
            isAcceptableContext(
                context = it,
                maxAccuracyMeters = maxAccuracyMeters,
                allowApproximate = allowApproximate,
                maxAgeMs = maxAgeMs
            )
        }
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED
    }

    private suspend fun requestWifiTriangulatedLocation(considerIp: Boolean): DeviceLocationContext? {
        val accessPoints = collectWifiAccessPoints()
        if (accessPoints.size < 2 && !considerIp) return null
        return when (val result = geolocationClient.locate(accessPoints, considerIp)) {
            is GoogleGeolocationClient.GeolocationResult.Success -> {
                Log.i(
                    TAG,
                    "Using Google geolocation fallback provider=${result.context.provider} acc=${result.context.accuracyMeters} aps=${accessPoints.size}"
                )
                result.context
            }
            is GoogleGeolocationClient.GeolocationResult.ApiKeyMissing -> null
            is GoogleGeolocationClient.GeolocationResult.Error -> {
                Log.w(TAG, "Google geolocation unavailable: ${result.message}")
                null
            }
        }
    }

    private fun collectWifiAccessPoints(): List<GoogleGeolocationClient.WifiAccessPoint> {
        val manager = wifiManager ?: return emptyList()
        val results = runCatching { manager.scanResults }.getOrNull().orEmpty()
        return results
            .asSequence()
            .filter { it.BSSID.isValidMacAddress() }
            .sortedByDescending { it.level }
            .take(MAX_WIFI_ACCESS_POINTS)
            .map { scan ->
                GoogleGeolocationClient.WifiAccessPoint(
                    macAddress = scan.BSSID,
                    signalStrength = scan.level,
                    channel = scan.frequency.takeIf { it > 0 }?.let(::frequencyToChannel),
                    ageMs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                        ((SystemClock.elapsedRealtimeNanos() - scan.timestamp) / 1_000_000L)
                            .toInt()
                            .coerceAtLeast(0)
                    } else {
                        null
                    }
                )
            }
            .toList()
    }

    private fun String.isValidMacAddress(): Boolean {
        if (!matches(Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}"))) return false
        val firstOctet = substring(0, 2).toIntOrNull(16) ?: return false
        return (firstOctet and 0x02) == 0
    }

    private fun frequencyToChannel(frequencyMhz: Int): Int {
        return when {
            frequencyMhz in 2412..2484 -> (frequencyMhz - 2407) / 5
            frequencyMhz in 5170..5885 -> (frequencyMhz - 5000) / 5
            else -> frequencyMhz
        }
    }

    private fun requestApproximateNetworkLocation(): DeviceLocationContext? {
        return runCatching {
            val conn =
                ActiveNetworkHttp.openConnection(appContext, "https://ipinfo.io/json").apply {
                    connectTimeout = 5_000
                    readTimeout = 5_000
                    requestMethod = "GET"
                }
            val code = conn.responseCode
            if (code !in 200..299) {
                conn.disconnect()
                return null
            }
            val body =
                conn.inputStream.use { input ->
                    BufferedReader(InputStreamReader(input, Charsets.UTF_8)).readText()
                }
            conn.disconnect()
            val json = JSONObject(body)
            val parts = json.optString("loc", "").split(',')
            if (parts.size != 2) return null
            val latitude = parts[0].toDoubleOrNull() ?: return null
            val longitude = parts[1].toDoubleOrNull() ?: return null
            val city = json.optString("city", "").trim()
            val region = json.optString("region", "").trim()
            val label = listOf(city, region).filter { it.isNotBlank() }.joinToString(", ")
            Log.i(TAG, "Using approximate IP location${if (label.isNotBlank()) " ($label)" else ""}: $latitude,$longitude")
            DeviceLocationContext(
                latitude = latitude,
                longitude = longitude,
                accuracyMeters = null,
                provider = "ip_geolocation",
                timestampMs = System.currentTimeMillis()
            )
        }.onFailure {
            Log.w(TAG, "Approximate network location unavailable: ${it.message}")
        }.getOrNull()
    }
}
