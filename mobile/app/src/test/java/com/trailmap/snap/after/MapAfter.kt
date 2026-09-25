@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.trailmap.snap.after

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.trailmap.data.MtbDifficulty
import com.trailmap.data.SurfaceType
import com.trailmap.data.Trail
import com.trailmap.snap.FauxMap
import com.trailmap.ui.MapMode
import com.trailmap.ui.MtbBadge
import com.trailmap.ui.TrailsUiState
import kotlin.math.roundToInt

/** M1: one-row top bar — search, mode toggle, and a Filters button carrying a count badge. */
@Composable
fun TopSearchBar(ui: TrailsUiState, activeFilters: Int, loading: Boolean, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shadowElevation = 4.dp,
    ) {
        Column {
            Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(10.dp))
                Text(
                    "Search trails",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                ModeToggle(ui.mode, Modifier.width(120.dp).height(36.dp))
                IconButton(onClick = {}) {
                    BadgedBox(badge = { if (activeFilters > 0) Badge { Text("$activeFilters") } }) {
                        Icon(Icons.Filled.Tune, contentDescription = "Filters")
                    }
                }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp))
        }
    }
}

@Composable
fun ModeToggle(mode: MapMode, modifier: Modifier = Modifier) {
    SingleChoiceSegmentedButtonRow(modifier) {
        listOf(MapMode.ALL to "All", MapMode.MTB to "MTB").forEachIndexed { i, (m, label) ->
            SegmentedButton(
                selected = mode == m,
                onClick = {},
                shape = SegmentedButtonDefaults.itemShape(i, 2),
                icon = {},
            ) { Text(label, style = MaterialTheme.typography.labelMedium) }
        }
    }
}

/** M2: status as a quiet pill under the bar — count + radius, or "Updating…" while loading. */
@Composable
fun StatusPill(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.92f),
        shadowElevation = 2.dp,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
        )
    }
}

/** M3: compact legend pinned bottom-left; in MTB mode it shows all seven S0–S6 grades. */
@Composable
fun CompactLegend(mode: MapMode, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.92f),
        shadowElevation = 2.dp,
    ) {
        if (mode == MapMode.MTB) {
            Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                MtbDifficulty.entries.forEach { d ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.width(22.dp).height(4.dp).clip(RoundedCornerShape(50)).background(d.color))
                        Text("S${d.scale}", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        } else {
            Row(
                Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                listOf(SurfaceType.PAVED, SurfaceType.GRAVEL, SurfaceType.DIRT).forEach { s ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(14.dp).height(4.dp).clip(RoundedCornerShape(50)).background(s.color))
                        Spacer(Modifier.width(4.dp))
                        Text(s.label, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

/** M5: richer peek card — stats, and Save / Add to ride without opening details. */
@Composable
fun PeekCardAfter(trail: Trail, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shadowElevation = 8.dp,
    ) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
            Box(
                Modifier.align(Alignment.CenterHorizontally).padding(vertical = 8.dp)
                    .width(32.dp).height(4.dp).clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    trail.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = {}) { Icon(Icons.Filled.StarBorder, "Save") }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SurfacePill(trail.surface)
                MtbBadge(trail.mtbScale)
                Icon(Icons.Filled.DirectionsWalk, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                Icon(Icons.Filled.DirectionsBike, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                MiniStat("%.1f mi".format(trail.lengthMiles), "length")
                MiniStat("+210 ft", "climb")
                MiniStat("%.1f mi".format(trail.distanceMiles), "away")
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {}, modifier = Modifier.weight(1f)) { Text("Details") }
                OutlinedButton(onClick = {}, modifier = Modifier.weight(1f)) {
                    Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Add to ride")
                }
            }
        }
    }
}

@Composable
fun MiniStat(value: String, label: String) = Column {
    Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun MapAfter(ui: TrailsUiState, selected: Trail? = null, sheet: Boolean = false, dark: Boolean = false) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        FauxMap(ui.filtered, dark = dark)
        Column(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TopSearchBar(ui, activeFilters = 1, loading = ui.loading, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            StatusPill(
                if (ui.loading) "Updating trails…"
                else "${ui.filtered.size} trails · ${ui.radiusMiles.roundToInt()} mi",
            )
        }
        CompactLegend(ui.mode, Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = if (selected != null) 262.dp else 36.dp))
        Column(
            Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = if (selected != null) 262.dp else 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SmallFloatingActionButton(onClick = {}, containerColor = MaterialTheme.colorScheme.surfaceContainerLowest) {
                Icon(Icons.Filled.Layers, "Map layers & theme")
            }
            SmallFloatingActionButton(onClick = {}, containerColor = MaterialTheme.colorScheme.surfaceContainerLowest) {
                Icon(Icons.Filled.CloudDownload, "Offline maps")
            }
            FloatingActionButton(onClick = {}, containerColor = MaterialTheme.colorScheme.primaryContainer) {
                Icon(Icons.Filled.MyLocation, "My location")
            }
        }
        if (selected != null) {
            PeekCardAfter(selected, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 28.dp))
        }
        if (sheet) FilterSheet(ui, Modifier.align(Alignment.BottomCenter))
    }
}

/** M1 (continued): everything the two chip rows held, in a sheet opened from the Filters button. */
@Composable
fun FilterSheet(ui: TrailsUiState, modifier: Modifier = Modifier) {
    Box(Modifier.fillMaxSize().background(Color(0x66000000)))
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
            Box(
                Modifier.align(Alignment.CenterHorizontally).padding(vertical = 10.dp)
                    .width(32.dp).height(4.dp).clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Filters", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = {}) { Text("Reset") }
            }
            SheetLabel("List trails within")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(3, 5, 10).forEachIndexed { i, mi ->
                    SegmentedButton(selected = mi == ui.radiusMiles.roundToInt(), onClick = {}, shape = SegmentedButtonDefaults.itemShape(i, 3)) {
                        Text("$mi mi")
                    }
                }
            }
            SheetLabel("Minimum length")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Any", "1+ mi", "3+ mi", "5+ mi", "10+ mi").forEachIndexed { i, l ->
                    FilterChip(selected = i == 0, onClick = {}, label = { Text(l) })
                }
            }
            SheetLabel("Surface")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(SurfaceType.PAVED, SurfaceType.GRAVEL, SurfaceType.DIRT).forEach { s ->
                    FilterChip(
                        selected = s != SurfaceType.DIRT, onClick = {}, label = { Text(s.label) },
                        leadingIcon = { Dot(s.color, 12) },
                    )
                }
            }
            SheetLabel("Use")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(true, {}, { Text("Walk") }, leadingIcon = { Icon(Icons.Filled.DirectionsWalk, null, Modifier.size(18.dp)) })
                FilterChip(true, {}, { Text("Bike") }, leadingIcon = { Icon(Icons.Filled.DirectionsBike, null, Modifier.size(18.dp)) })
            }
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Load trails as I pan", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Off = use “Search this area” instead",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = true, onCheckedChange = {})
            }
            Button(onClick = {}, modifier = Modifier.fillMaxWidth()) { Text("Show ${ui.filtered.size} trails") }
        }
    }
}

@Composable
private fun SheetLabel(text: String) = Text(
    text,
    style = MaterialTheme.typography.labelLarge,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(top = 14.dp, bottom = 6.dp),
)
