package com.trailmap.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

// Every role is set, light and dark. Leaving the container and surface roles unset made
// cards, selected chips and FABs fall back to Material's default lavender.
//
// Ink is primary *and* secondaryContainer, so filled buttons, selected chips and segments,
// the bottom bar's indicator and the switch all read as one thing: ink on paper. Inverse
// surface is the signboard band at the top of a screen.
private val LightColors = lightColorScheme(
    primary = Ink, onPrimary = Bone,
    primaryContainer = Color(0xFFD8E3D6), onPrimaryContainer = Ink,
    secondary = StarGold, onSecondary = Color.White,
    secondaryContainer = Ink, onSecondaryContainer = Bone,
    tertiary = Bark, onTertiary = Color.White,
    tertiaryContainer = Color(0xFFEBDCCB), onTertiaryContainer = Color(0xFF3A2410),
    background = Paper, onBackground = Ink,
    surface = Paper, onSurface = Ink,
    surfaceVariant = Color(0xFFDEE3DA), onSurfaceVariant = Color(0xFF525B52),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFECEFE8),
    surfaceContainer = Color(0xFFE5E9E1),
    surfaceContainerHigh = Color(0xFFDEE3DA),
    surfaceContainerHighest = Color(0xFFD6DCD2),
    outline = Color(0xFF6F786E), outlineVariant = Color(0xFFC7CEC3),
    inverseSurface = Ink, inverseOnSurface = Bone, inversePrimary = Color(0xFFC9D6C6),
    error = Color(0xFFA33A2A), onError = Color.White,
    errorContainer = Color(0xFFF6DDD7), onErrorContainer = Color(0xFF4A150C),
)

private val DarkColors = darkColorScheme(
    primary = Bone, onPrimary = Pine,
    primaryContainer = Color(0xFF2A3A2E), onPrimaryContainer = Bone,
    secondary = Color(0xFFE8C27A), onSecondary = Color(0xFF3F2E00),
    secondaryContainer = Color(0xFFCFD9CB), onSecondaryContainer = Pine,
    tertiary = Color(0xFFE0B48A), onTertiary = Color(0xFF3A2410),
    tertiaryContainer = Color(0xFF4A3420), onTertiaryContainer = Color(0xFFEBDCCB),
    background = Pine, onBackground = Bone,
    surface = Pine, onSurface = Bone,
    surfaceVariant = Color(0xFF2A362C), onSurfaceVariant = Color(0xFFB9C3B7),
    surfaceContainerLowest = Color(0xFF090E0B),
    surfaceContainerLow = Color(0xFF141B16),
    surfaceContainer = Color(0xFF192219),
    surfaceContainerHigh = Color(0xFF212C23),
    surfaceContainerHighest = Color(0xFF2A362C),
    outline = Color(0xFF8A948A), outlineVariant = Color(0xFF333F35),
    inverseSurface = Color(0xFF1F2B22), inverseOnSurface = Bone, inversePrimary = Ink,
    error = Color(0xFFF2A598), onError = Color(0xFF4A150C),
    errorContainer = Color(0xFF6E2418), onErrorContainer = Color(0xFFF6DDD7),
)

/**
 * Sign plates, not pills: 8 dp on controls, 12 on cards and menus, 16 on sheets and the peek
 * card. Buttons ignore this (Material pins them to a full pill), which is why screens use
 * [com.trailmap.ui.SignButton].
 */
private val TrailmapShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

/**
 * How many signboard bands are on screen. A band is dark in both themes and runs under the
 * status bar, so while one shows the status-bar icons go light; the map, which has no band,
 * keeps icons that suit the theme. A count rather than a flag, because during a navigation
 * transition the leaving screen's band disposes after the arriving one's has registered.
 */
internal val signBandsShowing = mutableIntStateOf(0)

/** Registers a signboard band for as long as the caller is in composition. */
@Composable
fun SignBandOnScreen() {
    DisposableEffect(Unit) {
        signBandsShowing.intValue++
        onDispose { signBandsShowing.intValue-- }
    }
}

@Composable
fun TrailmapTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // The map runs under the status bar, and nothing told the bar which way round its icons
    // should be, so they stayed white — invisible over the light map, and over the whole app
    // whenever the in-app theme is Light while the phone is in dark mode.
    val view = LocalView.current
    val lightIcons = darkTheme || signBandsShowing.intValue > 0
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !lightIcons
            isAppearanceLightNavigationBars = !darkTheme
        }
    }
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = TrailmapTypography,
        shapes = TrailmapShapes,
        content = content,
    )
}
