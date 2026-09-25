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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Forest
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.trailmap.data.ElevationProfile
import com.trailmap.data.Trail
import com.trailmap.data.TrailSystem
import com.trailmap.data.UseType
import com.trailmap.snap.FauxMap
import com.trailmap.ui.MapMode
import com.trailmap.ui.MtbBadge
import com.trailmap.ui.ScaleRangeChip
import com.trailmap.ui.TrailsUiState
import kotlin.math.roundToInt

// ---------- Trails list ----------

@Composable
fun ListAfter(ui: TrailsUiState, empty: Boolean = false) {
    val trails = if (empty) emptyList() else ui.listed
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Trails", fontWeight = FontWeight.Bold)
                        Text(
                            if (ui.mode == MapMode.MTB) "${ui.systems.size} systems · within ${ui.radiusMiles.roundToInt()} mi"
                            else "${trails.size} within ${ui.radiusMiles.roundToInt()} mi · nearest first",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = { IconButton(onClick = {}) { Icon(Icons.Filled.StarBorder, "Saved only") } },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            // L1: search + one chip row (mode, Filters sheet, sort) instead of search + two rows.
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(48.dp),
            ) {
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(12.dp))
                    Text(if (empty) "zzz" else "Search trails", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ModeToggle(ui.mode, Modifier.width(120.dp).height(34.dp))
                FilterChip(
                    selected = true, onClick = {}, label = { Text("Filters · 1") },
                    leadingIcon = { Icon(Icons.Filled.Tune, null, Modifier.size(18.dp)) },
                )
                AssistChip(
                    onClick = {}, label = { Text("Sort: Distance") },
                    trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null, Modifier.size(18.dp)) },
                )
            }
            when {
                trails.isEmpty() -> EmptyTrails()
                ui.mode == MapMode.MTB -> LazyColumn {
                    ui.systems.forEach { sys ->
                        item { SystemHeaderAfter(sys) }
                        sys.trails.forEach { t -> item { TrailRowAfter(t, t.id in ui.savedIds); HorizontalDivider(Modifier.padding(start = 28.dp)) } }
                    }
                }
                else -> LazyColumn {
                    trails.forEach { t -> item { TrailRowAfter(t, t.id in ui.savedIds); HorizontalDivider(Modifier.padding(start = 28.dp)) } }
                }
            }
        }
    }
}

/** L2: flat row, surface shown as a colored edge + word, one meta line, ~40% shorter than a card. */
@Composable
fun TrailRowAfter(t: Trail, saved: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(4.dp).height(44.dp).clip(RoundedCornerShape(50)).background(t.surface.color))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(t.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${t.surface.label} · %.1f mi · %.1f mi away".format(t.lengthMiles, t.distanceMiles),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(6.dp))
                if (UseType.WALK in t.uses) Icon(Icons.Filled.DirectionsWalk, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                if (UseType.BIKE in t.uses) Icon(Icons.Filled.DirectionsBike, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (t.mtbScale != null) {
                Spacer(Modifier.height(4.dp))
                MtbBadge(t.mtbScale)
            }
        }
        IconButton(onClick = {}) {
            Icon(
                if (saved) Icons.Filled.Star else Icons.Filled.StarBorder, null,
                tint = if (saved) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SystemHeaderAfter(sys: TrailSystem) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Forest, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(sys.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text("${sys.trails.size} trails · %.1f mi".format(sys.totalMiles), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ScaleRangeChip(sys.scaleMin, sys.scaleMax)
            TextButton(onClick = {}) { Text("Map") }
        }
    }
}

/** L4: an empty state that says why and offers the fix. */
@Composable
private fun EmptyTrails() {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.SearchOff, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Text("No trails match", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "Your search and 1 filter hide all 8 trails within 10 mi.",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {}) { Text("Clear filters") }
            OutlinedButton(onClick = {}) { Text("Search 25 mi") }
        }
    }
}

// ---------- Trail detail ----------

@Composable
fun DetailAfter(trail: Trail, profile: ElevationProfile) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(trail.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = {}) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = {}) { Icon(Icons.Filled.Star, "Saved", tint = MaterialTheme.colorScheme.secondary) }
                    IconButton(onClick = {}) { Icon(Icons.Filled.Share, "Share") }
                    IconButton(onClick = {}) { Icon(Icons.Filled.MoreVert, "More") }
                },
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = {}, modifier = Modifier.weight(1f)) {
                        Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Add to ride")
                    }
                    OutlinedButton(onClick = {}, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.Directions, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Directions")
                    }
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState())) {
            // D1: the route itself, drawn from trail.paths — works offline, no extra request.
            Box(Modifier.fillMaxWidth().height(190.dp)) {
                FauxMap(listOf(trail))
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp),
                    shadowElevation = 2.dp,
                ) { Text("View on map", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) }
            }
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SurfacePill(trail.surface)
                    MtbBadge(trail.mtbScale)
                    Text("Walking & biking · %.1f mi away".format(trail.distanceMiles), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
                // D3: the four numbers that decide a ride; "away" moves into the line above.
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        StatAfter("%.1f mi".format(trail.lengthMiles), "Length")
                        StatAfter("+${profile.ascentFeet.roundToInt()} ft", "Climb")
                        StatAfter("−${profile.descentFeet.roundToInt()} ft", "Descent")
                        StatAfter("~${(trail.lengthMiles / 10 * 60).roundToInt()} min", "By bike")
                    }
                }
                // D5: surfaceMix is already on every Trail; show it.
                Column {
                    Text("Surface", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    SurfaceMixBar(trail.surfaceMix)
                }
                Column {
                    Text("Elevation", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    ElevationChartAfter(profile)
                }
            }
        }
    }
}

@Composable
private fun StatAfter(value: String, label: String) = Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
