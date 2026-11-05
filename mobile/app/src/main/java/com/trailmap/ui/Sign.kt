@file:OptIn(ExperimentalMaterial3Api::class)

package com.trailmap.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.trailmap.ui.theme.SignBandOnScreen

/*
 * The signboard: the pieces that give every screen the same trailhead-kiosk shape. A dark
 * band across the top holds the screen's name (and, on the Trails list, the search plate);
 * everything under it is paper. Buttons are sign plates with 8 dp corners rather than pills.
 */

/**
 * Colours for a TopAppBar drawn as the signboard band, and the light status-bar icons that
 * go with it while the bar is on screen.
 */
@Composable
fun signBand(): TopAppBarColors {
    SignBandOnScreen()
    val ink = MaterialTheme.colorScheme.inverseSurface
    val bone = MaterialTheme.colorScheme.inverseOnSurface
    return TopAppBarDefaults.topAppBarColors(
        containerColor = ink,
        scrolledContainerColor = ink,
        titleContentColor = bone,
        navigationIconContentColor = bone,
        actionIconContentColor = bone,
    )
}

/** Secondary text on the band: a date, a count, a version. */
@Composable
fun onSignBandMuted(): Color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.72f)

/** The one corner radius every button shares. */
val SignShape = RoundedCornerShape(8.dp)

/** A filled button as a sign plate. Same parameters as Material's, minus the shape. */
@Composable
fun SignButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) = Button(
    onClick = onClick,
    modifier = modifier,
    enabled = enabled,
    shape = SignShape,
    colors = colors,
    contentPadding = contentPadding,
    content = content,
)

/** An outlined button as a sign plate. */
@Composable
fun SignOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    border: BorderStroke? = ButtonDefaults.outlinedButtonBorder(enabled),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) = OutlinedButton(
    onClick = onClick,
    modifier = modifier,
    enabled = enabled,
    shape = SignShape,
    colors = colors,
    border = border,
    contentPadding = contentPadding,
    content = content,
)

/**
 * A painted blaze — the short rectangle marked on a tree to say which trail this is — in the
 * trail's surface colour (or horse-trail purple). Leads every trail row.
 */
@Composable
fun Blaze(color: Color, modifier: Modifier = Modifier) = Box(
    modifier.width(7.dp).height(26.dp).clip(RoundedCornerShape(2.dp)).background(color),
)
