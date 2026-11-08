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

// Placeholder palette until the visual identity is decided (SPEC section 4, Phase 7).
// Change the seed colors here; nothing else should hardcode colors.
private val SeedPrimary = Color(0xFF5B5FC7)
private val SeedSecondary = Color(0xFF5E5D72)
private val SeedTertiary = Color(0xFF7A5368)

private val LightColors: ColorScheme = lightColorScheme(
    primary = SeedPrimary,
    secondary = SeedSecondary,
    tertiary = SeedTertiary,
)

private val DarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFFBFC2FF),
    secondary = Color(0xFFC7C4DD),
    tertiary = Color(0xFFE9B9D2),
)

/**
 * App theme. Uses Material You dynamic color on Android 12+ and the placeholder palette
 * otherwise.
 */
@Composable
fun SelfbandTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
