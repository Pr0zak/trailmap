package com.trailmap.snap

import androidx.compose.runtime.Composable
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.trailmap.snap.after.DetailAfter
import com.trailmap.snap.after.DiagnosticsAfter
import com.trailmap.snap.after.ListAfter
import com.trailmap.snap.after.MapAfter
import com.trailmap.snap.after.OfflineAfter
import com.trailmap.snap.after.ProposedTheme
import com.trailmap.snap.after.RideDetailAfter
import com.trailmap.snap.after.RidesAfter
import org.junit.Rule
import org.junit.Test

/** The proposals in the UI review, rendered with the same [Samples] as [BeforeSnapshots]. */
class AfterSnapshots {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5)

    private fun shot(dark: Boolean = false, content: @Composable () -> Unit) =
        paparazzi.snapshot { ProposedTheme(dark) { content() } }

    @Test fun map_idle() = shot { MapAfter(Samples.ui) }
    @Test fun map_loading() = shot { MapAfter(Samples.ui.copy(loading = true)) }
    @Test fun map_selected() = shot { MapAfter(Samples.ui, selected = Samples.trails[0]) }
    @Test fun map_mtb() = shot { MapAfter(Samples.uiMtb) }
    @Test fun map_filters() = shot { MapAfter(Samples.ui, sheet = true) }
    @Test fun list_all() = shot { ListAfter(Samples.ui) }
    @Test fun list_mtb() = shot { ListAfter(Samples.uiMtb) }
    @Test fun list_empty() = shot { ListAfter(Samples.ui, empty = true) }
    @Test fun list_dark() = shot(dark = true) { ListAfter(Samples.ui) }
    @Test fun detail() = shot { DetailAfter(Samples.trails[0], Samples.profile) }
    @Test fun rides() = shot { RidesAfter(Samples.rides) }
    @Test fun rides_empty() = shot { RidesAfter(emptyList()) }
    @Test fun ride_detail() = shot { RideDetailAfter(Samples.rides[0]) }
    @Test fun offline() = shot { OfflineAfter() }
    @Test fun diagnostics() = shot { DiagnosticsAfter(Samples.diagLines) }
}
