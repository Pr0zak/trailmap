package com.trailmap.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import android.Manifest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.tasks.await

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
        try {
            val client = LocationServices.getFusedLocationProviderClient(context)
            val loc = client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null).await()
                ?: client.lastLocation.await()
            if (loc != null) return GeoPoint(loc.latitude, loc.longitude)
        } catch (_: Exception) {
            // Play Services missing (e.g. non-Google emulator image) → fall through.
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
        // Default test location: Kansas City (downtown / Crown Center area).
        val KANSAS_CITY = GeoPoint(39.0997, -94.5786)
    }
}
