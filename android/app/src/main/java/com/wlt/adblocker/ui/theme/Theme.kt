package com.wlt.adblocker.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * WLT theme.
 *
 * Material3 dynamic color is INTENTIONALLY OFF — we use brand colors so the
 * app looks the same on every device regardless of the user's wallpaper.
 * Dynamic color would also let OEM "themes" override our carefully chosen
 * green/amber palette with whatever blue/purple happens to be in fashion,
 * which is exactly what the design guidelines say not to do.
 *
 * Status bar / navigation bar appearance is set via WindowCompat (not the
 * deprecated `statusBarColor` API — Task 14 deprecation fix).
 */
@Composable
fun WltTheme(
    // WLT defaults to LIGHT theme per user request — clean, professional, readable.
    // The light palette uses the same dark-green/teal + amber brand identity
    // on a near-white surface for excellent daylight readability.
    darkTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) {
        darkColorScheme(
            primary = WltPrimary,
            onPrimary = WltOnPrimary,
            primaryContainer = WltPrimaryContainer,
            onPrimaryContainer = WltOnPrimaryContainer,
            secondary = WltSecondary,
            onSecondary = WltOnSecondary,
            secondaryContainer = WltSecondaryContainer,
            onSecondaryContainer = WltOnSecondaryContainer,
            tertiary = WltTertiary,
            onTertiary = WltOnTertiary,
            background = WltBackground,
            onBackground = WltOnBackground,
            surface = WltSurface,
            onSurface = WltOnSurface,
            surfaceVariant = WltSurfaceVariant,
            onSurfaceVariant = WltOnSurfaceVariant,
            error = WltError,
            onError = WltOnError,
            errorContainer = WltErrorContainer,
            onErrorContainer = WltOnErrorContainer,
            outline = WltOutline,
            outlineVariant = WltOutlineVariant,
        )
    } else {
        lightColorScheme(
            primary = WltPrimaryLight,
            onPrimary = WltOnPrimaryLight,
            primaryContainer = WltPrimaryContainerLight,
            onPrimaryContainer = WltOnPrimaryContainerLight,
            secondary = WltSecondaryLight,
            onSecondary = WltOnSecondaryLight,
            secondaryContainer = WltSecondaryContainerLight,
            onSecondaryContainer = WltOnSecondaryContainerLight,
            tertiary = WltSecondaryLight,
            onTertiary = WltOnSecondaryLight,
            background = WltBackgroundLight,
            onBackground = WltOnBackgroundLight,
            surface = WltSurfaceLight,
            onSurface = WltOnSurfaceLight,
            surfaceVariant = WltSurfaceVariantLight,
            onSurfaceVariant = WltOnSurfaceVariantLight,
            error = WltErrorLight,
            onError = WltOnErrorLight,
            outline = WltOutlineLight,
        )
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            // Set the status bar icon appearance to match the theme. We do NOT
            // set statusBarColor — it's deprecated and the system bar is
            // transparent under edge-to-edge anyway.
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = WltTypography,
        content = content,
    )
}
