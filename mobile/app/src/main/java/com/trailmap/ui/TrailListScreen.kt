package com.trailmap.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
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
    TrailListContent(
        ui = ui,
        filters = FilterActions.of(vm),
        onSetShowSavedOnly = vm::setShowSavedOnly,
        onSetSort = vm::setSort,
        onToggleSaved = vm::toggleSaved,
        onOpenTrail = onOpenTrail,
        onOpenSystem = { system ->
            vm.focusOn(system.center)
            onShowOnMap()
        },
    )
}

/** Stateless body of [TrailListScreen], so it can be rendered with sample state. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TrailListContent(
    ui: TrailsUiState,
    filters: FilterActions,
    onSetShowSavedOnly: (Boolean) -> Unit,
    onSetSort: (TrailSort) -> Unit,
    onToggleSaved: (String) -> Unit,
    onOpenTrail: (String) -> Unit,
    onOpenSystem: (TrailSystem) -> Unit,
) {
    // The list honours the radius chip; the map deliberately draws the wider cached set.
    val trails = ui.listed

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Trails", fontWeight = FontWeight.Bold)
                        Text(
                            when {
                                ui.loading -> "Loading…"
                                ui.mode == MapMode.MTB ->
                                    "${ui.systems.size} systems · within ${ui.radiusMiles.roundToInt()} mi"
                                else -> "${trails.size} within ${ui.radiusMiles.roundToInt()} mi · nearest first"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { onSetShowSavedOnly(!ui.showSavedOnly) }) {
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
            // Same bar and sheet as the map, so the two screens filter the same way.
            var showFilters by remember { mutableStateOf(false) }
            if (showFilters) FilterSheet(ui, filters, onDismiss = { showFilters = false })
            TrailSearchBar(
                ui = ui,
                filters = filters,
                onOpenFilters = { showFilters = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )

            SortChip(ui.sort, onSetSort, Modifier.padding(horizontal = 12.dp))

            when {
                ui.loading && trails.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator()
                }
                trails.isEmpty() -> EmptyTrails(
                    ui = ui,
                    onClearFilters = {
                        filters.reset()
                        filters.setQuery("")
                        onSetShowSavedOnly(false)
                    },
                    onWiden = { filters.setRadiusMiles(it) },
                )
                ui.mode == MapMode.MTB -> LazyColumn {
                    ui.systems.forEach { system ->
                        item(key = "hdr_${system.id}") {
                            SystemHeader(system) { onOpenSystem(system) }
                        }
                        items(system.trails, key = { it.id }) { trail ->
                            TrailRow(
                                trail = trail,
                                isSaved = ui.isSaved(trail.id),
                                onToggleSave = { onToggleSaved(trail.id) },
                                onClick = { onOpenTrail(trail.id) },
                            )
                        }
                    }
                }
                else -> LazyColumn {
                    items(trails, key = { it.id }) { trail ->
                        TrailRow(
                            trail = trail,
                            isSaved = ui.isSaved(trail.id),
                            onToggleSave = { onToggleSaved(trail.id) },
                            onClick = { onOpenTrail(trail.id) },
                        )
                    }
                }
            }
        }
    }
}

/** "Sort: Distance ▾" with a menu of the three orders. */
@Composable
private fun SortChip(sort: TrailSort, onSetSort: (TrailSort) -> Unit, modifier: Modifier = Modifier) {
    Box(modifier) {
        var open by remember { mutableStateOf(false) }
        AssistChip(
            onClick = { open = true },
            label = { Text("Sort: ${sort.label}") },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null, Modifier.size(18.dp)) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            TrailSort.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    leadingIcon = { RadioButton(selected = option == sort, onClick = null) },
                    onClick = {
                        onSetSort(option)
                        open = false
                    },
                )
            }
        }
    }
}

/**
 * Nothing to list: say why, and offer the fix — clear the filters when they're what's hiding
 * trails, or look further out when there's simply nothing this close.
 */
@Composable
private fun EmptyTrails(ui: TrailsUiState, onClearFilters: () -> Unit, onWiden: (Int) -> Unit) {
    val hidden = ui.unfilteredNearbyCount
    val filtering = activeFilterCount(ui) > 0 || ui.query.isNotBlank() || ui.showSavedOnly
    val radius = ui.radiusMiles.roundToInt()
    val wider = radiusOptions(ui.mode).firstOrNull { it > radius }
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            if (ui.error != null) Icons.Filled.CloudOff else Icons.Filled.SearchOff,
            contentDescription = null,
            Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(12.dp))
        Text(
            when {
                ui.error != null -> "Couldn't load trails"
                ui.showSavedOnly && ui.savedIds.isEmpty() -> "No saved trails yet"
                else -> "No trails match"
            },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            when {
                ui.error != null -> ui.error
                ui.showSavedOnly && ui.savedIds.isEmpty() -> "Tap the star on a trail to keep it here."
                filtering && hidden > 0 -> {
                    val what = when {
                        ui.showSavedOnly -> "Showing saved only hides"
                        ui.query.isNotBlank() && activeFilterCount(ui) == 0 -> "Your search hides"
                        ui.query.isNotBlank() -> "Your search and filters hide"
                        else -> "Your filters hide"
                    }
                    "$what all $hidden ${if (hidden == 1) "trail" else "trails"} within $radius mi."
                }
                else -> "There are no named trails within $radius mi."
            },
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (filtering) Button(onClick = onClearFilters) { Text(if (ui.query.isNotBlank()) "Clear search & filters" else "Clear filters") }
            if (wider != null && ui.error == null) {
                OutlinedButton(onClick = { onWiden(wider) }) { Text("Search $wider mi") }
            }
        }
    }
}

/** Header for a clustered trail system in MTB mode: a tinted band, with Map to recenter there. */
@Composable
internal fun SystemHeader(system: TrailSystem, onClick: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Forest, contentDescription = null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Text(system.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(
                    "${system.trails.size} ${if (system.trails.size == 1) "trail" else "trails"} · %.1f mi".format(system.totalMiles),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ScaleRangeChip(system.scaleMin, system.scaleMax)
            TextButton(onClick = onClick) { Text("Map") }
        }
    }
}

/** Difficulty-range pill, e.g. "S1–S4". Renders nothing if no member is rated. */
@Composable
internal fun ScaleRangeChip(scaleMin: Int?, scaleMax: Int?) {
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

/**
 * One trail: a surface-colored edge, the name, one meta line and the save star. Flat, with a
 * divider — about 40% shorter than the card it replaced, so more of the list fits on screen.
 */
@Composable
internal fun TrailRow(
    trail: Trail,
    isSaved: Boolean,
    onToggleSave: () -> Unit,
    onClick: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(4.dp).height(44.dp).clip(RoundedCornerShape(50)).background(trail.surface.color))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    trail.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${trail.surface.label} · %.1f mi · %.1f mi away".format(trail.lengthMiles, trail.distanceMiles),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(6.dp))
                    UseIcons(trail.uses, tint = MaterialTheme.colorScheme.onSurfaceVariant, size = 16)
                }
                if (trail.mtbScale != null) {
                    Spacer(Modifier.height(4.dp))
                    MtbBadge(trail.mtbScale)
                }
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
        HorizontalDivider(Modifier.padding(start = 28.dp))
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
            // White on gravel gold is 2.2:1; dark brown is 6.2:1.
            color = if (surface == SurfaceType.GRAVEL) Color(0xFF3A2A00) else Color.White,
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
fun UseIcons(
    uses: Set<UseType>,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.primary,
    size: Int = 18,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (UseType.WALK in uses) {
            Icon(Icons.Filled.DirectionsWalk, contentDescription = "Walking", tint = tint, modifier = Modifier.size(size.dp))
        }
        if (UseType.BIKE in uses) {
            Icon(Icons.Filled.DirectionsBike, contentDescription = "Biking", tint = tint, modifier = Modifier.size(size.dp))
        }
    }
}
