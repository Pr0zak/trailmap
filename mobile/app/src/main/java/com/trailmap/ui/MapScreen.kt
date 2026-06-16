package com.trailmap.ui

import android.Manifest
import android.annotation.SuppressLint
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import com.trailmap.data.SurfaceType
import com.trailmap.data.Trail
import com.trailmap.offline.OfflinePacks
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
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
// Live display uses the bundled asset styles above. MapLibre's OfflineManager can't read
// asset://, so offline downloads point at the same style JSON hosted on the public repo.
private const val STYLE_LIGHT_URL =
    "https://raw.githubusercontent.com/Pr0zak/trailmap/main/mobile/app/src/main/assets/osm_raster_style.json"
private const val STYLE_DARK_URL =
    "https://raw.githubusercontent.com/Pr0zak/trailmap/main/mobile/app/src/main/assets/carto_dark_style.json"

@SuppressLint("MissingPermission")
@Composable
fun MapScreen(vm: TrailsViewModel, onOpenTrail: (String) -> Unit) {
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
    ) { vm.locateAndLoad() }
    var requested by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!requested) {
            requested = true
            launcher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
    // Belt-and-suspenders: if nothing loaded (permission flow short-circuited), kick a load.
    LaunchedEffect(Unit) {
        if (ui.trails.isEmpty() && !ui.loading) vm.load()
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

    // Center the camera on ui.center whenever it changes and the style is ready.
    LaunchedEffect(ui.center, styleRef.value) {
        if (styleRef.value != null) {
            mapRef.value?.cameraPosition = CameraPosition.Builder()
                .target(LatLng(ui.center.lat, ui.center.lon))
                .zoom(12.5)
                .build()
        }
    }

    // Redraw the trail lines whenever the filtered set changes (and the source exists).
    LaunchedEffect(ui.filtered, styleRef.value) {
        styleRef.value?.getSourceAs<GeoJsonSource>(SRC_TRAILS)?.setGeoJson(trailsFc(ui.filtered))
    }

    // Highlight the selected trail (or clear the highlight when nothing is selected).
    LaunchedEffect(ui.selectedTrailId, styleRef.value) {
        val selected = ui.selectedTrailId?.let { vm.trailById(it) }
        val fc = if (selected != null) trailsFc(listOf(selected)) else EMPTY_FC
        styleRef.value?.getSourceAs<GeoJsonSource>(SRC_HIGHLIGHT)?.setGeoJson(fc)
    }

    // One-shot: tapping a trail-system header focuses the map on that park, then clears it.
    LaunchedEffect(ui.focusTarget) {
        val target = ui.focusTarget ?: return@LaunchedEffect
        mapRef.value?.animateCamera(
            org.maplibre.android.camera.CameraUpdateFactory.newLatLngZoom(
                LatLng(target.lat, target.lon), 14.0,
            ),
        )
        vm.consumeFocus()
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { c ->
                MapView(c).apply {
                    onCreate(null)
                    getMapAsync { map ->
                        mapRef.value = map
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

        // top overlay: filter chips on a translucent rounded surface
        Surface(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .padding(8.dp),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        ) {
            FilterChips(
                ui = ui,
                onToggleSurface = vm::toggleSurface,
                onToggleUse = vm::toggleUse,
                onSetMode = vm::setMode,
                onSetRadiusMiles = vm::setRadiusMiles,
                onSetMinLength = vm::setMinLength,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }

        if (ui.loading) {
            CircularProgressIndicator(
                Modifier.align(Alignment.TopCenter).padding(top = 64.dp),
            )
        }

        ui.error?.let { msg ->
            Surface(
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 64.dp),
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

        // bottom peek card — only for the trail the user tapped (nothing auto-selected at startup)
        ui.selectedTrailId?.let { vm.trailById(it) }?.let { selected ->
            NearestTrailCard(
                trail = selected,
                onDetails = { onOpenTrail(selected.id) },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, bottom = 90.dp),
            )
        }

        // color key — difficulty (MTB) or surface (ALL)
        MapLegend(
            mode = ui.mode,
            dark = dark,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 8.dp),
        )

        // right-edge controls: theme toggle, offline download, my-location
        val context = androidx.compose.ui.platform.LocalContext.current
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 90.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SmallFloatingActionButton(
                onClick = { vm.cycleMapTheme() },
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Icon(
                    when (ui.mapTheme) {
                        MapTheme.SYSTEM -> Icons.Filled.BrightnessAuto
                        MapTheme.LIGHT -> Icons.Filled.LightMode
                        MapTheme.DARK -> Icons.Filled.DarkMode
                    },
                    contentDescription = "Map theme: ${ui.mapTheme.name.lowercase()}",
                )
            }
            SmallFloatingActionButton(
                onClick = {
                    val map = mapRef.value
                    if (map == null) {
                        Toast.makeText(context, "Map not ready", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Downloading this area for offline…", Toast.LENGTH_SHORT).show()
                        OfflinePacks.downloadVisible(
                            context, map, if (dark) STYLE_DARK_URL else STYLE_LIGHT_URL,
                        ) { msg -> Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
                    }
                },
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Icon(Icons.Filled.CloudDownload, contentDescription = "Download area for offline")
            }
            FloatingActionButton(
                onClick = { vm.locateAndLoad() },
                containerColor = MaterialTheme.colorScheme.primary,
            ) {
                Icon(Icons.Filled.MyLocation, contentDescription = "My location")
            }
        }
    }
}

/** Compact map color key: difficulty colors in MTB mode, surface colors in ALL mode. */
@Composable
private fun MapLegend(mode: MapMode, dark: Boolean, modifier: Modifier = Modifier) {
    val title: String
    val items: List<Pair<Color, String>>
    if (mode == MapMode.MTB) {
        title = "Difficulty"
        items = listOf(
            Color((if (dark) 0xFF66D08A else 0xFF43A047).toInt()) to "Easy",
            Color((if (dark) 0xFF5BB0F5 else 0xFF1E88E5).toInt()) to "Intermediate",
            Color((if (dark) 0xFFBDBDBD else 0xFF424242).toInt()) to "Advanced",
            Color((if (dark) 0xFFFF6B6B else 0xFFE53935).toInt()) to "Expert",
        )
    } else {
        title = "Surface"
        items = listOf(
            Color((if (dark) 0xFF4CC57F else 0xFF2E7D4F).toInt()) to "Paved",
            Color((if (dark) 0xFFF2C744 else 0xFFDAA520).toInt()) to "Gravel",
            Color((if (dark) 0xFFCC7A4D else 0xFFA0522D).toInt()) to "Dirt",
        )
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        shadowElevation = 4.dp,
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.size(4.dp))
            items.forEach { (color, label) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .width(14.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(50))
                            .background(color),
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Spacer(Modifier.size(3.dp))
            }
        }
    }
}

@Composable
private fun NearestTrailCard(trail: Trail, onDetails: () -> Unit, modifier: Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(trail.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.size(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SurfaceBadge(trail.surface)
                    Spacer(Modifier.size(8.dp))
                    Text(
                        "%.1f mi · %.1f mi away".format(trail.lengthMiles, trail.distanceMiles),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.size(8.dp))
            Button(onClick = onDetails) { Text("Details") }
        }
    }
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
private fun trailColorExpr(dark: Boolean): Expression = Expression.match(
    Expression.get("mtb"),
    Expression.literal("0"), Expression.color((if (dark) 0xFF66D08A else 0xFF43A047).toInt()),
    Expression.literal("1"), Expression.color((if (dark) 0xFF3FD89A else 0xFF1E9E6A).toInt()),
    Expression.literal("2"), Expression.color((if (dark) 0xFF5BB0F5 else 0xFF1E88E5).toInt()),
    Expression.literal("3"), Expression.color((if (dark) 0xFFBDBDBD else 0xFF424242).toInt()),
    Expression.literal("4"), Expression.color((if (dark) 0xFFFF6B6B else 0xFFE53935).toInt()),
    Expression.literal("5"), Expression.color((if (dark) 0xFFE57373 else 0xFFB71C1C).toInt()),
    Expression.literal("6"), Expression.color((if (dark) 0xFFD84343 else 0xFF7F0000).toInt()),
    surfaceColorExpr(dark), // default: unrated trails → surface color
)

/** Data-driven line color by "surface", brightened on the dark basemap for contrast. */
private fun surfaceColorExpr(dark: Boolean): Expression {
    val paved = if (dark) 0xFF4CC57F else 0xFF2E7D4F
    val gravel = if (dark) 0xFFF2C744 else 0xFFDAA520  // gold
    val dirt = if (dark) 0xFFCC7A4D else 0xFFA0522D    // sienna
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

/** Build a FeatureCollection: one LineString feature per Trail.path, tagged surface + id. */
private fun trailsFc(trails: List<Trail>): String {
    val features = mutableListOf<JsonObject>()
    for (trail in trails) {
        for (path in trail.paths) {
            if (path.size < 2) continue
            features += buildJsonObject {
                put("type", "Feature")
                put("properties", buildJsonObject {
                    put("id", trail.id)
                    put("surface", trail.surface.name)
                    put("mtb", trail.mtbScale?.toString() ?: "none")
                })
                put("geometry", buildJsonObject {
                    put("type", "LineString")
                    put("coordinates", buildJsonArray {
                        path.forEach { gp -> add(buildJsonArray { add(gp.lon); add(gp.lat) }) }
                    })
                })
            }
        }
    }
    return buildJsonObject {
        put("type", "FeatureCollection")
        put("features", JsonArray(features))
    }.toString()
}
