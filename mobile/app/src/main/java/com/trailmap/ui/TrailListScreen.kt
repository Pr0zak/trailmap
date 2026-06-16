package com.trailmap.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Forest
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trailmap.data.MtbDifficulty
import com.trailmap.data.SurfaceType
import com.trailmap.data.Trail
import com.trailmap.data.TrailSystem
import com.trailmap.data.UseType
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrailListScreen(
    vm: TrailsViewModel,
    onOpenTrail: (String) -> Unit,
    onShowOnMap: () -> Unit,
) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val trails = ui.filtered

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Nearby Trails", fontWeight = FontWeight.Bold)
                        Text(
                            when {
                                ui.loading -> "Loading…"
                                ui.mode == MapMode.MTB ->
                                    "${ui.systems.size} systems · within ${ui.radiusMiles.roundToInt()} mi"
                                else -> "${trails.size} trails · sorted by distance"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { vm.setShowSavedOnly(!ui.showSavedOnly) }) {
                        Icon(
                            if (ui.showSavedOnly) Icons.Filled.Star else Icons.Filled.StarBorder,
                            contentDescription = if (ui.showSavedOnly) "Showing saved only" else "Show saved only",
                            tint = if (ui.showSavedOnly) {
                                MaterialTheme.colorScheme.secondary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = ui.query,
                onValueChange = vm::setQuery,
                singleLine = true,
                placeholder = { Text("Search trails by name") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (ui.query.isNotEmpty()) {
                        IconButton(onClick = { vm.setQuery("") }) {
                            Icon(Icons.Filled.Clear, contentDescription = "Clear search")
                        }
                    }
                },
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )

            FilterChips(
                ui = ui,
                onToggleSurface = vm::toggleSurface,
                onToggleUse = vm::toggleUse,
                onSetMode = vm::setMode,
                onSetRadiusMiles = vm::setRadiusMiles,
                onSetMinLength = vm::setMinLength,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )

            when {
                ui.loading && trails.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator()
                }
                trails.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text(
                        ui.error
                            ?: if (ui.showSavedOnly) "No saved trails yet." else "No trails match your filters.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
                ui.mode == MapMode.MTB -> LazyColumn(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ui.systems.forEach { system ->
                        item(key = "hdr_${system.id}") {
                            SystemHeader(system) {
                                vm.focusOn(system.center)
                                onShowOnMap()
                            }
                        }
                        items(system.trails, key = { it.id }) { trail ->
                            TrailRow(
                                trail = trail,
                                isSaved = ui.isSaved(trail.id),
                                onToggleSave = { vm.toggleSaved(trail.id) },
                                onClick = { onOpenTrail(trail.id) },
                            )
                        }
                    }
                }
                else -> LazyColumn(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(trails, key = { it.id }) { trail ->
                        TrailRow(
                            trail = trail,
                            isSaved = ui.isSaved(trail.id),
                            onToggleSave = { vm.toggleSaved(trail.id) },
                            onClick = { onOpenTrail(trail.id) },
                        )
                    }
                }
            }
        }
    }
}

/** Header card for a clustered trail system in MTB mode. Tapping it recenters the map there. */
@Composable
private fun SystemHeader(system: TrailSystem, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Forest,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.size(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                system.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "${system.trails.size} trails · %.1f mi · View on map".format(system.totalMiles),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ScaleRangeChip(system.scaleMin, system.scaleMax)
        Spacer(Modifier.size(8.dp))
        Icon(
            Icons.Filled.Map,
            contentDescription = "View on map",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** Difficulty-range pill, e.g. "S1–S4". Renders nothing if no member is rated. */
@Composable
private fun ScaleRangeChip(scaleMin: Int?, scaleMax: Int?) {
    if (scaleMin == null || scaleMax == null) return
    val color = MtbDifficulty.of(scaleMax)?.color ?: MaterialTheme.colorScheme.primary
    val label = if (scaleMin == scaleMax) "S$scaleMin" else "S$scaleMin–S$scaleMax"
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(color)
            .padding(horizontal = 10.dp, vertical = 3.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
    }
}

@Composable
private fun TrailRow(
    trail: Trail,
    isSaved: Boolean,
    onToggleSave: () -> Unit,
    onClick: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(
                    trail.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.size(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SurfaceBadge(trail.surface)
                    MtbBadge(trail.mtbScale)
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "%.1f mi".format(trail.lengthMiles),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "%.1f mi away".format(trail.distanceMiles),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(4.dp))
                UseIcons(trail.uses)
            }
            IconButton(onClick = onToggleSave) {
                Icon(
                    if (isSaved) Icons.Filled.Star else Icons.Filled.StarBorder,
                    contentDescription = if (isSaved) "Remove from saved" else "Save trail",
                    tint = if (isSaved) {
                        MaterialTheme.colorScheme.secondary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

@Composable
fun SurfaceBadge(surface: SurfaceType, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(surface.color)
            .padding(horizontal = 10.dp, vertical = 3.dp),
    ) {
        Text(
            surface.label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
    }
}

/** Difficulty pill for MTB-rated trails. Renders nothing when [scale] has no mtb:scale mapping. */
@Composable
fun MtbBadge(scale: Int?, modifier: Modifier = Modifier) {
    val difficulty = MtbDifficulty.of(scale) ?: return
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(difficulty.color)
            .padding(horizontal = 10.dp, vertical = 3.dp),
    ) {
        Text(
            difficulty.badge,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
    }
}

@Composable
fun UseIcons(uses: Set<UseType>, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (UseType.WALK in uses) {
            Icon(
                Icons.Filled.DirectionsWalk,
                contentDescription = "Walking",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
        if (UseType.BIKE in uses) {
            Icon(
                Icons.Filled.DirectionsBike,
                contentDescription = "Biking",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
