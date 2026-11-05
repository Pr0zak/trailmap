package com.trailmap.ui.theme

import androidx.compose.ui.graphics.Color

/*
 * "Signboard" palette. The trail data owns every hue on screen — paved green, gravel
 * gold, dirt sienna, horse purple, and the six sign colours for difficulty — so the app's own
 * chrome stays out of that colour space: forest ink on cool paper, inverted at night. The one
 * exception is the gold of the saved star, the pin from the app icon.
 */

/** Forest ink: text, the signboard band, filled buttons, selected chips. */
val Ink = Color(0xFF17291F)

/** Paper: the light background, with a faint green cast like a topo sheet. */
val Paper = Color(0xFFF2F4EE)

/** Bone: light text on ink, and the primary colour at night. */
val Bone = Color(0xFFE9EEE6)

/** Pine: the dark background. */
val Pine = Color(0xFF0E1511)

/** The saved star, and nothing else. */
val StarGold = Color(0xFFA9780E)

/** Kept for the map's surface legend and badges, which carry data colours, not chrome. */
val TrailGreen = Color(0xFF2E7D4F)
val TrailGreenDark = Color(0xFF1B5E3A)
val Bark = Color(0xFF8A5A2B)
val Gravel = Color(0xFFC9922B)
