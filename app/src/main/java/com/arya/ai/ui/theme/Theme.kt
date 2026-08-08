package com.arya.ai.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

private val AryaDarkScheme = darkColorScheme(
    primary = AryaSignal,
    onPrimary = AryaSignalOn,
    primaryContainer = AryaSignalContainerDark,
    onPrimaryContainer = AryaOnSignalContainerDark,
    secondary = AryaSprout,
    secondaryContainer = AryaSproutContainerDark,
    onSecondaryContainer = AryaSproutContainer,
    tertiary = AryaEmber,
    tertiaryContainer = AryaEmberContainerDark,
    onTertiaryContainer = AryaEmberContainer,
    background = AryaInk,
    onBackground = AryaPaper,
    surface = AryaInkSurface,
    onSurface = AryaPaper,
    surfaceVariant = AryaInkSurfaceVariant,
    onSurfaceVariant = AryaPaper.copy(alpha = 0.72f),
    error = AryaError
)

private val AryaLightScheme = lightColorScheme(
    primary = AryaSignalDark,
    onPrimary = AryaPaper,
    primaryContainer = AryaSignalContainerLight,
    onPrimaryContainer = AryaOnSignalContainerLight,
    secondary = AryaSprout,
    secondaryContainer = AryaSproutContainer,
    onSecondaryContainer = AryaSproutOn,
    tertiary = AryaEmber,
    tertiaryContainer = AryaEmberContainer,
    onTertiaryContainer = AryaEmberOn,
    background = AryaPaper,
    onBackground = AryaInk,
    surface = AryaPaperSurface,
    onSurface = AryaInk,
    surfaceVariant = AryaPaperSurface,
    onSurfaceVariant = AryaInk.copy(alpha = 0.72f),
    error = AryaError
)

/** Rounder than Material's 12dp default on cards, sharper on buttons — small deliberate
 *  asymmetry so content containers (cards) read as "soft/quiet" and actions (buttons) read
 *  as "precise/pressable", instead of everything sharing one uniform roundness. */
private val AryaShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp)
)

@Composable
fun AryaTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colorScheme = if (darkTheme) AryaDarkScheme else AryaLightScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = AryaTypography,
        shapes = AryaShapes,
        content = content
    )
}
