package com.shakelock

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Brand: deep indigo/violet, with coral as the "escape" accent.
val HeroGradient = Brush.linearGradient(listOf(Color(0xFF2B2896), Color(0xFF6A4BD8)))
val NightGradient = Brush.verticalGradient(listOf(Color(0xFF0F0E2E), Color(0xFF26236E), Color(0xFF3D2F8F)))
val Coral = Color(0xFFFF8A6B)

private val LightColors = lightColorScheme(
    primary = Color(0xFF4B47D6),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE2E0FF),
    onPrimaryContainer = Color(0xFF15124F),
    secondary = Color(0xFF5E5C71),
    secondaryContainer = Color(0xFFE4E0F5),
    onSecondaryContainer = Color(0xFF1B1A2C),
    tertiary = Color(0xFFD0563A),
    tertiaryContainer = Color(0xFFFFDBD1),
    onTertiaryContainer = Color(0xFF3B0A00),
    background = Color(0xFFF7F6FB),
    onBackground = Color(0xFF1B1B21),
    surface = Color(0xFFF7F6FB),
    onSurface = Color(0xFF1B1B21),
    onSurfaceVariant = Color(0xFF5F5D6E),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF2F1F8),
    surfaceContainer = Color(0xFFEEEDF6),
    surfaceContainerHigh = Color(0xFFE9E7F2),
    surfaceContainerHighest = Color(0xFFE4E2EE),
    outlineVariant = Color(0xFFD3D0E0),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFC2C0FF),
    onPrimary = Color(0xFF221E8A),
    primaryContainer = Color(0xFF3A36B5),
    onPrimaryContainer = Color(0xFFE2E0FF),
    secondary = Color(0xFFC8C4DC),
    secondaryContainer = Color(0xFF3A3850),
    onSecondaryContainer = Color(0xFFE4E0F5),
    tertiary = Coral,
    tertiaryContainer = Color(0xFF6E2A18),
    onTertiaryContainer = Color(0xFFFFDBD1),
    background = Color(0xFF121218),
    onBackground = Color(0xFFE5E3EC),
    surface = Color(0xFF121218),
    onSurface = Color(0xFFE5E3EC),
    onSurfaceVariant = Color(0xFFC8C5D5),
    surfaceContainerLowest = Color(0xFF0D0D12),
    surfaceContainerLow = Color(0xFF1A1922),
    surfaceContainer = Color(0xFF1E1D27),
    surfaceContainerHigh = Color(0xFF262530),
    surfaceContainerHighest = Color(0xFF2E2D38),
    outlineVariant = Color(0xFF46455A),
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
)

@Composable
fun ShakeLockTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        shapes = AppShapes,
        content = content,
    )
}
