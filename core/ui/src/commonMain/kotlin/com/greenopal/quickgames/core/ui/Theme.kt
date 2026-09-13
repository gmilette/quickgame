package com.greenopal.quickgames.core.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object PixelPalette {
    val background = Color(0xFF0F0F1B)
    val surface = Color(0xFF1B1B2F)
    val primary = Color(0xFF7BE87B)
    val secondary = Color(0xFFF8D878)
    val accent = Color(0xFF6BC8F8)
    val danger = Color(0xFFF87858)
    val text = Color(0xFFECECEC)
}

private val pixelColors = darkColorScheme(
    primary = PixelPalette.primary,
    onPrimary = Color.Black,
    secondary = PixelPalette.secondary,
    onSecondary = Color.Black,
    tertiary = PixelPalette.accent,
    background = PixelPalette.background,
    onBackground = PixelPalette.text,
    surface = PixelPalette.surface,
    onSurface = PixelPalette.text,
    error = PixelPalette.danger
)

private fun mono(size: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = weight,
    fontSize = size.sp
)

private val pixelTypography = Typography(
    displayLarge = mono(34, FontWeight.Bold),
    headlineMedium = mono(24, FontWeight.Bold),
    titleLarge = mono(20, FontWeight.Bold),
    titleMedium = mono(16, FontWeight.Bold),
    bodyLarge = mono(15),
    bodyMedium = mono(13),
    labelLarge = mono(14, FontWeight.Bold),
    labelMedium = mono(12)
)

@Composable
fun QuickGamesTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = pixelColors,
        typography = pixelTypography,
        content = content
    )
}
