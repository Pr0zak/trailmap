package com.trailmap.snap

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.trailmap.R
import org.junit.Rule
import org.junit.Test

/** The launcher icon's real layers, cropped the way launchers crop an adaptive icon. */
class IconSnapshot {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5)

    @Composable
    private fun Icon(shape: Shape, sizeDp: Int, themed: Boolean = false) {
        // An adaptive icon is a 108dp canvas; launchers show the middle 72dp.
        val bg = if (themed) Color(0xFFDCEBDD) else Color(0xFFF2C744)
        Box(Modifier.size(sizeDp.dp).clip(shape).background(bg)) {
            Image(
                painterResource(if (themed) R.drawable.ic_launcher_monochrome else R.drawable.ic_launcher_foreground),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                colorFilter = if (themed) ColorFilter.tint(Color(0xFF16361F)) else null,
                modifier = Modifier.size((sizeDp * 1.5f).dp).padding(0.dp),
            )
        }
    }

    @Test fun launcherIcon() = paparazzi.snapshot {
        Row(
            Modifier.background(Color(0xFF22382A)).padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(CircleShape, 76)
            Icon(RoundedCornerShape(22.dp), 76)
            Icon(CircleShape, 48)
            Icon(CircleShape, 76, themed = true)
        }
    }
}
