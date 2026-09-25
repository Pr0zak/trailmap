package com.trailmap.snap

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.trailmap.ui.DiagnosticsContent
import com.trailmap.ui.FilterActions
import com.trailmap.ui.MapOverlays
import com.trailmap.ui.OfflineAreaUi
import com.trailmap.ui.OfflineContent
import com.trailmap.ui.RideDetailContent
import com.trailmap.ui.RidesContent
import com.trailmap.ui.TrailDetailContent
import com.trailmap.ui.TrailListContent
import com.trailmap.ui.TrailsUiState
import com.trailmap.ui.theme.TrailmapTheme
import org.junit.Rule
import org.junit.Test

/** The app as it is today (app-v0.11.1), each screen fed [Samples]. */
class BeforeSnapshots {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5)

    private fun shot(dark: Boolean = false, content: @Composable () -> Unit) =
        paparazzi.snapshot { TrailmapTheme(darkTheme = dark) { content() } }

    @Composable
    private fun Map(ui: TrailsUiState, selected: String? = null) = Box(Modifier.fillMaxSize()) {
        FauxMap(ui.filtered)
        MapOverlays(
            ui = ui, dark = false,
            selectedTrail = selected?.let { id -> ui.trails.first { it.id == id } },
            filters = FilterActions(), onSearchThisArea = {}, onOpenTrail = {}, onClearSelection = {},
            onCycleMapTheme = {}, onOpenOffline = {}, onRecenter = {},
        )
    }

    @Test fun map_idle() = shot { Map(Samples.ui) }
    @Test fun map_loading() = shot { Map(Samples.ui.copy(loading = true)) }
    @Test fun map_selected() = shot { Map(Samples.ui, selected = "name_trolley_track_trail") }
    @Test fun map_mtb() = shot { Map(Samples.uiMtb) }

    @Composable
    private fun List(ui: TrailsUiState) = TrailListContent(
        ui = ui, filters = FilterActions(), onSetQuery = {}, onSetShowSavedOnly = {},
        onToggleSaved = {}, onOpenTrail = {}, onOpenSystem = {},
    )

    @Test fun list_all() = shot { List(Samples.ui) }
    @Test fun list_mtb() = shot { List(Samples.uiMtb) }
    @Test fun list_empty() = shot { List(Samples.ui.copy(query = "zzz")) }
    @Test fun list_dark() = shot(dark = true) { List(Samples.ui) }

    @Test fun detail() = shot {
        TrailDetailContent(
            trail = Samples.trails[0], profile = Samples.profile, ui = Samples.ui,
            onBack = {}, onToggleSaved = {}, onCreateRide = { _, _ -> }, onAddToRide = { _, _ -> },
        )
    }

    @Test fun rides() = shot { RidesContent(Samples.rides, onOpenRide = {}, onCreateRide = { "" }) }
    @Test fun rides_empty() = shot { RidesContent(emptyList(), onOpenRide = {}, onCreateRide = { "" }) }

    @Test fun ride_detail() = shot {
        RideDetailContent(Samples.rides[0], onBack = {}, onOpenTrail = {}, onRemoveTrail = {}, onRename = {}, onDelete = {})
    }

    @Test fun offline() = shot {
        OfflineContent(
            ui = Samples.ui.copy(
                viewBounds = com.trailmap.data.ViewBounds(39.15, 39.05, -94.5, -94.65, 12.0),
                offlineTrailBytes = 12_900_000L,
                trailPrefetch = "Trail data: 6 of 9 circles",
            ),
            areas = listOf(
                OfflineAreaUi(1, "KC Metro 1", 100, true, 18_422),
                OfflineAreaUi(2, "Current view 1", 46, false, 1_210),
            ),
            status = "Downloading Current view 1… 46%",
            onBack = {}, onOpenDiagnostics = {}, onDownloadView = {}, onDownloadPreset = {},
            onRetry = {}, onDelete = {}, onClearTrails = {},
        )
    }

    @Test fun diagnostics() = shot {
        DiagnosticsContent(Samples.diagLines, onBack = {}, onRefresh = {}, onShare = {}, onClear = {})
    }
}
