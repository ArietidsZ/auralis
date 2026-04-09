package com.dialect.interpreter.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.dp
import com.dialect.interpreter.ui.theme.*

enum class InterpretStatus {
    IDLE, CONNECTING, LISTENING, PROCESSING, SPEAKING, ERROR
}

/**
 * Minimal status pill with breathing dot animation.
 */
@Composable
fun StatusIndicator(
    status: InterpretStatus,
    modifier: Modifier = Modifier
) {
    val (color, label) = when (status) {
        InterpretStatus.IDLE -> Pair(TextSecondary, "就绪")
        InterpretStatus.CONNECTING -> Pair(ProcessingAmber, "连接中…")
        InterpretStatus.LISTENING -> Pair(LiveGreen, "监听中")
        InterpretStatus.PROCESSING -> Pair(ProcessingAmber, "处理中")
        InterpretStatus.SPEAKING -> Pair(AccentLight, "播放中")
        InterpretStatus.ERROR -> Pair(ErrorRed, "错误")
    }

    val animColor by animateColorAsState(
        targetValue = color,
        animationSpec = tween(300),
        label = "statusColor"
    )

    // Breathing pulse on the dot
    val infiniteTransition = rememberInfiniteTransition(label = "breathe")
    val dotScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (status == InterpretStatus.IDLE) 1f else 1.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dotScale"
    )

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(RadiusPill))
            .background(animColor.copy(alpha = 0.10f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .scale(dotScale)
                .clip(CircleShape)
                .background(animColor)
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = animColor
        )
    }
}

private val EaseInOutSine = CubicBezierEasing(0.37f, 0f, 0.63f, 1f)
