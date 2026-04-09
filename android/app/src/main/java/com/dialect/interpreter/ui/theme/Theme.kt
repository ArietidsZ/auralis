package com.dialect.interpreter.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

// ======================================================
//  Apple-inspired adaptive color system
// ======================================================

// Accent — System Indigo
val AccentLight = Color(0xFF5856D6)
val AccentDark = Color(0xFF7D7AFF)

// Backgrounds
val BgPrimary = Color(0xFFF2F2F7)          // iOS system grouped background
val BgPrimaryDark = Color(0xFF000000)
val BgElevated = Color(0xFFFFFFFF)
val BgElevatedDark = Color(0xFF1C1C1E)
val BgSecondary = Color(0xFFF2F2F7)
val BgSecondaryDark = Color(0xFF2C2C2E)

// Text
val TextPrimary = Color(0xFF1C1C1E)
val TextPrimaryDark = Color(0xFFF2F2F7)
val TextSecondary = Color(0xFF8E8E93)      // Same in both modes
val TextTertiary = Color(0xFFC7C7CC)
val TextTertiaryDark = Color(0xFF48484A)

// Chat bubbles
val BubbleSource = Color(0xFFF2F2F7)
val BubbleSourceDark = Color(0xFF1C1C1E)
val BubbleTarget = Color(0x145856D6)       // Indigo 8%
val BubbleTargetDark = Color(0x1F7D7AFF)   // Indigo 12%

// Functional (semantic — same across modes)
val RecordingRed = Color(0xFFFF3B30)
val ProcessingAmber = Color(0xFFFF9500)
val SuccessGreen = Color(0xFF34C759)
val ErrorRed = Color(0xFFFF3B30)
val LiveGreen = Color(0xFF30D158)

// Separators
val SeparatorLight = Color(0x4D3C3C43)     // iOS separator
val SeparatorDarkColor = Color(0x4D545458)

// Frosted glass / material
val GlassFill = Color.White.copy(alpha = 0.08f)
val GlassBorder = Color.White.copy(alpha = 0.12f)

// ======================================================
//  Design Tokens
// ======================================================
val RadiusBubble = 20.dp
val RadiusCard = 16.dp
val RadiusPill = 28.dp
val RadiusSmall = 10.dp

// ======================================================
//  Adaptive accessors  (consumed by screens/components)
// ======================================================

object AppColors {
    @Composable fun accent() = if (isSystemInDarkTheme()) AccentDark else AccentLight
    @Composable fun bg() = if (isSystemInDarkTheme()) BgPrimaryDark else BgPrimary
    @Composable fun surface() = if (isSystemInDarkTheme()) BgElevatedDark else BgElevated
    @Composable fun surfaceSecondary() = if (isSystemInDarkTheme()) BgSecondaryDark else BgSecondary
    @Composable fun text() = if (isSystemInDarkTheme()) TextPrimaryDark else TextPrimary
    @Composable fun textSecondary() = TextSecondary
    @Composable fun textTertiary() = if (isSystemInDarkTheme()) TextTertiaryDark else TextTertiary
    @Composable fun bubbleSource() = if (isSystemInDarkTheme()) BubbleSourceDark else BubbleSource
    @Composable fun bubbleTarget() = if (isSystemInDarkTheme()) BubbleTargetDark else BubbleTarget
    @Composable fun separator() = if (isSystemInDarkTheme()) SeparatorDarkColor else SeparatorLight
}

// ======================================================
//  Material 3 Color Schemes
// ======================================================

private val DarkColorScheme = darkColorScheme(
    primary = AccentDark,
    onPrimary = Color.White,
    secondary = AccentDark.copy(alpha = 0.7f),
    tertiary = LiveGreen,
    background = BgPrimaryDark,
    surface = BgElevatedDark,
    surfaceVariant = BgSecondaryDark,
    onBackground = TextPrimaryDark,
    onSurface = TextPrimaryDark,
    onSurfaceVariant = TextSecondary,
    error = ErrorRed,
    outline = SeparatorDarkColor,
)

private val LightColorScheme = lightColorScheme(
    primary = AccentLight,
    onPrimary = Color.White,
    secondary = AccentLight.copy(alpha = 0.7f),
    tertiary = LiveGreen,
    background = BgPrimary,
    surface = BgElevated,
    surfaceVariant = BgSecondary,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    onSurfaceVariant = TextSecondary,
    error = ErrorRed,
    outline = SeparatorLight,
)

// ======================================================
//  Typography — SF-style system sans-serif
// ======================================================

val AppTypography = Typography(
    displaySmall = Typography().displaySmall.copy(
        fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp
    ),
    headlineMedium = Typography().headlineMedium.copy(
        fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp
    ),
    titleLarge = Typography().titleLarge.copy(
        fontWeight = FontWeight.SemiBold, fontSize = 20.sp, letterSpacing = 0.sp
    ),
    titleMedium = Typography().titleMedium.copy(
        fontWeight = FontWeight.SemiBold, fontSize = 17.sp, letterSpacing = (-0.2).sp
    ),
    titleSmall = Typography().titleSmall.copy(
        fontWeight = FontWeight.Medium, fontSize = 15.sp
    ),
    bodyLarge = Typography().bodyLarge.copy(fontSize = 17.sp, lineHeight = 24.sp),
    bodyMedium = Typography().bodyMedium.copy(fontSize = 15.sp, lineHeight = 20.sp),
    bodySmall = Typography().bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = Typography().labelLarge.copy(fontWeight = FontWeight.Medium, fontSize = 15.sp),
    labelMedium = Typography().labelMedium.copy(fontSize = 12.sp, letterSpacing = 0.sp),
    labelSmall = Typography().labelSmall.copy(fontSize = 11.sp, letterSpacing = 0.sp),
)

// ======================================================
//  Theme Composable
// ======================================================

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
            // Transparent bars for edge-to-edge
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            val ic = WindowCompat.getInsetsController(window, view)
            ic.isAppearanceLightStatusBars = !darkTheme
            ic.isAppearanceLightNavigationBars = !darkTheme
        }
    }
    MaterialTheme(colorScheme = colorScheme, typography = AppTypography, content = content)
}
