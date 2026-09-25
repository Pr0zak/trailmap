@file:OptIn(ExperimentalMaterial3Api::class)

package com.trailmap.snap.after

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.trailmap.data.Ride
import com.trailmap.data.SurfaceType
import com.trailmap.ui.MtbBadge

private fun Ride.mix(): Map<SurfaceType, Double> =
    surfaceMix.mapKeys { (k, _) -> runCatching { SurfaceType.valueOf(k) }.getOrDefault(SurfaceType.UNKNOWN) }

// ---------- Rides ----------

@Composable
fun RidesAfter(rides: List<Ride>) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("Rides", fontWeight = FontWeight.Bold) }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = {}, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("New ride") })
        },
    ) { pad ->
        if (rides.isEmpty()) {
            Column(
                Modifier.padding(pad).fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.Filled.Route, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(12.dp))
                Text("Plan a ride", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "Chain trails together to see the total distance and surface mix before you go.",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = {}) { Text("Browse trails") }
            }
            return@Scaffold
        }
        LazyColumn(Modifier.padding(pad), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            rides.forEach { r ->
                item {
                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.Bottom) {
                                    Text(r.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                    Text("%.1f mi".format(r.totalMiles), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                }
                                Text(
                                    r.trails.joinToString(" → ") { it.name },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                                Spacer(Modifier.height(10.dp))
                                SurfaceMixBar(r.mix(), legend = false)
                            }
                            Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RideDetailAfter(ride: Ride) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(ride.name, fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = {}) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = {}) { Icon(Icons.Filled.Edit, "Rename") }
                    IconButton(onClick = {}) { Icon(Icons.Filled.Delete, "Delete") }
                },
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Button(onClick = {}, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Icon(Icons.Filled.Map, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Show ride on map")
                }
            }
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad)) {
            item {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("%.1f".format(ride.totalMiles), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                        Text(" mi", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 4.dp))
                        Spacer(Modifier.weight(1f))
                        Text("${ride.trails.size} trails", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(12.dp))
                    SurfaceMixBar(ride.mix())
                }
                HorizontalDivider()
            }
            var cum = 0.0
            ride.trails.forEachIndexed { i, t ->
                val start = cum
                cum += t.lengthMeters / 1609.344
                val end = cum
                item {
                    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.DragIndicator, "Reorder", tint = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(8.dp))
                        Box(
                            Modifier.size(28.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center,
                        ) { Text("${i + 1}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer) }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(t.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                val s = runCatching { SurfaceType.valueOf(t.surface) }.getOrDefault(SurfaceType.UNKNOWN)
                                Dot(s.color, 8)
                                Text(
                                    "${s.label} · %.1f mi · mile %.1f–%.1f".format(t.lengthMeters / 1609.344, start, end),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                MtbBadge(t.mtbScale)
                            }
                        }
                        IconButton(onClick = {}) { Icon(Icons.Filled.Close, "Remove", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
    }
}

// ---------- Offline ----------

@Composable
fun OfflineAfter() {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Offline maps") },
                navigationIcon = { IconButton(onClick = {}) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { IconButton(onClick = {}) { Icon(Icons.Filled.BugReport, "Diagnostics") } },
            )
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
            // O3: one storage summary instead of a paragraph at the bottom.
            item {
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        MiniStat("2", "areas")
                        MiniStat("18.4k", "map tiles")
                        MiniStat("12.3 MB", "trail data")
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = {}) { Text("Manage") }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            // O2: the running download is a card at the top, with map and trail progress side by side.
            item {
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Current view 1", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            IconButton(onClick = {}) { Icon(Icons.Filled.Pause, "Pause") }
                        }
                        Text("Map tiles 46%", style = MaterialTheme.typography.labelMedium)
                        LinearProgressIndicator(progress = { 0.46f }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
                        Text("Trail data 6 of 9", style = MaterialTheme.typography.labelMedium)
                        LinearProgressIndicator(progress = { 6 / 9f }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
                    }
                }
                Spacer(Modifier.height(12.dp))
                Button(onClick = {}, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Download, null); Spacer(Modifier.width(8.dp)); Text("Download current view")
                }
                Text(
                    "About 1,200 tiles and 1 trail circle · zoom 12–15",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp).fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
                Text("Regions", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            // O1: regions as a list with state — downloaded ones say so instead of offering a duplicate.
            listOf(
                Triple("KC Metro", "Metro · zoom 10–14", true),
                Triple("Lawrence, KS", "City · zoom 11–14", false),
                Triple("Columbia, MO", "City · zoom 11–14", false),
                Triple("Springfield, MO", "City · zoom 10–14", false),
                Triple("St. Louis", "Metro · zoom 10–14", false),
                Triple("Missouri (overview)", "State · zoom 6–9 · no trail detail", false),
                Triple("Kansas (overview)", "State · zoom 6–9 · no trail detail", false),
            ).forEach { (name, sub, done) ->
                item {
                    ListItem(
                        headlineContent = { Text(name) },
                        supportingContent = { Text(sub) },
                        trailingContent = {
                            if (done) Icon(Icons.Filled.CheckCircle, "Downloaded", tint = MaterialTheme.colorScheme.primary)
                            else Icon(Icons.Filled.Download, "Download")
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

// ---------- Diagnostics ----------

@Composable
fun DiagnosticsAfter(lines: List<String>) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Diagnostics")
                        Text("trailmap 0.11.1 · auto-refreshing", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = { IconButton(onClick = {}) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = {}) { Icon(Icons.Filled.Share, "Share") }
                    IconButton(onClick = {}) { Icon(Icons.Filled.Delete, "Clear") }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad)) {
            // X1: a summary of the last load, then tag filters, then colored lines.
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    MiniStat("3.4 s", "last load")
                    MiniStat("network", "source")
                    MiniStat("mail.ru", "mirror")
                    MiniStat("1", "errors")
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("All", "Errors", "load", "overpass", "cache", "camera", "map").forEachIndexed { i, t ->
                    FilterChip(selected = i == 0, onClick = {}, label = { Text(t) })
                }
            }
            LazyColumn(Modifier.padding(horizontal = 12.dp)) {
                lines.forEach { line ->
                    val time = line.substring(0, 8)
                    val tag = line.substring(14, 22).trim()
                    val msg = line.substring(23)
                    val error = "failed" in msg
                    item {
                        Row(
                            Modifier.fillMaxWidth()
                                .background(if (error) MaterialTheme.colorScheme.errorContainer else Color.Transparent, RoundedCornerShape(8.dp))
                                .padding(vertical = 5.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Text(time, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(62.dp))
                            Box(
                                Modifier.width(64.dp).clip(RoundedCornerShape(4.dp)).background(tagColor(tag)).padding(horizontal = 4.dp, vertical = 1.dp),
                            ) { Text(tag, style = MaterialTheme.typography.labelSmall, color = Color.White, fontWeight = FontWeight.Bold) }
                            Spacer(Modifier.width(8.dp))
                            Text(msg, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                                color = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
        }
    }
}

private fun tagColor(tag: String) = when (tag) {
    "load" -> Color(0xFF2E7D4F)
    "overpass" -> Color(0xFF1E6FB8)
    "cache" -> Color(0xFF6B5B95)
    "camera" -> Color(0xFF8A5A2B)
    "map" -> Color(0xFF00796B)
    else -> Color(0xFF616161)
}
