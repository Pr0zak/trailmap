package com.trailmap.ui

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
private data class PresetRegion(
    val label: String,
    val north: Double,
    val south: Double,
    val east: Double,
    val west: Double,
    val minZoom: Double,
    val maxZoom: Double,
)

private val PRESETS = listOf(
    PresetRegion("KC Metro", 39.40, 38.80, -94.30, -94.80, 10.0, 14.0),
    PresetRegion("Lawrence, KS", 38.99, 38.90, -95.15, -95.30, 11.0, 14.0),
    PresetRegion("Columbia, MO", 39.00, 38.88, -92.25, -92.40, 11.0, 14.0),
    PresetRegion("Springfield, MO", 37.30, 37.08, -93.18, -93.42, 10.0, 14.0),
    PresetRegion("St. Louis", 38.78, 38.52, -90.15, -90.45, 10.0, 14.0),
    PresetRegion("Missouri (overview)", 40.65, 35.95, -89.05, -95.80, 6.0, 9.0),
    PresetRegion("Kansas (overview)", 40.05, 36.95, -94.55, -102.10, 6.0, 9.0),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineScreen(vm: TrailsViewModel, onBack: () -> Unit) {
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
    }

    // While anything is mid-download, re-poll the list every ~2s so progress bars advance.
    val downloading = areas.any { !it.complete }
    LaunchedEffect(downloading) {
        while (downloading) {
            delay(2000)
            refresh()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Offline maps") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Spacer(Modifier.height(8.dp))
                val vb = ui.viewBounds
                Button(
                    onClick = {
                        if (vb != null) {
                            val n = nextName(areas, "Current view")
                            val minZoom = max(10.0, floor(vb.zoom))
                            OfflinePacks.downloadBounds(
                                context, n, styleUrl,
                                vb.north, vb.south, vb.east, vb.west,
                                minZoom, 15.0,
                            ) { s ->
                                status = s
                                refresh()
                            }
                            status = "Starting download…"
                            refresh()
                        }
                    },
                    enabled = vb != null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Download, contentDescription = null)
                    Text("  Download current view")
                }
                if (vb == null) {
                    Text(
                        "Pan the map first to set a current view.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                status?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                }
            }

            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Download a region",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            items(PRESETS) { preset ->
                OutlinedButton(
                    onClick = {
                        val n = nextName(areas, preset.label)
                        OfflinePacks.downloadBounds(
                            context, n, styleUrl,
                            preset.north, preset.south, preset.east, preset.west,
                            preset.minZoom, preset.maxZoom,
                        ) { s ->
                            status = s
                            refresh()
                        }
                        status = "Starting ${preset.label} download…"
                        refresh()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(preset.label, modifier = Modifier.fillMaxWidth())
                }
            }

            item {
                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Text(
                    "Downloaded areas",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (areas.isEmpty()) {
                    Text(
                        "No offline areas yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }

            items(areas, key = { it.region.id }) { area ->
                AreaRow(
                    area = area,
                    onRetry = {
                        OfflinePacks.retry(area) { }
                        refresh()
                    },
                    onDelete = {
                        OfflinePacks.delete(area) { refresh() }
                    },
                )
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun AreaRow(area: OfflineArea, onRetry: () -> Unit, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(area.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                if (area.complete) {
                    Text(
                        "Ready · ${area.completedTiles} tiles",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LinearProgressIndicator(
                        progress = { area.percent / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Downloading… ${area.percent}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (!area.complete) {
                IconButton(onClick = onRetry) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Retry ${area.name}")
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete ${area.name}")
            }
        }
    }
}

/** Next free "<base> N" name given the areas already present (so repeats don't collide). */
private fun nextName(areas: List<OfflineArea>, base: String): String {
    var i = 1
    val existing = areas.map { it.name }.toSet()
    while ("$base $i" in existing) i++
    return "$base $i"
}
