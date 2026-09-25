package com.trailmap.ui

import com.trailmap.data.Ride
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trailmap.data.SurfaceType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RideDetailScreen(
    vm: TrailsViewModel,
    id: String,
    onBack: () -> Unit,
    onOpenTrail: (String) -> Unit,
    onShowOnMap: () -> Unit = {},
) {
    val ui by vm.state.collectAsStateWithLifecycle()
    RideDetailContent(
        ride = ui.rides.firstOrNull { it.id == id },
        onBack = onBack,
        onOpenTrail = onOpenTrail,
        onRemoveTrail = { trailId -> vm.removeTrailFromRide(id, trailId) },
        onRename = { name -> vm.renameRide(id, name) },
        onDelete = { vm.deleteRide(id) },
        onMove = { from, to -> vm.moveTrailInRide(id, from, to) },
        onShowOnMap = {
            vm.showRideOnMap(id)
            onShowOnMap()
        },
    )
}

/** Stateless body of [RideDetailScreen], so it can be rendered with sample state. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RideDetailContent(
    ride: Ride?,
    onBack: () -> Unit,
    onOpenTrail: (String) -> Unit,
    onRemoveTrail: (String) -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onMove: (Int, Int) -> Unit = { _, _ -> },
    onShowOnMap: () -> Unit = {},
) {
    var showRename by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }

    if (ride == null) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Ride", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                )
            },
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize(), Alignment.Center) {
                Text("Ride not found.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(ride.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showRename = true }) {
                        Icon(Icons.Filled.Edit, contentDescription = "Rename ride")
                    }
                    IconButton(onClick = { showDelete = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete ride")
                    }
                },
            )
        },
        bottomBar = {
            if (ride.trails.isNotEmpty()) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                    Button(
                        onClick = onShowOnMap,
                        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
                    ) {
                        Icon(Icons.Filled.Map, contentDescription = null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Show ride on map")
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            RideHeader(ride)
            HorizontalDivider()
            if (ride.trails.isEmpty()) {
                Text(
                    "No trails yet. Open a trail and tap Add to ride.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            } else {
                ReorderableTrails(ride, onOpenTrail, onRemoveTrail, onMove)
            }
        }
    }

    if (showRename) {
        var name by remember { mutableStateOf(ride.name) }
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text("Rename ride") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("Ride name") },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onRename(name)
                    showRename = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showRename = false }) { Text("Cancel") }
            },
        )
    }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("Delete ride?") },
            text = { Text("\"${ride.name}\" will be permanently removed.") },
            confirmButton = {
                TextButton(onClick = {
                    showDelete = false
                    onDelete()
                    onBack()
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = false }) { Text("Cancel") }
            },
        )
    }
}

/** Total, trail count and one surface bar for the whole ride. */
@Composable
private fun RideHeader(ride: Ride) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("%.1f".format(ride.totalMiles), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            Text(" mi", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 4.dp))
            Spacer(Modifier.weight(1f))
            val n = ride.trails.size
            Text(
                if (n == 1) "1 trail" else "$n trails",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (ride.trails.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            SurfaceMixBar(ride.surfaceMixTyped())
        }
    }
}

/** [Ride.surfaceMix] keyed by [SurfaceType] rather than its stored name. */
internal fun Ride.surfaceMixTyped(): Map<SurfaceType, Double> =
    surfaceMix.entries.groupBy({ runCatching { SurfaceType.valueOf(it.key) }.getOrDefault(SurfaceType.UNKNOWN) }, { it.value })
        .mapValues { it.value.sum() }

/**
 * The ride's trails in order, numbered, with the running mileage. Drag a row by its handle to
 * reorder: the row follows the finger, the rows it passes slide out of the way, and the move
 * is committed on release. A ride is a handful of trails, so this is a plain Column.
 */
@Composable
private fun ReorderableTrails(
    ride: Ride,
    onOpenTrail: (String) -> Unit,
    onRemoveTrail: (String) -> Unit,
    onMove: (Int, Int) -> Unit,
) {
    var dragging by remember { mutableStateOf<Int?>(null) }
    var dragY by remember { mutableFloatStateOf(0f) }
    var rowHeight by remember { mutableFloatStateOf(1f) }
    val target = dragging?.let { from -> (from + (dragY / rowHeight).roundToInt()).coerceIn(0, ride.trails.lastIndex) }

    var cum = 0.0
    ride.trails.forEachIndexed { i, t ->
        val start = cum
        cum += t.lengthMeters / METERS_PER_MILE
        val end = cum
        // Rows between the dragged row and its target shift one slot toward where it came from.
        val shift = when {
            dragging == null || target == null || i == dragging -> 0f
            dragging!! < i && i <= target -> -rowHeight
            target <= i && i < dragging!! -> rowHeight
            else -> 0f
        }
        val isDragged = i == dragging
        key(t.id) {
            Surface(
                color = if (isDragged) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surface,
                shadowElevation = if (isDragged) 6.dp else 0.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .zIndex(if (isDragged) 1f else 0f)
                    .onSizeChanged { rowHeight = it.height.toFloat().coerceAtLeast(1f) }
                    .graphicsLayer { translationY = if (isDragged) dragY else shift }
                    .clickable { onOpenTrail(t.id) },
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.DragIndicator,
                        contentDescription = "Drag to reorder",
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier
                            .padding(8.dp)
                            .pointerInput(ride.trails) {
                                detectDragGestures(
                                    onDragStart = { dragging = i; dragY = 0f },
                                    onDragEnd = {
                                        val from = dragging
                                        val to = target
                                        dragging = null
                                        dragY = 0f
                                        if (from != null && to != null && from != to) onMove(from, to)
                                    },
                                    onDragCancel = { dragging = null; dragY = 0f },
                                ) { change, amount ->
                                    change.consume()
                                    dragY += amount.y
                                }
                            },
                    )
                    Box(
                        Modifier.size(28.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("${i + 1}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            t.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val surface = runCatching { SurfaceType.valueOf(t.surface) }.getOrDefault(SurfaceType.UNKNOWN)
                            Dot(surface.color, 8)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "${surface.label} · %.1f mi · mile %.1f–%.1f".format(t.lengthMeters / METERS_PER_MILE, start, end),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            MtbBadge(t.mtbScale, Modifier.padding(start = 6.dp))
                        }
                    }
                    IconButton(onClick = { onRemoveTrail(t.id) }) {
                        Icon(Icons.Filled.Close, contentDescription = "Remove from ride", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

private const val METERS_PER_MILE = 1609.344
