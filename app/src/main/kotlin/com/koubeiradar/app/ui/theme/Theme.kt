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

// Tonal palette generated from the brand seed #FF6A00 (Material "vibrant" scheme).
private val LightColors = lightColorScheme(
    primary = Color(0xFFA14000), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDBCC), onPrimaryContainer = Color(0xFF7B2F00),
    secondary = Color(0xFF7D5635), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDCC2), onSecondaryContainer = Color(0xFF623F20),
    tertiary = Color(0xFF7D571D), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDDB4), onTertiaryContainer = Color(0xFF624005),
    error = Color(0xFFBA1A1A), onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF93000A),
    background = Color(0xFFFFF8F6), onBackground = Color(0xFF261812),
    surface = Color(0xFFFFF8F6), onSurface = Color(0xFF261812),
    surfaceVariant = Color(0xFFFCDCCE), onSurfaceVariant = Color(0xFF584238),
    outline = Color(0xFF8B7266), outlineVariant = Color(0xFFDFC0B3),
    inverseSurface = Color(0xFF3D2D25), inverseOnSurface = Color(0xFFFFEDE6), inversePrimary = Color(0xFFFFB694),
    surfaceTint = Color(0xFFA14000),
    surfaceDim = Color(0xFFF0D4C9), surfaceBright = Color(0xFFFFF8F6),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFFFF1EB),
    surfaceContainer = Color(0xFFFFEAE1), surfaceContainerHigh = Color(0xFFFEE3D7),
    surfaceContainerHighest = Color(0xFFF8DDD2),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB694), onPrimary = Color(0xFF571F00),
    primaryContainer = Color(0xFF7B2F00), onPrimaryContainer = Color(0xFFFFDBCC),
    secondary = Color(0xFFEFBC94), onSecondary = Color(0xFF48290C),
    secondaryContainer = Color(0xFF623F20), onSecondaryContainer = Color(0xFFFFDCC2),
    tertiary = Color(0xFFF1BE7A), onTertiary = Color(0xFF452B00),
    tertiaryContainer = Color(0xFF624005), onTertiaryContainer = Color(0xFFFFDDB4),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF1D100A), onBackground = Color(0xFFF8DDD2),
    surface = Color(0xFF1D100A), onSurface = Color(0xFFF8DDD2),
    surfaceVariant = Color(0xFF584238), onSurfaceVariant = Color(0xFFDFC0B3),
    outline = Color(0xFFA68B7F), outlineVariant = Color(0xFF584238),
    inverseSurface = Color(0xFFF8DDD2), inverseOnSurface = Color(0xFF3D2D25), inversePrimary = Color(0xFFA14000),
    surfaceTint = Color(0xFFFFB694),
    surfaceDim = Color(0xFF1D100A), surfaceBright = Color(0xFF47352E),
    surfaceContainerLowest = Color(0xFF180B06), surfaceContainerLow = Color(0xFF261812),
    surfaceContainer = Color(0xFF2B1C15), surfaceContainerHigh = Color(0xFF36261F),
    surfaceContainerHighest = Color(0xFF42312A),
)

/** Semantic colours Material 3 has no roles for: "good" and "caution". They stay fixed when dynamic colour is on. */
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
    success = Color(0xFF376934), successContainer = Color(0xFFB8F1AD), onSuccessContainer = Color(0xFF002203),
    warning = Color(0xFF825500), warningContainer = Color(0xFFFFDDB2), onWarningContainer = Color(0xFF291800),
)

private val DarkKoubeiColors = KoubeiColors(
    success = Color(0xFF9CD593), successContainer = Color(0xFF1E511E), onSuccessContainer = Color(0xFFB8F1AD),
    warning = Color(0xFFFFB94E), warningContainer = Color(0xFF624000), onWarningContainer = Color(0xFFFFDDB2),
)

private val LocalKoubeiColors = staticCompositionLocalOf { LightKoubeiColors }

val MaterialTheme.koubeiColors: KoubeiColors
    @Composable
    @ReadOnlyComposable
    get() = LocalKoubeiColors.current

@Composable
fun KoubeiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
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
