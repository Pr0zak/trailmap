package com.trailmap.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trailmap.data.ViewBounds
import com.trailmap.offline.OfflineArea
import com.trailmap.offline.OfflinePacks
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.tan

/** A preset offline map: a labeled box with its own zoom depth. */
internal data class PresetRegion(
    val label: String,
    val kind: String,
    val north: Double,
    val south: Double,
    val east: Double,
    val west: Double,
    val minZoom: Double,
    val maxZoom: Double,
) {
    val bounds: ViewBounds get() = ViewBounds(north = north, south = south, east = east, west = west, zoom = minZoom)

    /** A zoomed-out map of a whole state: too coarse for trails, and its box clips neighbours. */
    val overview: Boolean get() = kind == "State overview"
}

internal val PRESETS = listOf(
    PresetRegion("KC Metro", "Metro", 39.40, 38.80, -94.30, -94.80, 10.0, 14.0),
    PresetRegion("Lawrence, KS", "City", 38.99, 38.90, -95.15, -95.30, 11.0, 14.0),
    PresetRegion("Columbia, MO", "City", 39.00, 38.88, -92.25, -92.40, 11.0, 14.0),
    PresetRegion("Springfield, MO", "City", 37.30, 37.08, -93.18, -93.42, 10.0, 14.0),
    PresetRegion("St. Louis", "Metro", 38.78, 38.52, -90.15, -90.45, 10.0, 14.0),
    PresetRegion("Missouri (overview)", "State overview", 40.65, 35.95, -89.05, -95.80, 6.0, 9.0),
    PresetRegion("Kansas (overview)", "State overview", 40.05, 36.95, -94.55, -102.10, 6.0, 9.0),
)

/** The basemap's vector tiles stop at zoom 14; MapLibre draws closer zooms from those. */
private const val MAX_TILE_ZOOM = 14.0

/**
 * Offline maps: basemap tiles for areas to use without signal. Trails come from the state packs
 * (the Trail data screen), not from here — downloading an area adds the packs for its states,
 * so it still arrives with its trails in one tap. Up to 0.16.0 each area also pulled its own
 * trail data from Overpass; that is gone, and what it saved can be cleared.
 */
@Composable
fun OfflineScreen(
    vm: TrailsViewModel,
    onBack: () -> Unit,
    onOpenDiagnostics: () -> Unit = {},
    onOpenStates: () -> Unit = {},
) {
    val context = LocalContext.current
    val ui by vm.state.collectAsStateWithLifecycle()

    val dark = when (ui.mapTheme) {
        MapTheme.SYSTEM -> isSystemInDarkTheme()
        MapTheme.LIGHT -> false
        MapTheme.DARK -> true
    }
    val styleUrl = OfflinePacks.styleUrl(dark)

    var areas by remember { mutableStateOf<List<OfflineArea>>(emptyList()) }
    var status by remember { mutableStateOf<String?>(null) }

    fun refresh() = OfflinePacks.list(context) { areas = it }

    // Raise the tile cap + load existing areas on entry.
    LaunchedEffect(Unit) {
        OfflinePacks.ensureLimit(context)
        refresh()
        vm.refreshOfflineSize()
    }

    // While anything is mid-download, re-poll the list every ~2s so progress bars advance.
    val downloading = areas.any { !it.complete }
    LaunchedEffect(downloading) {
        while (downloading) {
            delay(2000)
            refresh()
        }
    }

    /** Add the area's state packs; says which were added, for the status line. */
    fun withTrails(bounds: ViewBounds, overview: Boolean): String? =
        vm.addPackStatesFor(bounds, overview).takeIf { it.isNotEmpty() }?.let { "adding ${it.joinToString(", ")} trails" }

    // The tile download reports progress into the status line, so the note about which states'
    // trails came with it rides along on every update rather than vanishing at the first one.
    fun downloadView() {
        val vb = ui.viewBounds ?: return
        val n = nextName(areas.map { it.name }, "Current view")
        val note = withTrails(vb, overview = false)
        OfflinePacks.downloadBounds(
            context, n, styleUrl,
            vb.north, vb.south, vb.east, vb.west,
            max(10.0, floor(vb.zoom)).coerceAtMost(MAX_TILE_ZOOM), MAX_TILE_ZOOM,
        ) { s ->
            status = listOfNotNull(s, note).joinToString(" · ")
            refresh()
        }
        status = listOfNotNull("Starting download…", note).joinToString(" · ")
        refresh()
    }

    fun downloadPreset(preset: PresetRegion) {
        val n = nextName(areas.map { it.name }, preset.label)
        val note = withTrails(preset.bounds, preset.overview)
        OfflinePacks.downloadBounds(
            context, n, styleUrl,
            preset.north, preset.south, preset.east, preset.west,
            preset.minZoom, preset.maxZoom,
        ) { s ->
            status = listOfNotNull(s, note).joinToString(" · ")
            refresh()
        }
        status = listOfNotNull("Starting ${preset.label}…", note).joinToString(" · ")
        refresh()
    }

    // Whether each area's trails are on the phone, redone when the installed states change.
    val installedStates = ui.packStates.filter { it.installed }.map { it.slug }
    val areaTrails = remember(areas.map { it.region.id }, installedStates) {
        areas.associate { a -> a.region.id to a.bounds?.let(vm::areaTrailsOnPhone) }
    }
    val presetTrails = remember(installedStates) {
        PRESETS.filter { !it.overview }.associate { it.label to vm.areaTrailsOnPhone(it.bounds) }
    }

    OfflineContent(
        ui = ui,
        areas = areas.map {
            OfflineAreaUi(
                it.region.id, it.name, it.percent, it.complete, it.completedTiles,
                trailsOnPhone = areaTrails[it.region.id],
                oldStyle = it.styleUrl?.let { url -> !OfflinePacks.isCurrentStyle(url) } == true,
            )
        },
        presetTrails = presetTrails,
        status = status,
        onBack = onBack,
        onOpenDiagnostics = onOpenDiagnostics,
        onDownloadView = ::downloadView,
        onDownloadPreset = ::downloadPreset,
        onRetry = { id ->
            areas.firstOrNull { it.region.id == id }?.let { OfflinePacks.retry(it) { } }
            refresh()
        },
        onDelete = { id -> areas.firstOrNull { it.region.id == id }?.let { OfflinePacks.delete(it) { refresh() } } },
        onAddTrails = { id ->
            areas.firstOrNull { it.region.id == id }?.bounds?.let { b -> status = withTrails(b, overview = false)?.replaceFirstChar { it.uppercase() } }
        },
        onAddPresetTrails = { preset -> status = withTrails(preset.bounds, preset.overview)?.replaceFirstChar { it.uppercase() } },
        onClearOldTrails = vm::clearOfflineTrails,
        onOpenStates = onOpenStates,
        onCheckPack = vm::checkTrailPacks,
    )
}

/** What the screen shows for one downloaded area — plain data, free of MapLibre types. */
internal data class OfflineAreaUi(
    val id: Long,
    val name: String,
    val percent: Int,
    val complete: Boolean,
    val completedTiles: Long,
    /** The area's trails load from the phone's state packs; null when not known. */
    val trailsOnPhone: Boolean? = null,
    /** Downloaded with a basemap the app no longer draws, so it no longer helps offline. */
    val oldStyle: Boolean = false,
)

/** Stateless body of [OfflineScreen], so it can be rendered with sample state. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OfflineContent(
    ui: TrailsUiState,
    areas: List<OfflineAreaUi>,
    status: String?,
    onBack: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onDownloadView: () -> Unit,
    onDownloadPreset: (PresetRegion) -> Unit,
    onRetry: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    presetTrails: Map<String, Boolean> = emptyMap(),
    onAddTrails: (Long) -> Unit = {},
    onAddPresetTrails: (PresetRegion) -> Unit = {},
    onClearOldTrails: () -> Unit = {},
    onOpenStates: () -> Unit = {},
    onCheckPack: () -> Unit = {},
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Offline maps") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onOpenDiagnostics) {
                        Icon(Icons.Filled.BugReport, contentDescription = "Diagnostics")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Trails first: the state packs are what make loads instant, here or offline.
            item(key = "pack") { TrailPackCard(ui, onOpenStates, onCheckPack) }

            if (ui.offlineTrailBytes > 0L) {
                item(key = "old_trails") { OldTrailDataCard(ui.offlineTrailBytes, onClearOldTrails) }
            }

            item(key = "summary") { StorageSummary(areas) }

            // Anything still downloading.
            items(areas.filter { !it.complete }, key = { "dl_${it.id}" }) { area ->
                DownloadCard(area, onRetry = { onRetry(area.id) }, onDelete = { onDelete(area.id) })
            }

            item {
                val vb = ui.viewBounds
                Button(onClick = onDownloadView, enabled = vb != null, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Download, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Download current view")
                }
                Text(
                    if (vb == null) {
                        "Pan the map first to set a current view."
                    } else {
                        val minZoom = max(10.0, floor(vb.zoom)).coerceAtMost(MAX_TILE_ZOOM).toInt()
                        "About ${formatCount(tileCount(vb.north, vb.south, vb.east, vb.west, minZoom, MAX_TILE_ZOOM.toInt()))} " +
                            "map tiles · zoom $minZoom–${MAX_TILE_ZOOM.toInt()} · plus its states' trails"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
                status?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                    )
                }
            }

            item {
                Text("Regions", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            item {
                Column {
                    PRESETS.forEach { preset ->
                        // Areas are named "<label> N", so a finished one means this preset is done.
                        val mine = areas.filter { it.name.startsWith(preset.label + " ") }
                        val done = mine.any { it.complete && !it.oldStyle }
                        val running = mine.any { !it.complete }
                        val trailsMissing = presetTrails[preset.label] == false
                        ListItem(
                            headlineContent = { Text(preset.label) },
                            supportingContent = {
                                val tiles = tileCount(preset.north, preset.south, preset.east, preset.west, preset.minZoom.toInt(), preset.maxZoom.toInt())
                                Text(
                                    if (done && trailsMissing) "Map saved · its trails aren't on the phone"
                                    else "${preset.kind} · zoom ${preset.minZoom.toInt()}–${preset.maxZoom.toInt()} · ~${formatCount(tiles)} tiles",
                                )
                            },
                            trailingContent = {
                                when {
                                    running -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                    done && trailsMissing -> TextButton(onClick = { onAddPresetTrails(preset) }) { Text("Add trails") }
                                    done -> Icon(Icons.Filled.CheckCircle, contentDescription = "Downloaded", tint = MaterialTheme.colorScheme.primary)
                                    else -> Icon(Icons.Filled.Download, contentDescription = "Download ${preset.label}")
                                }
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable(enabled = !done && !running) { onDownloadPreset(preset) },
                        )
                        HorizontalDivider()
                    }
                }
            }

            val ready = areas.filter { it.complete }
            if (ready.isNotEmpty()) {
                item {
                    Text("Downloaded", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                items(ready, key = { it.id }) { area ->
                    ListItem(
                        headlineContent = { Text(area.name) },
                        supportingContent = {
                            Text(
                                "Map ${formatCount(area.completedTiles)} tiles · " + when {
                                    area.oldStyle -> "old map style, not used any more — delete and download again"
                                    area.trailsOnPhone == true -> "trails on this phone"
                                    area.trailsOnPhone == false -> "its trails aren't on the phone"
                                    else -> "map only"
                                },
                            )
                        },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (area.trailsOnPhone == false && !area.oldStyle) {
                                    TextButton(onClick = { onAddTrails(area.id) }) { Text("Add trails") }
                                }
                                IconButton(onClick = { onDelete(area.id) }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Delete ${area.name}")
                                }
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
        }
    }
}

/**
 * Trail data by state: which states' trails live on the phone, with the way into the Trail
 * data screen that chooses them.
 */
@Composable
private fun TrailPackCard(ui: TrailsUiState, onOpenStates: () -> Unit, onCheck: () -> Unit) {
    val onPhone = ui.packStates.filter { it.installed }
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                if (onPhone.isEmpty()) "Trail data by state" else "${onPhone.joinToString(", ") { it.name }} trails on this phone",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                if (onPhone.isEmpty()) {
                    "Put a state's trails on the phone and the map loads them instantly, even offline, " +
                        "instead of waiting on OpenStreetMap's servers."
                } else {
                    megabytes(onPhone.sumOf { it.bytes }) +
                        (osmDate(onPhone.mapNotNull { it.osmTimestamp }.minOrNull())?.let { " · OpenStreetMap data from $it" } ?: "") +
                        ". Loads there are instant and work offline."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PackSyncStatus(ui)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onOpenStates) { Text(if (onPhone.isEmpty()) "Choose states" else "Manage states") }
                if (onPhone.isNotEmpty()) TextButton(onClick = onCheck) { Text("Check for updates") }
            }
        }
    }
}

/** Trail data 0.16.0 and earlier saved per area. The state packs hold those trails now. */
@Composable
private fun OldTrailDataCard(bytes: Long, onClear: () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Old trail downloads · ${megabytes(bytes)}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "Saved per area by earlier versions. State packs hold these trails now.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onClear) { Text("Clear") }
        }
    }
}

/** Offline map areas and tiles held. */
@Composable
private fun StorageSummary(areas: List<OfflineAreaUi>) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            SummaryStat("${areas.size}", if (areas.size == 1) "offline map" else "offline maps")
            SummaryStat(formatCount(areas.sumOf { it.completedTiles }), "map tiles")
        }
    }
}

@Composable
private fun SummaryStat(value: String, label: String) = Column {
    Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** A map download in progress. Retry restarts an area that has stalled. */
@Composable
private fun DownloadCard(area: OfflineAreaUi, onRetry: () -> Unit, onDelete: () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    area.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onRetry) { Icon(Icons.Filled.Refresh, contentDescription = "Retry ${area.name}") }
                IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Delete ${area.name}") }
            }
            Column(Modifier.padding(end = 12.dp)) {
                ProgressLine("Map tiles ${area.percent}%", area.percent / 100f)
            }
        }
    }
}

@Composable
private fun ProgressLine(label: String, fraction: Float) {
    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp))
}

/**
 * Web-mercator tiles covering a box over a zoom range — what MapLibre will download for it.
 * Close enough to warn before a big download; the real count can differ slightly at edges.
 */
internal fun tileCount(north: Double, south: Double, east: Double, west: Double, minZoom: Int, maxZoom: Int): Long {
    fun x(lon: Double, z: Int) = floor((lon + 180.0) / 360.0 * (1 shl z)).toLong()
    fun y(lat: Double, z: Int): Long {
        val r = Math.toRadians(lat.coerceIn(-85.0511, 85.0511))
        return floor((1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0 * (1 shl z)).toLong()
    }
    return (minZoom..maxZoom).sumOf { z ->
        (x(east, z) - x(west, z) + 1) * (y(south, z) - y(north, z) + 1)
    }
}

internal fun formatCount(n: Long): String = when {
    n >= 10_000 -> "%.1fk".format(n / 1000.0)
    else -> "%,d".format(n)
}

private fun nextName(names: List<String>, base: String): String {
    var i = 1
    val existing = names.toSet()
    while ("$base $i" in existing) i++
    return "$base $i"
}
