package com.trailmap.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.trailmap.data.SurfaceType
import kotlin.math.roundToInt

/** A small filled circle, e.g. a surface color key. */
@Composable
fun Dot(color: Color, sizeDp: Int = 10) = Box(Modifier.size(sizeDp.dp).clip(CircleShape).background(color))

/**
 * One stacked bar splitting a length across surfaces, largest first, with an optional
 * "Gravel 80% · Paved 20%" key under it. [mix] values are any consistent unit (fractions,
 * meters); only their proportions matter.
 */
@Composable
fun SurfaceMixBar(mix: Map<SurfaceType, Double>, modifier: Modifier = Modifier, legend: Boolean = true) {
    val parts = mix.entries.filter { it.value > 0 }.sortedByDescending { it.value }
    val total = parts.sumOf { it.value }
    if (total <= 0) return
    Column(modifier) {
        Row(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50))) {
            parts.forEach { (surface, v) ->
                Box(Modifier.weight((v / total).toFloat().coerceAtLeast(0.01f)).height(8.dp).background(surface.color))
            }
        }
        if (legend) {
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                parts.forEach { (surface, v) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Dot(surface.color, 8)
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "${surface.label} ${(v / total * 100).roundToInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
