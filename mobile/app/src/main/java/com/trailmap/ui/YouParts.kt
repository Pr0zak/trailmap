package com.trailmap.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.ElectricBike
import androidx.compose.material.icons.filled.Hiking
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.trailmap.data.ActivityKind
import com.trailmap.data.TrailCondition
import com.trailmap.data.TrailStatus
import com.trailmap.data.TrailVisits
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/*
 * Pieces shared by every screen that shows your own activity from myvitals: the visit line on
 * a trail, a condition chip, dates, and the one colour that means "you" on the map.
 */

/** "You" on the map: your tracks, and the glow on trails you've ridden. As (light, dark). */
internal val YOU_LINE_COLOR = 0xFF2962FF to 0xFF82B1FF

/** The same blue for Compose, e.g. the legend swatch and the ridden stretch in the preview. */
internal fun youColor(dark: Boolean) = Color((if (dark) YOU_LINE_COLOR.second else YOU_LINE_COLOR.first).toInt())

/** Whether the app is drawing its dark theme — the scheme's surface says so. */
@Composable
internal fun darkTheme(): Boolean = MaterialTheme.colorScheme.surface.luminance() < 0.5f

/** Badge fill per condition, dark enough for white text (at least 4.5:1). */
internal val TrailCondition.color: Color
    get() = when (this) {
        TrailCondition.OPEN -> Color(0xFF2E7D32)
        TrailCondition.DELAYED -> Color(0xFF8D5A00)
        TrailCondition.CLOSED -> Color(0xFFC62828)
        TrailCondition.UNKNOWN -> Color(0xFF616161)
    }

/** "Ridden 69× · last Jun 16", "On foot 3× · last May 2", or both counts. Null if neither. */
internal fun visitText(v: TrailVisits?, now: Long = System.currentTimeMillis()): String? {
    if (v == null) return null
    val parts = ArrayList<String>()
    if (v.rides.isNotEmpty()) parts += "Ridden ${v.rides.size}×"
    if (v.onFoot.isNotEmpty()) parts += if (parts.isEmpty()) "On foot ${v.onFoot.size}×" else "on foot ${v.onFoot.size}×"
    if (parts.isEmpty()) return null
    val last = listOfNotNull(v.lastRidden, v.lastOnFoot).maxOrNull()
    if (last != null) parts += "last ${shortDate(last, now)}"
    return parts.joinToString(" · ")
}

/** A check and [visitText], in the primary colour — the Trails list and the peek card. */
@Composable
internal fun VisitLine(v: TrailVisits?, modifier: Modifier = Modifier) {
    val text = visitText(v) ?: return
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.CheckCircle, contentDescription = null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(4.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** "Closed" on red, "Open" on green, and so on. */
@Composable
internal fun ConditionChip(status: TrailStatus, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(status.condition.color)
            .padding(horizontal = 10.dp, vertical = 3.dp),
    ) {
        Text(
            status.condition.label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
    }
}

/** A chip plus "Rain · Sep 25", for a system header or the detail screen. */
@Composable
internal fun ConditionLine(status: TrailStatus, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        ConditionChip(status)
        val detail = listOfNotNull(status.comment, status.updatedAt?.let { shortDate(it) }).joinToString(" · ")
        if (detail.isNotEmpty()) {
            Spacer(Modifier.width(6.dp))
            Text(
                detail,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A thin bar filled to [fraction] in the "you" blue. */
@Composable
internal fun CoverageBar(fraction: Double, dark: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Box(
            Modifier.fillMaxWidth(fraction.toFloat().coerceIn(0f, 1f)).height(8.dp)
                .clip(RoundedCornerShape(50)).background(youColor(dark)),
        )
    }
}

internal val ActivityKind.icon: ImageVector
    get() = when (this) {
        ActivityKind.BIKE -> Icons.Filled.DirectionsBike
        ActivityKind.EBIKE -> Icons.Filled.ElectricBike
        ActivityKind.MTB -> Icons.Filled.Landscape
        ActivityKind.WALK -> Icons.Filled.DirectionsWalk
        ActivityKind.HIKE -> Icons.Filled.Hiking
        ActivityKind.RUN -> Icons.Filled.DirectionsRun
        ActivityKind.OTHER -> Icons.Filled.DirectionsWalk
    }

/** "Jun 16" this year, "Jun 16, 2024" before it. */
internal fun shortDate(ms: Long, now: Long = System.currentTimeMillis()): String {
    val year = { t: Long -> Calendar.getInstance().apply { timeInMillis = t }.get(Calendar.YEAR) }
    val pattern = if (year(ms) == year(now)) "MMM d" else "MMM d, yyyy"
    return SimpleDateFormat(pattern, Locale.US).format(Date(ms))
}

/** "Sat, Sep 21, 2026 · 6:10 PM" for a recorded activity's header. */
internal fun longDateTime(ms: Long): String = SimpleDateFormat("EEE, MMM d, yyyy · h:mm a", Locale.US).format(Date(ms))

/** "just now", "12 min ago", "3 h ago", "yesterday", or a date. */
internal fun ago(ms: Long, now: Long = System.currentTimeMillis()): String {
    val d = (now - ms).coerceAtLeast(0)
    return when {
        d < 60_000 -> "just now"
        d < 3_600_000 -> "${d / 60_000} min ago"
        d < 24 * 3_600_000 -> "${d / 3_600_000} h ago"
        d < 48 * 3_600_000 -> "yesterday"
        else -> shortDate(ms, now)
    }
}

/** "1 h 42 min", "38 min". */
internal fun duration(seconds: Int): String {
    val m = (seconds + 30) / 60
    return if (m < 60) "$m min" else "${m / 60} h ${m % 60} min"
}
