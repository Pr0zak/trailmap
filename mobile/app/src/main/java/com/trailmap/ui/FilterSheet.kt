@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.trailmap.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.trailmap.data.SurfaceType
import com.trailmap.data.HorseTrailFilter
import com.trailmap.data.UseType
import kotlin.math.roundToInt

/** The callbacks the search bar and [FilterSheet] need, bundled so screens pass one value. */
data class FilterActions(
    val toggleSurface: (SurfaceType) -> Unit = {},
    val toggleUse: (UseType) -> Unit = {},
    val setHorseTrails: (HorseTrailFilter) -> Unit = {},
    val setMode: (MapMode) -> Unit = {},
    val setRadiusMiles: (Int) -> Unit = {},
    val setMinLength: (Double) -> Unit = {},
    val setAutoLoad: (Boolean) -> Unit = {},
    val setQuery: (String) -> Unit = {},
    val reset: () -> Unit = {},
) {
    companion object {
        fun of(vm: TrailsViewModel) = FilterActions(
            toggleSurface = { vm.toggleSurface(it) },
            toggleUse = { vm.toggleUse(it) },
            setHorseTrails = vm::setHorseTrails,
            setMode = vm::setMode,
            setRadiusMiles = vm::setRadiusMiles,
            setMinLength = { vm.setMinLength(it) },
            setAutoLoad = { vm.setAutoLoadOnPan(it) },
            setQuery = { vm.setQuery(it) },
            reset = { vm.resetFilters() },
        )
    }
}

/** Radius choices per mode. Must match the ViewModel's defaults (5 mi ALL, 25 mi MTB). */
internal fun radiusOptions(mode: MapMode) = if (mode == MapMode.MTB) listOf(10, 25, 40) else listOf(3, 5, 10)

private val LENGTH_OPTIONS = listOf("Any" to 0.0, "1+ mi" to 1.0, "3+ mi" to 3.0, "5+ mi" to 5.0, "10+ mi" to 10.0)
private val SHOWN_SURFACES = listOf(SurfaceType.PAVED, SurfaceType.GRAVEL, SurfaceType.DIRT)

/**
 * How many filters differ from their defaults — the number on the Filters badge. Radius and
 * mode aren't counted: they choose which trails exist, not which of them are hidden.
 */
internal fun activeFilterCount(ui: TrailsUiState): Int {
    val d = TrailsUiState()
    var n = 0
    if (ui.selectedSurfaces != d.selectedSurfaces) n++
    if (ui.selectedUses != d.selectedUses) n++
    if (ui.horseTrails != d.horseTrails) n++
    if (ui.minLengthMiles != d.minLengthMiles) n++
    if (!ui.autoLoadOnPan) n++
    return n
}

/**
 * The one-row search bar shared by the map and the Trails list: name search, the All / MTB
 * toggle, and a Filters button whose badge counts active filters. Replaces the two rows of
 * chips, most of which scrolled off the right edge.
 */
@Composable
fun TrailSearchBar(
    ui: TrailsUiState,
    filters: FilterActions,
    onOpenFilters: () -> Unit,
    modifier: Modifier = Modifier,
    showLoading: Boolean = false,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shadowElevation = 3.dp,
    ) {
        Column {
            Row(
                Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    if (ui.query.isEmpty()) {
                        Text("Search trails", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    BasicTextField(
                        value = ui.query,
                        onValueChange = filters.setQuery,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (ui.query.isNotEmpty()) {
                    IconButton(onClick = { filters.setQuery("") }) {
                        Icon(Icons.Filled.Clear, contentDescription = "Clear search")
                    }
                } else {
                    ModeToggle(ui.mode, filters.setMode, Modifier.width(120.dp).height(36.dp))
                }
                IconButton(onClick = onOpenFilters) {
                    val count = activeFilterCount(ui)
                    BadgedBox(badge = { if (count > 0) Badge { Text("$count") } }) {
                        Icon(Icons.Filled.Tune, contentDescription = "Filters")
                    }
                }
            }
            // Loading is a thin bar along the bar's bottom edge: visible, but it covers no
            // map and doesn't shift anything when it comes and goes.
            if (showLoading && ui.loading) LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp))
        }
    }
}

/** All / MTB as a two-segment toggle. */
@Composable
fun ModeToggle(mode: MapMode, onSetMode: (MapMode) -> Unit, modifier: Modifier = Modifier) {
    SingleChoiceSegmentedButtonRow(modifier) {
        listOf(MapMode.ALL to "All", MapMode.MTB to "MTB").forEachIndexed { i, (m, label) ->
            SegmentedButton(
                selected = mode == m,
                onClick = { onSetMode(m) },
                shape = SegmentedButtonDefaults.itemShape(i, 2),
                icon = {},
            ) { Text(label, style = MaterialTheme.typography.labelMedium) }
        }
    }
}

/**
 * Everything the old chip rows held, in a sheet: radius, minimum length, surfaces, uses and
 * the auto-load switch. Changes apply as they're made; the button just closes the sheet.
 */
@Composable
fun FilterSheet(ui: TrailsUiState, filters: FilterActions, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        FilterSheetContent(ui, filters, onDone = onDismiss)
    }
}

/** The sheet's body, separate so it can be rendered without a window. */
@Composable
internal fun FilterSheetContent(ui: TrailsUiState, filters: FilterActions, onDone: () -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Filters",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = filters.reset, enabled = activeFilterCount(ui) > 0) { Text("Reset") }
        }

        SheetLabel(if (ui.mode == MapMode.MTB) "Search within" else "List trails within")
        val options = radiusOptions(ui.mode)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, miles ->
                SegmentedButton(
                    selected = ui.radiusMiles.roundToInt() == miles,
                    onClick = { filters.setRadiusMiles(miles) },
                    shape = SegmentedButtonDefaults.itemShape(i, options.size),
                ) { Text("$miles mi") }
            }
        }

        SheetLabel("Minimum length")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LENGTH_OPTIONS.forEach { (label, miles) ->
                FilterChip(
                    selected = ui.minLengthMiles == miles,
                    onClick = { filters.setMinLength(miles) },
                    label = { Text(label) },
                )
            }
        }

        SheetLabel("Surface")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SHOWN_SURFACES.forEach { surface ->
                FilterChip(
                    selected = surface in ui.selectedSurfaces,
                    onClick = { filters.toggleSurface(surface) },
                    label = { Text(surface.label) },
                    leadingIcon = {
                        Box(Modifier.size(12.dp).clip(CircleShape).background(surface.color))
                    },
                )
            }
        }

        SheetLabel("Use")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = UseType.WALK in ui.selectedUses,
                onClick = { filters.toggleUse(UseType.WALK) },
                label = { Text("Walk") },
                leadingIcon = { Icon(Icons.Filled.DirectionsWalk, null, Modifier.size(FilterChipDefaults.IconSize)) },
            )
            FilterChip(
                selected = UseType.BIKE in ui.selectedUses,
                onClick = { filters.toggleUse(UseType.BIKE) },
                label = { Text("Bike") },
                leadingIcon = { Icon(Icons.Filled.DirectionsBike, null, Modifier.size(FilterChipDefaults.IconSize)) },
            )
            FilterChip(
                selected = UseType.HORSE in ui.selectedUses,
                onClick = { filters.toggleUse(UseType.HORSE) },
                label = { Text("Horse") },
                leadingIcon = { Icon(HorseIcon, null, Modifier.size(FilterChipDefaults.IconSize)) },
            )
        }

        // Trails built for horses (purple on the map), as opposed to multi-use trails that
        // horses may also use, which the Horse chip above covers.
        SheetLabel("Horse trails")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HorseTrailFilter.entries.forEach { f ->
                FilterChip(
                    selected = ui.horseTrails == f,
                    onClick = { filters.setHorseTrails(f) },
                    label = { Text(f.label) },
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        HorizontalDivider()
        // Auto-load refetches trails as you pan. On by default; the switch lets heavy map
        // browsing stay off the public Overpass API when you don't want it.
        Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Load trails as I pan", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Off = use “Search this area” instead",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = ui.autoLoadOnPan, onCheckedChange = filters.setAutoLoad)
        }
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
            val n = ui.filtered.size
            Text(if (n == 1) "Show 1 trail" else "Show $n trails")
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
