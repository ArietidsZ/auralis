package com.dialect.interpreter.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import com.dialect.interpreter.ui.theme.AppColors
import kotlin.math.sin

/**
 * Vertical bar waveform driven by live capture amplitude. With
 * [reduceMotion] (system animator scale 0) the decorative idle/phase
 * animation is skipped; bars still react to real amplitude (spec U01).
 */
@Composable
fun WaveformVisualizer(
    amplitude: Float,
    isActive: Boolean,
    modifier: Modifier = Modifier,
    reduceMotion: Boolean = false,
) {
    val barCount = 7
    val accent = AppColors.accent()
    val accentFaded = accent.copy(alpha = 0.35f)

    val breathPhase = if (isActive && !reduceMotion) {
        val infiniteTransition = rememberInfiniteTransition(label = "waveActive")
        val phase by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 2f * Math.PI.toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(1800, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "phase"
        )
        phase
    } else {
        0f
    }

    Row(
        modifier = modifier.height(40.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(barCount) { index ->
            val normalizedIndex = index.toFloat() / (barCount - 1)

            // Target fraction: active amplitude + per-bar offset, or idle resting bar
            val target = if (isActive) {
                val offset = sin(normalizedIndex * Math.PI.toFloat() + breathPhase * 1.5f)
                (amplitude * (0.5f + 0.5f * offset)).coerceIn(0.15f, 1f)
            } else {
                0.16f
            }

            val fraction = target.coerceIn(0.08f, 1f)

            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight(fraction)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(accent, accentFaded)
                        )
                    )
            )
        }
    }
}
