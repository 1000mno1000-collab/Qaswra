package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

private val VoiceProColorScheme = darkColorScheme(
    primary = CyanGlow,
    onPrimary = StudioObsidian,
    primaryContainer = CyanMuted.copy(alpha = 0.35f),
    onPrimaryContainer = TextPrimary,
    secondary = VioletGlow,
    onSecondary = StudioObsidian,
    secondaryContainer = VioletDeep.copy(alpha = 0.45f),
    onSecondaryContainer = TextPrimary,
    tertiary = AmberWarm,
    onTertiary = StudioObsidian,
    background = StudioObsidian,
    onBackground = TextPrimary,
    surface = StudioMidnight,
    onSurface = TextPrimary,
    surfaceVariant = StudioSurface,
    onSurfaceVariant = TextSecondary,
    surfaceContainerHigh = StudioSurfaceElevated,
    outline = StudioBorder,
    error = CoralRecord,
    onError = TextPrimary
)

private val VoiceProShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

@Composable
fun VoiceProTheme(
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(
            colorScheme = VoiceProColorScheme,
            typography = StudioTypography,
            shapes = VoiceProShapes,
            content = content
        )
    }
}
