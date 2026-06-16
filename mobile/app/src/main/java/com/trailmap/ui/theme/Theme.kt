package com.trailmap.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = TrailGreen,
    onPrimary = Color.White,
    primaryContainer = TrailGreenDark,
    onPrimaryContainer = Color.White,
    secondary = Gravel,
    tertiary = Bark,
    background = Sand,
    surface = Color.White,
    onBackground = Charcoal,
    onSurface = Charcoal,
)

private val DarkColors = darkColorScheme(
    primary = TrailGreen,
    secondary = Gravel,
    tertiary = Bark,
)

@Composable
fun TrailmapTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = TrailmapTypography,
        content = content,
    )
}
