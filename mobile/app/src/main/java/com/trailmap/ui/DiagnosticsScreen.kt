package com.trailmap.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.delay
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.trailmap.BuildConfig
import com.trailmap.data.DiagLog

/**
 * The diagnostic log, newest first, with a share button.
 *
 * There's no backend to ship logs to and a public repo can't carry an upload token, so the
 * log stays on the device until the user deliberately sends it somewhere.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var lines by remember { mutableStateOf(DiagLog.snapshot()) }
    // Refresh while open, so a load you trigger elsewhere shows up without a button press.
    LaunchedEffect(Unit) {
        while (true) {
            delay(2000)
            lines = DiagLog.snapshot()
        }
    }
    DiagnosticsContent(
        lines = lines,
        onBack = onBack,
        onShare = {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "trailmap diagnostics")
                putExtra(Intent.EXTRA_TEXT, DiagLog.dump())
            }
            context.startActivity(Intent.createChooser(send, "Share log"))
        },
        onClear = { DiagLog.clear(); lines = DiagLog.snapshot() },
    )
}

/** Stateless body of [DiagnosticsScreen], so it can be rendered with sample lines. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DiagnosticsContent(
    lines: List<String>,
    onBack: () -> Unit,
    onShare: () -> Unit,
    onClear: () -> Unit,
) {

    var filter by remember { mutableStateOf("All") }
    val parsed = remember(lines) { lines.map(::parseLine) }
    val tags = remember(parsed) { parsed.map { it.tag }.filter { it.isNotEmpty() }.distinct() }
    val shown = parsed.filter {
        when (filter) {
            "All" -> true
            "Errors" -> it.error
            else -> it.tag == filter
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Diagnostics")
                        Text(
                            "trailmap ${BuildConfig.VERSION_NAME} · auto-refreshing",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onShare) {
                        Icon(Icons.Filled.Share, contentDescription = "Share log")
                    }
                    IconButton(onClick = onClear) {
                        Icon(Icons.Filled.Delete, contentDescription = "Clear log")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            LastLoadSummary(parsed, Modifier.padding(horizontal = 12.dp))
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                (listOf("All", "Errors") + tags).forEach { t ->
                    FilterChip(selected = filter == t, onClick = { filter = t }, label = { Text(t) })
                }
            }
            if (shown.isEmpty()) {
                Text(
                    if (lines.isEmpty()) "Nothing logged yet. Pan the map, then come back." else "No lines match.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                items(shown) { line -> LogRow(line) }
            }
        }
    }
}

/** One log line split into its parts. Lines are "HH:mm:ss.SSS  tag      message" (DiagLog). */
private data class LogLine(val time: String, val tag: String, val message: String, val error: Boolean)

private fun parseLine(raw: String): LogLine {
    if (raw.length < 23 || raw[12] != ' ') return LogLine("", "", raw, false)
    val msg = raw.substring(23)
    val error = listOf("failed", "rate-limited", "error", "Couldn't").any { msg.contains(it, ignoreCase = true) }
    return LogLine(raw.substring(0, 8), raw.substring(14, 22).trim(), msg, error)
}

/** The last load's time and size, the mirror that last answered, and the error count. */
@Composable
private fun LastLoadSummary(lines: List<LogLine>, modifier: Modifier = Modifier) {
    val load = lines.firstOrNull { it.tag == "load" && it.message.startsWith("done in ") }
    val ms = load?.message?.removePrefix("done in ")?.substringBefore(" ms")?.toLongOrNull()
    val trails = load?.message?.substringAfter("ms, ", "")?.substringBefore(" trails", "")?.takeIf { it.isNotEmpty() }
    val mirror = lines.firstOrNull { it.tag == "http" && " OK " in it.message }?.message?.substringBefore(" OK ")
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            SummaryItem(ms?.let { "%.1f s".format(it / 1000.0) } ?: "—", "last load")
            SummaryItem(trails ?: "—", "trails")
            SummaryItem(mirror?.removePrefix("overpass.")?.removePrefix("maps.") ?: "—", "mirror")
            SummaryItem("${lines.count { it.error }}", "errors")
        }
    }
}

@Composable
private fun SummaryItem(value: String, label: String) = Column {
    Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun LogRow(line: LogLine) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(
                if (line.error) MaterialTheme.colorScheme.errorContainer else Color.Transparent,
                RoundedCornerShape(8.dp),
            )
            .padding(vertical = 5.dp, horizontal = 4.dp),
    ) {
        Text(
            line.time,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(62.dp),
        )
        if (line.tag.isNotEmpty()) {
            Box(
                Modifier
                    .width(58.dp)
                    .background(tagColor(line.tag), RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            ) {
                Text(line.tag, style = MaterialTheme.typography.labelSmall, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1)
            }
            Spacer(Modifier.width(8.dp))
        }
        Text(
            line.message,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = if (line.error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** A fixed color per tag, dark enough for white text. Unknown tags are grey. */
private fun tagColor(tag: String) = when (tag) {
    "load" -> Color(0xFF2E7D4F)
    "http" -> Color(0xFF1565C0)
    "cache" -> Color(0xFF5E4B8B)
    "camera" -> Color(0xFF8A5A2B)
    "map" -> Color(0xFF00695C)
    "offline" -> Color(0xFF6D4C41)
    else -> Color(0xFF616161)
}
