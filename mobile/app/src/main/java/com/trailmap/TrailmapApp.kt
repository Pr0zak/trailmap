package com.trailmap

import android.app.Application
import org.maplibre.android.MapLibre

class TrailmapApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // MapLibre 11 requires init before any MapView is inflated. No API key needed.
        MapLibre.getInstance(this)
    }
}
