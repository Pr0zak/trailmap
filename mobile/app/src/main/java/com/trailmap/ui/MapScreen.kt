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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { vm.bootstrap() }
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

    // The camera belongs to the user from here on: panning drives which trails are fetched,
    // so following ui.center would yank the map back and re-trigger the fetch it just
    // answered. Its starting position is set in the AndroidView factory below, and explicit
    // recentres go through ui.focusTarget.
    // Redraw the trail lines when the data or the filters change. Keyed on ui.filterKey (a
    // short String) rather than ui.filtered, so a camera idle doesn't drag Compose through a
    // structural comparison of every trail's geometry. The GeoJSON is built on a background
    // dispatcher — a Kansas City pull is ~30k coordinates and would jank the frame here.
    LaunchedEffect(ui.filterKey, styleRef.value) {
        val style = styleRef.value ?: return@LaunchedEffect
        val t0 = android.os.SystemClock.elapsedRealtime()
        // Horse trails get their own color in ALL mode only; MTB colors mean difficulty.
        val fc = withContext(Dispatchers.Default) { trailsFc(ui.filtered, horse = ui.mode == MapMode.ALL) }
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
        val lit = ui.trails.filter { it.id == ui.selectedTrailId || it.id in rideIds }
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
                            // A trailhead dot sits on top of the trails around it, so it wins.
                            val head = map.queryRenderedFeatures(box, LAYER_CONDITIONS).firstOrNull()
                            if (head != null && head.hasProperty("id")) {
                                vm.selectCondition(head.getNumberProperty("id").toLong())
                                return@addOnMapClickListener true
                            }
                            val f = map.queryRenderedFeatures(box, LAYER_TRAILS).firstOrNull()
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
            onRecenter = vm::recenterOnMe,
            onToggleSaved = vm::toggleSaved,
            onCreateRide = { name, t -> vm.createRide(name, seed = t) },
            onAddToRide = { rideId, t -> vm.addTrailToRide(rideId, t) },
            onClearRide = vm::clearRideHighlight,
            onGetPackState = vm::addPackState,
            onDismissPackSuggestion = vm::dismissPackSuggestion,
            selectedCondition = ui.selectedConditionId?.let { id -> ui.conditions.firstOrNull { it.id == id } },
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
                    mode = ui.mode, dark = dark,
                    ridden = ui.showRidden && ui.visits.isNotEmpty(),
                    tracks = ui.showTracks && ui.recorded.isNotEmpty(),
                )
                Spacer(Modifier.weight(1f))
                MapButtons(ui, onSetTheme, onOpenOffline, onRecenter, onSetLayer, onOpenMyVitals)
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
 * One-line map color key: surface colors in ALL mode, all seven mtb:scale grades in MTB mode.
 * The swatches are the line colors [trailColorExpr] draws — not the badge fills, which are
 * darker so their white labels stay readable.
 */
@Composable
private fun MapLegend(
    mode: MapMode,
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
            LegendColors(mode, dark)
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
private fun LegendColors(mode: MapMode, dark: Boolean) {
    if (mode == MapMode.MTB) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            MTB_LINE_COLORS.forEachIndexed { scale, (light, darkColor) ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Swatch(Color((if (dark) darkColor else light).toInt()), 22)
                    Text("S$scale", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
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

@Composable
private fun Swatch(color: Color, widthDp: Int) = Box(
    Modifier.width(widthDp.dp).height(4.dp).clip(RoundedCornerShape(50)).background(color),
)

/** mtb:scale 0..6 line colors as (light basemap, dark basemap). Shared by the lines and legend. */
private val MTB_LINE_COLORS = listOf(
    0xFF43A047 to 0xFF66D08A,
    0xFF1E9E6A to 0xFF3FD89A,
    0xFF1E88E5 to 0xFF5BB0F5,
    0xFF424242 to 0xFFBDBDBD,
    0xFFE53935 to 0xFFFF6B6B,
    0xFFB71C1C to 0xFFE57373,
    0xFF7F0000 to 0xFFD84343,
)

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
                MtbBadge(trail.mtbScale, Modifier.padding(start = 6.dp))
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
    // Casing = a wider line drawn underneath the colored line. A dark outline on the light
    // basemap and a light halo on the dark basemap make the colored lines pop on either.
    val casing = if (dark) 0x66FFFFFF.toInt() else 0x55000000.toInt()
    style.addLayer(
        LineLayer(LAYER_TRAILS_CASING, SRC_TRAILS).withProperties(
            PropertyFactory.lineWidth(lineWidthExpr(2f)),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            PropertyFactory.lineColor(casing),
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
 * Combined line color: MTB-rated trails (the "mtb" prop is "0".."6") are colored by
 * difficulty; everything else ("none") falls through to the surface color. One expression
 * serves both ALL mode (all "none" → surface colors) and MTB mode (rated → difficulty).
 */
internal fun trailColorExpr(dark: Boolean): Expression {
    val stops = MTB_LINE_COLORS.flatMapIndexed { scale, (light, darkColor) ->
        listOf(Expression.literal("$scale"), Expression.color((if (dark) darkColor else light).toInt()))
    }
    return Expression.match(
        Expression.get("mtb"),
        *stops.toTypedArray(),
        surfaceColorExpr(dark), // default: unrated trails → surface color
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
private fun trailsFc(trails: List<Trail>, horse: Boolean = false): String {
    val sb = StringBuilder(1 shl 16)
    sb.append("{\"type\":\"FeatureCollection\",\"features\":[")
    var first = true
    for (trail in trails) {
        val mtb = trail.mtbScale?.toString() ?: "none"
        for ((index, path) in trail.paths.withIndex()) {
            if (path.size < 2) continue
            if (!first) sb.append(',')
            first = false
            sb.append("{\"type\":\"Feature\",\"properties\":{\"id\":")
            appendJsonString(sb, trail.id)
            sb.append(",\"surface\":\"").append(trail.surface.name)
            sb.append("\",\"mtb\":\"").append(mtb)
            sb.append("\",\"horse\":").append(horse && trail.horseTrailPaths.getOrElse(index) { false })
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
