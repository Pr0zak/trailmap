package com.trailmap

import android.app.Application
import android.content.Context
import com.trailmap.data.DiagLog
import com.trailmap.data.OverpassClient
import com.trailmap.data.Prefs
import org.maplibre.android.MapLibre

class TrailmapApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // MapLibre 11 requires init before any MapView is inflated. No API key needed.
        MapLibre.getInstance(this)
    }

    companion object {
        private var client: OverpassClient? = null

        /**
         * The one Overpass client for the process.
         *
         * It has to outlive the ViewModel: a diagnostics log from a Pixel 9a showed the
         * ViewModel's init block running twice at launch, i.e. two instances, which meant two
         * separate in-memory parse caches and the same area deserialised twice. Holding the
         * client here means the parse cache, the learned mirror and the rate-limit state are
         * shared no matter how many times the Activity is recreated.
         */
        @Synchronized
        fun overpass(context: Context): OverpassClient {
            val app = context.applicationContext
            return client ?: OverpassClient(app.cacheDir, Prefs(app)).also {
                client = it
                DiagLog.log("app", "overpass client created")
            }
        }
    }
}
