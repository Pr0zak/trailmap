package com.trailmap.ui

import android.Manifest
import android.annotation.SuppressLint
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trailmap.data.DifficultyTier
import com.trailmap.data.Amenity
import com.trailmap.data.AmenityKind
import com.trailmap.data.DiagLog
import com.trailmap.data.Polyline
import com.trailmap.data.RecordedTrack
import com.trailmap.data.SurfaceType
import com.trailmap.data.Trail
import com.trailmap.data.TrailCondition
import com.trailmap.data.TrailConditions
import com.trailmap.data.TrailStatus
import com.trailmap.data.TrailVisits
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.SymbolLayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource

private const val SRC_TRAILS = "trails"
private const val LAYER_TRAILS = "trails-line"
private const val LAYER_TRAILS_CASING = "trails-line-casing"
private const val LAYER_DOUBLE_BLACK = "trails-double-black"
private const val LAYER_ONEWAY = "trails-oneway"
private const val IMG_ONEWAY = "oneway-arrow"
private const val SRC_HIGHLIGHT = "trail-highlight"
private const val LAYER_HIGHLIGHT = "trail-highlight-line"
// Your activity from myvitals: every recorded track, the ridden stretches of trails, one
// recorded activity drawn on its own, and trailheads with their open/closed state.
private const val SRC_TRACKS = "my-tracks"
private const val LAYER_TRACKS = "my-tracks-line"
private const val SRC_RIDDEN = "ridden"
private const val LAYER_RIDDEN = "ridden-glow"
private const val SRC_TRACK_HL = "track-highlight"
private const val LAYER_TRACK_HL = "track-highlight-line"
private const val LAYER_TRACK_HL_CASING = "track-highlight-casing"
private const val SRC_CONDITIONS = "conditions"
private const val SRC_AMENITIES = "amenities"
private const val SRC_CONTEXT = "context-trails"
private const val LAYER_CONTEXT = "context-trails-line"
private const val LAYER_CONTEXT_GRAVEL = "context-trails-gravel"

/** MTB mode's other trails, as (light, dark): a light grey under the rated lines. */
private val CONTEXT_LINE_COLOR = 0xCC9E9E9E to 0xCC8A8A8A
private const val LAYER_AMENITIES = "amenities-icon"
private const val LAYER_AMENITY_PARKING = "amenities-parking"
private const val LAYER_CONDITIONS = "conditions-dot"
private const val LAYER_CONDITION_LABELS = "conditions-label"
private const val EMPTY_FC = """{"type":"FeatureCollection","features":[]}"""
// OpenFreeMap vector styles: keyless and unmetered, and fine to download for offline use. CARTO's
// dark_all raster (dark, before 0.15.0) started answering every tile with "API KEY REQUIRED"; OSM's
// raster tiles (light, before 0.17.0) forbid the bulk downloading an offline area is.
private const val STYLE_LIGHT = "https://tiles.openfreemap.org/styles/liberty"
private const val STYLE_DARK = "https://tiles.openfreemap.org/styles/dark"

@SuppressLint("MissingPermission")
@Composable
fun MapScreen(
    vm: TrailsViewModel,
    onOpenTrail: (String) -> Unit,
    onOpenOffline: () -> Unit,
    onOpenMyVitals: () -> Unit = {},
) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val dark = when (ui.mapTheme) {
        MapTheme.SYSTEM -> isSystemInDarkTheme()
        MapTheme.LIGHT -> false
        MapTheme.DARK -> true
    }

    val mapRef = remember { mutableStateOf<MapLibreMap?>(null) }
    val styleRef = remember { mutableStateOf<Style?>(null) }
    val mapViewRef = remember { mutableStateOf<MapView?>(null) }

    // Forward the host lifecycle into the MapView (GL surface). Final teardown is in onRelease.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            val mv = mapViewRef.value ?: return@LifecycleEventObserver
            when (event) {
                Lifecycle.Event.ON_START -> mv.onStart()
                Lifecycle.Event.ON_RESUME -> mv.onResume()
                Lifecycle.Event.ON_PAUSE -> mv.onPause()
                Lifecycle.Event.ON_STOP -> mv.onStop()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // First-launch: request location, then locateAndLoad either way (KC fallback inside).
    val locationGranted = remember { mutableStateOf(vm.hasLocationPermission()) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        locationGranted.value = granted || vm.hasLocationPermission()
        vm.bootstrap()
    }
    LaunchedEffect(Unit) {
        // Only ask when the permission is actually missing. Re-launching the contract on an
        // already-granted permission fires the callback immediately, which used to mean a
        // full network refetch on every Map→Trails→Map bounce.
        if (vm.hasLocationPermission()) vm.bootstrap()
        else launcher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    // (Re)load the basemap style when the map is ready or the theme flips, then re-add
    // the trail source + casing/line layers. styleRef updating re-fires the feature effect.
    LaunchedEffect(mapRef.value, dark) {
        val map = mapRef.value ?: return@LaunchedEffect
        styleRef.value = null
        map.setStyle(Style.Builder().fromUri(if (dark) STYLE_DARK else STYLE_LIGHT)) { style ->
            applyTrailLayers(style, dark)
            styleRef.value = style
        }
    }

    // The blue "you are here" dot with its accuracy ring, fed live GPS fixes while the map is
    // on screen (started, location granted). CameraMode.NONE: the dot follows you, the camera doesn't —
    // panning drives trail loading, so tracking would fight the user. Re-activated on every
    // style load, since a theme flip replaces the style the component draws into.
    LaunchedEffect(styleRef.value, locationGranted.value) {
        val style = styleRef.value ?: return@LaunchedEffect
        val map = mapRef.value ?: return@LaunchedEffect
        if (!locationGranted.value) return@LaunchedEffect
        map.showLocationDot(context, style)
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            vm.locationUpdates().collect { fix ->
                val lc = map.locationComponent
                if (styleRef.value === style && lc.isLocationComponentActivated) lc.forceLocationUpdate(fix)
            }
        }
    }

    // The camera belongs to the user from here on: panning drives which trails are fetched,
    // so following ui.center would yank the map back and re-trigger the fetch it just
    // answered. Its starting position is set in the AndroidView factory below, and explicit
    // recentres go through ui.focusTarget.
    // Redraw the trail lines when the data or the filters change. Keyed on ui.filterKey (a
    // short String) rather than ui.filtered, so a camera idle doesn't drag Compose through a
    // structural comparison of every trail's geometry. The GeoJSON is built on a background
    // dispatcher — a Kansas City pull is ~30k coordinates and would jank the frame here.
    LaunchedEffect(ui.filterKey, ui.colorBy, styleRef.value) {
        val style = styleRef.value ?: return@LaunchedEffect
        val t0 = android.os.SystemClock.elapsedRealtime()
        // Horse trails get their own color when colouring by surface, in ALL mode only.
        // One colour key at a time (the Layers menu's "Colour trails by"): mixing levels with
        // surfaces put "easy" green beside "paved" green.
        val byLevel = ui.colorBy == ColorBy.DIFFICULTY
        val fc = withContext(Dispatchers.Default) {
            trailsFc(ui.filtered, horse = !byLevel && ui.mode == MapMode.ALL, levels = byLevel)
        }
        if (styleRef.value !== style) return@LaunchedEffect // theme flipped mid-build
        style.getSourceAs<GeoJsonSource>(SRC_TRAILS)?.setGeoJson(fc)
        DiagLog.log("map", "drew ${ui.filtered.size} trails, ${fc.length / 1024} KB in ${android.os.SystemClock.elapsedRealtime() - t0} ms")
    }

    // Highlight the selected trail (or clear the highlight when nothing is selected).
    // A highlighted ride lights up all of its trails that are loaded.
    LaunchedEffect(ui.selectedTrailId, ui.highlightedRideId, ui.rides, ui.trailsVersion, styleRef.value) {
        val style = styleRef.value ?: return@LaunchedEffect
        val rideIds = ui.highlightedRideId
            ?.let { rid -> ui.rides.firstOrNull { it.id == rid } }
            ?.trails?.map { it.id }?.toSet().orEmpty()
        val lit = ui.trails.filter { it.id == ui.selectedTrailId || it.id in rideIds } +
            ui.contextTrails.filter { it.id == ui.selectedTrailId }
        val fc = if (lit.isNotEmpty()) trailsFc(lit) else EMPTY_FC
        style.getSourceAs<GeoJsonSource>(SRC_HIGHLIGHT)?.setGeoJson(fc)
    }

    // Your recorded tracks, all of them, when the layer is on. A few hundred simplified tracks
    // are ~80k points; decoding and serialising them is done off the main thread, once per
    // sync or toggle.
    LaunchedEffect(ui.recorded, ui.showTracks, styleRef.value) {
        val style = styleRef.value ?: return@LaunchedEffect
        val fc = if (!ui.showTracks || ui.recorded.isEmpty()) EMPTY_FC else withContext(Dispatchers.Default) { tracksFc(ui.recorded) }
        if (styleRef.value !== style) return@LaunchedEffect
        style.getSourceAs<GeoJsonSource>(SRC_TRACKS)?.setGeoJson(fc)
    }

    // The ridden stretches of the trails on screen, as a glow under their lines.
    LaunchedEffect(ui.filterKey, ui.visitsVersion, ui.showRidden, styleRef.value) {
        val style = styleRef.value ?: return@LaunchedEffect
        val fc = if (!ui.showRidden || ui.visits.isEmpty()) EMPTY_FC else withContext(Dispatchers.Default) {
            pathsFc(ui.filtered.mapNotNull { ui.visits[it.id]?.riddenPaths }.flatten())
        }
        if (styleRef.value !== style) return@LaunchedEffect
        style.getSourceAs<GeoJsonSource>(SRC_RIDDEN)?.setGeoJson(fc)
    }

    // One recorded activity, bold, when "Show on map" was chosen for it.
    LaunchedEffect(ui.highlightedTrackId, ui.recorded, styleRef.value) {
        val style = styleRef.value ?: return@LaunchedEffect
        val track = ui.highlightedTrackId?.let { ui.recordedById[it] }
        val fc = if (track == null) EMPTY_FC else withContext(Dispatchers.Default) { pathsFc(listOf(Polyline.decode(track.polyline))) }
        style.getSourceAs<GeoJsonSource>(SRC_TRACK_HL)?.setGeoJson(fc)
    }

    // MTB mode's other trails, grey (gravel in gold when that switch is on), under the MTB lines.
    LaunchedEffect(ui.mtbGravel, styleRef.value) {
        val style = styleRef.value ?: return@LaunchedEffect
        style.getLayer(LAYER_CONTEXT_GRAVEL)?.setProperties(
            PropertyFactory.visibility(if (ui.mtbGravel) Property.VISIBLE else Property.NONE),
        )
    }
    LaunchedEffect(ui.contextVersion, ui.mode, styleRef.value) {
        val style = styleRef.value ?: return@LaunchedEffect
        val fc = if (ui.mode != MapMode.MTB || ui.contextTrails.isEmpty()) EMPTY_FC
        else withContext(Dispatchers.Default) { trailsFc(ui.contextTrails) }
        if (styleRef.value !== style) return@LaunchedEffect
        style.getSourceAs<GeoJsonSource>(SRC_CONTEXT)?.setGeoJson(fc)
    }

    // Trail amenities, when the layer is on.
    LaunchedEffect(ui.amenitiesVersion, ui.showAmenities, styleRef.value) {
        val style = styleRef.value ?: return@LaunchedEffect
        val fc = if (!ui.showAmenities || ui.amenities.isEmpty()) EMPTY_FC else withContext(Dispatchers.Default) { amenitiesFc(ui.amenities) }
        if (styleRef.value !== style) return@LaunchedEffect
        style.getSourceAs<GeoJsonSource>(SRC_AMENITIES)?.setGeoJson(fc)
    }

    // Trailheads on the status board, coloured open / closed.
    LaunchedEffect(ui.conditions, ui.showConditions, styleRef.value) {
        val style = styleRef.value ?: return@LaunchedEffect
        val fc = if (!ui.showConditions) EMPTY_FC else conditionsFc(ui.conditions)
        style.getSourceAs<GeoJsonSource>(SRC_CONDITIONS)?.setGeoJson(fc)
    }

    // One-shot: an explicit recenter (my-location, or a tapped trail-system header).
    LaunchedEffect(ui.focusTarget, styleRef.value) {
        val target = ui.focusTarget ?: return@LaunchedEffect
        val map = mapRef.value ?: return@LaunchedEffect
        if (styleRef.value == null) return@LaunchedEffect // wait for the style, don't drop the move
        map.animateCamera(
            org.maplibre.android.camera.CameraUpdateFactory.newLatLngZoom(
                LatLng(target.point.lat, target.point.lon),
                target.zoom ?: map.cameraPosition.zoom,
            ),
        )
        vm.consumeFocus()
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { c ->
                // Hand the MapView its camera up front. Constructed bare, MapLibre starts at
                // lat 0, lon 0, zoom 0 and reports a camera-idle from there before any effect
                // can move it — which used to overwrite the saved camera with the whole world,
                // so returning from another tab dropped the user onto a blank global map.
                val start = vm.lastCamera ?: CameraTarget(ui.center, TrailsViewModel.DEFAULT_ZOOM)
                val options = MapLibreMapOptions.createFromAttributes(c).camera(
                    CameraPosition.Builder()
                        .target(LatLng(start.point.lat, start.point.lon))
                        .zoom(start.zoom ?: TrailsViewModel.DEFAULT_ZOOM)
                        .build(),
                )
                MapView(c, options).apply {
                    onCreate(null)
                    getMapAsync { map ->
                        mapRef.value = map
                        // Every time the camera settles: record the viewport (for "download
                        // current view" offline) and let the ViewModel decide whether the pan
                        // moved far enough off the loaded area to warrant fetching new trails.
                        map.addOnCameraIdleListener {
                            val b = map.projection.visibleRegion.latLngBounds
                            val cam = map.cameraPosition
                            // CameraPosition.target is @Nullable; fall back to the bbox midpoint.
                            val lat = cam.target?.latitude ?: ((b.latitudeNorth + b.latitudeSouth) / 2.0)
                            val lon = cam.target?.longitude ?: ((b.longitudeEast + b.longitudeWest) / 2.0)
                            vm.onCameraIdle(
                                com.trailmap.data.ViewBounds(
                                    north = b.latitudeNorth, south = b.latitudeSouth,
                                    east = b.longitudeEast, west = b.longitudeWest,
                                    zoom = cam.zoom,
                                ),
                                com.trailmap.data.GeoPoint(lat, lon),
                            )
                        }
                        map.addOnMapClickListener { ll ->
                            // Query a padded box around the tap (not a single pixel) so tapping
                            // NEAR a thin trail line still selects it.
                            val pt = map.projection.toScreenLocation(ll)
                            val tol = 30f
                            val box = android.graphics.RectF(pt.x - tol, pt.y - tol, pt.x + tol, pt.y + tol)
                            // An amenity icon sits on top of the trails, so it wins.
                            val amen = map.queryRenderedFeatures(box, LAYER_AMENITIES, LAYER_AMENITY_PARKING).firstOrNull()
                            if (amen != null && amen.hasProperty("id")) {
                                vm.selectAmenity(amen.getStringProperty("id"))
                                return@addOnMapClickListener true
                            }
                            // A trailhead dot sits on top of the trails around it, so it wins.
                            val head = map.queryRenderedFeatures(box, LAYER_CONDITIONS).firstOrNull()
                            if (head != null && head.hasProperty("id")) {
                                vm.selectCondition(head.getNumberProperty("id").toLong())
                                return@addOnMapClickListener true
                            }
                            val f = map.queryRenderedFeatures(box, LAYER_TRAILS).firstOrNull()
                                ?: map.queryRenderedFeatures(box, LAYER_CONTEXT_GRAVEL, LAYER_CONTEXT).firstOrNull()
                            val id = f?.takeIf { it.hasProperty("id") }?.getStringProperty("id")
                            if (id != null) {
                                vm.selectTrail(id) // highlight + show the peek card
                                true
                            } else {
                                vm.clearSelection() // tap empty map → deselect
                                false
                            }
                        }
                    }
                    onStart(); onResume()
                    mapViewRef.value = this
                }
            },
            onRelease = {
                mapViewRef.value = null
                it.onPause(); it.onStop(); it.onDestroy()
            },
        )

        MapOverlays(
            ui = ui,
            dark = dark,
            selectedTrail = ui.selectedTrailId?.let { vm.trailById(it) },
            filters = FilterActions.of(vm),
            onSearchThisArea = vm::searchThisArea,
            onOpenTrail = onOpenTrail,
            onClearSelection = { vm.clearSelection() },
            onSetTheme = vm::setMapTheme,
            onOpenOffline = onOpenOffline,
            onRecenter = { vm.recenterOnMe(mapRef.value?.myLocation()) },
            onToggleSaved = vm::toggleSaved,
            onCreateRide = { name, t -> vm.createRide(name, seed = t) },
            onAddToRide = { rideId, t -> vm.addTrailToRide(rideId, t) },
            onClearRide = vm::clearRideHighlight,
            onGetPackState = vm::addPackState,
            onDismissPackSuggestion = vm::dismissPackSuggestion,
            selectedCondition = ui.selectedConditionId?.let { id -> ui.conditions.firstOrNull { it.id == id } },
            selectedAmenity = ui.selectedAmenityId?.let { id -> ui.amenities.firstOrNull { it.id == id } },
            onSetAmenities = vm::setShowAmenities,
            onSetColorBy = vm::setColorBy,
            onSetMtbGravel = vm::setMtbGravel,
            onSetLayer = vm::setLayer,
            onOpenMyVitals = onOpenMyVitals,
            onClearTrack = vm::clearTrackHighlight,
        )
    }
}

/**
 * Everything drawn over the map — filter card, status strip, peek card, legend and buttons —
 * kept free of MapLibre and the ViewModel so it can be rendered with sample state.
 */
@Composable
internal fun BoxScope.MapOverlays(
    ui: TrailsUiState,
    dark: Boolean,
    selectedTrail: Trail?,
    filters: FilterActions,
    onSearchThisArea: () -> Unit,
    onOpenTrail: (String) -> Unit,
    onClearSelection: () -> Unit,
    onSetTheme: (MapTheme) -> Unit,
    onOpenOffline: () -> Unit,
    onRecenter: () -> Unit,
    onToggleSaved: (String) -> Unit = {},
    onCreateRide: (String, Trail) -> Unit = { _, _ -> },
    onAddToRide: (String, Trail) -> Unit = { _, _ -> },
    onClearRide: () -> Unit = {},
    onGetPackState: (String) -> Unit = {},
    onDismissPackSuggestion: () -> Unit = {},
    selectedCondition: TrailStatus? = null,
    selectedAmenity: Amenity? = null,
    onSetAmenities: (Boolean) -> Unit = {},
    onSetColorBy: (ColorBy) -> Unit = {},
    onSetMtbGravel: (Boolean) -> Unit = {},
    onSetLayer: (YouLayer, Boolean) -> Unit = { _, _ -> },
    onOpenMyVitals: () -> Unit = {},
    onClearTrack: () -> Unit = {},
) {
    var showAddToRide by remember { mutableStateOf(false) }
        var showFilters by remember { mutableStateOf(false) }
        if (showFilters) FilterSheet(ui, filters, onDismiss = { showFilters = false })

        // Top: the search bar with the status strip stacked under it in one column, so the
        // strip follows the bar's real height. It used to sit at a fixed 108 dp, which put
        // the loading pill on top of the old chip rows.
        Column(
            // The map runs under the status bar; only the controls step down below it.
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().statusBarsPadding().padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TrailSearchBar(
                ui = ui,
                filters = filters,
                onOpenFilters = { showFilters = true },
                modifier = Modifier.fillMaxWidth(),
                showLoading = true,
            )

            // Under the bar: a "Search this area" button when the view has drifted off the
            // loaded trails and auto-load is off, otherwise a quiet count of what's drawn.
            val offerManualSearch = ui.viewportStale && !ui.loading && !ui.autoLoadOnPan
            if (offerManualSearch) {
                ElevatedButton(onClick = { onSearchThisArea() }) {
                    Icon(Icons.Filled.Search, contentDescription = null, Modifier.size(16.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Search this area")
                }
            } else {
                StatusPill(
                    when {
                        ui.loading && ui.mode == MapMode.MTB ->
                            "Loading mountain-bike trails… the first load can take up to a minute"
                        ui.loading -> "Updating trails…"
                        // At a wide zoom the fetch covers the middle of the screen, not all of
                        // it. Say so, so a sparse map reads as the edge of what was pulled.
                        !ui.canAutoCover && ui.error == null -> "${trailCount(ui.filtered.size)} near the centre · zoom in for more"
                        else -> trailCount(ui.filtered.size)
                    },
                )
            }

            // "Show ride on map" banner: which ride is highlighted, and how many of its
            // trails are loaded yet (the rest arrive as the map loads that area).
            ui.highlightedRideId?.let { rid -> ui.rides.firstOrNull { it.id == rid } }?.let { ride ->
                val shown = ride.trails.count { t -> ui.trails.any { it.id == t.id } }
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shadowElevation = 2.dp,
                ) {
                    Row(Modifier.padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (shown < ride.trails.size) "${ride.name} · $shown of ${ride.trails.size} trails loaded"
                            else "${ride.name} · %.1f mi".format(ride.totalMiles),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        IconButton(onClick = onClearRide) {
                            Icon(Icons.Filled.Close, contentDescription = "Stop showing ride")
                        }
                    }
                }
            }

            // "Show on map" for a recorded activity: which one, and a way to put it away.
            ui.highlightedTrackId?.let { ui.recordedById[it] }?.let { track ->
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shadowElevation = 2.dp,
                ) {
                    Row(Modifier.padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(track.kind.icon, contentDescription = null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                        Spacer(Modifier.size(6.dp))
                        Text(
                            listOfNotNull(
                                track.name ?: track.kind.label,
                                shortDate(track.start),
                                track.distanceMiles?.let { "%.1f mi".format(it) },
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        IconButton(onClick = onClearTrack) {
                            Icon(Icons.Filled.Close, contentDescription = "Stop showing this activity")
                        }
                    }
                }
            }

            // Over a state whose trails aren't on the phone: every load here is a public
            // Overpass query, so offer the state's pack — a tap, and it downloads in the
            // background.
            ui.packSuggestion?.let { st ->
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shadowElevation = 2.dp,
                ) {
                    Row(Modifier.padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f, fill = false).padding(vertical = 8.dp)) {
                            Text(
                                "Get ${st.name} trails on this phone",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                            Text(
                                "Instant loading, even offline · ${megabytes(st.bytes)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                        TextButton(onClick = { onGetPackState(st.slug) }) { Text("Get") }
                        IconButton(onClick = onDismissPackSuggestion) {
                            Icon(Icons.Filled.Close, contentDescription = "Not now")
                        }
                    }
                }
            }

            val notice = ui.error
                ?: "Offline — showing saved trails".takeIf { ui.servingStale && !ui.loading }
            notice?.let { msg ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                ) {
                    Text(
                        msg,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }

        // Bottom: legend and buttons in a row, the peek card under them, all in one column —
        // so the card pushes the controls up by its real height instead of a guessed offset.
        // The column's bottom padding clears the MapLibre attribution.
        Column(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                // color key — difficulty (MTB) or surface (ALL)
                MapLegend(
                    colorBy = ui.colorBy, dark = dark,
                    mtbContext = if (ui.mode == MapMode.MTB) (if (ui.mtbGravel) MtbContext.GRAVEL else MtbContext.GREY) else null,
                    ridden = ui.showRidden && ui.visits.isNotEmpty(),
                    tracks = ui.showTracks && ui.recorded.isNotEmpty(),
                )
                Spacer(Modifier.weight(1f))
                MapButtons(ui, onSetTheme, onOpenOffline, onRecenter, onSetLayer, onOpenMyVitals, onSetAmenities, onSetColorBy, onSetMtbGravel)
            }
            // peek card — only for the trail the user tapped (nothing auto-selected at startup)
            if (selectedTrail != null) {
                TrailPeekCard(
                    trail = selectedTrail,
                    saved = ui.isSaved(selectedTrail.id),
                    onDetails = { onOpenTrail(selectedTrail.id) },
                    onToggleSaved = { onToggleSaved(selectedTrail.id) },
                    onAddToRide = { showAddToRide = true },
                    onClose = onClearSelection,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    visits = ui.visits[selectedTrail.id],
                    condition = remember(selectedTrail, ui.conditions) { TrailConditions.forTrail(selectedTrail, ui.conditions) },
                )
            } else if (selectedCondition != null) {
                ConditionPeekCard(
                    status = selectedCondition,
                    onClose = onClearSelection,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                )
            } else if (selectedAmenity != null) {
                AmenityPeekCard(
                    amenity = selectedAmenity,
                    onClose = onClearSelection,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                )
            }
        }
        if (showAddToRide && selectedTrail != null) {
            AddToRideDialog(
                rides = ui.rides,
                trail = selectedTrail,
                onDismiss = { showAddToRide = false },
                onCreateRide = onCreateRide,
                onAddToRide = onAddToRide,
            )
        }
    }

/** Right-edge controls: layers menu (theme + your activity), offline download, my-location. */
@Composable
private fun MapButtons(
    ui: TrailsUiState,
    onSetTheme: (MapTheme) -> Unit,
    onOpenOffline: () -> Unit,
    onRecenter: () -> Unit,
    onSetLayer: (YouLayer, Boolean) -> Unit,
    onOpenMyVitals: () -> Unit,
    onSetAmenities: (Boolean) -> Unit = {},
    onSetColorBy: (ColorBy) -> Unit = {},
    onSetMtbGravel: (Boolean) -> Unit = {},
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Theme lives behind a Layers button: it's changed rarely, and the old three-state
        // A / sun / moon icon didn't say what it did.
        Box {
            var layersOpen by remember { mutableStateOf(false) }
            SmallFloatingActionButton(
                onClick = { layersOpen = true },
                containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
            ) {
                Icon(Icons.Filled.Layers, contentDescription = "Layers and theme")
            }
            DropdownMenu(expanded = layersOpen, onDismissRequest = { layersOpen = false }) {
                Text(
                    "Theme",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
                listOf(
                    MapTheme.SYSTEM to "Match system",
                    MapTheme.LIGHT to "Light",
                    MapTheme.DARK to "Dark",
                ).forEach { (theme, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        leadingIcon = {
                            RadioButton(selected = ui.mapTheme == theme, onClick = null)
                        },
                        onClick = {
                            onSetTheme(theme)
                            layersOpen = false
                        },
                    )
                }
                androidx.compose.material3.HorizontalDivider()
                Text(
                    "Colour trails by",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
                ColorBy.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label) },
                        leadingIcon = { RadioButton(selected = ui.colorBy == option, onClick = null) },
                        onClick = { onSetColorBy(option) },
                    )
                }
                DropdownMenuItem(
                    text = { Text("Gravel trails in MTB mode") },
                    leadingIcon = { androidx.compose.material3.Checkbox(checked = ui.mtbGravel, onCheckedChange = null) },
                    onClick = { onSetMtbGravel(!ui.mtbGravel) },
                )
                androidx.compose.material3.HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Amenities") },
                    leadingIcon = { androidx.compose.material3.Checkbox(checked = ui.showAmenities, onCheckedChange = null) },
                    onClick = { onSetAmenities(!ui.showAmenities) },
                )
                androidx.compose.material3.HorizontalDivider()
                Text(
                    "Your activity",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
                if (ui.myVitals.connected) {
                    YouLayer.entries.forEach { layer ->
                        val on = when (layer) {
                            YouLayer.RIDDEN -> ui.showRidden
                            YouLayer.TRACKS -> ui.showTracks
                            YouLayer.CONDITIONS -> ui.showConditions
                        }
                        DropdownMenuItem(
                            text = { Text(layer.label) },
                            leadingIcon = { androidx.compose.material3.Checkbox(checked = on, onCheckedChange = null) },
                            // Stays open, so several layers can be flipped in one go.
                            onClick = { onSetLayer(layer, !on) },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("myvitals…") },
                        onClick = {
                            layersOpen = false
                            onOpenMyVitals()
                        },
                    )
                } else {
                    DropdownMenuItem(
                        text = { Text("Connect myvitals…") },
                        onClick = {
                            layersOpen = false
                            onOpenMyVitals()
                        },
                    )
                }
            }
        }
        SmallFloatingActionButton(
            onClick = onOpenOffline,
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        ) {
            Icon(Icons.Filled.CloudDownload, contentDescription = "Offline areas")
        }
        FloatingActionButton(
            onClick = onRecenter,
            containerColor = MaterialTheme.colorScheme.primary,
        ) {
            Icon(Icons.Filled.MyLocation, contentDescription = "My location")
        }
    }
}

/** "1 trail" / "12 trails". */
private fun trailCount(n: Int) = if (n == 1) "1 trail" else "$n trails"

/** A small translucent pill of status text under the filter card. */
@Composable
private fun StatusPill(text: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.92f),
        shadowElevation = 2.dp,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
        )
    }
}

/**
 * Map color key: the six trail-sign difficulty colours plus "Unrated", or the surface colours —
 * whichever the Layers menu's "Colour trails by" picks.
 * The swatches are the line colors [trailColorExpr] draws — not the badge fills, which are
 * darker so their white labels stay readable.
 */
@Composable
private fun MapLegend(
    colorBy: ColorBy,
    mtbContext: MtbContext? = null,
    dark: Boolean,
    modifier: Modifier = Modifier,
    ridden: Boolean = false,
    tracks: Boolean = false,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.92f),
        shadowElevation = 2.dp,
    ) {
        Column {
            LegendColors(colorBy, dark, mtbContext)
            // Your layers get a second line, so the colour key above keeps its width.
            if (ridden || tracks) {
                Row(
                    Modifier.padding(start = 10.dp, end = 10.dp, bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (ridden) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.width(18.dp).height(10.dp).clip(RoundedCornerShape(50))
                                    .background(youColor(dark).copy(alpha = 0.45f)),
                                contentAlignment = Alignment.Center,
                            ) { Swatch(MaterialTheme.colorScheme.onSurfaceVariant, 12) }
                            Spacer(Modifier.size(4.dp))
                            Text("Ridden", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                    if (tracks) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.width(16.dp).height(2.dp).clip(RoundedCornerShape(50)).background(youColor(dark)))
                            Spacer(Modifier.size(4.dp))
                            Text("My tracks", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LegendColors(colorBy: ColorBy, dark: Boolean, mtbContext: MtbContext? = null) {
    if (colorBy == ColorBy.DIFFICULTY) {
        // Six levels in three rows of two, easiest first: a row of three crowded the map buttons.
        Column(
            Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            DifficultyTier.entries.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    row.forEach { tier ->
                        val (light, darkColor) = TIER_LINE_COLORS.getValue(tier)
                        Row(Modifier.width(112.dp), verticalAlignment = Alignment.CenterVertically) {
                            TierSwatch(Color((if (dark) darkColor else light).toInt()), tier, dark)
                            Spacer(Modifier.size(4.dp))
                            Text("${tier.symbol} ${tier.label}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Swatch(Color((if (dark) UNRATED_LINE_COLOR.second else UNRATED_LINE_COLOR.first).toInt()), 22)
                Spacer(Modifier.size(4.dp))
                Text("Unrated", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
                // MTB mode's background trails share the row.
                if (mtbContext == MtbContext.GRAVEL) {
                    Spacer(Modifier.size(12.dp))
                    Swatch(Color(SURFACE_LINE_COLORS[1].second.let { if (dark) it.second else it.first }.toInt()), 22)
                    Spacer(Modifier.size(4.dp))
                    Text("Gravel", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    } else {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            (SURFACE_LINE_COLORS + ("Horse trail" to HORSE_LINE_COLOR)).forEach { (label, colors) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Swatch(Color((if (dark) colors.second else colors.first).toInt()), 14)
                    Spacer(Modifier.size(4.dp))
                    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

/** A legend swatch drawn like the line: white rim on the dark map, dashed centre for expert. */
@Composable
private fun TierSwatch(color: Color, tier: DifficultyTier, dark: Boolean) {
    val rim = when {
        dark && color.luminance() < 0.05f -> Modifier.border(1.dp, Color.White, RoundedCornerShape(50))
        !dark && color.luminance() > 0.9f -> Modifier.border(1.dp, Color(0xFF333333), RoundedCornerShape(50))
        else -> Modifier
    }
    Box(Modifier.width(22.dp).height(6.dp).clip(RoundedCornerShape(50)).then(rim).background(color)) {
        if (tier == DifficultyTier.DOUBLE_BLACK) {
            Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                repeat(3) { Box(Modifier.width(3.dp).height(2.dp).background(Color.White)) }
            }
        }
    }
}

@Composable
private fun Swatch(color: Color, widthDp: Int) = Box(
    Modifier.width(widthDp.dp).height(4.dp).clip(RoundedCornerShape(50)).background(color),
)

/**
 * Trail-sign line colours as (light basemap, dark basemap), shared by the lines and legend:
 * white, green, blue, black, double black (black with a white dashed centre,
 * [LAYER_DOUBLE_BLACK]) and orange for pro lines. White and black keep their sign colours on
 * both maps; [difficultyCasingExpr] gives white a dark casing on the light map and black a
 * white one on the dark map, which is what keeps them visible.
 */
private val TIER_LINE_COLORS = mapOf(
    DifficultyTier.WHITE to (0xFFFFFFFF to 0xFFFFFFFF),
    DifficultyTier.GREEN to (0xFF2E9E44 to 0xFF4CC86A),
    DifficultyTier.BLUE to (0xFF1E6FD9 to 0xFF4C9BFF),
    DifficultyTier.BLACK to (0xFF111111 to 0xFF000000),
    DifficultyTier.DOUBLE_BLACK to (0xFF111111 to 0xFF000000),
    DifficultyTier.PRO to (0xFFF57C00 to 0xFFFF9800),
)


/** What MTB mode's legend adds for its background trails. */
private enum class MtbContext { GREY, GRAVEL }

/** "lvl" for a piece with no rating while lines are coloured by difficulty. */
private const val UNRATED = "UNRATED"

/** Unrated trails while colouring by difficulty, as (light, dark): a neutral grey no level uses. */
private val UNRATED_LINE_COLOR = 0xFF8A8A8A to 0xFF9E9E9E

/** Surface line colors as (light, dark). Shared by the lines and legend. */
private val SURFACE_LINE_COLORS = listOf(
    "Paved" to (0xFF2E7D4F to 0xFF4CC57F),
    "Gravel" to (0xFFDAA520 to 0xFFF2C744),
    "Dirt" to (0xFFA0522D to 0xFFCC7A4D),
)

/**
 * Horse trails ([Trail.horseTrailPaths]) in ALL mode, as (light, dark): purple, which no
 * surface, basemap road, park or water uses on either map.
 */
private val HORSE_LINE_COLOR = 0xFF7B1FA2 to 0xFFCE93D8

/**
 * The card for a tapped trail: stats, plus Save and Add to ride so the common actions don't
 * need a trip through the detail screen.
 */
@Composable
private fun TrailPeekCard(
    trail: Trail,
    saved: Boolean,
    onDetails: () -> Unit,
    onToggleSaved: () -> Unit,
    onAddToRide: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier,
    visits: TrailVisits? = null,
    condition: TrailStatus? = null,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shadowElevation = 8.dp,
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    trail.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onToggleSaved) {
                    Icon(
                        if (saved) Icons.Filled.Star else Icons.Filled.StarBorder,
                        contentDescription = if (saved) "Remove from saved" else "Save trail",
                        tint = if (saved) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = "Dismiss")
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                SurfaceBadge(trail.surface)
                LevelBadge(trail.level, trail.mtbScale, Modifier.padding(start = 6.dp))
                if (condition != null) ConditionChip(condition, Modifier.padding(start = 6.dp))
                Spacer(Modifier.size(8.dp))
                UseIcons(trail.uses, size = 16)
            }
            VisitLine(visits, Modifier.padding(top = 6.dp))
            Spacer(Modifier.size(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                PeekStat("%.1f mi".format(trail.lengthMiles), "length")
                PeekStat("%.1f mi".format(trail.distanceMiles), "away")
            }
            Spacer(Modifier.size(12.dp))
            Row(Modifier.padding(end = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onDetails, modifier = Modifier.weight(1f)) { Text("Details") }
                OutlinedButton(onClick = onAddToRide, modifier = Modifier.weight(1f)) {
                    Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Add to ride")
                }
            }
        }
    }
}

/** The card for a tapped trailhead: open or closed, the board's message, and how fresh it is. */
@Composable
private fun ConditionPeekCard(status: TrailStatus, onClose: () -> Unit, modifier: Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shadowElevation = 8.dp,
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    status.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = "Dismiss")
                }
            }
            ConditionChip(status)
            status.comment?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp, end = 8.dp))
            }
            Text(
                listOfNotNull(
                    status.updatedAt?.let { "Posted ${ago(it)}" },
                    status.checkedAt?.let { "checked ${ago(it)}" },
                ).joinToString(" · ").ifEmpty { "No reading yet" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            // Only an http(s) page: the address comes from the server.
            status.link?.let { url ->
                TextButton(
                    onClick = {
                        runCatching {
                            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                        }
                    },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp),
                ) { Text("Open on RainoutLine") }
            }
        }
    }
}

/**
 * The card for a tapped amenity: what it is, its name, fee and hours, and who runs it with a
 * link to their page when the map has one.
 */
@Composable
internal fun AmenityPeekCard(amenity: Amenity, onClose: () -> Unit, modifier: Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shadowElevation = 8.dp,
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(amenity.kind.glyph, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(end = 8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        amenity.name ?: amenity.kind.label,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                    if (amenity.name != null) {
                        Text(amenity.kind.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = "Dismiss")
                }
            }
            val facts = listOfNotNull(amenity.feeText, amenity.hours)
            if (facts.isNotEmpty()) {
                Text(facts.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp, end = 8.dp))
            }
            amenity.operator?.let {
                Text("Run by $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, end = 8.dp))
            }
            Row {
                TextButton(
                    onClick = {
                        val p = amenity.point
                        runCatching {
                            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("geo:${p.lat},${p.lon}?q=${p.lat},${p.lon}")))
                        }
                    },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp),
                ) { Text("Directions") }
                // Only an http(s) page: map data is open to anyone to edit.
                amenity.website?.let { url ->
                    Spacer(Modifier.size(16.dp))
                    TextButton(
                        onClick = { runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) } },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp),
                    ) { Text("Website") }
                }
            }
        }
    }
}

@Composable
private fun PeekStat(value: String, label: String) = Column {
    Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * Add the trail layers, bottom to top: your tracks, the selection's highlight glow, the ridden
 * glow, casing, the coloured line, one recorded activity drawn bold, and trailhead dots.
 */
private fun applyTrailLayers(style: Style, dark: Boolean) {
    val you = (if (dark) YOU_LINE_COLOR.second else YOU_LINE_COLOR.first).toInt()
    style.addSource(GeoJsonSource(SRC_TRAILS, EMPTY_FC))
    style.addSource(GeoJsonSource(SRC_HIGHLIGHT, EMPTY_FC))
    style.addSource(GeoJsonSource(SRC_TRACKS, EMPTY_FC))
    style.addSource(GeoJsonSource(SRC_RIDDEN, EMPTY_FC))
    style.addSource(GeoJsonSource(SRC_TRACK_HL, EMPTY_FC))
    style.addSource(GeoJsonSource(SRC_CONDITIONS, EMPTY_FC))
    style.addSource(GeoJsonSource(SRC_AMENITIES, EMPTY_FC))
    style.addSource(GeoJsonSource(SRC_CONTEXT, EMPTY_FC))
    // Every recorded track, thin and under everything: where you've been, trail or not.
    style.addLayer(
        LineLayer(LAYER_TRACKS, SRC_TRACKS).withProperties(
            PropertyFactory.lineWidth(
                Expression.interpolate(
                    Expression.linear(), Expression.zoom(),
                    Expression.stop(10, 1.0f), Expression.stop(14, 2.0f), Expression.stop(17, 3.5f),
                ),
            ),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            PropertyFactory.lineColor(you),
            PropertyFactory.lineOpacity(0.55f),
        ),
    )
    // Highlight glow for the selected trail — widest, drawn underneath everything so it
    // halos around the colored line. Bright yellow reads on both light + dark basemaps.
    style.addLayer(
        LineLayer(LAYER_HIGHLIGHT, SRC_HIGHLIGHT).withProperties(
            PropertyFactory.lineWidth(lineWidthExpr(7f)),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            PropertyFactory.lineColor(0xF0FFD54F.toInt()),
        ),
    )
    // Trails you've ridden glow blue underneath, so the surface colour still reads and the
    // glow shows at every zoom (a stripe inside a 1 px line would vanish when zoomed out).
    style.addLayer(
        LineLayer(LAYER_RIDDEN, SRC_RIDDEN).withProperties(
            PropertyFactory.lineWidth(lineWidthExpr(5f)),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            PropertyFactory.lineColor(you),
            PropertyFactory.lineOpacity(if (dark) 0.5f else 0.42f),
        ),
    )
    // MTB mode's other trails: thin and grey, under everything rated, so the map shows the
    // greenways and paths between systems without competing with the difficulty colours.
    style.addLayer(
        LineLayer(LAYER_CONTEXT, SRC_CONTEXT).withProperties(
            PropertyFactory.lineWidth(lineWidthExpr(-0.3f)),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            PropertyFactory.lineColor((if (dark) CONTEXT_LINE_COLOR.second else CONTEXT_LINE_COLOR.first).toInt()),
        ),
    )
    // …and, when "Gravel trails in MTB mode" is on, the gravel ones in gravel gold, full width.
    style.addLayer(
        LineLayer(LAYER_CONTEXT_GRAVEL, SRC_CONTEXT).withProperties(
            PropertyFactory.lineWidth(lineWidthExpr()),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            PropertyFactory.lineColor(SURFACE_LINE_COLORS[1].second.let { if (dark) it.second else it.first }.toInt()),
            PropertyFactory.visibility(Property.NONE),
        ).withFilter(Expression.eq(Expression.get("surface"), Expression.literal(SurfaceType.GRAVEL.name))),
    )
    // Casing = a wider line drawn underneath the colored line. A dark outline on the light
    // basemap and a light halo on the dark basemap make the colored lines pop on either.
    style.addLayer(
        LineLayer(LAYER_TRAILS_CASING, SRC_TRAILS).withProperties(
            PropertyFactory.lineWidth(lineWidthExpr(2f)),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            PropertyFactory.lineColor(difficultyCasingExpr(dark)),
        ),
    )
    style.addLayer(
        LineLayer(LAYER_TRAILS, SRC_TRAILS).withProperties(
            PropertyFactory.lineWidth(lineWidthExpr()),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            PropertyFactory.lineColor(trailColorExpr(dark)),
        ),
    )
    // Double black (mtb:scale 4-6): a white dashed centre down the black line, so expert
    // trails read apart from advanced ones the way a ◆◆ sign does.
    style.addLayer(
        LineLayer(LAYER_DOUBLE_BLACK, SRC_TRAILS).withProperties(
            PropertyFactory.lineWidth(lineWidthExpr(-1f)),
            PropertyFactory.lineColor(0xFFFFFFFF.toInt()),
            PropertyFactory.lineDasharray(arrayOf(1.5f, 1.5f)),
        ).withFilter(
            Expression.eq(Expression.get("lvl"), Expression.literal(DifficultyTier.DOUBLE_BLACK.name)),
        ),
    )
    // One-way pieces: small chevrons along the line pointing the way you may ride, from z13
    // (below that they would crowd into a smear). oneway=-1 pieces run against their drawn
    // direction, so their chevrons are turned round.
    style.addImage(IMG_ONEWAY, onewayArrow())
    style.addLayer(
        SymbolLayer(LAYER_ONEWAY, SRC_TRAILS).withProperties(
            PropertyFactory.symbolPlacement(Property.SYMBOL_PLACEMENT_LINE),
            PropertyFactory.symbolSpacing(60f),
            PropertyFactory.iconImage(IMG_ONEWAY),
            PropertyFactory.iconSize(
                Expression.interpolate(Expression.linear(), Expression.zoom(), Expression.stop(13, 0.55f), Expression.stop(17, 0.9f)),
            ),
            PropertyFactory.iconRotate(
                Expression.switchCase(Expression.eq(Expression.get("oneway"), Expression.literal(-1)), Expression.literal(180f), Expression.literal(0f)),
            ),
            PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
        ).withFilter(Expression.neq(Expression.get("oneway"), Expression.literal(0))).also { it.minZoom = 13f },
    )
    // Amenities: an icon per kind, over the trails. Parking only from z15 — a metro holds
    // hundreds of trail-side car parks and they bury the rest further out.
    AmenityKind.entries.forEach { style.addImage(amenityImage(it), amenityIcon(it)) }
    fun amenityLayer(id: String, parking: Boolean, minZoom: Float) = SymbolLayer(id, SRC_AMENITIES).withProperties(
        PropertyFactory.iconImage(Expression.concat(Expression.literal("amen-"), Expression.get("kind"))),
        PropertyFactory.iconSize(
            Expression.interpolate(Expression.linear(), Expression.zoom(), Expression.stop(13, 0.8f), Expression.stop(16, 1.0f)),
        ),
        PropertyFactory.iconAllowOverlap(true),
        PropertyFactory.iconIgnorePlacement(true),
    ).withFilter(
        if (parking) Expression.eq(Expression.get("kind"), Expression.literal(AmenityKind.PARKING.name))
        else Expression.neq(Expression.get("kind"), Expression.literal(AmenityKind.PARKING.name)),
    ).also { it.minZoom = minZoom }
    style.addLayer(amenityLayer(LAYER_AMENITY_PARKING, parking = true, minZoom = 15f))
    style.addLayer(amenityLayer(LAYER_AMENITIES, parking = false, minZoom = 13f))
    // One recorded activity, on top of the trails it used.
    style.addLayer(
        LineLayer(LAYER_TRACK_HL_CASING, SRC_TRACK_HL).withProperties(
            PropertyFactory.lineWidth(lineWidthExpr(4f)),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            PropertyFactory.lineColor(if (dark) 0xFF101418.toInt() else 0xFFFFFFFF.toInt()),
        ),
    )
    style.addLayer(
        LineLayer(LAYER_TRACK_HL, SRC_TRACK_HL).withProperties(
            PropertyFactory.lineWidth(lineWidthExpr(1.5f)),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            PropertyFactory.lineColor(you),
        ),
    )
    // Trailheads on the status board: a dot in the condition's colour, labelled close up.
    style.addLayer(
        CircleLayer(LAYER_CONDITIONS, SRC_CONDITIONS).withProperties(
            PropertyFactory.circleRadius(
                Expression.interpolate(
                    Expression.linear(), Expression.zoom(),
                    Expression.stop(8, 4.0f), Expression.stop(12, 7.0f), Expression.stop(16, 9.0f),
                ),
            ),
            PropertyFactory.circleColor(conditionColorExpr()),
            PropertyFactory.circleStrokeColor(if (dark) 0xFF101418.toInt() else 0xFFFFFFFF.toInt()),
            PropertyFactory.circleStrokeWidth(2f),
        ),
    )
    style.addLayer(
        SymbolLayer(LAYER_CONDITION_LABELS, SRC_CONDITIONS).withProperties(
            PropertyFactory.textField(Expression.get("label")),
            PropertyFactory.textFont(arrayOf("Noto Sans Bold")),
            PropertyFactory.textSize(12f),
            PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
            PropertyFactory.textOffset(arrayOf(0f, 0.9f)),
            PropertyFactory.textColor(conditionColorExpr()),
            PropertyFactory.textHaloColor(if (dark) 0xFF101418.toInt() else 0xFFFFFFFF.toInt()),
            PropertyFactory.textHaloWidth(1.5f),
        ).also { it.minZoom = 11f },
    )
}

/** Condition colour per trailhead, from the feature's "condition" (a [TrailCondition] name). */
private fun conditionColorExpr(): Expression = Expression.match(
    Expression.get("condition"),
    *TrailCondition.entries.flatMap {
        listOf(Expression.literal(it.name), Expression.color(it.color.toArgbInt()))
    }.toTypedArray(),
    Expression.color(TrailCondition.UNKNOWN.color.toArgbInt()),
)

private fun Color.toArgbInt(): Int = android.graphics.Color.argb(
    (alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt(),
)

private fun amenityImage(kind: AmenityKind) = "amen-${kind.name}"

/**
 * An amenity's map icon, ~24 dp: the blue "P" square everyone reads as parking, and for the
 * rest the kind's emoji on a white disc with a dark rim, which shows on either basemap.
 */
private fun amenityIcon(kind: AmenityKind): android.graphics.Bitmap {
    val density = android.content.res.Resources.getSystem().displayMetrics.density
    val size = (24 * density).toInt().coerceAtLeast(24)
    val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    val half = size / 2f
    val rim = 1.5f * density
    if (kind == AmenityKind.PARKING) {
        val r = android.graphics.RectF(rim, rim, size - rim, size - rim)
        paint.color = 0xFFFFFFFF.toInt()
        c.drawRoundRect(r, 5 * density, 5 * density, paint)
        paint.color = 0xFF1565C0.toInt()
        r.inset(rim, rim)
        c.drawRoundRect(r, 4 * density, 4 * density, paint)
        paint.color = 0xFFFFFFFF.toInt()
        paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
    } else {
        paint.color = 0xFF37474F.toInt()
        c.drawCircle(half, half, half, paint)
        paint.color = 0xFFFFFFFF.toInt()
        c.drawCircle(half, half, half - rim, paint)
    }
    paint.textAlign = android.graphics.Paint.Align.CENTER
    paint.textSize = size * if (kind == AmenityKind.PARKING) 0.62f else 0.55f
    val fm = paint.fontMetrics
    c.drawText(if (kind == AmenityKind.PARKING) "P" else kind.glyph, half, half - (fm.ascent + fm.descent) / 2, paint)
    return bmp
}

/** Amenities as GeoJSON points carrying their id and kind. */
private fun amenitiesFc(amenities: List<Amenity>): String {
    val sb = StringBuilder(64 + amenities.size * 96)
    sb.append("{\"type\":\"FeatureCollection\",\"features\":[")
    amenities.forEachIndexed { i, a ->
        if (i > 0) sb.append(',')
        sb.append("{\"type\":\"Feature\",\"properties\":{\"id\":")
        appendJsonString(sb, a.id)
        sb.append(",\"kind\":\"").append(a.kind.name).append("\"},\"geometry\":{\"type\":\"Point\",\"coordinates\":[")
        sb.append(round5(a.point.lon)).append(',').append(round5(a.point.lat)).append("]}}")
    }
    sb.append("]}")
    return sb.toString()
}

/**
 * The one-way chevron, pointing along +x (a line-placed symbol's direction of travel): dark
 * with a white halo, so it reads on every line colour and both basemaps.
 */
private fun onewayArrow(): android.graphics.Bitmap {
    val size = 32
    val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    val path = android.graphics.Path().apply {
        moveTo(10f, 7f); lineTo(22f, 16f); lineTo(10f, 25f)
    }
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeCap = android.graphics.Paint.Cap.ROUND
        strokeJoin = android.graphics.Paint.Join.ROUND
    }
    paint.color = 0xF2FFFFFF.toInt(); paint.strokeWidth = 9f
    c.drawPath(path, paint)
    paint.color = 0xFF1A1A1A.toInt(); paint.strokeWidth = 4.5f
    c.drawPath(path, paint)
    return bmp
}

/** Turns on the location dot (the caller has checked the permission). */
@SuppressLint("MissingPermission")
private fun MapLibreMap.showLocationDot(context: android.content.Context, style: Style) {
    try {
        locationComponent.apply {
            activateLocationComponent(
                LocationComponentActivationOptions.builder(context, style)
                    // No engine of its own: MapLibre's asks the platform "fused" provider at
                    // BALANCED accuracy whatever priority it is given — a Wi-Fi/cell fix that
                    // can sit 100 m off, and never GPS. The map feeds it GPS fixes instead.
                    .useDefaultLocationEngine(false)
                    .build(),
            )
            isLocationComponentEnabled = true
            cameraMode = CameraMode.NONE
            renderMode = RenderMode.COMPASS
        }
    } catch (e: SecurityException) {
        // Permission revoked between the check and here; the map works without the dot.
    }
}

/** Where the map's location dot is, or null when it isn't showing or has no fix yet. */
private fun MapLibreMap.myLocation(): com.trailmap.data.GeoPoint? {
    val lc = locationComponent
    if (!lc.isLocationComponentActivated || !lc.isLocationComponentEnabled) return null
    return lc.lastKnownLocation?.let { com.trailmap.data.GeoPoint(it.latitude, it.longitude) }
}

// Zoom-interpolated stroke width: thin when zoomed out (so dense trail networks don't
// merge into blobs), wider when zoomed in. [extra] widens it (casing peeks out; highlight glows).
private fun lineWidthExpr(extra: Float = 0f): Expression =
    Expression.interpolate(
        Expression.exponential(1.4f),
        Expression.zoom(),
        Expression.stop(10, 1.0f + extra),
        Expression.stop(13, 2.0f + extra),
        Expression.stop(16, 4.5f + extra),
        Expression.stop(19, 8.0f + extra),
    )

/**
 * Combined line color: rated pieces (the "lvl" prop is a [DifficultyTier] name) take their
 * sign colour; everything else ("none") falls through to the surface color. One expression
 * serves both ALL mode (every piece "none" → surface colors) and MTB mode.
 */
internal fun trailColorExpr(dark: Boolean): Expression {
    val stops = DifficultyTier.entries.flatMap { tier ->
        val (light, darkColor) = TIER_LINE_COLORS.getValue(tier)
        listOf(Expression.literal(tier.name), Expression.color((if (dark) darkColor else light).toInt()))
    }
    return Expression.match(
        Expression.get("lvl"),
        *stops.toTypedArray(),
        Expression.literal(UNRATED), Expression.color((if (dark) UNRATED_LINE_COLOR.second else UNRATED_LINE_COLOR.first).toInt()),
        surfaceColorExpr(dark), // default: unrated trails → surface color
    )
}

/**
 * Casing under the trail lines: a dark outline on the light basemap, a light halo on the dark
 * one — made solid white under black-rated lines there, which would otherwise vanish.
 */
private fun difficultyCasingExpr(dark: Boolean): Expression {
    val normal = Expression.color(if (dark) 0x66FFFFFF.toInt() else 0x55000000.toInt())
    // The sign colour that would vanish into this basemap: black on dark, white on light.
    val (vanishing, casing) = if (dark) {
        setOf(DifficultyTier.BLACK, DifficultyTier.DOUBLE_BLACK) to 0xE6FFFFFF.toInt()
    } else {
        setOf(DifficultyTier.WHITE) to 0xE6333333.toInt()
    }
    return Expression.match(
        Expression.get("lvl"),
        *vanishing.flatMap { listOf(Expression.literal(it.name), Expression.color(casing)) }.toTypedArray(),
        normal,
    )
}

/**
 * Data-driven line color for unrated lines: purple where the piece is a horse trail (the
 * "horse" prop, set in ALL mode only), otherwise by "surface", brightened on the dark basemap.
 */
private fun surfaceColorExpr(dark: Boolean): Expression = Expression.switchCase(
    Expression.eq(Expression.get("horse"), Expression.literal(true)),
    Expression.color((if (dark) HORSE_LINE_COLOR.second else HORSE_LINE_COLOR.first).toInt()),
    surfaceOnlyExpr(dark),
)

private fun surfaceOnlyExpr(dark: Boolean): Expression {
    fun pick(i: Int) = SURFACE_LINE_COLORS[i].second.let { if (dark) it.second else it.first }
    val paved = pick(0)
    val gravel = pick(1) // gold
    val dirt = pick(2)   // sienna
    val unknown = if (dark) 0xFFB6B6B6 else 0xFF7A7A7A
    return Expression.match(
        Expression.get("surface"),
        Expression.literal(SurfaceType.PAVED.name), Expression.color(paved.toInt()),
        Expression.literal(SurfaceType.GRAVEL.name), Expression.color(gravel.toInt()),
        Expression.literal(SurfaceType.DIRT.name), Expression.color(dirt.toInt()),
        Expression.literal(SurfaceType.UNKNOWN.name), Expression.color(unknown.toInt()),
        Expression.color(unknown.toInt()), // default
    )
}

/**
 * Build a FeatureCollection — one LineString feature per [Trail.paths] entry, tagged with the
 * trail id, surface bucket, mtb:scale and, when [horse], whether that piece is open to horses —
 * as raw JSON.
 *
 * Hand-rolled with a StringBuilder rather than kotlinx's buildJsonObject: a Kansas City pull
 * is on the order of 30,000 coordinates, and the builder path allocates a JsonArray plus two
 * JsonPrimitives for every single vertex before serializing the tree. Coordinates are emitted
 * at five decimal places (~1 m), which is finer than any of this data is surveyed and roughly
 * a third shorter than full Double precision.
 *
 * Call this off the main thread.
 */
private fun trailsFc(trails: List<Trail>, horse: Boolean = false, levels: Boolean = false): String {
    val sb = StringBuilder(1 shl 16)
    sb.append("{\"type\":\"FeatureCollection\",\"features\":[")
    var first = true
    for (trail in trails) {
        for ((index, path) in trail.paths.withIndex()) {
            // Per piece: a loop's sections can be rated differently.
            val lvl = if (!levels) "none" else (trail.levelPaths.getOrNull(index) ?: trail.level)?.name ?: UNRATED
            if (path.size < 2) continue
            if (!first) sb.append(',')
            first = false
            sb.append("{\"type\":\"Feature\",\"properties\":{\"id\":")
            appendJsonString(sb, trail.id)
            sb.append(",\"surface\":\"").append(trail.surface.name)
            sb.append("\",\"lvl\":\"").append(lvl)
            sb.append("\",\"horse\":").append(horse && trail.horseTrailPaths.getOrElse(index) { false })
            sb.append(",\"oneway\":").append(trail.onewayPaths.getOrElse(index) { 0 })
            sb.append("},\"geometry\":{\"type\":\"LineString\",\"coordinates\":[")
            for (i in path.indices) {
                if (i > 0) sb.append(',')
                val p = path[i]
                sb.append('[').append(round5(p.lon)).append(',').append(round5(p.lat)).append(']')
            }
            sb.append("]}}")
        }
    }
    sb.append("]}")
    return sb.toString()
}

/** Plain lines with no properties — the ridden stretches and a single highlighted track. */
private fun pathsFc(paths: List<List<com.trailmap.data.GeoPoint>>): String {
    val sb = StringBuilder(1 shl 14)
    sb.append("{\"type\":\"FeatureCollection\",\"features\":[")
    var first = true
    for (path in paths) {
        if (path.size < 2) continue
        if (!first) sb.append(',')
        first = false
        sb.append("{\"type\":\"Feature\",\"properties\":{},\"geometry\":{\"type\":\"LineString\",\"coordinates\":[")
        for (i in path.indices) {
            if (i > 0) sb.append(',')
            sb.append('[').append(round5(path[i].lon)).append(',').append(round5(path[i].lat)).append(']')
        }
        sb.append("]}}")
    }
    sb.append("]}")
    return sb.toString()
}

/** Every recorded track as a line. Call off the main thread: this decodes all of them. */
private fun tracksFc(tracks: List<RecordedTrack>): String =
    pathsFc(tracks.filter { it.kind.ride || it.kind.onFoot }.map { Polyline.decode(it.polyline) })

/** Trailheads with a pinned location, as points carrying id, condition and label. */
private fun conditionsFc(statuses: List<TrailStatus>): String {
    val sb = StringBuilder("{\"type\":\"FeatureCollection\",\"features\":[")
    var first = true
    for (s in statuses) {
        val p = s.point ?: continue
        if (!first) sb.append(',')
        first = false
        sb.append("{\"type\":\"Feature\",\"properties\":{\"id\":").append(s.id)
        sb.append(",\"condition\":\"").append(s.condition.name).append("\",\"label\":")
        appendJsonString(sb, s.condition.label)
        sb.append("},\"geometry\":{\"type\":\"Point\",\"coordinates\":[")
        sb.append(round5(p.lon)).append(',').append(round5(p.lat)).append("]}}")
    }
    sb.append("]}")
    return sb.toString()
}

/** Round to 5 decimals and render locale-independently (Double.toString never localizes). */
private fun round5(v: Double): String = (Math.round(v * 100000.0) / 100000.0).toString()

/** Append [raw] as a quoted JSON string, escaping the two characters that can break out. */
private fun appendJsonString(sb: StringBuilder, raw: String) {
    sb.append('"')
    for (c in raw) when {
        c == '"' -> sb.append("\\\"")
        c == '\\' -> sb.append("\\\\")
        c.code < 0x20 -> sb.append(' ')
        else -> sb.append(c)
    }
    sb.append('"')
}
