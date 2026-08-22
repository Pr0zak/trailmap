package com.trailmap.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
    val radiusMeters: Int = 8000,
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
     * In MTB mode, [filtered] grouped into nearby trail systems; empty otherwise.
     * Also `by lazy` — [clusterTrailSystems] is O(n²) in haversine distances, and the list
     * screen reads this property more than once per frame.
     */
    val systems: List<TrailSystem> by lazy {
        if (mode == MapMode.MTB) clusterTrailSystems(filtered) else emptyList()
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
    private val overpass = OverpassClient(app.cacheDir)
    private val elevation = ElevationClient()
    private val locator = Locator(app)
    private val prefs = Prefs(app)

    private val _state = MutableStateFlow(
        TrailsUiState(
            radiusMeters = 8000, // ALL mode default ~5 mi (wide enough to catch parkway/bike routes)
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

    /** The in-flight trail fetch, cancelled whenever a newer one starts. */
    private var loadJob: Job? = null

    /** The pending debounced pan reload. */
    private var panJob: Job? = null

    /** Monotonic load counter — a response whose sequence is stale never reaches the UI. */
    private var loadSeq = 0

    /** Center of the load currently running, so a camera idle doesn't duplicate it. */
    private var pendingCenter: GeoPoint? = null

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
            load(here, _state.value.radiusMeters)
        }
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
            load(here, _state.value.radiusMeters, force = true)
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
        loadJob = viewModelScope.launch {
            _state.update { it.copy(error = null, center = center) }
            // Hold the spinner back briefly: an area that's already parsed in memory comes
            // back in well under this, and flashing a pill for it reads as churn.
            val spinner = launch {
                delay(SPINNER_DELAY_MS)
                if (seq == loadSeq) _state.update { it.copy(loading = true) }
            }
            try {
                val trails = overpass.fetchTrails(center, radiusMeters, mtb = mtb, forceRefresh = force)
                spinner.cancel()
                if (seq != loadSeq) return@launch // a newer load already won
                _state.update { s ->
                    s.copy(
                        loading = false,
                        trails = trails,
                        trailsVersion = s.trailsVersion + 1,
                        loadedCenter = center,
                        loadedRadiusMeters = radiusMeters,
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
                _state.update { it.copy(loading = false, error = e.message ?: "Failed to load trails") }
            } finally {
                if (seq == loadSeq) pendingCenter = null
            }
        }
    }

    fun setRadius(meters: Int) {
        if (_state.value.radiusMeters == meters) return // re-tapping the active chip is a no-op
        _state.update { it.copy(radiusMeters = meters) }
        load(_state.value.center, meters)
    }

    /** Radius selector in miles (used by MTB mode: 10 / 25 / 40 mi). */
    fun setRadiusMiles(miles: Int) = setRadius((miles * 1609.344).toInt())

    /** Switch ALL ↔ MTB. MTB defaults to a wide 25-mi radius; ALL to ~5 mi. */
    fun setMode(mode: MapMode) {
        if (_state.value.mode == mode) return
        val radius = if (mode == MapMode.MTB) (25 * 1609.344).toInt() else 8000
        _state.update { it.copy(mode = mode, radiusMeters = radius, selectedTrailId = null) }
        load(_state.value.center, radius)
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
        lastCamera = CameraTarget(center, bounds.zoom)
        val viewR = viewRadiusMeters(bounds, center)
        val fetchR = fetchRadiusFor(prev, viewR)
        val stale = needsRefetch(prev, viewR, center)
        val canCover = fetchR >= viewR * COVER_RATIO && bounds.zoom >= HARD_ZOOM_FLOOR
        _state.update { it.copy(viewBounds = bounds, viewportStale = stale, canAutoCover = canCover) }

        if (!stale || !prev.autoLoadOnPan || !canCover) return
        panJob?.cancel()
        panJob = viewModelScope.launch {
            // Revisiting an area already parsed in memory should feel immediate, so the
            // ride-out-the-flick debounce only applies when we'd actually hit the network.
            val warm = overpass.isWarm(center, fetchR, mtb = prev.mode == MapMode.MTB)
            delay(if (warm) PAN_DEBOUNCE_WARM_MS else PAN_DEBOUNCE_MS)
            load(center, fetchR)
        }
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
        return stepped.coerceIn(s.radiusMeters, maxOf(MAX_AUTO_RADIUS, s.radiusMeters))
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
    private fun needsRefetch(s: TrailsUiState, viewRadius: Double, center: GeoPoint): Boolean {
        val inFlight = pendingCenter?.takeIf { s.loading }
        val reference = inFlight ?: s.loadedCenter ?: return s.trails.isEmpty()
        val radius = if (inFlight != null) s.radiusMeters else s.loadedRadiusMeters
        val uncovered = Geo.haversineMeters(reference, center) + viewRadius - radius
        return uncovered > viewRadius * EDGE_SLACK
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
        private const val EDGE_SLACK = 0.25

        /** Fetch a little wider than the screen, so a small pan doesn't immediately re-fire. */
        private const val FETCH_MARGIN = 1.35

        /** Auto-load only while a fetch could actually fill the screen. */
        private const val COVER_RATIO = 0.9

        /** Sanity backstop: never auto-fetch from a continent-scale view. */
        private const val HARD_ZOOM_FLOOR = 9.0

        /** Coarse radius steps, so nearby viewports keep reusing the same cache keys. */
        private val RADIUS_LADDER = intArrayOf(6000, 8000, 12000, 16000)

        /** Largest circle we'll pull automatically; past this the user asks explicitly. */
        private const val MAX_AUTO_RADIUS = 16000
    }
}
