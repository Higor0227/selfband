// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Selfband identity (Phase 7): a Material 3 scheme from the coral of the icon (seed #FF7A59)
// over warm neutrals. Nothing else in the app should hardcode colors.

private val LightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF9C4323),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDBD0),
    onPrimaryContainer = Color(0xFF3A0B00),
    secondary = Color(0xFF77574C),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDBD0),
    onSecondaryContainer = Color(0xFF2C160D),
    tertiary = Color(0xFF6B5E2F),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF5E1A7),
    onTertiaryContainer = Color(0xFF231B00),
    background = Color(0xFFFFF8F6),
    onBackground = Color(0xFF231917),
    surface = Color(0xFFFFF8F6),
    onSurface = Color(0xFF231917),
    surfaceVariant = Color(0xFFF5DED8),
    onSurfaceVariant = Color(0xFF53433F),
    outline = Color(0xFF85736E),
    outlineVariant = Color(0xFFD8C2BC),
    inverseSurface = Color(0xFF392E2B),
    inverseOnSurface = Color(0xFFFFEDE8),
    inversePrimary = Color(0xFFFFB59E),
    surfaceTint = Color(0xFF9C4323),
    surfaceBright = Color(0xFFFFF8F6),
    surfaceDim = Color(0xFFE8D6D2),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFF1ED),
    surfaceContainer = Color(0xFFFCEAE5),
    surfaceContainerHigh = Color(0xFFF7E4DF),
    surfaceContainerHighest = Color(0xFFF1DFDA),
)

private val DarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFFFFB59E),
    onPrimary = Color(0xFF5E1700),
    primaryContainer = Color(0xFF7E2C10),
    onPrimaryContainer = Color(0xFFFFDBD0),
    secondary = Color(0xFFE7BDB1),
    onSecondary = Color(0xFF442A21),
    secondaryContainer = Color(0xFF5D4036),
    onSecondaryContainer = Color(0xFFFFDBD0),
    tertiary = Color(0xFFD8C58D),
    onTertiary = Color(0xFF3B2F05),
    tertiaryContainer = Color(0xFF53461A),
    onTertiaryContainer = Color(0xFFF5E1A7),
    background = Color(0xFF1A110F),
    onBackground = Color(0xFFF1DFDA),
    surface = Color(0xFF1A110F),
    onSurface = Color(0xFFF1DFDA),
    surfaceVariant = Color(0xFF53433F),
    onSurfaceVariant = Color(0xFFD8C2BC),
    outline = Color(0xFFA08C87),
    outlineVariant = Color(0xFF53433F),
    inverseSurface = Color(0xFFF1DFDA),
    inverseOnSurface = Color(0xFF392E2B),
    inversePrimary = Color(0xFF9C4323),
    surfaceTint = Color(0xFFFFB59E),
    surfaceBright = Color(0xFF423734),
    surfaceDim = Color(0xFF1A110F),
    surfaceContainerLowest = Color(0xFF140C0A),
    surfaceContainerLow = Color(0xFF231917),
    surfaceContainer = Color(0xFF271D1B),
    surfaceContainerHigh = Color(0xFF322825),
    surfaceContainerHighest = Color(0xFF3D322F),
)

/** Whether this device can take its colors from the wallpaper (Android 12+). */
val supportsDynamicColor: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * App theme: the Selfband palette, or the wallpaper colors (Material You) when the user turns
 * that on in Settings and the device supports it.
 */
@Composable
fun SelfbandTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = when {
        dynamicColor && supportsDynamicColor -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
