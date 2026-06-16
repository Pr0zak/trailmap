package com.trailmap.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trailmap.data.ElevationProfile
import com.trailmap.data.UseType
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrailDetailScreen(vm: TrailsViewModel, id: String, onBack: () -> Unit) {
    val trail = vm.trailById(id)
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val profile: ElevationProfile? = profiles[id]

    LaunchedEffect(id) { vm.ensureProfile(id) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(trail?.name ?: "Trail", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (trail == null) {
            Box(Modifier.padding(padding).fillMaxSize(), Alignment.Center) {
                Text("Trail not found.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Scaffold
        }

        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SurfaceBadge(trail.surface)
                Spacer(Modifier.size(6.dp))
                MtbBadge(trail.mtbScale)
                Spacer(Modifier.size(10.dp))
                Text(usesLine(trail.uses), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard("Length", "%.1f mi".format(trail.lengthMiles), Modifier.weight(1f))
                StatCard("Away", "%.1f mi".format(trail.distanceMiles), Modifier.weight(1f))
                StatCard(
                    "Elevation",
                    profile?.takeIf { it.points.isNotEmpty() }
                        ?.let { "${it.ascentFeet.roundToInt()} ft" } ?: "—",
                    Modifier.weight(1f),
                )
            }

            Text("Elevation profile", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Card(Modifier.fillMaxWidth()) {
                when {
                    profile == null -> Box(
                        Modifier.fillMaxWidth().height(160.dp),
                        Alignment.Center,
                    ) { CircularProgressIndicator() }
                    profile.points.isEmpty() -> Box(
                        Modifier.fillMaxWidth().height(160.dp),
                        Alignment.Center,
                    ) {
                        Text(
                            "Elevation unavailable",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    else -> ElevationChart(profile, Modifier.padding(12.dp))
                }
            }
        }
    }
}

private fun usesLine(uses: Set<UseType>): String = when {
    UseType.WALK in uses && UseType.BIKE in uses -> "Walking & Biking"
    UseType.WALK in uses -> "Walking"
    UseType.BIKE in uses -> "Biking"
    else -> "Trail"
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 14.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(4.dp))
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}
