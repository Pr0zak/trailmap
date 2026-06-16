package com.trailmap.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.trailmap.data.ElevationProfile
import com.trailmap.ui.theme.TrailGreen
import kotlin.math.roundToInt

/**
 * Filled-area elevation profile. X = cumulative distance, Y = elevation normalized between
 * the profile's min/max (with ~5% vertical padding). Caller handles the empty/loading state.
 */
@Composable
fun ElevationChart(profile: ElevationProfile, modifier: Modifier = Modifier) {
    val pts = profile.points
    if (pts.isEmpty()) return

    val minM = pts.minOf { it.elevationMeters }
    val maxM = pts.maxOf { it.elevationMeters }
    val maxDist = pts.maxOf { it.distanceMeters }.coerceAtLeast(1.0)
    val pad = ((maxM - minM) * 0.05).coerceAtLeast(1.0)
    val lo = minM - pad
    val hi = maxM + pad
    val span = (hi - lo).coerceAtLeast(1.0)

    val areaColor = TrailGreen.copy(alpha = 0.18f)
    val baselineColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    Box(modifier.fillMaxWidth().height(160.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            fun x(d: Double) = (d / maxDist).toFloat() * w
            fun y(e: Double) = (1f - ((e - lo) / span).toFloat()) * h

            // subtle baseline at the bottom
            drawLine(
                color = baselineColor,
                start = Offset(0f, h - 1f),
                end = Offset(w, h - 1f),
                strokeWidth = 1f,
            )

            val line = Path().apply {
                moveTo(x(pts.first().distanceMeters), y(pts.first().elevationMeters))
                for (i in 1 until pts.size) lineTo(x(pts[i].distanceMeters), y(pts[i].elevationMeters))
            }
            val fill = Path().apply {
                addPath(line)
                lineTo(x(pts.last().distanceMeters), h)
                lineTo(x(pts.first().distanceMeters), h)
                close()
            }
            drawPath(fill, color = areaColor)
            drawPath(line, color = TrailGreen, style = Stroke(width = 3f))
        }

        val maxFt = (maxM * 3.28084).roundToInt()
        val minFt = (minM * 3.28084).roundToInt()
        Text(
            "$maxFt ft",
            style = MaterialTheme.typography.labelSmall,
            color = labelColor,
            modifier = Modifier.align(Alignment.TopStart).padding(4.dp),
        )
        Text(
            "$minFt ft",
            style = MaterialTheme.typography.labelSmall,
            color = labelColor,
            modifier = Modifier.align(Alignment.BottomStart).padding(4.dp),
        )
    }
}
