package com.lectora.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.lectora.app.data.ReaderTheme

private val LightColors = lightColorScheme(
    primary = Color(0xFF7A4E2D),
    onPrimary = Color.White,
    secondary = Color(0xFF6B5D4F),
    background = Color(0xFFFBF7F0),
    surface = Color(0xFFFBF7F0),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFE9B98F),
    onPrimary = Color(0xFF3E2C23),
    secondary = Color(0xFFD6C3B0),
    background = Color(0xFF1A1714),
    surface = Color(0xFF1A1714),
)

@Composable
fun LectoraTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(LocalContext.current) else dynamicLightColorScheme(LocalContext.current)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}

/** Colores de la página de lectura (independientes del tema de la app). */
data class PageColors(val background: Color, val text: Color, val secondaryText: Color, val highlight: Color)

@Composable
fun pageColors(theme: ReaderTheme): PageColors {
    val dark = when (theme) {
        ReaderTheme.SYSTEM -> isSystemInDarkTheme()
        ReaderTheme.DARK -> true
        else -> false
    }
    return when {
        theme == ReaderTheme.SEPIA -> PageColors(Color(0xFFF4ECD8), Color(0xFF3B2F25), Color(0xFF7B6A58), Color(0x66E0A458))
        dark -> PageColors(Color(0xFF121212), Color(0xFFE6E1DA), Color(0xFF9E978F), Color(0x667A5A2E))
        else -> PageColors(Color(0xFFFFFDF9), Color(0xFF1E1B18), Color(0xFF6E665E), Color(0x66FFD27A))
    }
}
