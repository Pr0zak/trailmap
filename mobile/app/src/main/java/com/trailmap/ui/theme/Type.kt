package com.trailmap.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.trailmap.R

/**
 * Overpass (Red Hat, OFL), bundled in res/font. It descends from Highway Gothic, the face on
 * US road and recreation signs, which is where a trailhead's own typography comes from — and
 * it shares a name with the API that feeds the map. One family throughout: weight and size
 * carry the hierarchy, not a second face.
 */
val Overpass = FontFamily(
    Font(R.font.overpass_regular, FontWeight.Normal),
    Font(R.font.overpass_semibold, FontWeight.SemiBold),
    Font(R.font.overpass_bold, FontWeight.Bold),
    Font(R.font.overpass_extrabold, FontWeight.ExtraBold),
)

/** Lengths and distances line up in columns, so every style asks for tabular figures. */
private const val FIGURES = "tnum"

private fun style(size: Int, line: Int, weight: FontWeight, tracking: Float = 0f) = TextStyle(
    fontFamily = Overpass,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.sp,
    fontFeatureSettings = FIGURES,
)

val TrailmapTypography = Typography(
    displayLarge = style(44, 48, FontWeight.ExtraBold, -1f),
    displayMedium = style(36, 40, FontWeight.ExtraBold, -0.8f),
    displaySmall = style(30, 34, FontWeight.ExtraBold, -0.6f),
    headlineLarge = style(30, 36, FontWeight.ExtraBold, -0.5f),
    headlineMedium = style(26, 32, FontWeight.ExtraBold, -0.4f),
    headlineSmall = style(22, 28, FontWeight.Bold, -0.2f),
    titleLarge = style(20, 26, FontWeight.Bold, -0.1f),
    titleMedium = style(16, 22, FontWeight.SemiBold),
    titleSmall = style(14, 20, FontWeight.SemiBold),
    bodyLarge = style(16, 23, FontWeight.Normal),
    bodyMedium = style(14, 20, FontWeight.Normal),
    bodySmall = style(12, 16, FontWeight.Normal),
    labelLarge = style(14, 20, FontWeight.SemiBold),
    labelMedium = style(12, 16, FontWeight.SemiBold),
    labelSmall = style(11, 16, FontWeight.SemiBold),
)
