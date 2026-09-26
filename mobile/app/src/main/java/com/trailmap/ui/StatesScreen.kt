package com.trailmap.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Trail data by state: which states' trails live on the phone. A chosen state downloads in the
 * background and then loads instantly and offline; removing one deletes its pack at once.
 * States come from the release's index, so a state taken out of the build disappears here.
 */
@Composable
fun StatesScreen(vm: TrailsViewModel, onBack: () -> Unit) {
    val ui by vm.state.collectAsStateWithLifecycle()
    StatesContent(
        ui = ui,
        onBack = onBack,
        onAdd = vm::addPackState,
        onRemove = vm::removePackState,
        onCheck = vm::checkTrailPacks,
    )
}

/** Stateless body of [StatesScreen], so it can be rendered with sample state. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StatesContent(
    ui: TrailsUiState,
    onBack: () -> Unit,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    onCheck: () -> Unit,
) {
    val chosen = ui.packStates.filter { it.selected || it.installed }
    val nearby = ui.packStates.filter { it.nearby && it !in chosen }
    val rest = ui.packStates.filter { it !in chosen && it !in nearby }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Trail data") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onCheck) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Check for updates")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                Text(
                    "Trails in the states you pick live on this phone: they load instantly and work " +
                        "offline. Everywhere else they come from OpenStreetMap's servers, which can be slow. " +
                        "Packs update weekly by themselves.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            item { PackSyncStatus(ui) }

            if (ui.packStates.isEmpty()) {
                item {
                    Text(
                        "Fetching the list of states…",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                }
            }
            section("On this phone", chosen) { st ->
                StateRow(st, ui.packDownload) {
                    IconButton(onClick = { onRemove(st.slug) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Remove ${st.name}")
                    }
                }
            }
            section("Near the map", nearby) { st -> StateRow(st, null) { TextButton(onClick = { onAdd(st.slug) }) { Text("Add") } } }
            section("All states", rest) { st -> StateRow(st, null) { TextButton(onClick = { onAdd(st.slug) }) { Text("Add") } } }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.section(
    title: String,
    rows: List<PackState>,
    row: @Composable (PackState) -> Unit,
) {
    if (rows.isEmpty()) return
    item(key = "h_$title") {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
        )
    }
    items(rows, key = { "${title}_${it.slug}" }) { st ->
        row(st)
        HorizontalDivider()
    }
}

@Composable
private fun StateRow(st: PackState, download: PackDownload?, trailing: @Composable () -> Unit) {
    ListItem(
        headlineContent = { Text(st.name) },
        supportingContent = {
            Text(
                when {
                    download != null && download.state == st.name -> "Downloading…"
                    st.installed && st.updateAvailable -> "${megabytes(st.bytes)} · update downloading soon"
                    st.installed -> megabytes(st.bytes) + (osmDate(st.osmTimestamp)?.let { " · OpenStreetMap data from $it" } ?: "")
                    st.selected -> "Waiting to download · ${megabytes(st.bytes)}"
                    else -> megabytes(st.bytes)
                },
            )
        },
        trailingContent = trailing,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

/** What the sync is doing right now: downloading, waiting for a connection, or failed. */
@Composable
internal fun PackSyncStatus(ui: TrailsUiState) {
    val dl = ui.packDownload
    val message = when {
        dl != null -> null
        ui.packFailed -> "The last download didn't finish. It tries again next time the app opens."
        ui.packWaiting && ui.packStates.any { it.selected && !it.installed } -> "Waiting for a connection. Downloads start by themselves."
        else -> return
    }
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (dl != null) {
                Text(
                    if (dl.count > 1) "Downloading ${dl.state} (${dl.number} of ${dl.count})" else "Downloading ${dl.state}",
                    style = MaterialTheme.typography.labelLarge,
                )
                LinearProgressIndicator(
                    progress = { if (dl.total > 0) (dl.done.toFloat() / dl.total).coerceIn(0f, 1f) else 0f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "${megabytes(dl.done)} of ${megabytes(dl.total)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (message != null) {
                Text(message, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

internal fun megabytes(bytes: Long): String = "%.1f MB".format(bytes / (1024.0 * 1024.0))

/** "2026-09-25T20:24:36Z" → "Sep 25"; null when there is no usable date. */
internal fun osmDate(iso: String?): String? = iso?.let {
    runCatching {
        java.time.OffsetDateTime.parse(it).format(java.time.format.DateTimeFormatter.ofPattern("MMM d", java.util.Locale.US))
    }.getOrNull()
}
