package com.trailmap.offline

import android.content.Context
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * One downloaded (or downloading) offline tile region, surfaced to the UI.
 *
 * [name] is read from the region's metadata JSON ({"name":"…"}); the raw [region] handle is kept
 * so the screen can delete it. [percent]/[complete] drive the progress UI.
 */
data class OfflineArea(
    val name: String,
    val percent: Int,
    val complete: Boolean,
    val completedTiles: Long,
    val requiredTiles: Long,
    val region: OfflineRegion,
)

/**
 * Offline tile packs for trailmap. Wraps MapLibre's OfflineManager to download named map regions
 * (the current view, or a preset metro/state bbox) for offline use, list them with live progress,
 * and delete them.
 *
 * MapLibre's offline downloader fetches the style over the HTTP stack — it can't read asset:// —
 * so [styleUrl] returns the hosted raster style JSON on the public repo's main branch. The remote
 * raster tile URLs referenced inside that style are what MapLibre downloads + caches.
 *
 * All MapLibre offline callbacks fire on the main thread, so the lambdas here (which set Compose
 * state) are safe to call directly from them.
 */
object OfflinePacks {
    private const val LIGHT_STYLE =
        "https://raw.githubusercontent.com/Pr0zak/trailmap/main/mobile/app/src/main/assets/osm_raster_style.json"
    private const val DARK_STYLE =
        "https://raw.githubusercontent.com/Pr0zak/trailmap/main/mobile/app/src/main/assets/carto_dark_style.json"

    private const val MAX_RETRIES = 4
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    /** Hosted raster style URL for the active theme (offline downloader needs http(s), not asset://). */
    fun styleUrl(dark: Boolean): String = if (dark) DARK_STYLE else LIGHT_STYLE

    /** Raise the per-region tile cap before any download (default is 6000, too small for state-wide). */
    fun ensureLimit(context: Context) {
        OfflineManager.getInstance(context).setOfflineMapboxTileCountLimit(50_000L)
    }

    /** Decode the region name from its {"name":"…"} metadata, or a fallback if unparseable. */
    private fun nameOf(region: OfflineRegion): String =
        runCatching {
            val json = String(region.metadata, Charsets.UTF_8)
            // Minimal extraction — metadata is always our own {"name":"…"} blob.
            Regex("\"name\"\\s*:\\s*\"(.*?)\"").find(json)?.groupValues?.get(1)
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Offline area"

    private fun percentOf(status: OfflineRegionStatus): Int {
        if (status.isComplete) return 100
        val required = max(1L, status.requiredResourceCount)
        return (100.0 * status.completedResourceCount / required).roundToInt().coerceIn(0, 100)
    }

    /**
     * List every offline region with its current status, built into [OfflineArea]s. Because each
     * region's status is itself an async callback, we fan out and call [onResult] once all statuses
     * have come back. [onResult] is invoked on the main thread.
     */
    fun list(context: Context, onResult: (List<OfflineArea>) -> Unit) {
        OfflineManager.getInstance(context).listOfflineRegions(
            object : OfflineManager.ListOfflineRegionsCallback {
                override fun onList(offlineRegions: Array<OfflineRegion>?) {
                    val regions = offlineRegions?.toList().orEmpty()
                    if (regions.isEmpty()) {
                        onResult(emptyList())
                        return
                    }
                    val results = arrayOfNulls<OfflineArea>(regions.size)
                    var remaining = regions.size
                    regions.forEachIndexed { idx, region ->
                        region.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
                            override fun onStatus(status: OfflineRegionStatus?) {
                                results[idx] = if (status != null) {
                                    OfflineArea(
                                        name = nameOf(region),
                                        percent = percentOf(status),
                                        complete = status.isComplete,
                                        completedTiles = status.completedTileCount,
                                        requiredTiles = status.requiredResourceCount,
                                        region = region,
                                    )
                                } else {
                                    OfflineArea(nameOf(region), 0, false, 0L, 0L, region)
                                }
                                if (--remaining == 0) onResult(results.filterNotNull())
                            }

                            override fun onError(error: String?) {
                                // Still surface the region so the user can delete it.
                                results[idx] = OfflineArea(nameOf(region), 0, false, 0L, 0L, region)
                                if (--remaining == 0) onResult(results.filterNotNull())
                            }
                        })
                    }
                }

                override fun onError(error: String) {
                    onResult(emptyList())
                }
            },
        )
    }

    /**
     * Download a named region over an explicit bbox at the given zoom range. [onUpdate] receives
     * human-readable status strings ("Downloading… 42%", "Ready (N tiles)", error text) on the
     * main thread.
     */
    fun downloadBounds(
        context: Context,
        name: String,
        styleUrl: String,
        north: Double,
        south: Double,
        east: Double,
        west: Double,
        minZoom: Double,
        maxZoom: Double,
        onUpdate: (String) -> Unit,
    ) {
        try {
            ensureLimit(context)
            val bounds = LatLngBounds.from(north, east, south, west)
            val pixelRatio = context.resources.displayMetrics.density
            val definition = OfflineTilePyramidRegionDefinition(
                styleUrl,
                bounds,
                minZoom,
                maxZoom,
                pixelRatio,
            )
            val metadata = """{"name":"$name"}""".toByteArray(Charsets.UTF_8)

            onUpdate("Starting download…")

            OfflineManager.getInstance(context).createOfflineRegion(
                definition,
                metadata,
                object : OfflineManager.CreateOfflineRegionCallback {
                    override fun onError(error: String) {
                        onUpdate("Download failed: $error")
                    }

                    override fun onCreate(region: OfflineRegion) {
                        observeAndStart(region, onUpdate)
                    }
                },
            )
        } catch (e: Exception) {
            onUpdate("Download error: ${e.message}")
        }
    }

    /** Resume a stalled/incomplete region (manual "Retry" in the list). */
    fun retry(area: OfflineArea, onUpdate: (String) -> Unit) {
        onUpdate("Resuming…")
        observeAndStart(area.region, onUpdate)
    }

    /**
     * Observe a region's progress and start it, with **auto-retry on transient errors**:
     * a network error re-activates the download after a backoff (up to [MAX_RETRIES]); any tile
     * progress resets the budget. After the budget is spent it stops and leaves the area resumable
     * via [retry]. Tile-count-limit and completion are terminal.
     */
    private fun observeAndStart(region: OfflineRegion, onUpdate: (String) -> Unit) {
        var lastPercent = -1
        var lastCompleted = -1L
        var retries = 0
        region.setObserver(object : OfflineRegion.OfflineRegionObserver {
            override fun onStatusChanged(status: OfflineRegionStatus) {
                if (status.completedTileCount > lastCompleted) {
                    lastCompleted = status.completedTileCount
                    retries = 0 // making progress → refill the retry budget
                }
                if (status.isComplete) {
                    onUpdate("Ready (${status.completedTileCount} tiles)")
                    region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                    return
                }
                val percent = percentOf(status)
                if (percent != lastPercent) {
                    lastPercent = percent
                    onUpdate("Downloading… $percent%")
                }
            }

            override fun onError(error: OfflineRegionError) {
                if (retries < MAX_RETRIES) {
                    retries++
                    onUpdate("Network error — retrying ($retries/$MAX_RETRIES)…")
                    // exponential-ish backoff; re-activating resumes any tiles that failed.
                    handler.postDelayed(
                        { region.setDownloadState(OfflineRegion.STATE_ACTIVE) },
                        2000L * retries,
                    )
                } else {
                    onUpdate("Download failed (${error.reason}) — tap Retry to resume")
                    region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                }
            }

            override fun mapboxTileCountLimitExceeded(limit: Long) {
                onUpdate("Area too large (over $limit-tile limit) — pick a smaller area")
                region.setDownloadState(OfflineRegion.STATE_INACTIVE)
            }
        })
        region.setDownloadState(OfflineRegion.STATE_ACTIVE)
    }

    /** Delete one region (and its cached tiles). [onDone] fires on the main thread when finished. */
    fun delete(area: OfflineArea, onDone: () -> Unit) {
        area.region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
            override fun onDelete() = onDone()
            override fun onError(error: String) = onDone()
        })
    }
}
