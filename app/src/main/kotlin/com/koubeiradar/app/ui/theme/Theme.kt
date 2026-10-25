package com.koubeiradar.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Tonal palette generated from the deep-sea brand seed #2676ED.
private val LightColors = lightColorScheme(
    primary = Color(0xFF1B63D6), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD3E6FF), onPrimaryContainer = Color(0xFF062251),
    inversePrimary = Color(0xFF9CC9FF),
    secondary = Color(0xFF4A5F86), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDCE6F7), onSecondaryContainer = Color(0xFF0E2A4D),
    tertiary = Color(0xFF8A6A20), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF7E8C4), onTertiaryContainer = Color(0xFF3A2A00),
    error = Color(0xFFBA1A1A), onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF93000A),
    background = Color(0xFFF7FBFE), onBackground = Color(0xFF0D1B2E),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF0D1B2E),
    surfaceVariant = Color(0xFFE3EDF7), onSurfaceVariant = Color(0xFF44566E),
    outline = Color(0xFF7A8CA6), outlineVariant = Color(0xFFC7D6E6),
    inverseSurface = Color(0xFF2B3A4D), inverseOnSurface = Color(0xFFEAF1F8),
    surfaceTint = Color(0xFF1B63D6),
    surfaceDim = Color(0xFFDCE6F0), surfaceBright = Color(0xFFFFFFFF),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFFAFCFE),
    surfaceContainer = Color(0xFFF0F6FC), surfaceContainerHigh = Color(0xFFE8F1FA),
    surfaceContainerHighest = Color(0xFFDFEAF5),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9CC9FF), onPrimary = Color(0xFF002F66),
    primaryContainer = Color(0xFF0E3D7A), onPrimaryContainer = Color(0xFFD3E6FF),
    inversePrimary = Color(0xFF1B63D6),
    secondary = Color(0xFFB3C6E0), onSecondary = Color(0xFF1C3048),
    secondaryContainer = Color(0xFF33475F), onSecondaryContainer = Color(0xFFDCE6F7),
    tertiary = Color(0xFFEBD69E), onTertiary = Color(0xFF3A2A00),
    tertiaryContainer = Color(0xFF5A4410), onTertiaryContainer = Color(0xFFF7E8C4),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF061224), onBackground = Color(0xFFD8E6F5),
    surface = Color(0xFF0B1A30), onSurface = Color(0xFFD8E6F5),
    surfaceVariant = Color(0xFF1B2C46), onSurfaceVariant = Color(0xFFAFC2D8),
    outline = Color(0xFF8CA0BC), outlineVariant = Color(0xFF3A4C66),
    inverseSurface = Color(0xFFD8E6F5), inverseOnSurface = Color(0xFF0B1A30),
    surfaceTint = Color(0xFF9CC9FF),
    surfaceDim = Color(0xFF061224), surfaceBright = Color(0xFF22354F),
    surfaceContainerLowest = Color(0xFF040D1A), surfaceContainerLow = Color(0xFF0A1728),
    surfaceContainer = Color(0xFF102340), surfaceContainerHigh = Color(0xFF16304F),
    surfaceContainerHighest = Color(0xFF1E3B5E),
)

/** Semantic colours Material 3 has no roles for: "good" (muted ink green) and "caution" (warm gold). They stay fixed when dynamic colour is on. */
@Immutable
class KoubeiColors(
    val success: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
)

private val LightKoubeiColors = KoubeiColors(
    success = Color(0xFF2E7D46), successContainer = Color(0xFFC3EFCD), onSuccessContainer = Color(0xFF00210B),
    warning = Color(0xFF8A6A20), warningContainer = Color(0xFFF7E8C4), onWarningContainer = Color(0xFF3A2A00),
)

private val DarkKoubeiColors = KoubeiColors(
    success = Color(0xFF9CD9AE), successContainer = Color(0xFF14532B), onSuccessContainer = Color(0xFFC3EFCD),
    warning = Color(0xFFEBD69E), warningContainer = Color(0xFF5A4410), onWarningContainer = Color(0xFFF7E8C4),
)

private val LocalKoubeiColors = staticCompositionLocalOf { LightKoubeiColors }

val MaterialTheme.koubeiColors: KoubeiColors
    @Composable
    @ReadOnlyComposable
    get() = LocalKoubeiColors.current

@Composable
fun KoubeiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Brand colours come first: with true, Android 12+ takes the wallpaper palette and LightColors/DarkColors are ignored.
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme: ColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColors
        else -> LightColors
    }
    CompositionLocalProvider(LocalKoubeiColors provides if (darkTheme) DarkKoubeiColors else LightKoubeiColors) {
        MaterialTheme(colorScheme = colorScheme, content = content)
    }
}
