package com.trailmap.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import com.trailmap.data.GeoPoint
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.produceState
import kotlin.math.cos
import kotlin.math.min
import com.trailmap.data.ElevationProfile
import com.trailmap.data.Trail
import com.trailmap.data.TrailConditions
import com.trailmap.data.TrailRoute
import com.trailmap.data.TrailStatus
import com.trailmap.data.TrailVisits
import com.trailmap.data.UseType
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrailDetailScreen(
    vm: TrailsViewModel,
    id: String,
    onBack: () -> Unit,
    onShowOnMap: () -> Unit = {},
    onOpenRecorded: (String) -> Unit = {},
) {
    val trail = vm.trailById(id)
    val ui by vm.state.collectAsStateWithLifecycle()
    val profiles by vm.profiles.collectAsStateWithLifecycle()

    LaunchedEffect(id) { vm.ensureProfile(id) }
    // A trail opened from a recorded ride may not be in the loaded set, so ask directly.
    val visits = ui.visits[id] ?: remember(id, ui.visitsVersion, ui.recorded) { vm.visitsFor(id) }

    TrailDetailContent(
        trail = trail,
        profile = profiles[id],
        ui = ui,
        visits = visits,
        onOpenRecorded = onOpenRecorded,
        onBack = onBack,
        onToggleSaved = vm::toggleSaved,
        onCreateRide = { name, t -> vm.createRide(name, seed = t) },
        onAddToRide = { rideId, t -> vm.addTrailToRide(rideId, t) },
        onShowOnMap = { t ->
            vm.selectTrail(t.id)
            vm.focusOn(t.center, TRAIL_FOCUS_ZOOM)
            onShowOnMap()
        },
    )
}

/** Stateless body of [TrailDetailScreen], so it can be rendered with sample state. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TrailDetailContent(
    trail: Trail?,
    profile: ElevationProfile?,
    ui: TrailsUiState,
    onBack: () -> Unit,
    onToggleSaved: (String) -> Unit,
    onCreateRide: (String, Trail) -> Unit,
    onAddToRide: (String, Trail) -> Unit,
    onShowOnMap: (Trail) -> Unit = {},
    chartScrub: Float? = null,
    visits: TrailVisits? = null,
    onOpenRecorded: (String) -> Unit = {},
) {
    val context = LocalContext.current
    var showAddToRide by remember { mutableStateOf(false) }
    // Where the elevation chart's scrub marker is, mirrored as a dot on the route preview.
    var scrubPoint by remember(profile) {
        mutableStateOf(chartScrub?.let { f -> profile?.let { sampleAt(it, f)?.point } })
    }

    fun openDirections(t: Trail) {
        // Route to where the trail starts, not its centroid, which can sit mid-woods.
        val start = TrailRoute.order(t.paths).firstOrNull()?.firstOrNull() ?: t.center
        val uri = Uri.parse("geo:${start.lat},${start.lon}?q=${start.lat},${start.lon}(${Uri.encode(t.name)})")
        try {
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW, uri), "Directions"))
        } catch (e: Exception) {
            Toast.makeText(context, "No map app available", Toast.LENGTH_SHORT).show()
        }
    }

    fun share(t: Trail) {
        val text = "${t.name} — %.1f mi trail. ".format(t.lengthMiles) +
            "https://www.google.com/maps?q=${t.center.lat},${t.center.lon}"
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        try {
            context.startActivity(Intent.createChooser(intent, "Share trail"))
        } catch (e: Exception) {
            Toast.makeText(context, "Nothing to share with", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        trail?.name ?: "Trail",
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (trail != null) {
                        val saved = ui.isSaved(trail.id)
                        IconButton(onClick = { onToggleSaved(trail.id) }) {
                            Icon(
                                if (saved) Icons.Filled.Star else Icons.Filled.StarBorder,
                                contentDescription = if (saved) "Remove from saved" else "Save trail",
                                tint = if (saved) {
                                    MaterialTheme.colorScheme.secondary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                        IconButton(onClick = { share(trail) }) {
                            Icon(Icons.Filled.Share, contentDescription = "Share trail")
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (trail != null) {
                // The two things you do from here, as labeled buttons rather than bar icons.
                Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                    Row(
                        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Button(onClick = { showAddToRide = true }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Add to ride")
                        }
                        OutlinedButton(onClick = { openDirections(trail) }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.Directions, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Directions")
                        }
                    }
                }
            }
        },
    ) { padding ->
        if (trail == null) {
            Box(Modifier.padding(padding).fillMaxSize(), Alignment.Center) {
                Text("Trail not found.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Scaffold
        }

        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            RoutePreview(
                trail, marker = scrubPoint, onShowOnMap = { onShowOnMap(trail) },
                ridden = visits?.riddenPaths.orEmpty(),
            )

            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SurfaceBadge(trail.surface)
                    MtbBadge(trail.mtbScale, Modifier.padding(start = 6.dp))
                    Spacer(Modifier.size(10.dp))
                    Text(
                        (if (trail.horseTrail) "Horse trail · " else "") +
                            "${usesLine(trail.uses)} · %.1f mi away".format(trail.distanceMiles),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                // Open or closed, from the trail status board myvitals polls. Unpaved only.
                val condition = remember(trail, ui.conditions) { TrailConditions.forTrail(trail, ui.conditions) }
                if (condition != null) ConditionCard(condition)

                // The numbers that decide a ride. Climb and descent are "—" until the
                // profile arrives, rather than a spinner in the middle of the row.
                val prof = profile?.takeIf { it.points.isNotEmpty() }
                // Your own average for this kind of trail when myvitals has one; otherwise flat
                // 10 mph by bike and 3 on foot.
                val pace = ui.pace?.forTrail(trail)
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            Stat("%.1f mi".format(trail.lengthMiles), "Length")
                            Stat(prof?.let { "+${it.ascentFeet.roundToInt()} ft" } ?: "—", "Climb")
                            Stat(prof?.let { "−${it.descentFeet.roundToInt()} ft" } ?: "—", "Descent")
                            Stat(
                                estimate(trail, pace?.mph),
                                when {
                                    pace != null -> "Your pace"
                                    UseType.BIKE in trail.uses -> "By bike"
                                    else -> "Walking"
                                },
                            )
                        }
                        if (pace != null) {
                            Text(
                                "At your %.1f mph %s average · %d %s".format(
                                    pace.mph, pace.noun, pace.count,
                                    if (pace.kind.onFoot) (if (pace.count == 1) "walk" else "walks") else (if (pace.count == 1) "ride" else "rides"),
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp),
                            )
                        }
                    }
                }

                if (ui.recorded.isNotEmpty()) YourRidesHere(trail, visits, ui, onOpenRecorded)

                if (trail.surfaceMix.size > 1) {
                    Column {
                        SectionTitle("Surface")
                        SurfaceMixBar(trail.surfaceMix)
                    }
                }

                Column {
                    SectionTitle("Elevation")
                    when {
                        profile == null -> Box(Modifier.fillMaxWidth().height(160.dp), Alignment.Center) {
                            CircularProgressIndicator()
                        }
                        profile.points.isEmpty() -> Box(Modifier.fillMaxWidth().height(160.dp), Alignment.Center) {
                            Text("Elevation unavailable", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        else -> ElevationChart(profile, initialScrub = chartScrub, onScrub = { scrubPoint = it?.point })
                    }
                }
            }
        }
    }

    if (showAddToRide && trail != null) {
        AddToRideDialog(
            rides = ui.rides,
            trail = trail,
            onDismiss = { showAddToRide = false },
            onCreateRide = onCreateRide,
            onAddToRide = onAddToRide,
        )
    }
}

private fun usesLine(uses: Set<UseType>): String {
    val names = UseType.entries.filter { it in uses }.map { it.label }
    return when (names.size) {
        0 -> "Trail"
        1 -> names[0]
        else -> names.dropLast(1).joinToString(", ") + " & " + names.last()
    }
}

/** Trails with more ways than this are put in riding order off the main thread. */
private const val SYNC_ORDER_MAX_PATHS = 200

/** Zoom used when "View on map" centres a single trail. */
private const val TRAIL_FOCUS_ZOOM = 14.0

/** Time at [mph] — your own pace when there is one — else 10 mph by bike, 3 mph on foot. */
private fun estimate(trail: Trail, mph: Double? = null): String {
    val speed = mph ?: if (UseType.BIKE in trail.uses) 10.0 else 3.0
    val minutes = (trail.lengthMiles / speed * 60).roundToInt().coerceAtLeast(1)
    return if (minutes < 90) "~$minutes min" else "~%.1f h".format(minutes / 60.0)
}

/** The trail's open/closed state, with the board's message and how fresh it is. */
@Composable
private fun ConditionCard(status: TrailStatus) {
    val context = LocalContext.current
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        // Only an http(s) page opens: the address comes from the server.
        onClick = {
            status.link?.let { url ->
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            }
        },
        enabled = status.link != null,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ConditionChip(status)
                Spacer(Modifier.width(8.dp))
                Text(status.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            status.comment?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
            }
            Text(
                listOfNotNull(
                    status.updatedAt?.let { "Posted ${ago(it)}" },
                    status.checkedAt?.let { "checked ${ago(it)}" },
                    "RainoutLine".takeIf { status.link != null },
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/**
 * What your recorded rides say about this trail: how much of it you've covered, how often
 * and when, and the rides themselves. "Not yet" is said plainly — that's the useful answer
 * when picking somewhere new.
 */
@Composable
private fun YourRidesHere(trail: Trail, visits: TrailVisits?, ui: TrailsUiState, onOpenRecorded: (String) -> Unit) {
    val dark = darkTheme()
    Column {
        SectionTitle("Your rides here")
        when {
            visits == null -> Text(
                if (UseType.BIKE in trail.uses) "You haven't ridden this one yet." else "You haven't been on this one yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (visits.ridden) {
                    Text(
                        "Ridden %.1f of %.1f mi (%d%%)".format(
                            visits.riddenMeters / METERS_PER_MILE, visits.totalMeters / METERS_PER_MILE, (visits.riddenFraction * 100).roundToInt(),
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    CoverageBar(visits.riddenFraction, dark)
                    val first = visits.rides.mapNotNull { ui.recordedById[it]?.start }.minOrNull()
                    Text(
                        listOfNotNull(
                            if (visits.rides.size == 1) "1 ride" else "${visits.rides.size} rides",
                            first?.takeIf { visits.rides.size > 1 }?.let { "first ${shortDate(it)}" },
                            visits.lastRidden?.let { "last ${shortDate(it)}" },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (visits.riddenFraction < 0.9) {
                        Text(
                            "The blue edge on the map marks what you've ridden.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (visits.onFoot.isNotEmpty()) {
                    Text(
                        "On foot ${visits.onFoot.size}×" + (visits.lastOnFoot?.let { " · last ${shortDate(it)}" } ?: "") +
                            " · %d%% of it".format((visits.onFootFraction * 100).roundToInt()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                var showAll by remember(trail.id) { mutableStateOf(false) }
                val all = (visits.rides + visits.onFoot).mapNotNull { ui.recordedById[it] }.sortedByDescending { it.start }
                (if (showAll) all else all.take(RECENT_VISITS)).forEach { rec ->
                    RecordedRow(rec, onClick = { onOpenRecorded(rec.id) })
                }
                if (all.size > RECENT_VISITS) {
                    TextButton(onClick = { showAll = !showAll }) {
                        Text(if (showAll) "Show fewer" else "Show all ${all.size}")
                    }
                }
            }
        }
    }
}

/** How many of your visits the detail screen lists before "Show all". */
private const val RECENT_VISITS = 5

private const val METERS_PER_MILE = 1609.344

@Composable
private fun Stat(value: String, label: String) = Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SectionTitle(text: String) = Text(
    text,
    style = MaterialTheme.typography.titleSmall,
    fontWeight = FontWeight.Bold,
    modifier = Modifier.padding(bottom = 8.dp),
)

/**
 * The trail's own shape, drawn from [Trail.paths] on a plain tile — no basemap, so it costs no
 * request and works offline. Start is a filled dot, end a ringed one. [marker] (the elevation
 * chart's scrub position) is drawn as a larger dot in the theme's primary color.
 */
@Composable
private fun RoutePreview(trail: Trail, marker: GeoPoint?, onShowOnMap: () -> Unit, ridden: List<List<GeoPoint>> = emptyList()) {
    val glow = youColor(darkTheme()).copy(alpha = 0.5f)
    val bg = MaterialTheme.colorScheme.surfaceContainerHigh
    val grid = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    val lineColor = trail.surface.color
    val casing = MaterialTheme.colorScheme.surfaceContainerLowest
    val markerColor = MaterialTheme.colorScheme.primary
    // Start and end come from the trail's pieces in riding order, the same order the elevation
    // profile uses — the first and last OSM way were just whichever Overpass listed first.
    // Most trails are a few dozen ways and order in well under a millisecond, so do those in
    // place; only a very large one (a long MTB system) is handed to a background thread.
    val quick = remember(trail) { if (trail.paths.size <= SYNC_ORDER_MAX_PATHS) TrailRoute.order(trail.paths) else null }
    val runs by produceState(quick ?: emptyList(), trail) {
        if (quick == null) value = withContext(Dispatchers.Default) { TrailRoute.order(trail.paths) }
    }
    Box(Modifier.fillMaxWidth().height(190.dp).background(bg)) {
        Canvas(Modifier.fillMaxSize().padding(20.dp)) {
            val all = trail.paths.flatten()
            if (all.size < 2) return@Canvas
            // Equirectangular with a cos(lat) squeeze so the shape isn't stretched east-west.
            val k = cos(Math.toRadians(trail.center.lat))
            val xs = all.map { it.lon * k }
            val ys = all.map { it.lat }
            val w = (xs.max() - xs.min()).coerceAtLeast(1e-6)
            val h = (ys.max() - ys.min()).coerceAtLeast(1e-6)
            val scale = min(size.width / w, size.height / h).toFloat()
            val ox = (size.width - w.toFloat() * scale) / 2
            val oy = (size.height - h.toFloat() * scale) / 2
            fun pt(p: GeoPoint) = Offset(
                ox + ((p.lon * k - xs.min()) * scale).toFloat(),
                oy + ((ys.max() - p.lat) * scale).toFloat(),
            )
            for (i in 0..6) {
                val gx = size.width * i / 6f
                drawLine(grid, Offset(gx, -20f), Offset(gx, size.height + 20f), 1f)
            }
            for (i in 0..3) {
                val gy = size.height * i / 3f
                drawLine(grid, Offset(-20f, gy), Offset(size.width + 20f, gy), 1f)
            }
            // What you've ridden glows under the line, as on the map.
            ridden.filter { it.size >= 2 }.forEach { path ->
                val line = Path().apply {
                    moveTo(pt(path[0]).x, pt(path[0]).y)
                    path.drop(1).forEach { lineTo(pt(it).x, pt(it).y) }
                }
                drawPath(line, glow, style = Stroke(22f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            trail.paths.filter { it.size >= 2 }.forEach { path ->
                val line = Path().apply {
                    moveTo(pt(path[0]).x, pt(path[0]).y)
                    path.drop(1).forEach { lineTo(pt(it).x, pt(it).y) }
                }
                drawPath(line, casing, style = Stroke(12f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(line, lineColor, style = Stroke(7f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            runs.firstOrNull()?.let { first ->
                val last = runs.last()
                drawCircle(casing, 12f, pt(first.first()))
                drawCircle(lineColor, 9f, pt(first.first()))
                drawCircle(lineColor, 11f, pt(last.last()))
                drawCircle(casing, 6f, pt(last.last()))
            }
            marker?.let {
                drawCircle(casing, 18f, pt(it))
                drawCircle(markerColor, 13f, pt(it))
            }
        }
        Surface(
            onClick = onShowOnMap,
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            shadowElevation = 2.dp,
            modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp),
        ) {
            Text(
                "View on map",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}
