package com.dialect.interpreter.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dialect.interpreter.data.ModelRepository
import com.dialect.interpreter.ui.theme.*
import kotlinx.coroutines.launch

/**
 * Model extraction screen — hero layout with circular progress ring.
 */
@Composable
fun ModelDownloadScreen(
    modelRepository: ModelRepository,
    onDownloadComplete: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val progress by modelRepository.extractionProgress.collectAsState()
    var isExtracting by remember { mutableStateOf(false) }

    val accent = AppColors.accent()
    val bg = AppColors.bg()
    val text = AppColors.text()
    val secondary = AppColors.textSecondary()

    val frac = if (progress.totalFiles > 0) progress.completedFiles.toFloat() / progress.totalFiles else 0f

    // Smooth progress animation
    val animatedProgress by animateFloatAsState(
        targetValue = frac,
        animationSpec = tween(400, easing = EaseOutCubic),
        label = "progress"
    )

    // Rotation for indeterminate spinner feel during initial load
    val infiniteTransition = rememberInfiniteTransition(label = "spin")
    val ringRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2000, easing = LinearEasing)),
        label = "ringRotation"
    )

    // Staggered fade-in
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }

    val contentAlpha by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = tween(600, delayMillis = 200),
        label = "fadeIn"
    )

    LaunchedEffect(progress.isComplete) {
        if (progress.isComplete) {
            kotlinx.coroutines.delay(500)
            onDownloadComplete()
        }
    }

    Box(
        modifier = Modifier.fillMaxSize().background(bg),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Circular progress ring
            Box(
                modifier = Modifier.size(120.dp),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val strokeWidth = 6.dp.toPx()
                    val diameter = size.minDimension - strokeWidth
                    val topLeft = Offset(strokeWidth / 2, strokeWidth / 2)

                    // Track
                    drawArc(
                        color = accent.copy(alpha = 0.1f),
                        startAngle = 0f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = topLeft,
                        size = Size(diameter, diameter),
                        style = Stroke(strokeWidth, cap = StrokeCap.Round)
                    )

                    if (isExtracting && !progress.isComplete) {
                        // Filled arc
                        drawArc(
                            color = accent,
                            startAngle = -90f,
                            sweepAngle = animatedProgress * 360f,
                            useCenter = false,
                            topLeft = topLeft,
                            size = Size(diameter, diameter),
                            style = Stroke(strokeWidth, cap = StrokeCap.Round)
                        )
                    }
                }

                // Center icon
                when {
                    progress.isComplete -> Icon(
                        Icons.Default.CheckCircle, null,
                        tint = SuccessGreen,
                        modifier = Modifier.size(44.dp)
                    )
                    isExtracting -> Text(
                        "${(frac * 100).toInt()}%",
                        style = MaterialTheme.typography.titleMedium,
                        color = accent,
                        fontWeight = FontWeight.Bold
                    )
                    else -> Icon(
                        Icons.Default.Widgets, null,
                        tint = accent.copy(alpha = 0.6f),
                        modifier = Modifier.size(40.dp)
                    )
                }
            }

            Text(
                if (progress.isComplete) "准备就绪!" else "准备 AI 模型",
                style = MaterialTheme.typography.titleLarge,
                color = text,
                textAlign = TextAlign.Center
            )

            if (!isExtracting) {
                Text(
                    "模型已预装在应用中\n首次启动需要解压初始化",
                    style = MaterialTheme.typography.bodyMedium,
                    color = secondary,
                    textAlign = TextAlign.Center
                )
            }

            if (isExtracting && !progress.isComplete) {
                Text(
                    "${progress.completedFiles}/${progress.totalFiles}  ${progress.currentFileName}",
                    style = MaterialTheme.typography.labelSmall,
                    color = secondary
                )
            }

            if (progress.error != null) {
                Text(
                    progress.error!!,
                    color = ErrorRed,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            if (!progress.isComplete) {
                Button(
                    onClick = {
                        isExtracting = true
                        scope.launch { modelRepository.extractBundledModels() }
                    },
                    enabled = !isExtracting,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(RadiusPill),
                    colors = ButtonDefaults.buttonColors(containerColor = accent)
                ) {
                    Text(
                        if (isExtracting) "解压中…" else "开始",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp
                    )
                }
            }
        }
    }
}

private val EaseOutCubic = CubicBezierEasing(0.33f, 1f, 0.68f, 1f)
