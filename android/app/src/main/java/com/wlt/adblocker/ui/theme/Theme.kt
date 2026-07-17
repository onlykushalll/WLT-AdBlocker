package com.wlt.adblocker.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val WltGreen = Color(0xFF00C896)
private val WltGreenDark = Color(0xFF00A87E)
private val WltAmber = Color(0xFFFFB300)
private val WltRed = Color(0xFFEF4444)

private val DarkColors = darkColorScheme(
    primary = WltGreen,
    onPrimary = Color(0xFF003028),
    primaryContainer = Color(0xFF004D3D),
    onPrimaryContainer = Color(0xFFB0FFE6),
    secondary = WltAmber,
    onSecondary = Color(0xFF402900),
    secondaryContainer = Color(0xFF5C3F00),
    onSecondaryContainer = Color(0xFFFFDFA0),
    tertiary = Color(0xFF80CBC4),
    error = WltRed,
    background = Color(0xFF0A0F0D),
    onBackground = Color(0xFFE0E5E2),
    surface = Color(0xFF111714),
    onSurface = Color(0xFFE0E5E2),
    surfaceVariant = Color(0xFF1F2823),
    onSurfaceVariant = Color(0xFFB8C2BC),
    outline = Color(0xFF6B7570),
)

private val LightColors = lightColorScheme(
    primary = WltGreenDark,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB0FFE6),
    onPrimaryContainer = Color(0xFF003028),
    secondary = Color(0xFF8B5E00),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDFA0),
    onSecondaryContainer = Color(0xFF2C1900),
    tertiary = Color(0xFF00695C),
    error = WltRed,
    background = Color(0xFFF7FAF8),
    onBackground = Color(0xFF111714),
    surface = Color.White,
    onSurface = Color(0xFF111714),
    surfaceVariant = Color(0xFFDCE5E0),
    onSurfaceVariant = Color(0xFF3F4944),
    outline = Color(0xFF6B7570),
)

@Composable
fun WltTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }
    MaterialTheme(colorScheme = colors, content = content)
}
