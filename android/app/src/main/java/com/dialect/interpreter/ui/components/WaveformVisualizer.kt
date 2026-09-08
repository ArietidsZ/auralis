package com.dialect.interpreter.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import com.dialect.interpreter.ui.theme.AppColors
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.PI

/**
 * Vertical bar waveform driven by the live capture RMS (spec motion table:
 * "Input level"). One drawing surface: bar heights are computed in the draw
 * phase from a single shared level spring — no per-frame layout and no
 * independent per-bar springs. The level follows the real microphone RMS
 * through a bounded logarithmic map ([levelFraction]) that keeps quiet
 * speech readable; non-finite input falls back to the static rest outline.
 * Under [reduceMotion] the level snaps instead of settling, and nothing here
 * ever schedules periodic work — redraws follow real amplitude emissions.
 */
@Composable
fun WaveformVisualizer(
    amplitude: Float,
    isActive: Boolean,
    modifier: Modifier = Modifier,
    reduceMotion: Boolean = false,
    accent: Color = AppColors.accent(),
) {
    val accentFaded = accent.copy(alpha = 0.35f)
    val level = animateFloatAsState(
        targetValue = levelFraction(amplitude, isActive),
        animationSpec = if (reduceMotion) {
            snap()
        } else {
            spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)
        },
        label = "waveformLevel",
    )
    Canvas(modifier = modifier) {
        drawWaveBars(level, accent, accentFaded)
    }
}

private fun DrawScope.drawWaveBars(level: State<Float>, accent: Color, accentFaded: Color) {
    val barWidth = 4.dp.toPx()
    val spacing = 4.dp.toPx()
    val total = WAVE_BAR_COUNT * barWidth + (WAVE_BAR_COUNT - 1) * spacing
    val corner = CornerRadius(2.dp.toPx())
    // Deferred state read: the value is consumed here in the draw phase, so
    // settling animation frames invalidate only this canvas, not composition.
    val current = level.value
    var x = (size.width - total) / 2f
    for (index in 0 until WAVE_BAR_COUNT) {
        val height = (barHeightFraction(current, index) * size.height).coerceAtLeast(barWidth)
        drawRoundRect(
            brush = Brush.verticalGradient(
                colors = listOf(accent, accentFaded),
                startY = size.height - height,
                endY = size.height,
            ),
            topLeft = Offset(x, size.height - height),
            size = Size(barWidth, height),
            cornerRadius = corner,
        )
        x += barWidth + spacing
    }
}

// ---------------------------------------------------------------------------
// Level mapping (pure; covered by WaveformLevelMappingTest)

internal const val WAVE_BAR_COUNT = 7

/** Silence / not-listening outline as a fraction of the full height. */
internal const val WAVE_REST_LEVEL = 0.12f

/**
 * Perceptual window edges for the logarithmic map: ~0.005 RMS is near the
 * noise floor, ~0.5 RMS is loud speech. Quiet conversation (0.01–0.1 RMS)
 * spreads across the lower two-thirds of the bars instead of collapsing
 * into the old flat 0.15 floor.
 */
internal const val WAVE_QUIET_FLOOR = 0.005f
internal const val WAVE_LOUD_CEIL = 0.5f

/** Hard visual floor so the capsules stay visible at rest. */
internal const val WAVE_MIN_FRACTION = 0.06f

/**
 * Maps a real RMS capture level to the shared bar level in [0, 1]:
 * `log10` compression between [WAVE_QUIET_FLOOR] and [WAVE_LOUD_CEIL].
 * Inactive or non-finite input returns [WAVE_REST_LEVEL] (static decoration,
 * never a fabricated activity signal); out-of-range levels clamp.
 */
internal fun levelFraction(amplitude: Float, isActive: Boolean): Float {
    if (!isActive || !amplitude.isFinite()) return WAVE_REST_LEVEL
    val span = log10(WAVE_LOUD_CEIL) - log10(WAVE_QUIET_FLOOR)
    val t = (log10(amplitude) - log10(WAVE_QUIET_FLOOR)) / span
    return if (t.isNaN()) 0f else t.coerceIn(0f, 1f)
}

/** Static center-weighted bar window; a fixed shape, no time phase. */
internal fun barWindow(index: Int, barCount: Int = WAVE_BAR_COUNT): Float {
    val divisor = (barCount - 1).coerceAtLeast(1)
    return 0.55f + 0.45f * sin(PI.toFloat() * index / divisor)
}

/**
 * Height fraction of bar [index] for a shared [level] in [0, 1]: the static
 * window scaled between the rest outline and full height, bounded below by
 * [WAVE_MIN_FRACTION] so the bars never disappear.
 */
internal fun barHeightFraction(level: Float, index: Int, barCount: Int = WAVE_BAR_COUNT): Float =
    (barWindow(index, barCount) * (WAVE_REST_LEVEL + level * (1f - WAVE_REST_LEVEL)))
        .coerceIn(WAVE_MIN_FRACTION, 1f)
