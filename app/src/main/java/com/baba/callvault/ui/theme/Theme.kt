/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Brand-semantic colors that Material's ColorScheme has no slot for (success/warning/info).
 * Access in composables via [LocalCvBrand].current. The primary accent (teal) and the coral
 * highlight are also exposed here for convenience, though they equal colorScheme.primary/secondary.
 */
data class CvBrandColors(
    val success: Color,
    val warning: Color,
    val info: Color,
    val accent: Color,
    val highlight: Color,
)

val LocalCvBrand = staticCompositionLocalOf {
    CvBrandColors(success = Success, warning = Warning, info = Sky, accent = Teal, highlight = Coral)
}

private val DarkColors = darkColorScheme(
    primary = Teal,
    onPrimary = Color(0xFF00322C),
    primaryContainer = TealDeep,
    onPrimaryContainer = TealBright,
    secondary = Coral,
    onSecondary = Color(0xFF4A0E18),
    secondaryContainer = CoralDeep,
    onSecondaryContainer = CoralCtrLight,
    tertiary = Sky,
    onTertiary = Color(0xFF06283A),
    tertiaryContainer = SkyDeep,
    onTertiaryContainer = Color(0xFFCDE9FB),
    background = NavyBg,
    onBackground = Ice,
    surface = NavySurface,
    onSurface = Ice,
    surfaceVariant = NavySurfaceHi,
    onSurfaceVariant = IceMuted,
    surfaceContainerLowest = NavyBg,
    surfaceContainerLow = NavySurfaceLow,
    surfaceContainer = NavySurface,
    surfaceContainerHigh = NavySurfaceHi,
    surfaceContainerHighest = NavySurfaceHi2,
    outline = NavyOutline,
    outlineVariant = NavyOutlineDim,
    error = ErrorDark,
    onError = OnErrorDark,
    errorContainer = ErrorCtrDark,
    onErrorContainer = OnErrorCtrDark,
    scrim = Color(0xCC050912),
)

private val LightColors = lightColorScheme(
    primary = TealOnLight,
    onPrimary = White,
    primaryContainer = TealCtrLight,
    onPrimaryContainer = Color(0xFF00322C),
    secondary = CoralOnLight,
    onSecondary = White,
    secondaryContainer = CoralCtrLight,
    onSecondaryContainer = Color(0xFF40121B),
    tertiary = Color(0xFF0C6E9E),
    onTertiary = White,
    background = IceBg,
    onBackground = DeepNavyText,
    surface = LightSurfaceCv,
    onSurface = DeepNavyText,
    surfaceVariant = LightSurfaceVar,
    onSurfaceVariant = SlateMuted,
    surfaceContainerLowest = White,
    surfaceContainerLow = Color(0xFFEFF3F9),
    surfaceContainer = Color(0xFFE9EFF6),
    surfaceContainerHigh = Color(0xFFE2EAF3),
    surfaceContainerHighest = Color(0xFFDCE5F0),
    outline = LightOutline,
    outlineVariant = LightOutlineDim,
    error = ErrorLight,
    onError = White,
    errorContainer = Color(0xFFFFDAD7),
    onErrorContainer = Color(0xFF410005),
)

private val DarkBrand = CvBrandColors(success = Success, warning = Warning, info = Sky, accent = Teal, highlight = Coral)
private val LightBrand = CvBrandColors(success = SuccessOnLight, warning = WarningOnLight, info = Color(0xFF0C6E9E), accent = TealOnLight, highlight = CoralOnLight)

@Composable
fun CallVaultTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Brand colors are the identity; Material You (dynamic) is opt-in only.
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    val brand = if (darkTheme) DarkBrand else LightBrand

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val lightBars = colorScheme.background.luminance() > 0.5f
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = lightBars
                isAppearanceLightNavigationBars = lightBars
            }
        }
    }

    CompositionLocalProvider(LocalCvBrand provides brand) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = CvShapes,
            content = content,
        )
    }
}
