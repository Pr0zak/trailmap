package com.trailmap.ui

import com.trailmap.data.Ride
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Spacer
import android.widget.Toast
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.ui.platform.LocalContext
import com.trailmap.data.Trail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RidesScreen(
    vm: TrailsViewModel,
    onOpenRide: (String) -> Unit,
    onBrowseTrails: () -> Unit = {},
    onOpenRecorded: (String) -> Unit = {},
    onOpenMyVitals: () -> Unit = {},
) {
    val ui by vm.state.collectAsStateWithLifecycle()
    RidesContent(
        rides = ui.rides,
        onOpenRide = onOpenRide,
        onCreateRide = { vm.createRide(it) },
        onBrowseTrails = onBrowseTrails,
        ui = ui,
        onOpenRecorded = onOpenRecorded,
        onOpenMyVitals = onOpenMyVitals,
        onSync = vm::syncMyVitals,
    )
}

/** The Rides tab's two lists: rides you've planned here, and rides you've recorded. */
internal enum class RidesTab(val label: String) { PLANNED("Planned"), RECORDED("Recorded") }

/** Stateless body of [RidesScreen], so it can be rendered with sample state. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RidesContent(
    rides: List<Ride>,
    onOpenRide: (String) -> Unit,
    onCreateRide: (String) -> String,
    onBrowseTrails: () -> Unit = {},
    ui: TrailsUiState = TrailsUiState(),
    onOpenRecorded: (String) -> Unit = {},
    onOpenMyVitals: () -> Unit = {},
    onSync: () -> Unit = {},
    initialTab: RidesTab = RidesTab.PLANNED,
) {
    var showNewDialog by remember { mutableStateOf(false) }
    var tab by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(initialTab) }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Rides", fontWeight = FontWeight.Bold) },
                    actions = {
                        // The connection's settings, once there is one; before that the Recorded
                        // tab itself offers to connect.
                        if (ui.myVitals.connected) {
                            androidx.compose.material3.IconButton(onClick = onOpenMyVitals) {
                                Icon(Icons.Filled.Settings, contentDescription = "myvitals settings")
                            }
                        }
                    },
                )
                androidx.compose.material3.PrimaryTabRow(selectedTabIndex = tab.ordinal) {
                    RidesTab.entries.forEach { t ->
                        androidx.compose.material3.Tab(
                            selected = tab == t,
                            onClick = { tab = t },
                            text = { Text(t.label) },
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            if (tab == RidesTab.PLANNED) {
                ExtendedFloatingActionButton(
                    onClick = { showNewDialog = true },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("New ride") },
                )
            }
        },
    ) { padding ->
        if (tab == RidesTab.RECORDED) {
            RecordedList(ui, onOpenRecorded, onOpenMyVitals, onSync, Modifier.padding(padding))
        } else if (rides.isEmpty()) {
            Column(
                Modifier.padding(padding).fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.Filled.Route, contentDescription = null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.size(12.dp))
                Text("Plan a ride", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "Chain trails together to see the total distance and surface mix before you go.",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(16.dp))
                Button(onClick = onBrowseTrails) { Text("Browse trails") }
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 88.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(rides, key = { it.id }) { ride -> RideCard(ride) { onOpenRide(ride.id) } }
            }
        }
    }

    if (showNewDialog) {
        NewRideDialog(
            onDismiss = { showNewDialog = false },
            onCreate = { name ->
                showNewDialog = false
                val rideId = onCreateRide(name)
                onOpenRide(rideId)
            },
        )
    }
}

/** A ride at a glance: name and total, the trail sequence, and its surface split. */
@Composable
private fun RideCard(ride: Ride, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        ride.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text("%.1f mi".format(ride.totalMiles), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                Text(
                    if (ride.trails.isEmpty()) "No trails yet" else ride.trails.joinToString(" → ") { it.name },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (ride.trails.isNotEmpty()) {
                    Spacer(Modifier.size(10.dp))
                    SurfaceMixBar(ride.surfaceMixTyped(), legend = false)
                }
            }
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Shared "name a new ride" dialog: an OutlinedTextField + Create. */
@Composable
fun NewRideDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New ride") },
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
            TextButton(
                onClick = { onCreate(name.ifBlank { "Ride" }) },
            ) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * "Add to ride": pick one of [rides] or start a new one seeded with [trail]. Shared by the
 * trail detail screen and the map's peek card.
 */
@Composable
fun AddToRideDialog(
    rides: List<Ride>,
    trail: Trail,
    onDismiss: () -> Unit,
    onCreateRide: (String, Trail) -> Unit,
    onAddToRide: (String, Trail) -> Unit,
) {
    val context = LocalContext.current
    var showNewRide by remember { mutableStateOf(false) }
    if (showNewRide) {
        NewRideDialog(
            onDismiss = {
                showNewRide = false
                onDismiss()
            },
            onCreate = { name ->
                onCreateRide(name, trail)
                showNewRide = false
                onDismiss()
                Toast.makeText(context, "Added to $name", Toast.LENGTH_SHORT).show()
            },
        )
    } else {
        AlertDialog(
            onDismissRequest = { onDismiss() },
            title = { Text("Add to ride") },
            text = {
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    item(key = "new") {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { showNewRide = true }
                                .padding(vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.PlaylistAdd,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.size(12.dp))
                            Text(
                                "New ride…",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    items(rides, key = { it.id }) { ride ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onAddToRide(ride.id, trail)
                                    onDismiss()
                                    Toast.makeText(
                                        context,
                                        "Added to ${ride.name}",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                                .padding(vertical = 14.dp),
                        ) {
                            Column {
                                Text(
                                    ride.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    "${ride.trails.size} trails",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { onDismiss() }) { Text("Close") }
            },
        )
    }
}
