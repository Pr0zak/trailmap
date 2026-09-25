package com.trailmap.snap.after

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.trailmap.data.ElevationProfile
import com.trailmap.data.SurfaceType
import kotlin.math.roundToInt

/** Proposal C4: surface pills with text that passes contrast (white on goldenrod is ~2.2:1). */
@Composable
fun SurfacePill(surface: SurfaceType, modifier: Modifier = Modifier) {
    val fg = if (surface == SurfaceType.GRAVEL) Color(0xFF3A2A00) else Color.White
    Box(
        modifier.clip(RoundedCornerShape(50)).background(surface.color).padding(horizontal = 10.dp, vertical = 3.dp),
    ) {
        Text(surface.label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = fg)
    }
}

@Composable
fun Dot(color: Color, size: Int = 10) = Box(Modifier.size(size.dp).clip(CircleShape).background(color))

/** A single stacked bar showing how a length splits across surfaces, with a legend under it. */
@Composable
fun SurfaceMixBar(mix: Map<SurfaceType, Double>, modifier: Modifier = Modifier, legend: Boolean = true) {
    val total = mix.values.sum().coerceAtLeast(1e-9)
    val parts = mix.entries.sortedByDescending { it.value }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50))) {
            parts.forEach { (s, v) ->
                Box(Modifier.weight((v / total).toFloat().coerceAtLeast(0.01f)).height(8.dp).background(s.color))
            }
        }
        if (legend) {
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                parts.forEach { (s, v) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Dot(s.color, 8)
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "${s.label} ${(v / total * 100).roundToInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Proposal D4: elevation chart with a distance axis, gridlines, and a scrub marker (drawn at
 * [scrubAt] to show what touch-and-drag would look like).
 */
@Composable
fun ElevationChartAfter(profile: ElevationProfile, scrubAt: Float? = 0.42f, modifier: Modifier = Modifier) {
    val pts = profile.points
    val minM = pts.minOf { it.elevationMeters }
    val maxM = pts.maxOf { it.elevationMeters }
    val maxD = pts.maxOf { it.distanceMeters }
    val line = MaterialTheme.colorScheme.primary
    val fill = line.copy(alpha = 0.16f)
    val grid = MaterialTheme.colorScheme.outlineVariant
    val label = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier) {
        Row {
            Box(Modifier.weight(1f).height(150.dp)) {
                Canvas(Modifier.fillMaxWidth().height(150.dp)) {
                    val w = size.width; val h = size.height
                    fun x(d: Double) = (d / maxD).toFloat() * w
                    fun y(e: Double) = (1f - ((e - minM) / (maxM - minM)).toFloat()) * (h * 0.9f) + h * 0.05f
                    for (i in 0..3) {
                        val gy = h * i / 3f
                        drawLine(grid, Offset(0f, gy), Offset(w, gy), 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
                    }
                    val p = Path().apply {
                        moveTo(x(pts[0].distanceMeters), y(pts[0].elevationMeters))
                        pts.drop(1).forEach { lineTo(x(it.distanceMeters), y(it.elevationMeters)) }
                    }
                    val f = Path().apply { addPath(p); lineTo(w, h); lineTo(0f, h); close() }
                    drawPath(f, fill)
                    drawPath(p, line, style = Stroke(5f))
                    scrubAt?.let { s ->
                        val i = (s * (pts.size - 1)).roundToInt()
                        val sx = x(pts[i].distanceMeters); val sy = y(pts[i].elevationMeters)
                        drawLine(label, Offset(sx, 0f), Offset(sx, h), 2f)
                        drawCircle(Color.White, 11f, Offset(sx, sy))
                        drawCircle(line, 8f, Offset(sx, sy))
                    }
                }
                scrubAt?.let { s ->
                    val i = (s * (pts.size - 1)).roundToInt()
                    Box(
                        Modifier.align(Alignment.TopStart).padding(start = (s * 250).dp, top = 2.dp)
                            .clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.inverseSurface)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            "%.1f mi · %d ft".format(pts[i].distanceMeters / 1609.344, (pts[i].elevationMeters * 3.28084).roundToInt()),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.inverseOnSurface,
                        )
                    }
                }
            }
            Column(Modifier.height(150.dp).padding(start = 6.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Text("${(maxM * 3.28084).roundToInt()} ft", style = MaterialTheme.typography.labelSmall, color = label)
                Text("${(minM * 3.28084).roundToInt()} ft", style = MaterialTheme.typography.labelSmall, color = label)
            }
        }
        Row(Modifier.fillMaxWidth().padding(end = 44.dp, top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            val mi = maxD / 1609.344
            listOf(0.0, mi / 3, mi * 2 / 3, mi).forEach {
                Text("%.1f mi".format(it), style = MaterialTheme.typography.labelSmall, color = label)
            }
        }
    }
}
