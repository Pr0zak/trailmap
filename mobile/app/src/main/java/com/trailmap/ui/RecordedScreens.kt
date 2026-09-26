package com.trailmap.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trailmap.data.GeoPoint
import com.trailmap.data.Polyline
import com.trailmap.data.RecordedTrack
import com.trailmap.data.TrailOnTrack
import kotlin.math.cos
import kotlin.math.min

/*
 * Recorded activities from myvitals: the Rides tab's "Recorded" list, one row per activity,
 * and the screen for a single activity with the trails it went along.
 */

/** One recorded activity: what it was, when, how far and how long. */
@Composable
internal fun RecordedRow(rec: RecordedTrack, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(rec.kind.icon, contentDescription = rec.kind.label, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                rec.name ?: rec.kind.label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(
                    shortDate(rec.start),
                    rec.distanceMiles?.let { "%.1f mi".format(it) },
                    rec.durationS.takeIf { it > 0 }?.let { duration(it) },
                    rec.mph?.let { "%.1f mph".format(it) },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Which recorded activities the list shows. */
internal enum class RecordedFilter(val label: String) { ALL("All"), RIDES("Rides"), ON_FOOT("On foot") }

/**
 * The Rides tab's "Recorded" side: your activities from myvitals, newest first, or the offer
 * to connect when there is no server yet.
 */
@Composable
internal fun RecordedList(
    ui: TrailsUiState,
    onOpenRecorded: (String) -> Unit,
    onOpenMyVitals: () -> Unit,
    onSync: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!ui.myVitals.connected) {
        Column(
            modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Filled.Timeline, contentDescription = null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.size(12.dp))
            Text("Your rides, from myvitals", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "Connect your myvitals server to see the rides and walks you've recorded, which trails " +
                    "you've ridden, trails you haven't tried yet, and times at your own pace.",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(16.dp))
            Button(onClick = onOpenMyVitals) { Text("Connect myvitals") }
        }
        return
    }
    var filter by rememberSaveable { mutableStateOf(RecordedFilter.ALL) }
    val shown = remember(ui.recorded, filter) {
        ui.recorded.filter {
            when (filter) {
                RecordedFilter.ALL -> it.kind.ride || it.kind.onFoot
                RecordedFilter.RIDES -> it.kind.ride
                RecordedFilter.ON_FOOT -> it.kind.onFoot
            }
        }
    }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
        item(key = "head") {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    val rides = ui.recorded.count { it.kind.ride }
                    val foot = ui.recorded.count { it.kind.onFoot }
                    Text(
                        "$rides ${if (rides == 1) "ride" else "rides"} · $foot on foot",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        when {
                            ui.myVitals.syncing -> "Syncing…"
                            ui.myVitals.error != null -> "Last sync failed · synced ${ago(ui.myVitals.lastSync)}"
                            else -> "From myvitals · synced ${ago(ui.myVitals.lastSync)}"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (ui.myVitals.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (ui.myVitals.syncing) {
                    CircularProgressIndicator(Modifier.size(24.dp).padding(2.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = onSync) { Icon(Icons.Filled.Sync, contentDescription = "Sync now") }
                }
            }
            Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RecordedFilter.entries.forEach { f ->
                    FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) })
                }
            }
        }
        if (shown.isEmpty()) {
            item(key = "empty") {
                Text(
                    "Nothing with a GPS track here yet.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
        }
        items(shown, key = { it.id }) { rec ->
            RecordedRow(rec, onClick = { onOpenRecorded(rec.id) })
            HorizontalDivider(Modifier.padding(start = 48.dp))
        }
    }
}

@Composable
fun RecordedRideScreen(
    vm: TrailsViewModel,
    id: String,
    onBack: () -> Unit,
    onOpenTrail: (String) -> Unit,
    onShowOnMap: () -> Unit,
    onOpenRide: (String) -> Unit,
) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val trails by vm.recordedTrails.collectAsStateWithLifecycle()
    LaunchedEffect(id) { vm.loadRecordedTrails(id) }
    RecordedRideContent(
        track = ui.recordedById[id],
        trails = trails[id],
        onBack = onBack,
        onOpenTrail = onOpenTrail,
        onRetry = { vm.retryRecordedTrails(id) },
        onShowOnMap = {
            vm.showTrackOnMap(id)
            onShowOnMap()
        },
        onSaveAsRide = { vm.saveRecordedAsRide(id)?.let(onOpenRide) },
    )
}

/** Stateless body of [RecordedRideScreen], so it can be rendered with sample state. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecordedRideContent(
    track: RecordedTrack?,
    trails: RecordedTrails?,
    onBack: () -> Unit,
    onOpenTrail: (String) -> Unit,
    onRetry: () -> Unit,
    onShowOnMap: () -> Unit,
    onSaveAsRide: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            track?.let { it.name ?: it.kind.label } ?: "Activity",
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (track != null) {
                            Text(
                                longDateTime(track.start),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
        bottomBar = {
            if (track != null) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                    Row(
                        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Button(onClick = onShowOnMap, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.Map, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Show on map")
                        }
                        val ready = (trails as? RecordedTrails.Ready)?.trails.orEmpty()
                        OutlinedButton(onClick = onSaveAsRide, enabled = ready.isNotEmpty(), modifier = Modifier.weight(1f)) {
                            Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Save as ride")
                        }
                    }
                }
            }
        },
    ) { padding ->
        if (track == null) {
            Box(Modifier.padding(padding).fillMaxSize(), Alignment.Center) {
                Text("Activity not found.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Scaffold
        }
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            TrackPreview(track, (trails as? RecordedTrails.Ready)?.trails.orEmpty())
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        RecStat(track.distanceMiles?.let { "%.1f mi".format(it) } ?: "—", "Distance")
                        RecStat(track.durationS.takeIf { it > 0 }?.let { duration(it) } ?: "—", "Time")
                        RecStat(track.mph?.let { "%.1f mph".format(it) } ?: "—", "Average")
                    }
                }
                Column {
                    Text(
                        "Trails on this ${if (track.kind.onFoot) "outing" else "ride"}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    when (trails) {
                        null, RecordedTrails.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text("Finding the trails it went along…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        is RecordedTrails.Failed -> Column {
                            Text(trails.message, color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = onRetry) { Text("Try again") }
                        }
                        is RecordedTrails.Ready -> if (trails.trails.isEmpty()) {
                            Text(
                                "It didn't follow any named trail — streets, or paths without a name on the map.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            trails.trails.forEachIndexed { i, t -> TrailOnTrackRow(i + 1, t) { onOpenTrail(t.trail.id) } }
                        }
                    }
                }
            }
        }
    }
}

/** A trail the activity went along: its place in the order, and how much of it was covered. */
@Composable
private fun TrailOnTrackRow(number: Int, t: TrailOnTrack, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(28.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text("$number", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(t.trail.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(t.trail.surface.color, 8)
                Spacer(Modifier.width(6.dp))
                Text(
                    "${t.trail.surface.label} · %.1f of %.1f mi".format(t.coveredMeters / 1609.344, t.trail.lengthMiles),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                MtbBadge(t.trail.mtbScale, Modifier.padding(start = 6.dp))
            }
        }
    }
}

@Composable
private fun RecStat(value: String, label: String) = Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * The activity's track in blue over the trails it went along, drawn on a plain tile like the
 * trail detail's preview — no basemap, so it costs nothing and works offline.
 */
@Composable
private fun TrackPreview(track: RecordedTrack, trails: List<TrailOnTrack>) {
    val pts = remember(track.polyline) { Polyline.decode(track.polyline) }
    val bg = MaterialTheme.colorScheme.surfaceContainerHigh
    val grid = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    val casing = MaterialTheme.colorScheme.surfaceContainerLowest
    val blue = youColor(darkTheme())
    // Clipped: the trails it used run on past the track, and a Canvas draws outside itself.
    Box(Modifier.fillMaxWidth().height(200.dp).clipToBounds().background(bg)) {
        Canvas(Modifier.fillMaxSize().padding(20.dp)) {
            if (pts.size < 2) return@Canvas
            val k = cos(Math.toRadians(pts[pts.size / 2].lat))
            val minX = pts.minOf { it.lon * k }; val maxX = pts.maxOf { it.lon * k }
            val minY = pts.minOf { it.lat }; val maxY = pts.maxOf { it.lat }
            val w = (maxX - minX).coerceAtLeast(1e-6)
            val h = (maxY - minY).coerceAtLeast(1e-6)
            val scale = min(size.width / w, size.height / h).toFloat()
            val ox = (size.width - w.toFloat() * scale) / 2
            val oy = (size.height - h.toFloat() * scale) / 2
            fun pt(p: GeoPoint) = Offset(ox + ((p.lon * k - minX) * scale).toFloat(), oy + ((maxY - p.lat) * scale).toFloat())
            fun path(line: List<GeoPoint>) = Path().apply {
                moveTo(pt(line[0]).x, pt(line[0]).y)
                line.drop(1).forEach { lineTo(pt(it).x, pt(it).y) }
            }
            for (i in 0..6) drawLine(grid, Offset(size.width * i / 6f, -20f), Offset(size.width * i / 6f, size.height + 20f), 1f)
            for (i in 0..3) drawLine(grid, Offset(-20f, size.height * i / 3f), Offset(size.width + 20f, size.height * i / 3f), 1f)
            // The trails it used, in their surface colours, wide enough to show round the track.
            for (t in trails) for (line in t.trail.paths) {
                if (line.size < 2) continue
                drawPath(path(line), t.trail.surface.color.copy(alpha = 0.85f), style = Stroke(17f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            val route = path(pts)
            drawPath(route, casing, style = Stroke(9f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(route, blue, style = Stroke(5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawCircle(casing, 11f, pt(pts.first()))
            drawCircle(blue, 8f, pt(pts.first()))
            drawCircle(blue, 10f, pt(pts.last()))
            drawCircle(casing, 5f, pt(pts.last()))
        }
    }
}
