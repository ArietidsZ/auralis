package com.dialect.interpreter.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import com.dialect.interpreter.ui.theme.AppColors
import kotlin.math.sin

/**
 * Apple-style vertical bar waveform visualizer.
 * 7 rounded capsule bars animate height with spring physics,
 * creating an organic, fluid feel.
 */
@Composable
fun WaveformVisualizer(
    amplitude: Float,
    isActive: Boolean,
    modifier: Modifier = Modifier
) {
    val barCount = 7
    val accent = AppColors.accent()
    val accentFaded = accent.copy(alpha = 0.35f)

    val breathPhase = if (isActive) {
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

            // Target fraction: active amplitude + per-bar offset, or idle breathing
            val target = if (isActive) {
                val offset = sin(normalizedIndex * Math.PI.toFloat() + breathPhase * 1.5f)
                (amplitude * (0.5f + 0.5f * offset)).coerceIn(0.15f, 1f)
            } else {
                0.16f
            }

            val animatedFraction by animateFloatAsState(
                targetValue = target,
                animationSpec = spring(
                    dampingRatio = 0.45f,
                    stiffness = Spring.StiffnessLow
                ),
                label = "bar$index"
            )

            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight(animatedFraction.coerceIn(0.08f, 1f))
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
