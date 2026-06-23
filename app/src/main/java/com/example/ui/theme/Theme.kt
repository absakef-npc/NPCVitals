package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    secondary = DarkSecondary,
    tertiary = DarkGreen,
    background = DarkBackground,
    surface = DarkSurface,
    onPrimary = DarkBackground,
    onSecondary = DarkBackground,
    onTertiary = DarkBackground,
    onBackground = TextLight,
    onSurface = TextLight
)

private val LightColorScheme = lightColorScheme(
    primary = NpcBluePrimary,
    secondary = NpcBlueSecondary,
    tertiary = NationalGreen,
    background = SoftBackground,
    surface = SoftSurface,
    onPrimary = SoftSurface,
    onSecondary = SoftSurface,
    onTertiary = SoftSurface,
    onBackground = TextDark,
    onSurface = TextDark
)

private val HighContrastLightColorScheme = lightColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFF002266),
    secondary = androidx.compose.ui.graphics.Color(0xFF003399),
    tertiary = androidx.compose.ui.graphics.Color(0xFF004400),
    background = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
    surface = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
    onPrimary = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
    onSecondary = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
    onTertiary = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
    onBackground = androidx.compose.ui.graphics.Color(0xFF000000),
    onSurface = androidx.compose.ui.graphics.Color(0xFF000000),
    error = androidx.compose.ui.graphics.Color(0xFF990000),
    onError = androidx.compose.ui.graphics.Color(0xFFFFFFFF)
)

private val HighContrastDarkColorScheme = darkColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFF99CCFF),
    secondary = androidx.compose.ui.graphics.Color(0xFFCCE5FF),
    tertiary = androidx.compose.ui.graphics.Color(0xFF99FF99),
    background = androidx.compose.ui.graphics.Color(0xFF000000),
    surface = androidx.compose.ui.graphics.Color(0xFF121212),
    onPrimary = androidx.compose.ui.graphics.Color(0xFF000000),
    onSecondary = androidx.compose.ui.graphics.Color(0xFF000000),
    onTertiary = androidx.compose.ui.graphics.Color(0xFF000000),
    onBackground = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
    onSurface = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
    error = androidx.compose.ui.graphics.Color(0xFFFF9999),
    onError = androidx.compose.ui.graphics.Color(0xFF000000)
)

private fun applyColorBlindFilter(
    scheme: androidx.compose.material3.ColorScheme,
    filter: String
): androidx.compose.material3.ColorScheme {
    return when (filter) {
        "Monochromacy" -> {
            scheme.copy(
                primary = androidx.compose.ui.graphics.Color(0xFF333333),
                secondary = androidx.compose.ui.graphics.Color(0xFF555555),
                tertiary = androidx.compose.ui.graphics.Color(0xFF111111),
                background = if (scheme.background == androidx.compose.ui.graphics.Color(0xFF0F172A) || scheme.background == androidx.compose.ui.graphics.Color(0xFF000000)) androidx.compose.ui.graphics.Color(0xFF000000) else androidx.compose.ui.graphics.Color(0xFFFFFFFF),
                surface = if (scheme.background == androidx.compose.ui.graphics.Color(0xFF0F172A) || scheme.background == androidx.compose.ui.graphics.Color(0xFF000000)) androidx.compose.ui.graphics.Color(0xFF121212) else androidx.compose.ui.graphics.Color(0xFFFAFAFA),
                onBackground = if (scheme.background == androidx.compose.ui.graphics.Color(0xFF0F172A) || scheme.background == androidx.compose.ui.graphics.Color(0xFF000000)) androidx.compose.ui.graphics.Color(0xFFFFFFFF) else androidx.compose.ui.graphics.Color(0xFF000000),
                onSurface = if (scheme.background == androidx.compose.ui.graphics.Color(0xFF0F172A) || scheme.background == androidx.compose.ui.graphics.Color(0xFF000000)) androidx.compose.ui.graphics.Color(0xFFFFFFFF) else androidx.compose.ui.graphics.Color(0xFF000000)
            )
        }
        "Protanopia", "Deuteranopia" -> {
            scheme.copy(
                primary = androidx.compose.ui.graphics.Color(0xFF0055D4),
                secondary = androidx.compose.ui.graphics.Color(0xFFE69F00),
                tertiary = androidx.compose.ui.graphics.Color(0xFF56B4E9),
                error = androidx.compose.ui.graphics.Color(0xFFD55E00)
            )
        }
        "Tritanopia" -> {
            scheme.copy(
                primary = androidx.compose.ui.graphics.Color(0xFFE65100),
                secondary = androidx.compose.ui.graphics.Color(0xFF00E5FF),
                tertiary = androidx.compose.ui.graphics.Color(0xFF00B0FF),
                error = androidx.compose.ui.graphics.Color(0xFFD50000)
            )
        }
        else -> scheme
    }
}

@Composable
fun NPCVitalTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    isHighContrast: Boolean = false,
    colorBlindFilter: String = "None",
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    var colorScheme = when {
        isHighContrast -> if (darkTheme) HighContrastDarkColorScheme else HighContrastLightColorScheme
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    if (colorBlindFilter != "None") {
        colorScheme = applyColorBlindFilter(colorScheme, colorBlindFilter)
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
