package com.trailmap.ui

import com.trailmap.offline.TrailDownloads
import androidx.core.content.ContextCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import android.os.Build
import android.content.pm.PackageManager
import android.Manifest
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.tan
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trailmap.offline.OfflineArea
import com.trailmap.offline.OfflinePacks
import kotlinx.coroutines.delay
import kotlin.math.floor
import kotlin.math.max

/** A preset offline region: a labeled bbox with its own zoom depth. */
/** A preset's box as [ViewBounds], for the trail prefetch and coverage check. */
internal val PresetRegion.bounds: com.trailmap.data.ViewBounds
    get() = com.trailmap.data.ViewBounds(north = north, south = south, east = east, west = west, zoom = minZoom)

internal data class PresetRegion(
    val label: String,
    val kind: String,
    val north: Double,
    val south: Double,
    val east: Double,
    val west: Double,
    val minZoom: Double,
    val maxZoom: Double,
)

internal val PRESETS = listOf(
    PresetRegion("KC Metro", "Metro", 39.40, 38.80, -94.30, -94.80, 10.0, 14.0),
    PresetRegion("Lawrence, KS", "City", 38.99, 38.90, -95.15, -95.30, 11.0, 14.0),
    PresetRegion("Columbia, MO", "City", 39.00, 38.88, -92.25, -92.40, 11.0, 14.0),
    PresetRegion("Springfield, MO", "City", 37.30, 37.08, -93.18, -93.42, 10.0, 14.0),
    PresetRegion("St. Louis", "Metro", 38.78, 38.52, -90.15, -90.45, 10.0, 14.0),
    PresetRegion("Missouri (overview)", "State overview", 40.65, 35.95, -89.05, -95.80, 6.0, 9.0),
    PresetRegion("Kansas (overview)", "State overview", 40.05, 36.95, -94.55, -102.10, 6.0, 9.0),
)

@OptIn(ExperimentalMaterial3Api::class)
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

    // The download runs in the background with a progress notification; ask to show it
    // (Android 13+) the first time one starts. It runs either way.
    val notifyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun askToNotify() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifyLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun downloadView() {
        val vb = ui.viewBounds ?: return
        val n = nextName(areas.map { it.name }, "Current view")
        val minZoom = max(10.0, floor(vb.zoom))
        OfflinePacks.downloadBounds(
            context, n, styleUrl,
            vb.north, vb.south, vb.east, vb.west,
            minZoom, 15.0,
        ) { s ->
            status = s
            refresh()
        }
        // Tiles alone give you a basemap with no trails on it; pull the
        // trail data for the same box so the area is actually usable.
        askToNotify()
        vm.prefetchTrailsFor(vb, n)
        status = "Starting download…"
        refresh()
    }

    fun downloadPreset(preset: PresetRegion) {
        val n = nextName(areas.map { it.name }, preset.label)
        OfflinePacks.downloadBounds(
            context, n, styleUrl,
            preset.north, preset.south, preset.east, preset.west,
            preset.minZoom, preset.maxZoom,
        ) { s ->
            status = s
            refresh()
        }
        askToNotify()
        vm.prefetchTrailsFor(preset.bounds, preset.label)
        status = "Starting ${preset.label} download…"
        refresh()
    }

    // Map tiles and trail data download separately, so check each area's trails on disk. A
    // tick used to mean tiles only: areas saved before 0.11.0, or whose trail step failed,
    // showed as downloaded while every load there still went to the network.
    var coverage by remember { mutableStateOf<Map<Long, Pair<Int, Int>>>(emptyMap()) }
    var presetCoverage by remember { mutableStateOf<Map<String, Pair<Int, Int>>>(emptyMap()) }
    val coverageKey = listOf(areas.map { it.region.id to it.complete }, ui.offlineTrailBytes, ui.mode, ui.trailPrefetchProgress == null)
    LaunchedEffect(coverageKey) {
        coverage = areas.filter { it.complete }.mapNotNull { a -> a.bounds?.let { a.region.id to vm.trailCoverage(it) } }.toMap()
        presetCoverage = PRESETS.associate { it.label to vm.trailCoverage(it.bounds) }
    }

    OfflineContent(
        ui = ui,
        areas = areas.map {
            OfflineAreaUi(
                it.region.id, it.name, it.percent, it.complete, it.completedTiles, coverage[it.region.id],
                queued = it.bounds?.let { b -> TrailDownloads.keyFor(b, ui.mode == MapMode.MTB) in ui.trailQueuedKeys } == true,
            )
        },
        presetTrails = presetCoverage,
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
        onClearTrails = { vm.clearOfflineTrails() },
        onGetTrails = { id ->
            askToNotify()
            areas.firstOrNull { it.region.id == id }?.let { a -> a.bounds?.let { vm.prefetchTrailsFor(it, a.name) } }
        },
        onGetPresetTrails = { preset ->
            askToNotify()
            vm.prefetchTrailsFor(preset.bounds, preset.label)
        },
        onCancelTrails = vm::cancelTrailPrefetch,
        onGetAllTrails = {
            askToNotify()
            // One job per piece of ground: a preset and the area downloaded from it ("KC Metro",
            // "KC Metro 1") cover the same box, so queue it once, under the preset's name.
            val mtb = ui.mode == MapMode.MTB
            val jobs = LinkedHashMap<String, Pair<com.trailmap.data.ViewBounds, String>>()
            for (preset in PRESETS) {
                val tilesDone = areas.any { it.complete && it.name.startsWith(preset.label + " ") }
                val t = presetCoverage[preset.label]
                if (tilesDone && (t == null || t.first < t.second)) {
                    jobs.putIfAbsent(TrailDownloads.keyFor(preset.bounds, mtb), preset.bounds to preset.label)
                }
            }
            for (a in areas.filter { it.complete }) {
                val b = a.bounds ?: continue
                if (PRESETS.any { a.name.startsWith(it.label + " ") }) continue
                val t = coverage[a.region.id]
                if (t == null || t.first < t.second) jobs.putIfAbsent(TrailDownloads.keyFor(b, mtb), b to a.name)
            }
            jobs.filterKeys { it !in ui.trailQueuedKeys }.values.forEach { (b, name) -> vm.prefetchTrailsFor(b, name) }
        },
        onDismissTrailResult = vm::clearTrailPrefetch,
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
    /** Trail sections saved / needed for this area in the current mode; null = not known yet. */
    val trails: Pair<Int, Int>? = null,
    /** Its trail data is queued or downloading. */
    val queued: Boolean = false,
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
    onClearTrails: () -> Unit,
    presetTrails: Map<String, Pair<Int, Int>> = emptyMap(),
    onGetTrails: (Long) -> Unit = {},
    onGetPresetTrails: (PresetRegion) -> Unit = {},
    onCancelTrails: () -> Unit = {},
    onGetAllTrails: () -> Unit = {},
    onDismissTrailResult: () -> Unit = {},
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
            // What's stored, up top, instead of paragraphs at the bottom.
            item { StorageSummary(ui, areas, onClearTrails) }

            // The regional trail pack is what makes loads instant, so it leads the screen.
            item(key = "pack") { TrailPackCard(ui, onOpenStates, onCheckPack) }

            // Anything still downloading, with map tiles and trail data side by side.
            val tileDownloads = areas.filter { !it.complete }
            items(tileDownloads, key = { "dl_${it.id}" }) { area ->
                DownloadCard(area, ui.trailPrefetchProgress, onRetry = { onRetry(area.id) }, onDelete = { onDelete(area.id) })
            }

            // A trail download on its own — "Get trails" on an area whose tiles are done — has
            // no tile card to ride along in, so it gets its own. It used to show nothing at all.
            val trails = ui.trailPrefetchProgress
            if (trails != null && tileDownloads.isEmpty()) {
                item(key = "trails_dl") {
                    TrailDownloadCard(
                        ui.trailPrefetchArea ?: "This area", trails, onCancelTrails,
                        note = ui.trailPrefetch?.takeIf { it.startsWith("Servers busy") || it.startsWith("Waiting") },
                        queued = ui.trailQueued,
                    )
                }
            }
            // And its outcome stays up until dismissed, rather than a line of small print.
            val result = ui.trailPrefetch
            if (trails == null && result != null) {
                item(key = "trails_result") { TrailResultCard(ui.trailPrefetchArea, result, onDismissTrailResult) }
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
                        val minZoom = max(10.0, floor(vb.zoom)).toInt()
                        "About ${formatCount(tileCount(vb.north, vb.south, vb.east, vb.west, minZoom, 15))} " +
                            "map tiles · zoom $minZoom–15 · plus its trails"
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Regions",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    // Every downloaded map still missing trail data, queued in one go.
                    val missing = PRESETS.count { p ->
                        val tr = presetTrails[p.label]
                        areas.any { it.complete && it.name.startsWith(p.label + " ") } &&
                            (tr == null || tr.first < tr.second) &&
                            TrailDownloads.keyFor(p.bounds, ui.mode == MapMode.MTB) !in ui.trailQueuedKeys
                    } + areas.count { a ->
                        a.complete && !a.queued && a.trails != null && a.trails.first < a.trails.second &&
                            PRESETS.none { a.name.startsWith(it.label + " ") }
                    }
                    if (missing > 0) TextButton(onClick = onGetAllTrails) { Text("Get all trails") }
                }
            }
            item {
                Column {
                    PRESETS.forEach { preset ->
                        // Areas are named "<label> N", so a finished one means this preset is done.
                        val mine = areas.filter { it.name.startsWith(preset.label + " ") }
                        val tilesDone = mine.any { it.complete }
                        val queued = TrailDownloads.keyFor(preset.bounds, ui.mode == MapMode.MTB) in ui.trailQueuedKeys
                        val running = mine.any { !it.complete }
                        val trails = presetTrails[preset.label]
                        val trailsDone = trails != null && trails.first >= trails.second
                        val done = tilesDone && trailsDone
                        ListItem(
                            headlineContent = { Text(preset.label) },
                            supportingContent = {
                                val tiles = tileCount(preset.north, preset.south, preset.east, preset.west, preset.minZoom.toInt(), preset.maxZoom.toInt())
                                Text(
                                    if (tilesDone && trails != null && !trailsDone) {
                                        "Map saved · trails ${trails.first} of ${trails.second} sections"
                                    } else {
                                        "${preset.kind} · zoom ${preset.minZoom.toInt()}–${preset.maxZoom.toInt()} · ~${formatCount(tiles)} tiles"
                                    },
                                )
                            },
                            trailingContent = {
                                when {
                                    done -> Icon(Icons.Filled.CheckCircle, contentDescription = "Downloaded", tint = MaterialTheme.colorScheme.primary)
                                    running -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                    queued -> Text("Queued", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    tilesDone -> TextButton(
                                        onClick = { onGetPresetTrails(preset) },
                                    ) { Text("Get trails") }
                                    else -> Icon(Icons.Filled.Download, contentDescription = "Download ${preset.label}")
                                }
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable(enabled = !tilesDone && !running) { onDownloadPreset(preset) },
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
                            val t = area.trails
                            Text(
                                "Map ${formatCount(area.completedTiles)} tiles · " + when {
                                    t == null -> "checking trails…"
                                    t.first >= t.second -> "trails saved"
                                    else -> "trails ${t.first} of ${t.second} sections"
                                },
                            )
                        },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val t = area.trails
                                if (area.queued) {
                                    Text("Queued", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                } else if (t != null && t.first < t.second) {
                                    TextButton(
                                        onClick = { onGetTrails(area.id) },
                                    ) { Text("Get trails") }
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
 * Trail data by state: a summary of which states' trails live on the phone, with the way into
 * the Trail data screen that chooses them. It leads the screen because it is what makes loads
 * instant — the rest of this screen is map tiles and older per-area trail downloads.
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
                    "${megabytes(onPhone.sumOf { it.bytes })} · OpenStreetMap data from " +
                        "${osmDate(onPhone.mapNotNull { it.osmTimestamp }.minOrNull())}. Loads there are instant and work offline."
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

/** Areas, tiles and trail data held, with a way to clear the trail data. */
@Composable
private fun StorageSummary(ui: TrailsUiState, areas: List<OfflineAreaUi>, onClearTrails: () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                SummaryStat("${areas.size}", if (areas.size == 1) "area" else "areas")
                SummaryStat(formatCount(areas.sumOf { it.completedTiles }), "map tiles")
                SummaryStat("%.1f MB".format(ui.offlineTrailBytes / (1024.0 * 1024.0)), "trail data")
            }
            if (ui.offlineTrailBytes > 0L) TextButton(onClick = onClearTrails) { Text("Clear trails") }
        }
    }
}

@Composable
private fun SummaryStat(value: String, label: String) = Column {
    Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * A download in progress. Retry restarts an area that has stalled; the trail-data bar shows
 * only while the trail prefetch that accompanies a download is running.
 */
@Composable
private fun DownloadCard(area: OfflineAreaUi, trails: Pair<Int, Int>?, onRetry: () -> Unit, onDelete: () -> Unit) {
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
                trails?.let { (done, total) ->
                    ProgressLine("Trail data $done of $total", if (total == 0) 1f else done / total.toFloat())
                }
            }
        }
    }
}

/** Progress of a trail-data download, with Cancel. */
@Composable
private fun TrailDownloadCard(area: String, progress: Pair<Int, Int>, onCancel: () -> Unit, note: String? = null, queued: Int = 0) {
    val (done, total) = progress
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Trails for $area",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
            Column(Modifier.padding(end = 8.dp)) {
                if (total > 0) {
                    ProgressLine("Section $done of $total · carries on if you leave the app", done / total.toFloat())
                }
                note?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                if (queued > 0) {
                    Text(
                        if (queued == 1) "1 more area queued" else "$queued more areas queued",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
    }
}

/** How the last trail download ended, until dismissed. */
@Composable
private fun TrailResultCard(area: String?, message: String, onDismiss: () -> Unit) {
    val trouble = message.startsWith("Paused") || message.startsWith("Couldn't") || "too busy" in message
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (trouble) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                area?.let {
                    Text(it, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                }
                Text(message, style = MaterialTheme.typography.bodyMedium)
            }
            IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Dismiss") }
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

/** 1234 → "1,234", 18422 → "18.4k". */
internal fun formatCount(n: Long): String = when {
    n >= 10_000 -> "%.1fk".format(n / 1000.0)
    else -> "%,d".format(n)
}

/** Next free "<base> N" name given the areas already present (so repeats don't collide). */
private fun nextName(names: List<String>, base: String): String {
    var i = 1
    val existing = names.toSet()
    while ("$base $i" in existing) i++
    return "$base $i"
}