package com.trailmap.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import android.Manifest
import android.location.Location
import android.location.LocationListener
import android.os.Looper
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/** Wraps fused location with a Kansas City fallback so the app is testable anywhere. */
class Locator(private val context: Context) {

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** The device location, or Kansas City when there is no permission or no fix. */
    suspend fun current(): GeoPoint = fix() ?: KANSAS_CITY

    /** The device location, or null when there is no permission or no fix. */
    @SuppressLint("MissingPermission")
    suspend fun fix(): GeoPoint? {
        if (!hasPermission()) return null
        // 1) Google Play fused location (best on real devices with Play Services).
        //    Bounded: getCurrentLocation actively waits for a fresh fix, and on a cold GPS
        //    that can be tens of seconds. Nothing else starts until this returns — the first
        //    trail load included — and for choosing which area of trails to show, a slightly
        //    stale last-known fix is worth far more than a precise one that arrives late.
        val cts = CancellationTokenSource()
        try {
            val client = LocationServices.getFusedLocationProviderClient(context)
            val loc = withTimeoutOrNull(LOCATE_TIMEOUT_MS) {
                client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cts.token).await()
                    ?: client.lastLocation.await()
            }
            if (loc != null) return GeoPoint(loc.latitude, loc.longitude)
        } catch (_: Exception) {
            // Play Services missing (e.g. non-Google emulator image) → fall through.
        } finally {
            cts.cancel()
        }
        // 2) Platform LocationManager — works without Play Services and honors
        //    `adb emu geo fix` on the emulator and GPS/network fixes on real devices.
        try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            val best = providers.mapNotNull { p -> runCatching { lm?.getLastKnownLocation(p) }.getOrNull() }
                .maxByOrNull { it.time }
            if (best != null) return GeoPoint(best.latitude, best.longitude)
        } catch (_: Exception) {
            // ignore
        }
        // 3) No fix; current() falls back to Kansas City so the app is always usable.
        return null
    }

    /**
     * Live high-accuracy (GPS) fixes for the map's location dot, while collected. Play
     * Services' fused provider first; if it is missing or refuses, the platform GPS and
     * network providers. Emits nothing without permission.
     */
    @SuppressLint("MissingPermission")
    fun updates(): Flow<Location> = callbackFlow {
        if (!hasPermission()) { awaitClose {}; return@callbackFlow }
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val platform = LocationListener { trySend(it) }
        var platformOn = false
        fun usePlatform() {
            if (platformOn || lm == null) return
            platformOn = true
            for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                runCatching { lm.requestLocationUpdates(p, UPDATE_MS, 0f, platform, Looper.getMainLooper()) }
            }
        }
        val fused = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { trySend(it) }
            }
        }
        val client = runCatching { LocationServices.getFusedLocationProviderClient(context) }.getOrNull()
        if (client == null) usePlatform() else {
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, UPDATE_MS)
                .setMinUpdateIntervalMillis(UPDATE_MS / 2)
                .build()
            try {
                client.requestLocationUpdates(request, fused, Looper.getMainLooper())
                    .addOnFailureListener { usePlatform() }
            } catch (_: Exception) {
                usePlatform()
            }
        }
        awaitClose {
            runCatching { client?.removeLocationUpdates(fused) }
            if (platformOn) runCatching { lm?.removeUpdates(platform) }
        }
    }

    companion object {
        /** How often the location dot asks for a fix. */
        private const val UPDATE_MS = 1_000L

        /** Cap on how long a location fix may hold up the first trail load. */
        private const val LOCATE_TIMEOUT_MS = 4000L

        // Default test location: Kansas City (downtown / Crown Center area).
        val KANSAS_CITY = GeoPoint(39.0997, -94.5786)
    }
}
