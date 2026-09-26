package com.trailmap.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.trailmap.data.ElevPoint
import com.trailmap.data.ElevationProfile
import kotlin.math.roundToInt

private const val FEET_PER_METER = 3.28084
private const val METERS_PER_MILE = 1609.344

/**
 * Filled-area elevation profile with a mile axis, dashed gridlines and min/max labels.
 * Touch or drag across it to read the mile and elevation at that point. Y is scaled between
 * the profile's min and max (with ~5% padding). Caller handles the empty/loading state.
 *
 * [initialScrub] (0..1) pre-places the marker; used by UI snapshots.
 */
@Composable
fun ElevationChart(
    profile: ElevationProfile,
    modifier: Modifier = Modifier,
    initialScrub: Float? = null,
    onScrub: (ElevPoint?) -> Unit = {},
) {
    val pts = profile.points
    if (pts.isEmpty()) return

    val minM = pts.minOf { it.elevationMeters }
    val maxM = pts.maxOf { it.elevationMeters }
    val maxDist = pts.maxOf { it.distanceMeters }.coerceAtLeast(1.0)
    val pad = ((maxM - minM) * 0.05).coerceAtLeast(1.0)
    val lo = minM - pad
    val span = (maxM + pad - lo).coerceAtLeast(1.0)

    val line = MaterialTheme.colorScheme.primary
    val fill = line.copy(alpha = 0.16f)
    val grid = MaterialTheme.colorScheme.outlineVariant
    val label = MaterialTheme.colorScheme.onSurfaceVariant
    val tipBg = MaterialTheme.colorScheme.inverseSurface
    val tipFg = MaterialTheme.colorScheme.inverseOnSurface

    // Scrub position as a fraction of the width; null = no marker.
    var scrub by remember(profile) { mutableStateOf(initialScrub) }

    Column(modifier) {
        Row {
            BoxWithConstraints(Modifier.weight(1f).height(150.dp)) {
                val widthPx = constraints.maxWidth.toFloat()
                fun setFrom(x: Float) {
                    val f = (x / widthPx).coerceIn(0f, 1f)
                    scrub = f
                    // Tell the caller which sample is under the marker, so the route preview
                    // can show the same spot.
                    onScrub(sampleAt(profile, f))
                }
                Canvas(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(profile) { detectTapGestures { setFrom(it.x) } }
                        .pointerInput(profile) {
                            detectHorizontalDragGestures(
                                onDragStart = { setFrom(it.x) },
                            ) { change, _ -> setFrom(change.position.x) }
                        },
                ) {
                    val w = size.width
                    val h = size.height
                    fun x(d: Double) = (d / maxDist).toFloat() * w
                    fun y(e: Double) = (1f - ((e - lo) / span).toFloat()) * h

                    for (i in 0..3) {
                        val gy = h * i / 3f
                        drawLine(grid, Offset(0f, gy), Offset(w, gy), 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
                    }
                    // The line lifts across a gap between the trail's pieces; the fill doesn't,
                    // so the area stays one closed shape.
                    val path = Path().apply {
                        moveTo(x(pts.first().distanceMeters), y(pts.first().elevationMeters))
                        for (i in 1 until pts.size) {
                            val px = x(pts[i].distanceMeters)
                            val py = y(pts[i].elevationMeters)
                            if (pts[i].gapBefore) moveTo(px, py) else lineTo(px, py)
                        }
                    }
                    val area = Path().apply {
                        moveTo(x(pts.first().distanceMeters), y(pts.first().elevationMeters))
                        for (i in 1 until pts.size) lineTo(x(pts[i].distanceMeters), y(pts[i].elevationMeters))
                        lineTo(x(pts.last().distanceMeters), h)
                        lineTo(x(pts.first().distanceMeters), h)
                        close()
                    }
                    drawPath(area, fill)
                    drawPath(path, line, style = Stroke(width = 5f))

                    scrub?.let { f ->
                        val p = pointAt(pts.map { it.distanceMeters to it.elevationMeters }, f * maxDist)
                        val sx = x(p.first)
                        val sy = y(p.second)
                        drawLine(label, Offset(sx, 0f), Offset(sx, h), 2f)
                        drawCircle(Color.White, 11f, Offset(sx, sy))
                        drawCircle(line, 8f, Offset(sx, sy))
                    }
                }

                scrub?.let { f ->
                    val p = pointAt(pts.map { it.distanceMeters to it.elevationMeters }, f * maxDist)
                    val density = LocalDensity.current
                    // Keep the tooltip inside the chart: anchor left of the marker past halfway.
                    val xDp = with(density) { (f * widthPx).toDp() }
                    Box(
                        Modifier
                            .align(if (f > 0.5f) Alignment.TopEnd else Alignment.TopStart)
                            .padding(
                                start = if (f > 0.5f) 0.dp else xDp + 6.dp,
                                end = if (f > 0.5f) maxWidth - xDp + 6.dp else 0.dp,
                            )
                            .background(tipBg, RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            "%.1f mi · %d ft".format(p.first / METERS_PER_MILE, (p.second * FEET_PER_METER).roundToInt()),
                            style = MaterialTheme.typography.labelSmall,
                            color = tipFg,
                        )
                    }
                }
            }
            Column(
                Modifier.height(150.dp).padding(start = 6.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("${(maxM * FEET_PER_METER).roundToInt()} ft", style = MaterialTheme.typography.labelSmall, color = label)
                Text("${(minM * FEET_PER_METER).roundToInt()} ft", style = MaterialTheme.typography.labelSmall, color = label)
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(end = 44.dp, top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            val miles = maxDist / METERS_PER_MILE
            listOf(0.0, miles / 3, miles * 2 / 3, miles).forEach {
                Text("%.1f mi".format(it), style = MaterialTheme.typography.labelSmall, color = label)
            }
        }
    }
}

/** Linear interpolation of (distance, elevation) at [d] along a distance-sorted profile. */
private fun pointAt(pts: List<Pair<Double, Double>>, d: Double): Pair<Double, Double> {
    val i = pts.indexOfFirst { it.first >= d }
    if (i <= 0) return pts.first()
    val (d0, e0) = pts[i - 1]
    val (d1, e1) = pts[i]
    val t = if (d1 > d0) (d - d0) / (d1 - d0) else 0.0
    return d to (e0 + (e1 - e0) * t)
}

/** The profile sample nearest [fraction] (0..1) of the way along it. */
fun sampleAt(profile: ElevationProfile, fraction: Float): ElevPoint? {
    val maxDist = profile.points.maxOfOrNull { it.distanceMeters } ?: return null
    return profile.points.minByOrNull { kotlin.math.abs(it.distanceMeters - fraction * maxDist) }
}
