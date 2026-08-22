package com.trailmap.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import android.Manifest
import com.google.android.gms.location.LocationServices
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

    @SuppressLint("MissingPermission")
    suspend fun current(): GeoPoint {
        if (!hasPermission()) return KANSAS_CITY
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
        // 3) Last resort: Kansas City, so the app is always usable for testing.
        return KANSAS_CITY
    }

    companion object {
        /** Cap on how long a location fix may hold up the first trail load. */
        private const val LOCATE_TIMEOUT_MS = 4000L

        // Default test location: Kansas City (downtown / Crown Center area).
        val KANSAS_CITY = GeoPoint(39.0997, -94.5786)
    }
}
