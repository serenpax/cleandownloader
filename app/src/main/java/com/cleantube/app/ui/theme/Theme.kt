package com.cleantube.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Green = Color(0xFF1B8755)
private val GreenDark = Color(0xFF63D9A0)

private val LightColors = lightColorScheme(
    primary = Green,
    onPrimary = Color.White,
    secondary = Color(0xFF4E6355),
    background = Color(0xFFFAFAF8),
    surface = Color(0xFFFAFAF8),
)

private val DarkColors = darkColorScheme(
    primary = GreenDark,
    onPrimary = Color(0xFF003820),
    secondary = Color(0xFFB6CCBA),
    background = Color(0xFF101412),
    surface = Color(0xFF101412),
)

@Composable
fun CleanTubeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
