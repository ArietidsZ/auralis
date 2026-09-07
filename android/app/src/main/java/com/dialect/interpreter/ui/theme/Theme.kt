package com.dialect.interpreter.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

// ======================================================================
//  Auralis design tokens (spec 03 U01). Values verified for WCAG AA:
//   #17232A on #F6F8F7 = 15.0:1   #216B62 on #F6F8F7 = 5.9:1
//   #FFFFFF on #216B62 = 6.3:1    #F6F8F7 on #142128 = 15.4:1
//   #63B3A4 on #142128 = 6.7:1    (dark-mode accent, derived for contrast)
// ======================================================================

// Light mode — light conversation area
val LightBg = Color(0xFFF6F8F7)
val LightSurface = Color(0xFFFFFFFF)
val LightText = Color(0xFF17232A)
val LightTextSecondary = Color(0xFF5A6B66)

// Dark control area (light mode) / full dark surface (dark mode)
val ControlDark = Color(0xFF142128)
val DarkBg = Color(0xFF0F191D)
val DarkText = Color(0xFFF6F8F7)
val DarkTextSecondary = Color(0xFF9FB3AD)

// Accent
val AccentLight = Color(0xFF216B62)
val AccentDarkMode = Color(0xFF63B3A4)

// Functional (semantic)
val RecordRed = Color(0xFFC23A32)        // white icon on it = 5.3:1
val RecordRedOnDark = Color(0xFFE8907E)
val ErrorLight = Color(0xFFB3261E)
val ErrorOnDark = Color(0xFFE8907E)
val DraftAmberLight = Color(0xFF8A5A00)  // "模型尚未验证"
val DraftAmberOnDark = Color(0xFFE8B45A)
val SuccessLight = Color(0xFF1B6E4A)
val SuccessOnDark = Color(0xFF7BD3A8)

// Separators
val SeparatorLight = Color(0x1F17232A)
val SeparatorDark = Color(0x33F6F8F7)

// Shape / spacing rhythm (8dp grid)
val RadiusBubble = 18.dp
val RadiusCard = 16.dp
val RadiusPill = 26.dp

// ======================================================================
//  Adaptive accessors
// ======================================================================

object AppColors {
    @Composable fun accent() = if (isSystemInDarkTheme()) AccentDarkMode else AccentLight
    @Composable fun bg() = if (isSystemInDarkTheme()) DarkBg else LightBg
    @Composable fun surface() = if (isSystemInDarkTheme()) ControlDark else LightSurface

    /** The always-dark control area at the top of the main screen. */
    @Composable fun controlSurface() = ControlDark

    @Composable fun onControl() = DarkText
    @Composable fun onControlSecondary() = DarkTextSecondary
    @Composable fun text() = if (isSystemInDarkTheme()) DarkText else LightText
    @Composable fun textSecondary() = if (isSystemInDarkTheme()) DarkTextSecondary else LightTextSecondary
    @Composable fun bubbleSource() = if (isSystemInDarkTheme()) Color(0xFF1C2F35) else Color(0xFFECF1EF)
    @Composable fun bubbleTarget() = if (isSystemInDarkTheme()) Color(0xFF17332E) else Color(0xFFDCEEE9)
    @Composable fun error() = if (isSystemInDarkTheme()) ErrorOnDark else ErrorLight
    @Composable fun recordRed() = if (isSystemInDarkTheme()) RecordRedOnDark else RecordRed
    @Composable fun draftAmber() = if (isSystemInDarkTheme()) DraftAmberOnDark else DraftAmberLight
    @Composable fun success() = if (isSystemInDarkTheme()) SuccessOnDark else SuccessLight
    @Composable fun separator() = if (isSystemInDarkTheme()) SeparatorDark else SeparatorLight
}

// ======================================================================
//  Material 3 schemes
// ======================================================================

private val DarkColorScheme: ColorScheme = darkColorScheme(
    primary = AccentDarkMode,
    onPrimary = Color(0xFF0F191D),
    secondary = AccentDarkMode,
    background = DarkBg,
    surface = ControlDark,
    surfaceVariant = Color(0xFF1C2F35),
    onBackground = DarkText,
    onSurface = DarkText,
    onSurfaceVariant = DarkTextSecondary,
    error = ErrorOnDark,
    outline = SeparatorDark,
)

private val LightColorScheme: ColorScheme = lightColorScheme(
    primary = AccentLight,
    onPrimary = Color.White,
    secondary = AccentLight,
    background = LightBg,
    surface = LightSurface,
    surfaceVariant = Color(0xFFECF1EF),
    onBackground = LightText,
    onSurface = LightText,
    onSurfaceVariant = LightTextSecondary,
    error = ErrorLight,
    outline = SeparatorLight,
)

// ======================================================================
//  Theme
// ======================================================================

@Composable
fun DialectInterpreterTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
