package com.trailmap.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Every role is set, light and dark. Leaving the container and surface roles unset made
// cards, selected chips and FABs fall back to Material's default lavender.
private val LightColors = lightColorScheme(
    primary = TrailGreen, onPrimary = Color.White,
    primaryContainer = Color(0xFFC6EBD1), onPrimaryContainer = Color(0xFF0B2E19),
    secondary = Color(0xFFB07A12), onSecondary = Color.White,
    secondaryContainer = Color(0xFFF1E4C6), onSecondaryContainer = Color(0xFF2A1D05),
    tertiary = Bark,
    background = Sand, onBackground = Charcoal,
    surface = Sand, onSurface = Charcoal,
    surfaceVariant = Color(0xFFE4E2D8), onSurfaceVariant = Color(0xFF4A4D45),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF2EFE8),
    surfaceContainer = Color(0xFFEDEAE2),
    surfaceContainerHigh = Color(0xFFE8E5DC),
    surfaceContainerHighest = Color(0xFFE2DFD6),
    outline = Color(0xFF7A7D73), outlineVariant = Color(0xFFCAC7BC),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7FD6A0), onPrimary = Color(0xFF003919),
    primaryContainer = TrailGreenDark, onPrimaryContainer = Color(0xFFC6EBD1),
    secondary = Color(0xFFE8C27A), onSecondary = Color(0xFF3F2E00),
    secondaryContainer = Color(0xFF4D3A14), onSecondaryContainer = Color(0xFFF1E4C6),
    tertiary = Color(0xFFE0B48A),
    background = Color(0xFF121411), onBackground = Color(0xFFE3E3DC),
    surface = Color(0xFF121411), onSurface = Color(0xFFE3E3DC),
    surfaceVariant = Color(0xFF42463F), onSurfaceVariant = Color(0xFFC2C6BC),
    surfaceContainerLowest = Color(0xFF0D0F0C),
    surfaceContainerLow = Color(0xFF1A1C19),
    surfaceContainer = Color(0xFF1E201D),
    surfaceContainerHigh = Color(0xFF282B27),
    surfaceContainerHighest = Color(0xFF333531),
    outline = Color(0xFF8C9087), outlineVariant = Color(0xFF42463F),
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
