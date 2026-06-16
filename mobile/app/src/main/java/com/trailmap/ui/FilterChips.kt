package com.trailmap.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.trailmap.data.SurfaceType
import com.trailmap.data.UseType
import kotlin.math.roundToInt

/**
 * Filter controls reused on the Map overlay and the Trail list.
 *
 * Two short horizontally-scrollable rows stacked in a Column:
 *  - Row 1: mode toggle (All / MTB), radius selector (MTB only), min-length filter.
 *  - Row 2: surface chips (colored dot per [SurfaceType.color]) + use chips (walk/bike icon).
 *
 * UNKNOWN is intentionally not surfaced as a chip (it stays in the filter set by default).
 */
@Composable
fun FilterChips(
    ui: TrailsUiState,
    onToggleSurface: (SurfaceType) -> Unit,
    onToggleUse: (UseType) -> Unit,
    onSetMode: (MapMode) -> Unit,
    onSetRadiusMiles: (Int) -> Unit,
    onSetMinLength: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Row 1: mode + radius (MTB only) + length
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = ui.mode == MapMode.ALL,
                onClick = { onSetMode(MapMode.ALL) },
                label = { Text("All") },
            )
            FilterChip(
                selected = ui.mode == MapMode.MTB,
                onClick = { onSetMode(MapMode.MTB) },
                label = { Text("MTB") },
            )

            if (ui.mode == MapMode.MTB) {
                val selectedRadius = ui.radiusMiles.roundToInt()
                listOf(10, 25, 40).forEach { miles ->
                    FilterChip(
                        selected = selectedRadius == miles,
                        onClick = { onSetRadiusMiles(miles) },
                        label = { Text("$miles mi") },
                    )
                }
            }

            // Min-length filter (both modes). label -> miles
            val lengthOptions = listOf(
                "Any" to 0.0,
                "1+ mi" to 1.0,
                "3+ mi" to 3.0,
                "5+ mi" to 5.0,
                "10+ mi" to 10.0,
            )
            lengthOptions.forEach { (label, miles) ->
                FilterChip(
                    selected = ui.minLengthMiles == miles,
                    onClick = { onSetMinLength(miles) },
                    label = { Text(label) },
                )
            }
        }

        // Row 2: surfaces + uses
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(SurfaceType.PAVED, SurfaceType.GRAVEL, SurfaceType.DIRT).forEach { surface ->
                FilterChip(
                    selected = surface in ui.selectedSurfaces,
                    onClick = { onToggleSurface(surface) },
                    label = { Text(surface.label) },
                    leadingIcon = {
                        Box(
                            Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(surface.color),
                        )
                    },
                )
            }
            FilterChip(
                selected = UseType.WALK in ui.selectedUses,
                onClick = { onToggleUse(UseType.WALK) },
                label = { Text("Walk") },
                leadingIcon = {
                    Icon(
                        Icons.Filled.DirectionsWalk,
                        contentDescription = "Walking",
                        modifier = Modifier.size(FilterChipDefaults.IconSize),
                    )
                },
            )
            FilterChip(
                selected = UseType.BIKE in ui.selectedUses,
                onClick = { onToggleUse(UseType.BIKE) },
                label = { Text("Bike") },
                leadingIcon = {
                    Icon(
                        Icons.Filled.DirectionsBike,
                        contentDescription = "Biking",
                        modifier = Modifier.size(FilterChipDefaults.IconSize),
                    )
                },
            )
        }
    }
}
