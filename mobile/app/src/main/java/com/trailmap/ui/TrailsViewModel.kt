package com.trailmap.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.trailmap.data.DiagLog
import com.trailmap.data.ElevationClient
import com.trailmap.data.ElevationProfile
import com.trailmap.data.Geo
import com.trailmap.data.GeoPoint
import com.trailmap.data.HorseTrailFilter
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
import com.trailmap.data.MyVitalsClient
import com.trailmap.data.MyVitalsOffer
import com.trailmap.data.MyVitalsSettings
import com.trailmap.data.MyVitalsStore
import com.trailmap.data.PersonalPace
import com.trailmap.data.Polyline
import com.trailmap.data.RecordedTrack
import com.trailmap.data.TrackIndex
import com.trailmap.data.TrailOnTrack
import com.trailmap.data.TrailStatus
import com.trailmap.data.TrailVisits
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import androidx.work.WorkInfo
import com.trailmap.offline.TrailPackWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Map data mode: all trails (paved/gravel/dirt) vs mountain-bike trails only. */
enum class MapMode { ALL, MTB }

/** Trails list order. The map ignores it; `filtered` itself is always nearest-first. */
enum class TrailSort(val label: String) {
    DISTANCE("Distance"), LENGTH("Length"), NAME("Name"),

    /** Most recently ridden first, then the ones you haven't ridden. Needs myvitals. */
    LAST_RIDDEN("Last ridden"),
}

/** Filters sheet: every trail, only ones you've ridden, or only ones you haven't yet. */
enum class RiddenFilter(val label: String) { ANY("Any"), RIDDEN("Ridden"), NOT_YET("Not yet") }

/** Map layers drawn from your myvitals activity, toggled in the Layers menu. */
enum class YouLayer(val label: String, val default: Boolean) {
    RIDDEN("Ridden trails", true),
    TRACKS("My tracks", false),
    CONDITIONS("Trail conditions", true),
}

/** The myvitals connection as the UI shows it. */
data class MyVitalsUi(
    val connected: Boolean = false,
    /** The address as the user entered it (normalised). */
    val url: String = "",
    val autoSync: Boolean = true,
    /** Connect is checking a new address and token. */
    val connecting: Boolean = false,
    /** A sync is running. */
    val syncing: Boolean = false,
    val lastSync: Long = 0L,
    val lastConditionsSync: Long = 0L,
    /**
     * Why the last connect from the form, or the last sync, failed; cleared by the next
     * success. "Use this" on an offer reports in [TrailsUiState.myVitalsOfferError] instead.
     */
    val error: String? = null,
    /**
     * The saved connection has been read from the phone. Until then "not connected" only
     * means "not known yet", and the screen waits rather than show an empty form.
     */
    val settingsLoaded: Boolean = false,
) {
    companion object {
        fun of(s: MyVitalsSettings) = MyVitalsUi(
            settingsLoaded = true,
            connected = s.configured,
            url = s.url,
            autoSync = s.autoSync,
            lastSync = s.lastSync,
            lastConditionsSync = s.lastConditionsSync,
        )
    }
}

/** The trails a recorded ride went along, as its screen loads them. */
sealed interface RecordedTrails {
    data object Loading : RecordedTrails
    data class Ready(val trails: List<TrailOnTrack>) : RecordedTrails
    data class Failed(val message: String) : RecordedTrails
}

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
    val selectedUses: Set<UseType> = UseType.entries.toSet(),
    /** Show, hide, or show only horse trails. */
    val horseTrails: HorseTrailFilter = HorseTrailFilter.SHOW,
    val minLengthMiles: Double = 0.0,
    val query: String = "",
    val savedIds: Set<String> = emptySet(),
    val showSavedOnly: Boolean = false,
    val sort: TrailSort = TrailSort.DISTANCE,
    val mapTheme: MapTheme = MapTheme.SYSTEM,
    /** When set, the map recenters here (e.g. tapping a trail-system header); consume after use. */
    val focusTarget: CameraTarget? = null,
    /** The trail the user tapped on the map (peek card + highlight); null = nothing selected. */
    val selectedTrailId: String? = null,
    /** A ride whose trails are all highlighted on the map ("Show ride on map"). */
    val highlightedRideId: String? = null,
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
    /**
     * Bytes of trail data saved per region before state packs (0.16.0 and earlier). Still read,
     * but nothing writes it any more; the Offline screen offers to clear it while it exists.
     */
    val offlineTrailBytes: Long = 0L,
    /** Every state with trail data: offered by the release, chosen, or on the phone. */
    val packStates: List<PackState> = emptyList(),
    /** A state trail pack downloading now; null when none is. */
    val packDownload: PackDownload? = null,
    /** The pack sync is waiting — for a connection, or to retry a failed download. */
    val packWaiting: Boolean = false,
    /** The last pack sync gave up after its retries. */
    val packFailed: Boolean = false,
    /** The map is over a state whose trails aren't on the phone: offer it. Null otherwise. */
    val packSuggestion: PackState? = null,

    // --- Your activity, from myvitals -----------------------------------------------------
    val myVitals: MyVitalsUi = MyVitalsUi(),
    /**
     * An address and access key the myvitals app sent over (`MyVitalsHandoff`), waiting on the
     * myvitals screen for the user to check them and tap Connect. Never connected without a tap.
     */
    val myVitalsOffer: MyVitalsOffer? = null,
    /**
     * Why "Use this" on [myVitalsOffer] failed, shown in the offer's own card. Kept apart from
     * [MyVitalsUi.error] so the Connected card's "Last try failed" only ever means a sync.
     */
    val myVitalsOfferError: String? = null,
    /**
     * [myVitalsOffer] is the connection already in use (same address, same key): the screen
     * says so instead of offering to switch to it.
     */
    val myVitalsOfferCurrent: Boolean = false,
    /** Recorded activities with a GPS track, newest first. Empty when not connected. */
    val recorded: List<RecordedTrack> = emptyList(),
    /**
     * Your visits per trail id, for the trails in [trails]. Only trails you have ridden or
     * walked have an entry, so a missing id means "not yet" once matching has caught up.
     */
    val visits: Map<String, TrailVisits> = emptyMap(),
    /** Bumped whenever [visits] changes — a cheap key for effects, like [trailsVersion]. */
    val visitsVersion: Int = 0,
    val ridden: RiddenFilter = RiddenFilter.ANY,
    val showRidden: Boolean = YouLayer.RIDDEN.default,
    val showTracks: Boolean = YouLayer.TRACKS.default,
    val showConditions: Boolean = YouLayer.CONDITIONS.default,
    /** Open/closed trail systems, from the status board myvitals polls. */
    val conditions: List<TrailStatus> = emptyList(),
    /** A trailhead tapped on the map. */
    val selectedConditionId: Long? = null,
    /** A recorded activity drawn on the map ("Show on map" from the Rides tab). */
    val highlightedTrackId: String? = null,
    /** Your average speeds, for the detail screen's time estimate. */
    val pace: PersonalPace? = null,
) {
    /** [recorded] by id. */
    val recordedById: Map<String, RecordedTrack> by lazy { recorded.associateBy { it.id } }

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
            .filter {
                when (horseTrails) {
                    HorseTrailFilter.SHOW -> true
                    HorseTrailFilter.HIDE -> !it.horseTrail
                    HorseTrailFilter.ONLY -> it.horseTrail
                }
            }
            .filter { it.lengthMiles >= minLengthMiles }
            .filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
            .filter { !showSavedOnly || it.id in savedIds }
            .filter {
                when (ridden) {
                    RiddenFilter.ANY -> true
                    RiddenFilter.RIDDEN -> visits[it.id]?.ridden == true
                    RiddenFilter.NOT_YET -> visits[it.id]?.ridden != true
                }
            }
            .sortedBy { it.distanceMeters }
    }

    /**
     * [filtered] narrowed to the radius chip. The map deliberately draws everything cached —
     * a wider cached circle means panning inside it needs no refetch — but the Trails list
     * has to keep the chip honest and not pad itself with the next town over.
     */
    val listed: List<Trail> by lazy {
        val near = filtered.filter { it.distanceMeters <= radiusMeters }
        when (sort) {
            TrailSort.DISTANCE -> near
            TrailSort.LENGTH -> near.sortedByDescending { it.lengthMeters }
            TrailSort.NAME -> near.sortedBy { it.name.lowercase() }
            TrailSort.LAST_RIDDEN -> near.sortedWith(
                compareByDescending<Trail> { visits[it.id]?.lastRidden ?: Long.MIN_VALUE }.thenBy { it.distanceMeters },
            )
        }
    }

    /**
     * Named trails within the radius chip before any filter, search or saved-only toggle —
     * what the empty state counts when it says the filters are hiding everything.
     */
    val unfilteredNearbyCount: Int by lazy {
        trails.count { it.name != "Unnamed path" && it.distanceMeters <= radiusMeters }
    }

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
            append('|').append(horseTrails)
            append('|').append(minLengthMiles)
            append('|').append(query.trim().lowercase())
            append('|').append(showSavedOnly)
            // savedIds only changes what's filtered while the "saved only" toggle is on.
            append('|').append(if (showSavedOnly) savedIds.sorted().joinToString(",") else "")
            // Likewise visits, while the ridden filter is on.
            append('|').append(ridden).append(if (ridden != RiddenFilter.ANY) visitsVersion else 0)
        }
    }

    fun isSaved(id: String): Boolean = id in savedIds
}

/** One state's trail data, as the Offline and Trail data screens show it. */
data class PackState(
    val slug: String,
    val name: String,
    /** Download size, from the release; 0 when the release doesn't list it. */
    val bytes: Long,
    /** The user wants it on the phone. */
    val selected: Boolean,
    /** Its trails are on the phone now. */
    val installed: Boolean,
    /** When the OSM data on the phone was current (ISO-8601); null when not installed. */
    val osmTimestamp: String? = null,
    /** The release has a newer build than the one on the phone. */
    val updateAvailable: Boolean = false,
    /** The map is in or next to this state. */
    val nearby: Boolean = false,
)

/** Progress of a state pack download: which state, which of how many, and bytes. */
data class PackDownload(val state: String, val number: Int, val count: Int, val done: Long, val total: Long)

class TrailsViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = Prefs(app)
    private val overpass = com.trailmap.TrailmapApp.overpass(app)
    private val elevation = ElevationClient()
    private val locator = Locator(app)

    private val _state = MutableStateFlow(
        TrailsUiState(
            radiusMeters = DEFAULT_ALL_RADIUS,
            savedIds = prefs.savedIds(),
            mapTheme = runCatching { MapTheme.valueOf(prefs.mapTheme()) }.getOrDefault(MapTheme.SYSTEM),
            rides = prefs.rides(),
            autoLoadOnPan = prefs.autoLoadOnPan(),
            showRidden = prefs.layer(YouLayer.RIDDEN.name, YouLayer.RIDDEN.default),
            showTracks = prefs.layer(YouLayer.TRACKS.name, YouLayer.TRACKS.default),
            showConditions = prefs.layer(YouLayer.CONDITIONS.name, YouLayer.CONDITIONS.default),
        ),
    )
    val state: StateFlow<TrailsUiState> = _state.asStateFlow()

    // Elevation profiles cached per trail id (lazy-loaded when a detail screen opens).
    private val _profiles = MutableStateFlow<Map<String, ElevationProfile>>(emptyMap())
    val profiles: StateFlow<Map<String, ElevationProfile>> = _profiles.asStateFlow()

    /**
     * States whose map offer was dismissed this session. Declared before `init`: the pack
     * watcher started there reads it on its first, synchronous pass.
     */
    private val dismissedSuggestions = HashSet<String>()

    // --- myvitals -------------------------------------------------------------------------
    private val myVitalsStore = MyVitalsStore(File(app.filesDir, "myvitals"))
    private val myVitalsClient = MyVitalsClient()

    /** The saved connection. Read from disk once, then kept here; the store is the copy that lasts. */
    @Volatile private var myVitals = MyVitalsSettings()

    /**
     * Bumped whenever a connect or a disconnect replaces the saved connection, so the startup
     * read ([loadMyVitals]) can tell that what it read is older than what is now in place.
     * Main thread only.
     */
    private var settingsGen = 0

    /**
     * The last id given to an offer from the myvitals app. Starts from the monotonic clock
     * rather than 0: the myvitals screen remembers the id it applied across a process restart,
     * and a counter starting over could hand a new offer that same id.
     */
    private var offerSeq = System.nanoTime()

    /** Index over the recorded tracks; null when there are none. Rebuilt after a sync changes them. */
    @Volatile private var trackIndex: TrackIndex? = null

    /**
     * Visits already worked out, per trail id, with the geometry they were worked out for —
     * a pan reloads the same trails as new objects, and matching them again would be wasted.
     * Belongs to one index: a rebuild replaces it, so a job still running for the old index
     * can only fill a cache nobody reads.
     */
    private class VisitCache(val index: TrackIndex) {
        val entries = HashMap<String, Pair<String, TrailVisits?>>()
    }

    @Volatile private var visitCache: VisitCache? = null

    /**
     * Serialises every write to [myVitalsStore] together with the matching update of [myVitals].
     * Connect, sync, the conditions loop, the auto-sync switch and disconnect all write
     * settings.json; unguarded, a conditions save for the old server could land after a
     * connect's save and quietly revert the connection at the next cold start, or a save could
     * land after disconnect deleted the directory.
     */
    private val storeLock = Mutex()
    private var visitsJob: Job? = null
    private var syncJob: Job? = null
    private var conditionsJob: Job? = null

    /** When trail conditions were last asked for, success or not, so a failure isn't retried in a loop. */
    private var conditionsAttemptAt = 0L

    /** The app is on screen: auto-sync and the conditions refresh only run then. */
    private var foreground = false

    /**
     * Trails loaded for a recorded ride's trail list. They aren't part of the map's loaded
     * areas, but the ride's screen opens them, so [trailById] looks here too.
     */
    private val auxTrails = HashMap<String, Trail>()

    private val _recordedTrails = MutableStateFlow<Map<String, RecordedTrails>>(emptyMap())
    val recordedTrails: StateFlow<Map<String, RecordedTrails>> = _recordedTrails.asStateFlow()

    init {
        watchTrailPack()
        loadMyVitals()
        // Work out visits for whatever the map loads, as it loads it.
        viewModelScope.launch {
            _state.map { it.trailsVersion }.distinctUntilChanged().collect { scheduleVisits() }
        }
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

    /** Monotonic load counter — a response whose sequence is stale never reaches the UI. */
    private var loadSeq = 0

    /** Center and radius of the load currently running, so a camera idle doesn't duplicate it. */
    private var pendingCenter: GeoPoint? = null
    private var pendingRadius = 0

    /** Guards the one-time startup locate+load, for this ViewModel's lifetime. */
    private var bootstrapped = false

    /**
     * True while [bootstrap] is waiting on a location fix. Camera idles are ignored until it
     * resolves: the fix can take seconds, and until [load] is called there is no pending
     * circle for the gate to measure against, so the first idle would start a second load
     * that the bootstrap immediately supersedes.
     */
    private var bootstrapPending = false

    /**
     * Recently loaded circles, oldest first. The map draws the union of these rather than
     * whatever the last load returned — otherwise zooming out *removes* trails: the wide
     * cached circle you were looking at gets replaced by a fresh 16 km one, which is a dot in
     * the middle of an 89 km screen. Bounded, because each entry retains its geometry.
     */
    private val loadedAreas = mutableListOf<LoadedArea>()

    private class LoadedArea(
        val center: GeoPoint,
        val radius: Int,
        /** Which mode fetched this. ALL and MTB are different datasets for the same ground. */
        val mtb: Boolean,
        val trails: List<Trail>,
    ) {
        /** Retained geometry, which is what the memory budget is actually spent on. */
        val vertices: Int = trails.sumOf { t -> t.paths.sumOf { it.size } }
    }

    /**
     * Where the camera last settled. A plain field, not UI state: it is written on every
     * camera idle and must not cause a recomposition. The map reads it when it is recreated
     * (a tab switch disposes the composition) so returning to the Map tab restores the exact
     * pan and zoom instead of jumping back to the device location.
     */
    var lastCamera: CameraTarget? = null
        private set

    fun trailById(id: String): Trail? =
        _state.value.trails.firstOrNull { it.id == id } ?: synchronized(auxTrails) { auxTrails[id] }

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
        bootstrapPending = true
        viewModelScope.launch {
            try {
                val here = locator.current()
                _state.update { it.copy(center = here, focusTarget = CameraTarget(here, DEFAULT_ZOOM)) }
                chooseDefaultStates(here)
                load(here, initialFetchRadius())
                // Hold camera-driven loads off until this one lands, not merely until it has
                // started: load() only launches the job. Clearing the flag on launch let the
                // map's first camera idle — still at its start position, before the move to
                // the device location — supersede the startup load. A device log caught that
                // throwing away a 0.75 s disk hit for a 19.5 s network fetch.
                loadJob?.join()
            } finally {
                bootstrapPending = false
            }
            // The index may have landed while the startup load ran; the pack watcher skips
            // first-run choices until then.
            chooseDefaultStates(_state.value.center)
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
                // Hard ceiling on the whole thing. Each mirror gets its own 45 s call timeout,
                // and with three mirrors plus a retry a dead network could hold the "Loading
                // trails" pill up for over two minutes — observed at 80 s with all three
                // mirrors down. Better to say so quickly and leave the cached trails drawn.
                withTimeoutOrNull(if (mtb) MTB_LOAD_BUDGET_MS else LOAD_BUDGET_MS) {
                while (attempt < MAX_ATTEMPTS) {
                    try {
                        fetched = overpass.fetchTrails(center, radiusMeters, mtb = mtb, forceRefresh = force, withParks = false)
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
                }
                val result = fetched
                    ?: throw (failure ?: java.io.IOException("Timed out reaching OpenStreetMap"))
                val trails = result.trails

                spinner.cancel()
                if (seq != loadSeq) return@launch // a newer load already won
                val sameCircle = result.servedCenter == _state.value.loadedCenter &&
                    result.servedRadius == _state.value.loadedRadiusMeters
                val merged = withContext(Dispatchers.Default) {
                    // `mtb` is load()'s captured value, not a fresh state read — a mode switch
                    // landing mid-merge must not stamp this area with the wrong dataset.
                    mergeArea(result.servedCenter, result.servedRadius, mtb, trails, center)
                }
                // Identical circle and no new ground means identical geometry, so leave
                // trailsVersion alone and spare a GeoJSON rebuild for no visible difference.
                val unchanged = sameCircle && merged.size == _state.value.trails.size
                DiagLog.log(
                    "load",
                    "done in ${android.os.SystemClock.elapsedRealtime() - started} ms, " +
                        "${trails.size} trails in a ${result.servedRadius} m circle, " +
                        "${merged.size} shown across ${loadedAreas.size} areas",
                )
                _state.update { s ->
                    s.copy(
                        loading = false,
                        trails = merged,
                        trailsVersion = if (unchanged) s.trailsVersion else s.trailsVersion + 1,
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

                // MTB: the trails are up; now fetch the park polygons that name the systems.
                // Same job, so a newer load cancels this too. The trail elements are memoized,
                // so this second call only costs the park query.
                if (result.parksPending) {
                    val named = runCatching {
                        withTimeoutOrNull(PARKS_BUDGET_MS) {
                            overpass.fetchTrails(center, radiusMeters, mtb = true, forceRefresh = force)
                        }
                    }.getOrElse { if (it is CancellationException) throw it else null }
                    if (named == null || seq != loadSeq) {
                        DiagLog.log("load", "park names unavailable; systems keep fallback names")
                    } else {
                        val renamed = withContext(Dispatchers.Default) {
                            mergeArea(named.servedCenter, named.servedRadius, mtb, named.trails, center)
                        }
                        DiagLog.log("load", "park names added")
                        _state.update { it.copy(trails = renamed, trailsVersion = it.trailsVersion + 1) }
                    }
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
        // A few hundred metres short doesn't count: 10 mi is 16,093 m against the 16,000 m an
        // ALL-mode pull usually holds, and refetching for that 93 m cancelled whatever load
        // was in flight to download the same area again.
        if (meters > s.loadedRadiusMeters + RADIUS_CHIP_TOLERANCE_M) load(s.center, maxOf(meters, MIN_FETCH_RADIUS))
    }

    /** Radius selector in miles. Must agree exactly with the defaults, or re-selecting the
     *  chip that is already active counts as a change and refetches. */
    fun setRadiusMiles(miles: Int) = setRadius(milesToMeters(miles))

    /** Switch ALL ↔ MTB. MTB defaults to a wide 25-mi radius; ALL to ~5 mi. */
    fun setMode(mode: MapMode) {
        if (_state.value.mode == mode) return
        val radius = if (mode == MapMode.MTB) milesToMeters(25) else DEFAULT_ALL_RADIUS
        // Re-scope the drawn set to the new mode straight away rather than waiting for the
        // load. Filtering only inside mergeArea leaves the old mode's trails on screen for as
        // long as the fetch takes — and if it fails, permanently: an MTB switch that timed out
        // sat there showing paved city greenways under a legend headed "Difficulty".
        // Distances are left as they are; the next successful load refreshes them.
        val carried = unionFor(mode == MapMode.MTB)
        _state.update {
            it.copy(
                mode = mode,
                radiusMeters = radius,
                selectedTrailId = null,
                trails = carried,
                trailsVersion = it.trailsVersion + 1,
            )
        }
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
        if (bounds.zoom >= MIN_RECORDABLE_ZOOM) {
            updatePackSuggestion(center)
            // "Near the map" follows the map, but only needs redoing when it crosses into
            // another state's box.
            val near = overpass.pack?.index?.value?.states.orEmpty().filter { it.near(center, NEARBY_DEG) }.map { it.slug }.toSet()
            if (near != _state.value.packStates.filter { it.nearby }.map { it.slug }.toSet()) refreshPackStates()
        }
        if (bootstrapPending) return // the startup load is about to claim this area
        if (!stale || !prev.autoLoadOnPan || bounds.zoom < HARD_ZOOM_FLOOR) return
        // Would this "refetch" just hand back what is already on screen? The gate measures
        // distance from the loaded circle's centre, and a cached circle keeps its own centre
        // however far the map wanders inside it — so without this check every camera idle
        // re-reads the same file forever. A device log caught nine such loads in thirteen
        // seconds, each one a cache hit returning identical data.
        val wouldServe = overpass.cachedCircleFor(center, fetchR, prev.mode == MapMode.MTB)
        if (wouldServe != null &&
            wouldServe.first == prev.loadedCenter &&
            wouldServe.second == prev.loadedRadiusMeters
        ) {
            DiagLog.log(
                "camera",
                "idle z=%.1f screen=%.0f m — already covered by the loaded %d m area".format(
                    bounds.zoom, viewR, prev.loadedRadiusMeters,
                ),
            )
            return
        }
        DiagLog.log(
            "camera",
            "idle z=%.1f screen=%.0f m want=%d m have=%d m → refetch".format(
                bounds.zoom, viewR, fetchR, prev.loadedRadiusMeters,
            ),
        )
        panJob?.cancel()
        panJob = viewModelScope.launch {
            // Revisiting an area already parsed in memory should feel immediate, so the
            // ride-out-the-flick debounce only applies when we'd actually hit the network.
            val warm = overpass.isWarm(center, fetchR, mtb = prev.mode == MapMode.MTB)
            delay(if (warm) PAN_DEBOUNCE_WARM_MS else PAN_DEBOUNCE_MS)
            load(center, fetchR)
        }
    }

    /**
     * Fold a freshly loaded circle into [loadedAreas] and return the union, nearest first.
     *
     * Distances are re-measured from [viewCenter] so the Trails list stays coherent — each
     * circle originally measured them from its own centre, and mixing those would order the
     * list by an accident of fetch history.
     */
    private fun mergeArea(
        center: GeoPoint,
        radius: Int,
        mtb: Boolean,
        trails: List<Trail>,
        viewCenter: GeoPoint,
    ): List<Trail> {
        loadedAreas.removeAll { it.center == center && it.radius == radius && it.mtb == mtb }
        loadedAreas.add(LoadedArea(center, radius, mtb, trails))

        // Evict what is furthest from where the user is looking, never what was just loaded.
        // Evicting the *oldest* is what made the map collapse: panning through empty country
        // pulled in three circles holding 13, 5 and 57 trails, and each one displaced part of
        // the dense 302-trail metro circle that was the whole reason anything was on screen.
        while (loadedAreas.size > 1 &&
            (loadedAreas.size > MAX_LOADED_AREAS ||
                loadedAreas.sumOf { it.vertices } > MAX_RETAINED_VERTICES)
        ) {
            val newest = loadedAreas.lastIndex
            // Areas belonging to the other mode go first — they aren't being drawn, they're
            // just holding budget — then whatever is furthest from the viewport. The other
            // mode's circles survive on headroom, so switching back doesn't start from one.
            val victim = (0 until newest).maxWithOrNull(
                compareBy(
                    { if (loadedAreas[it].mtb == mtb) 0 else 1 },
                    { Geo.haversineMeters(loadedAreas[it].center, viewCenter) },
                ),
            ) ?: break
            loadedAreas.removeAt(victim)
        }

        return unionFor(mtb)
            .map { it.copy(distanceMeters = nearestVertexDistance(it, viewCenter)) }
            .sortedBy { it.distanceMeters }
    }

    /**
     * The retained trails belonging to one mode, deduped. ALL and MTB are different datasets
     * over the same ground: without this scoping, switching ALL → MTB left paved greenway
     * circles in the union, where they survive MTB's 25-mile list filter almost entirely, get
     * grouped into invented "systems", and render under a legend headed "Difficulty" despite
     * carrying no mtb:scale at all.
     *
     * The longest copy wins on an id collision, since a wider circle catches more of a named
     * trail's member ways. Distances are left untouched — recomputing them walks every vertex,
     * which is worth doing on a load but not on a mode toggle.
     */
    private fun unionFor(mtb: Boolean): List<Trail> {
        val byId = LinkedHashMap<String, Trail>()
        for (area in loadedAreas) {
            if (area.mtb != mtb) continue
            for (t in area.trails) {
                // Keep the most complete copy, not the most recent one. A named trail is
                // assembled from however many of its member ways a circle happened to catch,
                // so the same id can arrive shorter from a circle that only clipped its end —
                // and letting that win made long trails visibly shrink as you panned.
                val held = byId[t.id]
                if (held == null || t.lengthMeters > held.lengthMeters) byId[t.id] = t
            }
        }
        return byId.values.sortedBy { it.distanceMeters }
    }

    /** Distance to the closest vertex, without flattening the paths into a new list. */
    private fun nearestVertexDistance(trail: Trail, p: GeoPoint): Double {
        var best = Double.MAX_VALUE
        for (path in trail.paths) {
            for (v in path) {
                val d = Geo.haversineMeters(v, p)
                if (d < best) best = d
            }
        }
        return if (best == Double.MAX_VALUE) 0.0 else best
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
        e is java.io.InterruptedIOException || e.message?.contains("Timed out") == true ->
            if (haveTrails) "OpenStreetMap isn't responding — showing what's cached"
            else "OpenStreetMap isn't responding. Try again in a minute."
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
     * Has the map moved far enough off the loaded data to be worth asking again?
     *
     * This deliberately does *not* ask whether the loaded circle covers the screen. Past a
     * certain zoom it never can — the screen is 166 km across and the widest circle the app
     * will fetch is 24 km — so a coverage test is permanently unsatisfied and fires on every
     * single camera idle. An on-device log showed exactly that: three refetches in two
     * seconds while zoomed out, each cancelling the last mid-download, which is both why the
     * map was perpetually "Loading trails" and how the app earned a rate-limit block.
     *
     * Distance moved is the honest question, and the threshold scales two ways: with the
     * circle we hold, and — once the screen is wider than that circle — with the screen, so
     * the refetch cadence stays proportional to how much ground a pan actually covers.
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
        val threshold = maxOf(have * MIN_DRIFT_FRACTION, viewRadius * WIDE_DRIFT_FRACTION)
        return Geo.haversineMeters(reference, center) > threshold
    }

    /**
     * Mirror the state packs into UI state, and reload the moment a new pack covers the map:
     * until then that area came from Overpass, and a failed or slow load is likely what is still
     * on screen.
     */
    private fun watchTrailPack() {
        val packs = overpass.pack ?: return
        viewModelScope.launch {
            var known = packs.installed.value.map { it.id }.toSet()
            combine(packs.installed, packs.index, packs.selected) { installed, _, _ -> installed }.collect { installed ->
                refreshPackStates()
                val ids = installed.map { it.id }.toSet()
                val added = ids - known
                known = ids
                if (added.isNotEmpty()) {
                    val here = lastCamera?.point ?: _state.value.center
                    if (bootstrapped && !bootstrapPending && overpass.packCovers(here)) {
                        DiagLog.log("pack", "${added.joinToString()} ready; reloading the current area from it")
                        load(here, initialFetchRadius())
                    }
                }
                // First run: the index has just arrived and nothing is chosen yet.
                if (packs.selectionUnset && packs.index.value != null && bootstrapped && !bootstrapPending) {
                    chooseDefaultStates(_state.value.center)
                }
            }
        }
        viewModelScope.launch {
            TrailPackWorker.observe(getApplication()).collect { info ->
                _state.update { s ->
                    s.copy(
                        packDownload = info?.takeIf { it.state == WorkInfo.State.RUNNING }?.progress?.let { p ->
                            p.getString(TrailPackWorker.KEY_STATE)?.let { name ->
                                PackDownload(
                                    name,
                                    p.getInt(TrailPackWorker.KEY_NUMBER, 1),
                                    p.getInt(TrailPackWorker.KEY_COUNT, 1),
                                    p.getLong(TrailPackWorker.KEY_DONE, 0L),
                                    p.getLong(TrailPackWorker.KEY_TOTAL, 0L),
                                )
                            }
                        },
                        packWaiting = info?.state == WorkInfo.State.ENQUEUED,
                        packFailed = info?.state == WorkInfo.State.FAILED,
                    )
                }
            }
        }
    }

    /** Rebuild [TrailsUiState.packStates] from the index, the choice and what is on disk. */
    private fun refreshPackStates() {
        val packs = overpass.pack ?: return
        val installed = packs.installedStates()
        val selected = packs.selected.value
        val offered = packs.index.value?.states.orEmpty()
        val here = lastCamera?.point ?: _state.value.center
        val rows = offered.map { e ->
            val info = installed[e.slug]
            PackState(
                slug = e.slug,
                name = e.name,
                // What it takes on the phone once there; the release's size until then.
                bytes = info?.bytes ?: e.bytes,
                selected = e.slug in selected,
                installed = info != null,
                osmTimestamp = info?.osmTimestamp,
                updateAvailable = info != null && info.built.isNotEmpty() && e.built.isNotEmpty() && e.built > info.built,
                nearby = e.near(here, NEARBY_DEG),
            )
        } + (installed.keys + selected).filter { slug -> offered.none { it.slug == slug } }.distinct().map { slug ->
            // On the phone or chosen, but not in the index (not fetched yet, or dropped from the build).
            val info = installed[slug]
            PackState(slug, packs.stateName(slug), info?.bytes ?: 0, slug in selected, info != null, info?.osmTimestamp)
        }
        _state.update { it.copy(packStates = rows.sortedBy { r -> r.name }) }
        updatePackSuggestion(lastCamera?.point ?: _state.value.center)
    }

    /**
     * First run with nothing chosen: take the state (or, on a border, states) the user is in.
     * That is what makes the map fast out of the box; the Trail data screen changes it.
     */
    private fun chooseDefaultStates(here: GeoPoint) {
        val packs = overpass.pack ?: return
        if (!packs.selectionUnset) return
        val states = packs.statesAround(here, DEFAULT_STATES_REACH_M).map { it.slug }.toSet()
        if (states.isEmpty()) return
        DiagLog.log("pack", "first run: choosing ${states.joinToString()}")
        packs.setSelected(states)
        TrailPackWorker.enqueue(getApplication())
    }

    /** Offer the state under the map when its trails aren't on the phone. */
    private fun updatePackSuggestion(center: GeoPoint) {
        val packs = overpass.pack ?: return
        val suggestion = if (overpass.packCovers(center)) null else {
            // Around the map, not just under it: next to a state line the tile also needs the
            // neighbour, and offering only the state underfoot (already on the phone) would
            // leave the user wondering why loading is still slow.
            val slug = packs.statesAround(center, DEFAULT_STATES_REACH_M).map { it.slug }
                .firstOrNull { it !in packs.selected.value && it !in dismissedSuggestions }
            _state.value.packStates.firstOrNull { it.slug == slug }
        }
        if (suggestion != _state.value.packSuggestion) _state.update { it.copy(packSuggestion = suggestion) }
    }

    fun dismissPackSuggestion() {
        _state.value.packSuggestion?.let { dismissedSuggestions += it.slug }
        _state.update { it.copy(packSuggestion = null) }
    }

    /** Put a state's trails on the phone. */
    fun addPackState(slug: String) {
        overpass.pack?.select(slug) ?: return
        TrailPackWorker.enqueue(getApplication())
    }

    /** Take a state's trails off the phone; its pack is deleted straight away. */
    fun removePackState(slug: String) {
        overpass.pack?.deselect(slug)
    }

    /** Look for newer packs now; only changed states are downloaded. */
    fun checkTrailPacks() = TrailPackWorker.enqueue(getApplication())

    /** Recount the old per-region trail downloads; the Offline screen calls this when it opens. */
    fun refreshOfflineSize() = viewModelScope.launch {
        val bytes = withContext(Dispatchers.IO) { overpass.durableBytes() }
        _state.update { it.copy(offlineTrailBytes = bytes) }
    }

    /** Delete the old per-region trail downloads; state packs hold those trails now. */
    fun clearOfflineTrails() = viewModelScope.launch {
        withContext(Dispatchers.IO) { overpass.clearDurable() }
        _state.update { it.copy(offlineTrailBytes = 0L) }
    }

    /**
     * Add the trail packs an offline map area needs, so downloading a region brings its trails
     * as well as its map. Returns the names of the states added (empty if all were on the phone,
     * or the list of states hasn't been fetched yet).
     *
     * A state overview only takes the state it is centred on — its box is a rectangle that
     * clips half a dozen neighbours. A city or the current view takes every state it touches:
     * a metro on a state line (Kansas City, St. Louis) needs both to load from the phone.
     */
    fun addPackStatesFor(bounds: ViewBounds, overview: Boolean = false): List<String> {
        val packs = overpass.pack ?: return emptyList()
        val centre = GeoPoint((bounds.north + bounds.south) / 2, (bounds.east + bounds.west) / 2)
        val wanted = if (overview) packs.statesAt(centre) else packs.statesIn(bounds.west, bounds.south, bounds.east, bounds.north)
        val added = wanted.filter { it.slug !in packs.selected.value }.take(MAX_STATES_PER_AREA)
        if (added.isEmpty()) return emptyList()
        added.forEach { packs.select(it.slug) }
        TrailPackWorker.enqueue(getApplication())
        return added.map { it.name }
    }

    /** The whole of an offline area loads from the phone's trail packs. */
    fun areaTrailsOnPhone(bounds: ViewBounds): Boolean {
        for (i in 0..2) for (j in 0..2) {
            val p = GeoPoint(bounds.south + (bounds.north - bounds.south) * i / 2, bounds.west + (bounds.east - bounds.west) * j / 2)
            if (!overpass.packCovers(p)) return false
        }
        return true
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
    fun selectTrail(id: String) = _state.update { it.copy(selectedTrailId = id, selectedConditionId = null) }

    fun clearSelection() = _state.update { it.copy(selectedTrailId = null, selectedConditionId = null) }

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

    private fun Trail.toRideTrail() = RideTrail(
        id = id, name = name, lengthMeters = lengthMeters, surface = surface.name, mtbScale = mtbScale,
        lat = center.lat, lon = center.lon,
    )

    /** Move a ride's trail from one position to another (drag to reorder). */
    fun moveTrailInRide(rideId: String, from: Int, to: Int) = persistRides(
        _state.value.rides.map { r ->
            if (r.id != rideId || from !in r.trails.indices || to !in r.trails.indices) r
            else r.copy(trails = r.trails.toMutableList().apply { add(to, removeAt(from)) })
        },
    )

    /**
     * Highlight every trail in a ride and frame them. The camera goes to the middle of the
     * trails' recorded centres at a zoom that fits them; pan-loading then pulls the trails in
     * and the highlight picks them up as they arrive.
     */
    fun showRideOnMap(rideId: String) {
        val ride = rideById(rideId) ?: return
        val pts = ride.trails.mapNotNull { t -> t.lat?.let { lat -> t.lon?.let { GeoPoint(lat, it) } } }
            .ifEmpty { _state.value.trails.filter { t -> ride.trails.any { it.id == t.id } }.map { it.center } }
        val target = frame(pts)
        _state.update {
            it.copy(highlightedRideId = rideId, highlightedTrackId = null, selectedTrailId = null, focusTarget = target ?: it.focusTarget)
        }
    }

    /** A camera that fits [pts] with a margin; null for no points. */
    private fun frame(pts: List<GeoPoint>): CameraTarget? {
        if (pts.isEmpty()) return null
        val n = pts.maxOf { it.lat }; val s = pts.minOf { it.lat }
        val e = pts.maxOf { it.lon }; val w = pts.minOf { it.lon }
        // MapLibre's zoom counts 512-dp tiles, so a phone's ~393 dp width is about 0.75 of one;
        // leave a 60% margin around the span. (This used to assume 1.6 tiles across — 256-px
        // tiles — which framed everything a zoom level too close and cut the ends off.)
        val span = maxOf(n - s, (e - w) * kotlin.math.cos(Math.toRadians((n + s) / 2)), 0.005)
        val zoom = (kotlin.math.ln(0.75 * 360.0 / (span * 1.6)) / kotlin.math.ln(2.0)).coerceIn(9.0, 15.0)
        return CameraTarget(GeoPoint((n + s) / 2, (e + w) / 2), zoom)
    }

    fun clearRideHighlight() = _state.update { it.copy(highlightedRideId = null) }

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

    fun setSort(sort: TrailSort) = _state.update { it.copy(sort = sort) }

    /** Show only saved trails (used by the Trails list "saved" toggle). */
    fun setShowSavedOnly(on: Boolean) = _state.update { it.copy(showSavedOnly = on) }

    /** Pick the app + basemap theme (persisted): follow the system, or force light/dark. */
    fun setMapTheme(theme: MapTheme) = _state.update {
        prefs.setMapTheme(theme.name)
        it.copy(mapTheme = theme)
    }

    /** Filters sheet "Reset": every surface and use, any length. Mode and radius stay. */
    fun resetFilters() = _state.update {
        val defaults = TrailsUiState()
        it.copy(
            selectedSurfaces = defaults.selectedSurfaces,
            selectedUses = defaults.selectedUses,
            horseTrails = defaults.horseTrails,
            minLengthMiles = defaults.minLengthMiles,
            ridden = defaults.ridden,
        )
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

    fun setHorseTrails(f: HorseTrailFilter) = _state.update { it.copy(horseTrails = f) }

    fun toggleUse(u: UseType) = _state.update {
        val next = it.selectedUses.toMutableSet().apply { if (!add(u)) remove(u) }
        it.copy(selectedUses = next)
    }

    // --- Your activity, from myvitals ---------------------------------------------------------

    /** Pick up a saved connection and its tracks at startup; nothing touches the network here. */
    private fun loadMyVitals() = viewModelScope.launch {
        val gen = settingsGen
        val (settings, tracks, conditions) = withContext(Dispatchers.IO) {
            Triple(myVitalsStore.settings(), myVitalsStore.tracks(), myVitalsStore.conditions())
        }
        if (gen != settingsGen) {
            // A connect or disconnect finished while this was reading; what it put in place is
            // newer than what was read, and already on screen.
            _state.update { it.copy(myVitals = it.myVitals.copy(settingsLoaded = true)) }
            return@launch
        }
        myVitals = settings
        if (!settings.configured) {
            _state.update { it.copy(myVitals = it.myVitals.copy(settingsLoaded = true), myVitalsOfferCurrent = false) }
            return@launch
        }
        _state.update {
            it.copy(
                // A connect still being checked keeps its spinner and its message.
                myVitals = MyVitalsUi.of(settings).copy(connecting = it.myVitals.connecting, error = it.myVitals.error),
                recorded = tracks,
                conditions = conditions,
                // An offer that came with the launch arrived before this read; compare it now.
                myVitalsOfferCurrent = isCurrent(it.myVitalsOffer, settings),
            )
        }
        rebuildIndex(tracks)
        if (foreground) {
            maybeAutoSync()
            startConditionsLoop()
        }
    }

    /**
     * The app came on screen or left it. Auto-sync runs on the way in, when due, and trail
     * conditions refresh every [CONDITIONS_EVERY_MS] while it stays; nothing runs in the
     * background.
     */
    fun setForeground(on: Boolean) {
        foreground = on
        if (on) {
            maybeAutoSync()
            startConditionsLoop()
        } else {
            conditionsJob?.cancel()
        }
    }

    private fun maybeAutoSync() {
        val s = myVitals
        if (s.configured && s.autoSync && System.currentTimeMillis() - s.lastSync >= AUTO_SYNC_EVERY_MS) sync(manual = false)
    }

    /**
     * Connect from the form: check an address and token against myvitals and, if they work,
     * save them and take the tracks that came back with the check. Nothing is saved when it
     * fails.
     */
    fun connectMyVitals(url: String, token: String) = connect(url, token, fromOfferCard = false)

    /**
     * [connectMyVitals], and "Use this" on an offer, which reports a failure in the offer's
     * card ([TrailsUiState.myVitalsOfferError]) rather than as the connection's own error.
     */
    private fun connect(url: String, token: String, fromOfferCard: Boolean) {
        syncJob?.cancel()
        // A conditions fetch in flight is for the old server; landing after the new one's, it
        // would put the old board back. The loop starts again once this settles.
        conditionsJob?.cancel()
        // The offer on screen when Connect was tapped; a success settles it.
        val offerId = _state.value.myVitalsOffer?.id
        syncJob = viewModelScope.launch {
            // Cancelling syncJob may have stopped a sync mid-way, which leaves nothing to clear its flag.
            _state.update {
                it.copy(
                    myVitals = it.myVitals.copy(
                        connecting = true, syncing = false,
                        // A sync's failure is still true of the connection in use.
                        error = if (fromOfferCard) it.myVitals.error else null,
                    ),
                    myVitalsOfferError = null,
                )
            }
            try {
                val key = token.trim()
                val t0 = android.os.SystemClock.elapsedRealtime()
                val (base, tracks) = myVitalsClient.connect(url, key)
                val conditions = runCatching { myVitalsClient.conditions(base, key) }
                    .getOrElse { if (it is CancellationException) throw it else emptyList() }
                val now = System.currentTimeMillis()
                val settings = MyVitalsSettings(
                    url = MyVitalsClient.normalizeUrl(url), token = key, apiBase = base,
                    autoSync = myVitals.autoSync, lastSync = now, lastConditionsSync = now,
                )
                storeLock.withLock {
                    withContext(Dispatchers.IO) {
                        myVitalsStore.saveSettings(settings)
                        myVitalsStore.saveTracks(tracks)
                        myVitalsStore.saveConditions(conditions)
                    }
                    myVitals = settings
                    settingsGen++
                }
                DiagLog.log(
                    "myvitals",
                    "connected in ${android.os.SystemClock.elapsedRealtime() - t0} ms: " +
                        "${tracks.size} tracks, ${conditions.size} trail conditions",
                )
                _state.update {
                    // A newer offer that arrived during the connect stays for the user to see.
                    val waiting = it.myVitalsOffer?.takeIf { o -> o.id != offerId }
                    it.copy(
                        myVitals = MyVitalsUi.of(settings), recorded = tracks, conditions = conditions,
                        myVitalsOffer = waiting,
                        myVitalsOfferError = if (waiting == null) null else it.myVitalsOfferError,
                        myVitalsOfferCurrent = isCurrent(waiting, settings),
                    )
                }
                rebuildIndex(tracks)
                // A loop the app's return to the foreground started meanwhile was for the old server.
                conditionsJob?.cancel()
                if (foreground) startConditionsLoop()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DiagLog.log("myvitals", "connect failed: ${e.message}")
                val message = e.message ?: "Couldn't connect"
                _state.update {
                    // A newer offer arrived while this one was trying: its card or form must not
                    // wear this one's error.
                    val superseded = it.myVitalsOffer?.id != offerId
                    when {
                        superseded -> it.copy(myVitals = it.myVitals.copy(connecting = false))
                        fromOfferCard -> it.copy(myVitals = it.myVitals.copy(connecting = false), myVitalsOfferError = message)
                        else -> it.copy(myVitals = it.myVitals.copy(connecting = false, error = message))
                    }
                }
                // Nothing changed: a connection that was working carries on refreshing.
                if (foreground) startConditionsLoop()
            }
        }
    }

    /** [offer] is the saved connection: the same address once normalised, and the same key. */
    private fun isCurrent(offer: MyVitalsOffer?, s: MyVitalsSettings = myVitals): Boolean =
        offer != null && s.configured && offer.sameConnection(s.url, s.token)

    /**
     * The myvitals app sent its address and access key. They wait on the myvitals screen, filled
     * in, until the user connects or dismisses them; nothing is checked or saved before that.
     * Each offer gets its own id, even one identical to the last. The log gets the host only,
     * never the key.
     */
    fun offerMyVitals(offer: MyVitalsOffer) {
        val numbered = offer.copy(id = ++offerSeq)
        val current = isCurrent(numbered)
        DiagLog.log(
            "myvitals",
            "the myvitals app sent a connection to ${offer.host ?: "an address that isn't a web address"}" +
                if (current) " (the one already in use)" else "",
        )
        _state.update {
            it.copy(
                myVitalsOffer = numbered,
                myVitalsOfferError = null,
                myVitalsOfferCurrent = current,
                // Not connected, the offer fills the form, and an old "didn't accept that access
                // key" under it would be about some other key.
                myVitals = if (it.myVitals.connected) it.myVitals else it.myVitals.copy(error = null),
            )
        }
    }

    fun dismissMyVitalsOffer() = _state.update {
        it.copy(myVitalsOffer = null, myVitalsOfferError = null, myVitalsOfferCurrent = false)
    }

    /**
     * "Use this" on an offer while already connected. The working connection is only replaced
     * if the offered one connects, and the offer stays on screen until it does. An offer of
     * the connection already in use has nothing to switch to.
     */
    fun acceptMyVitalsOffer() {
        val offer = _state.value.myVitalsOffer ?: return
        if (isCurrent(offer)) {
            dismissMyVitalsOffer()
            return
        }
        connect(offer.url, offer.token, fromOfferCard = true)
    }

    /** "Sync now". */
    fun syncMyVitals() = sync(manual = true)

    /**
     * Fetch the tracks and trail conditions again. The whole list comes each time: it is a few
     * hundred kilobytes, and a full copy is the only way to see activities deleted, retyped or
     * given a route after the fact. A failure keeps what's on the phone.
     */
    private fun sync(manual: Boolean) {
        val s = myVitals
        if (!s.configured || syncJob?.isActive == true) return
        syncJob = viewModelScope.launch {
            _state.update { it.copy(myVitals = it.myVitals.copy(syncing = true, error = null)) }
            val t0 = android.os.SystemClock.elapsedRealtime()
            try {
                val tracks = myVitalsClient.tracks(s.apiBase, s.token)
                conditionsAttemptAt = System.currentTimeMillis()
                val conditions = runCatching { myVitalsClient.conditions(s.apiBase, s.token) }
                    .getOrElse { if (it is CancellationException) throw it else null }
                val now = System.currentTimeMillis()
                val next = storeLock.withLock {
                    // The connection changed while this was out: these are another server's tracks.
                    if (myVitals.apiBase != s.apiBase || myVitals.token != s.token) return@withLock null
                    val next = myVitals.copy(lastSync = now, lastConditionsSync = if (conditions != null) now else myVitals.lastConditionsSync)
                    withContext(Dispatchers.IO) {
                        myVitalsStore.saveSettings(next)
                        myVitalsStore.saveTracks(tracks)
                        conditions?.let { myVitalsStore.saveConditions(it) }
                    }
                    myVitals = next
                    next
                }
                if (next == null) {
                    _state.update { it.copy(myVitals = it.myVitals.copy(syncing = false)) }
                    return@launch
                }
                val changed = tracks != _state.value.recorded
                DiagLog.log(
                    "myvitals",
                    "${if (manual) "sync" else "auto-sync"} in ${android.os.SystemClock.elapsedRealtime() - t0} ms: " +
                        "${tracks.size} tracks${if (changed) " (changed)" else ""}, ${conditions?.size ?: "no"} trail conditions",
                )
                _state.update {
                    it.copy(
                        myVitals = MyVitalsUi.of(next),
                        recorded = if (changed) tracks else it.recorded,
                        conditions = conditions ?: it.conditions,
                    )
                }
                if (changed) rebuildIndex(tracks)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DiagLog.log("myvitals", "${if (manual) "sync" else "auto-sync"} failed: ${e.message}")
                _state.update { it.copy(myVitals = it.myVitals.copy(syncing = false, error = e.message ?: "Sync failed")) }
            }
        }
    }

    /** Refresh trail conditions every [CONDITIONS_EVERY_MS] while the app is on screen. */
    private fun startConditionsLoop() {
        if (conditionsJob?.isActive == true) return
        conditionsJob = viewModelScope.launch {
            while (true) {
                val s = myVitals
                if (!s.configured || !s.autoSync) break
                val dueAt = maxOf(s.lastConditionsSync, conditionsAttemptAt) + CONDITIONS_EVERY_MS
                val wait = dueAt - System.currentTimeMillis()
                if (wait > 0) {
                    delay(wait)
                    continue
                }
                conditionsAttemptAt = System.currentTimeMillis()
                val list = try {
                    myVitalsClient.conditions(s.apiBase, s.token)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    DiagLog.log("myvitals", "trail conditions failed: ${e.message}")
                    null
                } ?: continue
                val next = storeLock.withLock {
                    // The connection changed while this was out: it is another server's board.
                    // Checked under the lock, so a connect can't slip in between check and save.
                    if (myVitals.apiBase != s.apiBase || myVitals.token != s.token) return@withLock null
                    val next = myVitals.copy(lastConditionsSync = System.currentTimeMillis())
                    withContext(Dispatchers.IO) {
                        myVitalsStore.saveSettings(next)
                        myVitalsStore.saveConditions(list)
                    }
                    myVitals = next
                    next
                } ?: continue
                DiagLog.log("myvitals", "trail conditions: ${list.count { it.status == "open" }} open of ${list.size}")
                _state.update { it.copy(conditions = list, myVitals = it.myVitals.copy(lastConditionsSync = next.lastConditionsSync)) }
            }
        }
    }

    /** Sync on app open (at most hourly) plus the conditions refresh, or manual only. */
    fun setMyVitalsAutoSync(on: Boolean) {
        val next = myVitals.copy(autoSync = on)
        myVitals = next
        viewModelScope.launch {
            // Whatever is current when the lock comes free: a save in between may have moved on.
            storeLock.withLock {
                val cur = myVitals.copy(autoSync = on)
                if (!cur.configured) return@withLock
                withContext(Dispatchers.IO) { myVitalsStore.saveSettings(cur) }
                myVitals = cur
            }
        }
        _state.update { it.copy(myVitals = it.myVitals.copy(autoSync = on)) }
        if (on && foreground) {
            maybeAutoSync()
            startConditionsLoop()
        } else if (!on) {
            conditionsJob?.cancel()
        }
    }

    /** Forget the server: address, token, and every track and condition on the phone. */
    fun disconnectMyVitals() {
        syncJob?.cancel()
        conditionsJob?.cancel()
        visitsJob?.cancel()
        myVitals = MyVitalsSettings()
        settingsGen++
        trackIndex = null
        visitCache = null
        synchronized(auxTrails) { auxTrails.clear() }
        _recordedTrails.value = emptyMap()
        // After any save already under way, so nothing lands in the directory once it's gone.
        viewModelScope.launch { storeLock.withLock { withContext(Dispatchers.IO) { myVitalsStore.clear() } } }
        DiagLog.log("myvitals", "disconnected; tracks and token deleted from the phone")
        _state.update {
            it.copy(
                myVitals = MyVitalsUi(settingsLoaded = true),
                myVitalsOfferError = null,
                myVitalsOfferCurrent = false,
                recorded = emptyList(),
                visits = emptyMap(),
                visitsVersion = it.visitsVersion + 1,
                conditions = emptyList(),
                pace = null,
                ridden = RiddenFilter.ANY,
                sort = if (it.sort == TrailSort.LAST_RIDDEN) TrailSort.DISTANCE else it.sort,
                highlightedTrackId = null,
                selectedConditionId = null,
            )
        }
    }

    /** Index the tracks and work out the personal pace, off the main thread. */
    private suspend fun rebuildIndex(tracks: List<RecordedTrack>) {
        val (index, pace) = withContext(Dispatchers.Default) {
            val t0 = android.os.SystemClock.elapsedRealtime()
            val idx = if (tracks.isEmpty()) null else TrackIndex(tracks)
            DiagLog.log("myvitals", "indexed ${idx?.size ?: 0} tracks in ${android.os.SystemClock.elapsedRealtime() - t0} ms")
            idx to PersonalPace.from(tracks)
        }
        trackIndex = index
        visitCache = index?.let { VisitCache(it) }
        _state.update { it.copy(pace = pace) }
        scheduleVisits()
    }

    /**
     * Work out visits for the trails in the loaded set. Trails already matched against this
     * index with the same geometry are reused, so a pan only pays for the trails it brought in.
     */
    private fun scheduleVisits() {
        visitsJob?.cancel()
        val cache = visitCache
        if (cache == null) {
            if (_state.value.visits.isNotEmpty()) _state.update { it.copy(visits = emptyMap(), visitsVersion = it.visitsVersion + 1) }
            return
        }
        val trails = _state.value.trails
        visitsJob = viewModelScope.launch(Dispatchers.Default) {
            val t0 = android.os.SystemClock.elapsedRealtime()
            var matched = 0
            val out = HashMap<String, TrailVisits>()
            for (t in trails) {
                ensureActive()
                val sig = geometryKey(t)
                val held = synchronized(cache) { cache.entries[t.id] }
                val v = if (held != null && held.first == sig) {
                    held.second
                } else {
                    matched++
                    cache.index.visits(t).also { synchronized(cache) { cache.entries[t.id] = sig to it } }
                }
                if (v != null) out[t.id] = v
            }
            if (matched > 0) {
                DiagLog.log(
                    "myvitals",
                    "matched $matched trails in ${android.os.SystemClock.elapsedRealtime() - t0} ms; " +
                        "${out.values.count { it.ridden }} of ${trails.size} ridden",
                )
            }
            if (out != _state.value.visits) _state.update { it.copy(visits = out, visitsVersion = it.visitsVersion + 1) }
        }
    }

    /** Identity for a trail's geometry: a wider circle can hand back the same id with more of it. */
    private fun geometryKey(t: Trail) =
        "${t.paths.size}:${t.lengthMeters.toLong()}:${t.paths.firstOrNull()?.firstOrNull()}"

    /**
     * Visits for any trail, including one opened from a recorded ride that isn't in the loaded
     * set. One trail is a few milliseconds.
     */
    fun visitsFor(trailId: String): TrailVisits? {
        _state.value.visits[trailId]?.let { return it }
        val trail = trailById(trailId) ?: return null
        return trackIndex?.visits(trail)
    }

    fun setRiddenFilter(f: RiddenFilter) = _state.update { it.copy(ridden = f) }

    fun setLayer(layer: YouLayer, on: Boolean) {
        prefs.setLayer(layer.name, on)
        _state.update {
            when (layer) {
                YouLayer.RIDDEN -> it.copy(showRidden = on)
                YouLayer.TRACKS -> it.copy(showTracks = on)
                YouLayer.CONDITIONS -> it.copy(showConditions = on, selectedConditionId = if (on) it.selectedConditionId else null)
            }
        }
    }

    /** A trailhead tapped on the map. */
    fun selectCondition(id: Long) = _state.update { it.copy(selectedConditionId = id, selectedTrailId = null) }

    /** Draw a recorded activity on the map and frame it. */
    fun showTrackOnMap(trackId: String) {
        val track = _state.value.recordedById[trackId] ?: return
        val target = frame(Polyline.decode(track.polyline))
        _state.update {
            it.copy(
                highlightedTrackId = trackId, highlightedRideId = null, selectedTrailId = null,
                focusTarget = target ?: it.focusTarget,
            )
        }
    }

    fun clearTrackHighlight() = _state.update { it.copy(highlightedTrackId = null) }

    /**
     * The trails a recorded activity went along, in the order it reached them. Loads the
     * trails around its track — from the state packs where they cover it, which is instant and
     * offline — and matches the one track against them.
     */
    fun loadRecordedTrails(trackId: String) {
        val current = _recordedTrails.value[trackId]
        if (current is RecordedTrails.Ready || current is RecordedTrails.Loading) return
        val track = _state.value.recordedById[trackId] ?: return
        _recordedTrails.update { it + (trackId to RecordedTrails.Loading) }
        viewModelScope.launch {
            try {
                val pts = withContext(Dispatchers.Default) { Polyline.decode(track.polyline) }
                if (pts.size < 2) {
                    _recordedTrails.update { it + (trackId to RecordedTrails.Ready(emptyList())) }
                    return@launch
                }
                val center = GeoPoint((pts.maxOf { it.lat } + pts.minOf { it.lat }) / 2, (pts.maxOf { it.lon } + pts.minOf { it.lon }) / 2)
                val reach = pts.maxOf { Geo.haversineMeters(center, it) } + RECORDED_MARGIN_M
                val radius = reach.coerceIn(MIN_RECORDED_RADIUS_M, MAX_RECORDED_RADIUS_M).toInt()
                val t0 = android.os.SystemClock.elapsedRealtime()
                val result = overpass.fetchTrails(center, radius, mtb = false, withParks = false)
                val along = withContext(Dispatchers.Default) {
                    TrackIndex(listOf(track)).trailsAlong(track.id, result.trails.filter { it.name != "Unnamed path" })
                }
                DiagLog.log(
                    "myvitals",
                    "recorded ${track.id.substringBefore(':')} activity: ${along.size} trails along it " +
                        "(${result.trails.size} within $radius m) in ${android.os.SystemClock.elapsedRealtime() - t0} ms",
                )
                synchronized(auxTrails) { along.forEach { auxTrails[it.trail.id] = it.trail } }
                _recordedTrails.update { it + (trackId to RecordedTrails.Ready(along)) }
            } catch (e: CancellationException) {
                _recordedTrails.update { it - trackId }
                throw e
            } catch (e: Exception) {
                _recordedTrails.update { it + (trackId to RecordedTrails.Failed(e.message ?: "Couldn't load the trails")) }
            }
        }
    }

    /** Forget a failed trail lookup so the screen can try again. */
    fun retryRecordedTrails(trackId: String) {
        _recordedTrails.update { it - trackId }
        loadRecordedTrails(trackId)
    }

    /**
     * Save the trails a recorded activity went along as a planned ride, in the order it rode
     * them. Returns the new ride's id, or null before the trails have loaded.
     */
    fun saveRecordedAsRide(trackId: String): String? {
        val ready = _recordedTrails.value[trackId] as? RecordedTrails.Ready ?: return null
        if (ready.trails.isEmpty()) return null
        val track = _state.value.recordedById[trackId] ?: return null
        val name = track.name ?: "${track.kind.label}, ${java.text.SimpleDateFormat("MMM d", java.util.Locale.US).format(java.util.Date(track.start))}"
        val id = "ride_" + System.currentTimeMillis()
        persistRides(_state.value.rides + Ride(id, name, ready.trails.map { it.trail.toRideTrail() }))
        return id
    }

    /** Lazy-load the elevation profile for a trail when its detail screen opens. */
    fun ensureProfile(trailId: String) {
        if (_profiles.value.containsKey(trailId)) return
        val trail = trailById(trailId) ?: return
        viewModelScope.launch {
            try {
                val profile = elevation.profile(trail.paths)
                _profiles.update { it + (trailId to profile) }
            } catch (e: Exception) {
                _profiles.update { it + (trailId to ElevationProfile.EMPTY) }
            }
        }
    }

    companion object {
        private fun milesToMeters(miles: Int) = (miles * 1609.344).toInt()

        /** Auto-sync with myvitals when the app opens, at most this often. */
        private const val AUTO_SYNC_EVERY_MS = 60 * 60 * 1000L

        /** Trail conditions refresh this often while the app is open — myvitals' own poll rate. */
        private const val CONDITIONS_EVERY_MS = 15 * 60 * 1000L

        /** Trails loaded for a recorded ride reach this far past its track… */
        private const val RECORDED_MARGIN_M = 500.0

        /** …in a circle no smaller or larger than these. */
        private const val MIN_RECORDED_RADIUS_M = 2_000.0
        private const val MAX_RECORDED_RADIUS_M = 40_000.0

        /** ALL-mode default: 5 mi, wide enough to catch parkway and bike routes. */
        private val DEFAULT_ALL_RADIUS = milesToMeters(5)

        /** Zoom used when the camera is moved to the user's location. */
        const val DEFAULT_ZOOM = 12.5

        /** Zoom used when a trail-system header recenters the map on a park. */
        private const val SYSTEM_FOCUS_ZOOM = 14.0

        /** "Near the map" on the Trail data screen: within this many degrees of a state's box. */
        private const val NEARBY_DEG = 0.5

        /** A zoomed-out current view can touch many states; only this many are added for it. */
        private const val MAX_STATES_PER_AREA = 4

        /** First-run states and the map's offer take every state this close to the map. */
        private const val DEFAULT_STATES_REACH_M = 25_000.0

        /** Wait this long after the camera settles before refetching, to ride out a flick-pan. */
        private const val PAN_DEBOUNCE_MS = 450L

        /** Shorter wait when the area is already in memory and will come back immediately. */
        private const val PAN_DEBOUNCE_WARM_MS = 120L

        /** Hold the loading pill back this long so cache hits never flash it. */
        private const val SPINNER_DELAY_MS = 250L


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

        /**
         * At zooms where the screen is wider than any circle we'd fetch, the threshold scales
         * with the screen instead — half a screen of panning, rather than one fetch per idle.
         */
        private const val WIDE_DRIFT_FRACTION = 0.5

        /** Refetch when zooming out needs a radius this many times what we already hold. */
        private const val ZOOM_OUT_FACTOR = 1.5

        /**
         * Coarse radius steps, so nearby viewports keep reusing the same cache keys. Every
         * step is clamped into [MIN_FETCH_RADIUS]..[MAX_AUTO_RADIUS], which currently makes
         * this a single value — kept as a ladder because the clamp is what enforces the size,
         * and widening the range again should not mean restructuring the calculation.
         */
        private val RADIUS_LADDER = intArrayOf(16000, 24000)

        /**
         * Largest circle we'll pull automatically. Sized for reliability rather than coverage:
         * on-device logs put a 16 km named-way pull at ~1.45 MB, back in 3-7 s every time,
         * against 3.1-3.8 MB for 24 km — which is what timed out. A wider view gets less
         * ground covered, but it gets it.
         */
        private const val MAX_AUTO_RADIUS = 24000

        /** Never fetch less than this, whatever the chip says — see [fetchRadiusFor]. */
        private const val MIN_FETCH_RADIUS = 16000

        /** How far past the loaded circle the radius chip may reach before it forces a fetch. */
        private const val RADIUS_CHIP_TOLERANCE_M = 500

        /** Attempts per load, so a momentary 502/504 from Overpass isn't a dead end. */
        private const val MAX_ATTEMPTS = 2
        private const val RETRY_DELAY_MS = 2500L

        /** How many loaded circles the map keeps drawn at once. */
        private const val MAX_LOADED_AREAS = 10

        /**
         * Retained geometry ceiling, in polyline vertices across all held areas.
         *
         * A GeoPoint is an object header plus two Doubles, ~32 bytes, and the enclosing lists
         * add roughly as much again — call it ~70 bytes per retained vertex. 200,000 vertices
         * is therefore on the order of 14 MB, which is affordable against a phone's heap and
         * roughly four dense metro circles' worth. It also keeps rendering sane: 400 trails
         * carrying ~50,000 vertices build and upload their GeoJSON in 45 ms on a Pixel 9a, so
         * a full budget lands near a fifth of a second, paid only when the set changes.
         */
        private const val MAX_RETAINED_VERTICES = 200_000

        /** Ceiling on one load, across every mirror and retry. */
        private const val LOAD_BUDGET_MS = 25_000L

        /**
         * [LOAD_BUDGET_MS] for MTB. Its 40 km trail query takes 32-38 s on a healthy public
         * mirror (measured), so 25 s meant MTB mode could never load at all.
         */
        private const val MTB_LOAD_BUDGET_MS = 90_000L


        /** How long the follow-up park query may take before systems keep their fallback names. */
        private const val PARKS_BUDGET_MS = 60_000L
    }
}
