package com.trailmap.offline

import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Offline tile packs for trailmap. Wraps MapLibre's OfflineManager to download the map area
 * that's currently on screen for offline use.
 *
 * trailmap's basemap is a RASTER style loaded from an asset:// URI (e.g.
 * asset://osm_raster_style.json). OfflineTilePyramidRegionDefinition takes the style URI string,
 * so we pass `styleUri` straight through — the caller hands us the active asset style. The actual
 * map imagery comes from the remote raster tile servers referenced inside that style JSON, and
 * those remote tiles are what MapLibre fetches and caches into its offline store here.
 */
object OfflinePacks {
    /**
     * Download the currently-visible map area for offline use.
     *
     * All MapLibre offline callbacks fire on the main thread, so [onStatus] (which shows a Toast
     * in MapScreen) is safe to call directly from them.
     */
    fun downloadVisible(
        context: android.content.Context,
        map: MapLibreMap,
        styleUri: String,
        onStatus: (String) -> Unit,
    ) {
        try {
            // The area on screen right now.
            val bounds = map.projection.visibleRegion.latLngBounds

            // Don't download the whole world: start one zoom below the current camera (floored at
            // z10), and go down to z15 for usable trail detail.
            val minZoom = max(10.0, map.cameraPosition.zoom - 1.0)
            val maxZoom = 15.0
            val pixelRatio = context.resources.displayMetrics.density

            // The offline downloader can't fetch an asset:// style (it routes style fetches through
            // the HTTP stack). Copy the bundled asset style to a real file and hand it a file:// URI;
            // the remote tile URLs referenced inside the style are then downloaded + cached.
            val definition = OfflineTilePyramidRegionDefinition(
                resolveStyleUri(context, styleUri),
                bounds,
                minZoom,
                maxZoom,
                pixelRatio,
            )

            // Name the pack by its center + a rough timestamp so packs are distinguishable.
            val center = bounds.center
            val name = "trailmap-${"%.4f".format(center.latitude)},${"%.4f".format(center.longitude)}" +
                "-${System.currentTimeMillis()}"
            val metadata = """{"name":"$name"}""".toByteArray(Charsets.UTF_8)

            onStatus("Starting offline download…")

            OfflineManager.getInstance(context).createOfflineRegion(
                definition,
                metadata,
                object : OfflineManager.CreateOfflineRegionCallback {
                    override fun onError(error: String) {
                        onStatus("Offline download failed: $error")
                    }

                    override fun onCreate(region: OfflineRegion) {
                        // Track the last reported percent so we only surface occasional updates
                        // (a Toast per status change would spam the UI).
                        var lastPercent = -1

                        region.setObserver(object : OfflineRegion.OfflineRegionObserver {
                            override fun onStatusChanged(status: OfflineRegionStatus) {
                                if (status.isComplete) {
                                    onStatus("Offline area ready (${status.completedResourceCount} tiles)")
                                    region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                                    return
                                }
                                val required = max(1L, status.requiredResourceCount)
                                val percent =
                                    (100.0 * status.completedResourceCount / required).roundToInt()
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    onStatus("Downloading offline area… $percent%")
                                }
                            }

                            override fun onError(error: OfflineRegionError) {
                                onStatus("Offline download error: ${error.reason}")
                            }

                            override fun mapboxTileCountLimitExceeded(limit: Long) {
                                onStatus("Area too large for offline ($limit-tile limit) — zoom in")
                            }
                        })

                        region.setDownloadState(OfflineRegion.STATE_ACTIVE)
                    }
                },
            )
        } catch (e: Exception) {
            onStatus("Offline download error: ${e.message}")
        }
    }

    /** asset://name → copy to filesDir and return a file:// URI the offline downloader can read. */
    private fun resolveStyleUri(context: android.content.Context, styleUri: String): String {
        if (!styleUri.startsWith("asset://")) return styleUri
        val name = styleUri.removePrefix("asset://")
        val out = File(context.filesDir, name)
        context.assets.open(name).use { input -> out.outputStream().use { input.copyTo(it) } }
        return "file://${out.absolutePath}"
    }
}
