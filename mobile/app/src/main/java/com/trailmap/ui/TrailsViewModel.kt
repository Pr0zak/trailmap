package com.trailmap.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.trailmap.data.DiagLog
import com.trailmap.data.ElevationClient
import com.trailmap.data.ElevationProfile
import com.trailmap.data.Geo
import com.trailmap.data.GeoPoint
import com.trailmap.data.Locator
import com.trailmap.data.OverpassClient
import com.trailmap.data.Prefs
import com.trailmap.data.Ride
import com.trailmap.data.RideTrail
import com.trailmap.data.SurfaceType
import com.trailmap.data.ViewBounds
import com.trailmap.data.Trail
import com.trailmap.data.TrailSystem
import com.trailmap.data.TrailsResult
import com.trailmap.data.UseType
import com.trailmap.data.clusterTrailSystems
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Map data mode: all trails (paved/gravel/dirt) vs mountain-bike trails only. */
enum class MapMode { ALL, MTB }

/** Basemap theme: follow the system, or force light/dark independent of it. */
enum class MapTheme { SYSTEM, LIGHT, DARK }

/** A one-shot camera move request: where to go, and at what zoom (null = keep current). */
data class CameraTarget(val point: GeoPoint, val zoom: Double? = null)

data class TrailsUiState(
    val center: GeoPoint = Locator.KANSAS_CITY,
    /** The radius chip: how much trail the user wants around them. A floor for any fetch. */
    val radiusMeters: Int = (5 * 1609.344).toInt(),
    val mode: MapMode = MapMode.ALL,
    val loading: Boolean = false,
    val error: String? = null,
    val trails: List<Trail> = emptyList(),
    /**
     * Bumped every time [trails] is replaced. Lets the UI key effects on a cheap Int instead
     * of structurally comparing a List<Trail> — each Trail carries its full polyline geometry,
     * so `==` on that list walks tens of thousands of points.
     */
    val trailsVersion: Int = 0,
    val selectedSurfaces: Set<SurfaceType> =
        setOf(SurfaceType.PAVED, SurfaceType.GRAVEL, SurfaceType.DIRT, SurfaceType.UNKNOWN),
    val selectedUses: Set<UseType> = setOf(UseType.WALK, UseType.BIKE),
    val minLengthMiles: Double = 0.0,
    val query: String = "",
    val savedIds: Set<String> = emptySet(),
    val showSavedOnly: Boolean = false,
    val mapTheme: MapTheme = MapTheme.SYSTEM,
    /** When set, the map recenters here (e.g. tapping a trail-system header); consume after use. */
    val focusTarget: CameraTarget? = null,
    /** The trail the user tapped on the map (peek card + highlight); null = nothing selected. */
    val selectedTrailId: String? = null,
    /** User-built rides (named trail collections with summed length). */
    val rides: List<Ride> = emptyList(),
    /** Last map viewport, for "download the current view" offline. */
    val viewBounds: ViewBounds? = null,
    /** Refetch trails automatically when the map is panned off the loaded area. */
    val autoLoadOnPan: Boolean = true,
    /** Center [trails] were actually fetched around (null until the first load lands). */
    val loadedCenter: GeoPoint? = null,
    /** Radius [trails] were actually fetched with. */
    val loadedRadiusMeters: Int = 0,
    /** The camera has moved off the area [trails] cover — drives the "Search this area" button. */
    val viewportStale: Boolean = false,
    /**
     * False when the visible area is wider than any fetch we're willing to make automatically.
     * The map offers a deliberate "Search this area" then, instead of silently fetching a
     * circle that would still leave the screen edges blank.
     */
    val canAutoCover: Boolean = true,
    /** Set when the trails on screen came from an expired cache because the network failed. */
    val servingStale: Boolean = false,
    /** Progress of the trail-data download that accompanies an offline area; null when idle. */
    val trailPrefetch: String? = null,
) {
    val radiusMiles: Double get() = radiusMeters / 1609.344

    /**
     * Named/labeled trails passing the active filters + name search, nearest first.
     *
     * Computed once per state instance (`by lazy`, not `get()`): the map and the list both
     * read it, and it used to be re-filtered and re-sorted on every recomposition — including
     * the ones triggered by every camera idle.
     */
    val filtered: List<Trail> by lazy {
        trails
            // Only show trails that carry an OSM name — drop the many unnamed connector paths.
            .filter { it.name != "Unnamed path" }
            .filter { it.surface in selectedSurfaces }
            .filter { selectedUses.isEmpty() || it.uses.any { u -> u in selectedUses } }
            .filter { it.lengthMiles >= minLengthMiles }
            .filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
            .filter { !showSavedOnly || it.id in savedIds }
            .sortedBy { it.distanceMeters }
    }

    /**
     * [filtered] narrowed to the radius chip. The map deliberately draws everything cached —
     * a wider cached circle means panning inside it needs no refetch — but the Trails list
     * has to keep the chip honest and not pad itself with the next town over.
     */
    val listed: List<Trail> by lazy { filtered.filter { it.distanceMeters <= radiusMeters } }

    /**
     * In MTB mode, [listed] grouped into nearby trail systems; empty otherwise.
     * Also `by lazy` — [clusterTrailSystems] is O(n²) in haversine distances, and the list
     * screen reads this property more than once per frame.
     */
    val systems: List<TrailSystem> by lazy {
        if (mode == MapMode.MTB) clusterTrailSystems(listed) else emptyList()
    }

    /**
     * Cheap identity for "what [filtered] would produce" — a short String built from the data
     * version plus every filter input. Compose effects key on this instead of on [filtered]
     * itself, so an unrelated state change (a new viewport, say) doesn't force a deep compare
     * of the whole trail geometry.
     */
    val filterKey: String by lazy {
        buildString {
            append(trailsVersion).append('|')
            selectedSurfaces.map { it.ordinal }.sorted().forEach { append(it) }
            append('|')
            selectedUses.map { it.ordinal }.sorted().forEach { append(it) }
            append('|').append(minLengthMiles)
            append('|').append(query.trim().lowercase())
            append('|').append(showSavedOnly)
            // savedIds only changes what's filtered while the "saved only" toggle is on.
            append('|').append(if (showSavedOnly) savedIds.sorted().joinToString(",") else "")
        }
    }

    fun isSaved(id: String): Boolean = id in savedIds
}

class TrailsViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = Prefs(app)
    private val overpass = OverpassClient(app.cacheDir, prefs)
    private val elevation = ElevationClient()
    private val locator = Locator(app)

    private val _state = MutableStateFlow(
        TrailsUiState(
            radiusMeters = DEFAULT_ALL_RADIUS,
            savedIds = prefs.savedIds(),
            mapTheme = runCatching { MapTheme.valueOf(prefs.mapTheme()) }.getOrDefault(MapTheme.SYSTEM),
            rides = prefs.rides(),
            autoLoadOnPan = prefs.autoLoadOnPan(),
        ),
    )
    val state: StateFlow<TrailsUiState> = _state.asStateFlow()

    // Elevation profiles cached per trail id (lazy-loaded when a detail screen opens).
    private val _profiles = MutableStateFlow<Map<String, ElevationProfile>>(emptyMap())
    val profiles: StateFlow<Map<String, ElevationProfile>> = _profiles.asStateFlow()

    init {
        // First line of any shared log: which build and which device produced it.
        DiagLog.log(
            "app",
            "trailmap ${com.trailmap.BuildConfig.VERSION_NAME} on " +
                "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} " +
                "(Android ${android.os.Build.VERSION.RELEASE})",
        )
    }

    /** The in-flight trail fetch, cancelled whenever a newer one starts. */
    private var loadJob: Job? = null

    /** The pending debounced pan reload. */
    private var panJob: Job? = null

    /** The in-progress offline trail-data download. */
    private var prefetchJob: Job? = null

    /** Monotonic load counter — a response whose sequence is stale never reaches the UI. */
    private var loadSeq = 0

    /** Center and radius of the load currently running, so a camera idle doesn't duplicate it. */
    private var pendingCenter: GeoPoint? = null
    private var pendingRadius = 0

    /** Guards the one-time startup locate+load, for this ViewModel's lifetime. */
    private var bootstrapped = false

    /**
     * Where the camera last settled. A plain field, not UI state: it is written on every
     * camera idle and must not cause a recomposition. The map reads it when it is recreated
     * (a tab switch disposes the composition) so returning to the Map tab restores the exact
     * pan and zoom instead of jumping back to the device location.
     */
    var lastCamera: CameraTarget? = null
        private set

    fun trailById(id: String): Trail? = _state.value.trails.firstOrNull { it.id == id }

    fun hasLocationPermission(): Boolean = locator.hasPermission()

    /**
     * One-time startup: centre on the device (Kansas City fallback) and load, cache-first.
     * Idempotent, and the guard lives here rather than in the composition — MapScreen is a
     * NavHost destination, so switching to the Trails tab and back would otherwise re-run it
     * and re-download the area on every bounce.
     */
    fun bootstrap() {
        if (bootstrapped) return
        bootstrapped = true
        viewModelScope.launch {
            val here = locator.current()
            _state.update { it.copy(center = here, focusTarget = CameraTarget(here, DEFAULT_ZOOM)) }
            load(here, initialFetchRadius())
        }
    }

    /**
     * Radius for a load that isn't driven by a camera idle. It has to match what the camera
     * will ask for a moment later, or the first thing the map does on launch is supersede its
     * own bootstrap fetch — which is exactly what the diagnostics log caught it doing.
     */
    private fun initialFetchRadius(): Int {
        val s = _state.value
        return if (s.mode == MapMode.MTB) s.radiusMeters else maxOf(MIN_FETCH_RADIUS, s.radiusMeters)
    }

    /**
     * The my-location FAB: recentre on the device and refresh from the network. This is the
     * only path that bypasses the cache — an explicit tap is the one time the user is asking
     * for current data rather than fast data.
     */
    fun recenterOnMe() {
        bootstrapped = true
        viewModelScope.launch {
            val here = locator.current()
            // Move the camera explicitly — panning no longer follows [center], so an explicit
            // recentre has to ask for the move.
            _state.update { it.copy(center = here, focusTarget = CameraTarget(here, DEFAULT_ZOOM)) }
            load(here, initialFetchRadius(), force = true)
        }
    }

    fun load(
        center: GeoPoint = _state.value.center,
        radiusMeters: Int = _state.value.radiusMeters,
        force: Boolean = false,
    ) {
        val mtb = _state.value.mode == MapMode.MTB
        // Supersede any load already running: rapid pans would otherwise stack up several
        // multi-megabyte downloads and let an older one land last.
        loadJob?.cancel()
        val seq = ++loadSeq
        pendingCenter = center
        pendingRadius = radiusMeters
        loadJob = viewModelScope.launch {
            val started = android.os.SystemClock.elapsedRealtime()
            DiagLog.log("load", "start r=$radiusMeters force=$force mode=${_state.value.mode}")
            _state.update { it.copy(error = null, center = center) }
            // Hold the spinner back briefly: an area that's already parsed in memory comes
            // back in well under this, and flashing a pill for it reads as churn.
            val spinner = launch {
                delay(SPINNER_DELAY_MS)
                if (seq == loadSeq) _state.update { it.copy(loading = true) }
            }
            try {
                // Overpass answers a momentary overload with a 502/504 rather than a queue.
                // One retry turns most of those into a successful load instead of an error
                // banner the user can only clear by panning somewhere else.
                var attempt = 0
                var fetched: TrailsResult? = null
                var failure: Exception? = null
                while (attempt < MAX_ATTEMPTS) {
                    try {
                        fetched = overpass.fetchTrails(center, radiusMeters, mtb = mtb, forceRefresh = force)
                        failure = null
                        break
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failure = e
                        attempt++
                        // Retrying something that just spent the full timeout only doubles the
                        // wait; the same is true of a mirror that told us to back off.
                        if (attempt >= MAX_ATTEMPTS || e.isRateLimit || e.isTimeout) break
                        delay(RETRY_DELAY_MS)
                    }
                }
                val result = fetched ?: throw (failure ?: Exception("Failed to load trails"))
                val trails = result.trails
                DiagLog.log(
                    "load",
                    "done in ${android.os.SystemClock.elapsedRealtime() - started} ms, " +
                        "${trails.size} trails, covers ${result.servedRadius} m",
                )
                spinner.cancel()
                if (seq != loadSeq) return@launch // a newer load already won
                _state.update { s ->
                    s.copy(
                        loading = false,
                        trails = trails,
                        trailsVersion = s.trailsVersion + 1,
                        // The circle the data actually covers, which is not always the one
                        // asked for: a cached pull can answer a nearby request. Recording the
                        // request instead made the app think it held 5 mi when it held 15, and
                        // refetch far sooner than it needed to.
                        loadedCenter = result.servedCenter,
                        loadedRadiusMeters = result.servedRadius,
                        viewportStale = false,
                        servingStale = overpass.lastServedStale,
                        // Keep the peek card open only if the tapped trail survived the reload.
                        selectedTrailId = s.selectedTrailId?.takeIf { id -> trails.any { it.id == id } },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                spinner.cancel()
                if (seq != loadSeq) return@launch
                // Keep the trails already on screen; only surface the error.
                DiagLog.log("load", "failed after ${android.os.SystemClock.elapsedRealtime() - started} ms: ${e.message}")
                _state.update { it.copy(loading = false, error = loadErrorText(e, it.trails.isNotEmpty())) }
            } finally {
                if (seq == loadSeq) pendingCenter = null
            }
        }
    }

    fun setRadius(meters: Int) {
        if (_state.value.radiusMeters == meters) return // re-tapping the active chip is a no-op
        _state.update { it.copy(radiusMeters = meters) }
        // The chip narrows the Trails list, it no longer decides what gets downloaded. So it
        // only needs a fetch when it asks to list more than the loaded data actually covers.
        val s = _state.value
        if (meters > s.loadedRadiusMeters) load(s.center, maxOf(meters, MIN_FETCH_RADIUS))
    }

    /** Radius selector in miles. Must agree exactly with the defaults, or re-selecting the
     *  chip that is already active counts as a change and refetches. */
    fun setRadiusMiles(miles: Int) = setRadius(milesToMeters(miles))

    /** Switch ALL ↔ MTB. MTB defaults to a wide 25-mi radius; ALL to ~5 mi. */
    fun setMode(mode: MapMode) {
        if (_state.value.mode == mode) return
        val radius = if (mode == MapMode.MTB) milesToMeters(25) else DEFAULT_ALL_RADIUS
        _state.update { it.copy(mode = mode, radiusMeters = radius, selectedTrailId = null) }
        load(_state.value.center, initialFetchRadius())
    }

    // --- Map viewport → trail loading ---------------------------------------

    /**
     * Called when the map camera settles. Records the viewport (for offline downloads) and,
     * when auto-load is on, schedules a debounced refetch once the view has drifted off the
     * area the current trails were fetched for.
     *
     * The drift gate is what keeps this off the public Overpass API's back: a small nudge
     * reuses what's already loaded, and only a pan of more than [PAN_RELOAD_FRACTION] of the
     * loaded radius triggers a new query. Very zoomed-out views don't auto-load at all —
     * an 8 km circle in the middle of a state-wide view isn't worth the download, so the UI
     * offers a manual "Search this area" instead.
     */
    fun onCameraIdle(bounds: ViewBounds, center: GeoPoint) {
        val prev = _state.value
        // Ignore anything below world-region zoom: that is MapLibre's uninitialised camera,
        // not a place the user chose, and recording it would corrupt the restore position.
        if (bounds.zoom >= MIN_RECORDABLE_ZOOM) lastCamera = CameraTarget(center, bounds.zoom)
        val viewR = viewRadiusMeters(bounds, center)
        val fetchR = fetchRadiusFor(prev, viewR)
        val stale = needsRefetch(prev, viewR, center, fetchR)
        // Whether a fetch this size can fill the screen. This only drives a caption now —
        // it must never suppress the load. Refusing to fetch at a wide zoom left the user
        // staring at a blank metro-wide map, which is strictly worse than partial coverage.
        val canCover = fetchR >= viewR * COVER_RATIO
        _state.update { it.copy(viewBounds = bounds, viewportStale = stale, canAutoCover = canCover) }
        if (stale) {
            DiagLog.log(
                "camera",
                "idle z=%.1f screen=%.0f m want=%d m have=%d m → refetch".format(
                    bounds.zoom, viewR, fetchR, prev.loadedRadiusMeters,
                ),
            )
        }

        if (!stale || !prev.autoLoadOnPan || bounds.zoom < HARD_ZOOM_FLOOR) return
        panJob?.cancel()
        panJob = viewModelScope.launch {
            // Revisiting an area already parsed in memory should feel immediate, so the
            // ride-out-the-flick debounce only applies when we'd actually hit the network.
            val warm = overpass.isWarm(center, fetchR, mtb = prev.mode == MapMode.MTB)
            delay(if (warm) PAN_DEBOUNCE_WARM_MS else PAN_DEBOUNCE_MS)
            load(center, fetchR)
        }
    }

    private val Throwable.isRateLimit: Boolean get() = message?.contains("429") == true

    private val Throwable.isTimeout: Boolean
        get() = this is java.io.InterruptedIOException || this is java.net.SocketTimeoutException

    /**
     * A failure message that says what actually happened. "Rate-limited" and "the server is
     * momentarily busy" are different problems with different answers, and claiming the first
     * when it was the second sends the user off to wait for nothing.
     */
    private fun loadErrorText(e: Exception, haveTrails: Boolean): String = when {
        e.isRateLimit && haveTrails -> "OpenStreetMap is rate-limiting — showing cached trails"
        e.isRateLimit -> "OpenStreetMap is rate-limiting — try again shortly"
        haveTrails -> "Couldn't refresh trails — showing what's cached"
        else -> "Couldn't reach OpenStreetMap. Check your connection and try again."
    }

    /** Half-diagonal of the visible rectangle in metres — how big the screen is on the ground. */
    private fun viewRadiusMeters(b: ViewBounds, center: GeoPoint): Double =
        Geo.haversineMeters(center, GeoPoint(b.north, b.east))

    /**
     * Radius the next fetch should use.
     *
     * The radius chips are a floor, not a ceiling: they say how much trail the user wants
     * around them, and zooming in must never quietly take data away from the Trails list.
     * What the viewport adds is a growth path — when the screen is wider than the chip, fetch
     * enough to fill it, snapped onto a coarse ladder so that panning around one area keeps
     * landing on a small reusable set of cache keys rather than minting a file per pan.
     *
     * MTB mode opts out. "Systems within 25 miles" is a search radius, not a description of
     * the screen, and deriving it from the viewport would gut the feature.
     */
    private fun fetchRadiusFor(s: TrailsUiState, viewRadius: Double): Int {
        if (s.mode == MapMode.MTB) return s.radiusMeters
        val want = viewRadius * FETCH_MARGIN
        val stepped = RADIUS_LADDER.firstOrNull { it >= want } ?: MAX_AUTO_RADIUS
        // Floor is MIN_FETCH_RADIUS, not the chip. The chip says how much the user wants
        // *listed*; how much to download is a different question, and fetching only 5 mi
        // meant a single flick-pan left the loaded circle and paid for another round trip.
        // A named-way pull at this radius is ~1.5 MB and covers a lot of panning.
        return stepped.coerceIn(maxOf(MIN_FETCH_RADIUS, s.radiusMeters), MAX_AUTO_RADIUS)
    }

    /**
     * Does the data we hold still cover the screen? Refetching on how far the *centre* moved
     * gets this wrong at both ends — at high zoom the user pans two screens into blank map
     * before anything fires, and at low zoom a nudge refetches a circle that still leaves the
     * edges empty. Asking how much of the visible rectangle has no data behind it is the
     * question that actually matters, and it scales with zoom on its own.
     *
     * While a load is in flight it is judged against the area *that* load will cover, so the
     * camera idles during a fetch don't queue up a duplicate of it.
     */
    private fun needsRefetch(
        s: TrailsUiState,
        viewRadius: Double,
        center: GeoPoint,
        wantRadius: Int,
    ): Boolean {
        val inFlight = pendingCenter
        val reference = inFlight ?: s.loadedCenter ?: return s.trails.isEmpty()
        val have = if (inFlight != null) pendingRadius else s.loadedRadiusMeters
        // Zoomed out far enough that what we hold can no longer fill the screen.
        if (wantRadius > have * ZOOM_OUT_FACTOR) return true
        val drift = Geo.haversineMeters(reference, center)
        val uncovered = drift + viewRadius - have
        // Coverage says the screen has holes; drift says we have actually gone somewhere new.
        // Both are required: at a wide zoom the screen is permanently "uncovered", and the
        // coverage test alone would refetch on every single camera idle.
        return uncovered > viewRadius * EDGE_SLACK && drift > have * MIN_DRIFT_FRACTION
    }

    /**
     * Download the trail data for an offline area, so a downloaded region has trails on it and
     * not just basemap tiles. The bbox is covered with overlapping circles at the widest radius
     * the app ever fetches; because the cache serves any request a stored circle contains, one
     * of these covers every later pan and zoom inside the area.
     */
    fun prefetchTrailsFor(bounds: ViewBounds) {
        val mtb = _state.value.mode == MapMode.MTB
        prefetchJob?.cancel()
        prefetchJob = viewModelScope.launch {
            val circles = coverCircles(bounds)
            var failed = 0
            _state.update { it.copy(trailPrefetch = "Trails 0/${circles.size}") }
            var lastError: String? = null
            circles.forEachIndexed { i, c ->
                if (!overpass.hasArea(c, MAX_AUTO_RADIUS, mtb)) {
                    runCatching { overpass.prefetch(c, MAX_AUTO_RADIUS, mtb) }
                        .onFailure { e ->
                            if (e is CancellationException) throw e
                            failed++
                            lastError = e.message
                        }
                }
                _state.update { it.copy(trailPrefetch = "Trails ${i + 1}/${circles.size}") }
            }
            _state.update {
                it.copy(
                    trailPrefetch = when {
                        failed == 0 -> "Trails saved for offline use"
                        failed < circles.size -> "Trails partly saved (${circles.size - failed}/${circles.size})"
                        else -> "Couldn't download trails: ${lastError ?: "network error"}"
                    },
                )
            }
        }
    }

    fun clearTrailPrefetch() = _state.update { it.copy(trailPrefetch = null) }

    /**
     * Circle centres covering [b]. A circle of radius r covers a square of side r·√2, so a
     * grid step a little under that tiles the box with slight overlap and no gaps.
     */
    private fun coverCircles(b: ViewBounds): List<GeoPoint> {
        val stepMeters = MAX_AUTO_RADIUS * 1.3
        val latStep = stepMeters / 111_320.0
        val midLat = (b.north + b.south) / 2.0
        val midLon = (b.east + b.west) / 2.0
        val lonStep = latStep / kotlin.math.cos(Math.toRadians(midLat)).coerceAtLeast(0.1)
        // Grid centred on the box, not started from its corner: a box smaller than one step
        // then gets a single circle centred on it, rather than one offset half a step away
        // that leaves the area you actually care about out near the circle's edge.
        val rows = maxOf(1, kotlin.math.ceil((b.north - b.south) / latStep).toInt())
        val cols = maxOf(1, kotlin.math.ceil((b.east - b.west) / lonStep).toInt())
        val out = ArrayList<GeoPoint>()
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                if (out.size >= MAX_PREFETCH_CIRCLES) return out
                out.add(
                    GeoPoint(
                        midLat + (row - (rows - 1) / 2.0) * latStep,
                        midLon + (col - (cols - 1) / 2.0) * lonStep,
                    ),
                )
            }
        }
        return out
    }

    /** Manual "Search this area" — fetch trails around the current map viewport center. */
    fun searchThisArea() {
        val s = _state.value
        val b = s.viewBounds ?: return
        val c = lastCamera?.point ?: GeoPoint((b.north + b.south) / 2.0, (b.east + b.west) / 2.0)
        panJob?.cancel()
        load(c, fetchRadiusFor(s, viewRadiusMeters(b, c)))
    }

    /** Toggle automatic refetching as the map is panned (persisted). */
    fun setAutoLoadOnPan(on: Boolean) = _state.update {
        prefs.setAutoLoadOnPan(on)
        if (!on) panJob?.cancel()
        it.copy(autoLoadOnPan = on)
    }

    /** Select a trail (tapped on the map) → peek card + map highlight. */
    fun selectTrail(id: String) = _state.update { it.copy(selectedTrailId = id) }

    fun clearSelection() = _state.update { it.copy(selectedTrailId = null) }

    // --- Rides (combine trails into a named ride with a total length) ---

    fun rideById(id: String): Ride? = _state.value.rides.firstOrNull { it.id == id }

    private fun persistRides(rides: List<Ride>) {
        prefs.setRides(rides)
        _state.update { it.copy(rides = rides) }
    }

    /** Create a ride (optionally seeded with one trail) and return its id. */
    fun createRide(name: String, seed: Trail? = null): String {
        val id = "ride_" + System.currentTimeMillis()
        val ride = Ride(id, name.ifBlank { "Ride" }, seed?.let { listOf(it.toRideTrail()) } ?: emptyList())
        persistRides(_state.value.rides + ride)
        return id
    }

    /** Add a trail to a ride (no-op if already present). */
    fun addTrailToRide(rideId: String, trail: Trail) = persistRides(
        _state.value.rides.map { r ->
            if (r.id != rideId || r.trails.any { it.id == trail.id }) r
            else r.copy(trails = r.trails + trail.toRideTrail())
        },
    )

    fun removeTrailFromRide(rideId: String, trailId: String) = persistRides(
        _state.value.rides.map { r ->
            if (r.id != rideId) r else r.copy(trails = r.trails.filterNot { it.id == trailId })
        },
    )

    fun renameRide(rideId: String, name: String) = persistRides(
        _state.value.rides.map { if (it.id == rideId) it.copy(name = name.ifBlank { it.name }) else it },
    )

    fun deleteRide(rideId: String) = persistRides(_state.value.rides.filterNot { it.id == rideId })

    private fun Trail.toRideTrail() =
        RideTrail(id = id, name = name, lengthMeters = lengthMeters, surface = surface.name, mtbScale = mtbScale)

    /** Minimum-length filter in miles (0 = any). */
    fun setMinLength(miles: Double) = _state.update { it.copy(minLengthMiles = miles) }

    /** Free-text name search (filters the list + map by trail name substring). */
    fun setQuery(q: String) = _state.update { it.copy(query = q) }

    /** Star/unstar a trail; persisted across launches. */
    fun toggleSaved(id: String) = _state.update {
        val next = it.savedIds.toMutableSet().apply { if (!add(id)) remove(id) }
        prefs.setSavedIds(next)
        it.copy(savedIds = next)
    }

    /** Show only saved trails (used by the Trails list "saved" toggle). */
    fun setShowSavedOnly(on: Boolean) = _state.update { it.copy(showSavedOnly = on) }

    /** Force the basemap theme (persisted); cycles SYSTEM → LIGHT → DARK. */
    fun cycleMapTheme() = _state.update {
        val next = when (it.mapTheme) {
            MapTheme.SYSTEM -> MapTheme.LIGHT
            MapTheme.LIGHT -> MapTheme.DARK
            MapTheme.DARK -> MapTheme.SYSTEM
        }
        prefs.setMapTheme(next.name)
        it.copy(mapTheme = next)
    }

    /** Recenter the map on a point (e.g. a tapped trail-system header). */
    fun focusOn(point: GeoPoint, zoom: Double? = SYSTEM_FOCUS_ZOOM) =
        _state.update { it.copy(focusTarget = CameraTarget(point, zoom)) }

    /** Clear the one-shot focus target after the map has animated to it. */
    fun consumeFocus() = _state.update { it.copy(focusTarget = null) }

    fun toggleSurface(s: SurfaceType) = _state.update {
        val next = it.selectedSurfaces.toMutableSet().apply { if (!add(s)) remove(s) }
        it.copy(selectedSurfaces = next)
    }

    fun toggleUse(u: UseType) = _state.update {
        val next = it.selectedUses.toMutableSet().apply { if (!add(u)) remove(u) }
        it.copy(selectedUses = next)
    }

    /** Lazy-load the elevation profile for a trail when its detail screen opens. */
    fun ensureProfile(trailId: String) {
        if (_profiles.value.containsKey(trailId)) return
        val trail = trailById(trailId) ?: return
        viewModelScope.launch {
            try {
                val profile = elevation.profile(trail.allPoints)
                _profiles.update { it + (trailId to profile) }
            } catch (e: Exception) {
                _profiles.update { it + (trailId to ElevationProfile.EMPTY) }
            }
        }
    }

    companion object {
        private fun milesToMeters(miles: Int) = (miles * 1609.344).toInt()

        /** ALL-mode default: 5 mi, wide enough to catch parkway and bike routes. */
        private val DEFAULT_ALL_RADIUS = milesToMeters(5)

        /** Zoom used when the camera is moved to the user's location. */
        const val DEFAULT_ZOOM = 12.5

        /** Zoom used when a trail-system header recenters the map on a park. */
        private const val SYSTEM_FOCUS_ZOOM = 14.0

        /** Wait this long after the camera settles before refetching, to ride out a flick-pan. */
        private const val PAN_DEBOUNCE_MS = 450L

        /** Shorter wait when the area is already in memory and will come back immediately. */
        private const val PAN_DEBOUNCE_WARM_MS = 120L

        /** Hold the loading pill back this long so cache hits never flash it. */
        private const val SPINNER_DELAY_MS = 250L

        /** Refetch once this fraction of the visible rectangle has no data behind it. */
        private const val EDGE_SLACK = 0.4

        /** Fetch a little wider than the screen, so a small pan doesn't immediately re-fire. */
        private const val FETCH_MARGIN = 1.35

        /** Auto-load only while a fetch could actually fill the screen. */
        private const val COVER_RATIO = 0.9

        /** Below this, the camera is MapLibre's default (0,0 @ z0), not a user position. */
        private const val MIN_RECORDABLE_ZOOM = 3.0

        /** Sanity backstop: never auto-fetch from a continent-scale view. */
        private const val HARD_ZOOM_FLOOR = 8.0

        /** Refetch once the map has moved this fraction of the loaded radius. */
        private const val MIN_DRIFT_FRACTION = 0.35

        /** Refetch when zooming out needs a radius this many times what we already hold. */
        private const val ZOOM_OUT_FACTOR = 1.5

        /** Coarse radius steps, so nearby viewports keep reusing the same cache keys. */
        private val RADIUS_LADDER = intArrayOf(6000, 8000, 12000, 16000, 24000)

        /**
         * Largest circle we'll pull. Viable at this size only because the query asks for named
         * ways: a 24 km unnamed-inclusive pull would be tens of megabytes.
         */
        private const val MAX_AUTO_RADIUS = 24000

        /** Never fetch less than this, whatever the chip says — see [fetchRadiusFor]. */
        private const val MIN_FETCH_RADIUS = 16000

        /**
         * Ceiling on circles per offline area. Each is a few megabytes off a shared public API,
         * so a state-wide box covers what it can and the UI reports how far it got.
         */
        private const val MAX_PREFETCH_CIRCLES = 12

        /** Attempts per load, so a momentary 502/504 from Overpass isn't a dead end. */
        private const val MAX_ATTEMPTS = 2
        private const val RETRY_DELAY_MS = 2500L
    }
}
