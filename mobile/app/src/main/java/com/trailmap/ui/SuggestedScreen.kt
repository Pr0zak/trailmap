package com.trailmap.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.trailmap.data.DifficultyTier
import com.trailmap.data.SuggestedRide

/**
 * Rides → Suggested: rides you repeat (from myvitals) and named routes around the map. Both
 * come from data already on the phone; [suggestions] is worked out by the ViewModel.
 */
@Composable
internal fun SuggestedList(
    ui: TrailsUiState,
    suggestions: TrailsViewModel.SuggestionsUi,
    onSave: (SuggestedRide) -> Unit,
    onPreview: (SuggestedRide) -> Unit,
    onOpenMyVitals: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { SectionHeader("Rides you repeat", "Trail combinations you've ridden more than once, around the map") }
        when {
            !ui.myVitals.connected -> item {
                Hint("Connect myvitals to see the trail combinations you ride most.") {
                    TextButton(onClick = onOpenMyVitals) { Text("Connect myvitals") }
                }
            }
            suggestions.repeats.isEmpty() -> item {
                Hint(if (suggestions.working) "Looking through your rides…" else "No repeated rides around here yet — pan the map to where you ride.")
            }
            else -> items(suggestions.repeats, key = { it.id }) { SuggestionCard(it, onSave, onPreview) }
        }
        item { Spacer(Modifier.size(6.dp)) }
        item { SectionHeader("Named routes", "Loops and routes local trail groups have mapped in OpenStreetMap") }
        if (suggestions.routes.isEmpty()) {
            item {
                Hint(
                    if (suggestions.working) "Looking for routes…"
                    else "No named routes around here. Add this state's trails under Offline maps → Trail data, or try MTB mode.",
                )
            }
        } else {
            items(suggestions.routes, key = { it.id }) { SuggestionCard(it, onSave, onPreview) }
        }
        if (suggestions.working) item {
            Box(Modifier.fillMaxWidth().padding(8.dp), Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) }
        }
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String) = Column(Modifier.padding(horizontal = 4.dp)) {
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Hint(text: String, action: @Composable () -> Unit = {}) = Surface(
    shape = RoundedCornerShape(16.dp),
    color = MaterialTheme.colorScheme.surfaceContainer,
    modifier = Modifier.fillMaxWidth(),
) {
    Column(Modifier.padding(16.dp)) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        action()
    }
}

/** One suggestion: name and length, the trails in order, how hard it gets, Save / Show on map. */
@Composable
internal fun SuggestionCard(s: SuggestedRide, onSave: (SuggestedRide) -> Unit, onPreview: (SuggestedRide) -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    s.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text("%.1f mi".format(s.lengthMiles), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            val sub = when (s.kind) {
                SuggestedRide.Kind.REPEAT -> listOfNotNull(
                    "Ridden ${s.timesRidden}×",
                    s.lastRidden?.let { "last ${shortDate(it)}" },
                    "${s.trails.size} trails",
                )
                SuggestedRide.Kind.ROUTE -> listOfNotNull(s.trails.firstOrNull()?.parkName, "Named route")
            }.joinToString(" · ")
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (s.kind == SuggestedRide.Kind.REPEAT) {
                Text(
                    s.trails.joinToString(" → ") { it.name },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Spacer(Modifier.size(10.dp))
            LevelMixBar(s.levelMix)
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                s.level?.let { LevelBadge(it, null) }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { onPreview(s) }) { Text("Show on map") }
                OutlinedButton(onClick = { onSave(s) }) { Text("Save as ride") }
            }
        }
    }
}

/** How much of the ride is each level, in sign colours; unrated in grey. */
@Composable
private fun LevelMixBar(mix: Map<DifficultyTier?, Double>) {
    val total = mix.values.sum()
    if (total <= 0) return
    Row(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50))) {
        val ordered = DifficultyTier.entries.map { it as DifficultyTier? } + listOf(null)
        ordered.forEach { tier ->
            val m = mix[tier] ?: return@forEach
            if (m <= 0) return@forEach
            Box(
                Modifier.weight((m / total).toFloat()).height(8.dp).background(
                    when (tier) {
                        null -> Color(0xFF9E9E9E)
                        DifficultyTier.WHITE -> Color(0xFFE0E0E0)
                        else -> tier.color
                    },
                ),
            )
        }
    }
}
