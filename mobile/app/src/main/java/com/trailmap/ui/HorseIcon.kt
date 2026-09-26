package com.trailmap.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Horse trails in lists and badges; the light-map line colour (MapScreen's HORSE_LINE_COLOR). */
val HorseTrailColor = Color(0xFF7B1FA2)

/**
 * Horse head, for trails open to horses. Material's icon sets have no horse, so this is the
 * "horse-fill" glyph from Phosphor Icons (https://phosphoricons.com), MIT License,
 * Copyright (c) 2023 Phosphor Icons. Tinted like any other icon.
 */
val HorseIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Horse",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 256f,
        viewportHeight = 256f,
    ).addPath(
        pathData = addPathNodes(
            "M202.05,55A103.24,103.24,0,0,0,128,24h-8a8,8,0,0,0-8,8V59.53L11.81,121.19a8,8,0,0,0-2.59," +
                "11.05l13.78,22,.3.43a31.84,31.84,0,0,0,31.34,12.83c13.93-2.36,38.62-6.54,61.4,3.29l-26.6," +
                "36.57A84.71,84.71,0,0,1,69.34,194,8,8,0,1,0,58.67,206a103.32,103.32,0,0,0,69.26,26l2.17,0a104," +
                "104,0,0,0,72-177ZM124,112a12,12,0,1,1,12-12A12,12,0,0,1,124,112Z",
        ),
        fill = SolidColor(Color.Black),
    ).build()
}
