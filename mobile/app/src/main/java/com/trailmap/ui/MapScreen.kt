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
import com.trailmap.data.SurfaceType
import com.trailmap.data.Trail
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
private const val EMPTY_FC = """{"type":"FeatureCollection","features":[]}"""
private const val STYLE_LIGHT = "asset://osm_raster_style.json"
private const val STYLE_DARK = "asset://carto_dark_style.json"

@SuppressLint("MissingPermission")
@Composable
fun MapScreen(vm: TrailsViewModel, onOpenTrail: (String) -> Unit, onOpenOffline: () -> Unit) {
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
        val fc = withContext(Dispatchers.Default) { trailsFc(ui.filtered) }
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
                MapLegend(mode = ui.mode, dark = dark)
                Spacer(Modifier.weight(1f))
                MapButtons(ui, onSetTheme, onOpenOffline, onRecenter)
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

/** Right-edge controls: theme menu, offline download, my-location. */
@Composable
private fun MapButtons(
    ui: TrailsUiState,
    onSetTheme: (MapTheme) -> Unit,
    onOpenOffline: () -> Unit,
    onRecenter: () -> Unit,
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
                Icon(Icons.Filled.Layers, contentDescription = "Theme")
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
private fun MapLegend(mode: MapMode, dark: Boolean, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.92f),
        shadowElevation = 2.dp,
    ) {
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
                SURFACE_LINE_COLORS.forEach { (label, colors) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Swatch(Color((if (dark) colors.second else colors.first).toInt()), 14)
                        Spacer(Modifier.size(4.dp))
                        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
                    }
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
                Spacer(Modifier.size(8.dp))
                UseIcons(trail.uses, size = 16)
            }
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

@Composable
private fun PeekStat(value: String, label: String) = Column {
    Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Add the highlight glow + casing + colored line layers (bottom→top order). */
private fun applyTrailLayers(style: Style, dark: Boolean) {
    style.addSource(GeoJsonSource(SRC_TRAILS, EMPTY_FC))
    style.addSource(GeoJsonSource(SRC_HIGHLIGHT, EMPTY_FC))
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

/** Data-driven line color by "surface", brightened on the dark basemap for contrast. */
private fun surfaceColorExpr(dark: Boolean): Expression {
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
 * trail id, surface bucket and mtb:scale — as raw JSON.
 *
 * Hand-rolled with a StringBuilder rather than kotlinx's buildJsonObject: a Kansas City pull
 * is on the order of 30,000 coordinates, and the builder path allocates a JsonArray plus two
 * JsonPrimitives for every single vertex before serializing the tree. Coordinates are emitted
 * at five decimal places (~1 m), which is finer than any of this data is surveyed and roughly
 * a third shorter than full Double precision.
 *
 * Call this off the main thread.
 */
private fun trailsFc(trails: List<Trail>): String {
    val sb = StringBuilder(1 shl 16)
    sb.append("{\"type\":\"FeatureCollection\",\"features\":[")
    var first = true
    for (trail in trails) {
        val mtb = trail.mtbScale?.toString() ?: "none"
        for (path in trail.paths) {
            if (path.size < 2) continue
            if (!first) sb.append(',')
            first = false
            sb.append("{\"type\":\"Feature\",\"properties\":{\"id\":")
            appendJsonString(sb, trail.id)
            sb.append(",\"surface\":\"").append(trail.surface.name)
            sb.append("\",\"mtb\":\"").append(mtb)
            sb.append("\"},\"geometry\":{\"type\":\"LineString\",\"coordinates\":[")
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
