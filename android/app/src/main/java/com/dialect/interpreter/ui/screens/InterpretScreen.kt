package com.dialect.interpreter.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dialect.interpreter.inference.*
import com.dialect.interpreter.inference.PipelineOrchestrator.PipelineState
import com.dialect.interpreter.audio.AudioPlayer
import com.dialect.interpreter.audio.AudioRecorder
import com.dialect.interpreter.ui.components.*
import com.dialect.interpreter.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

// ---- Data model for chat messages ----

data class ChatMessage(
    val id: Long = System.nanoTime(),
    val sourceText: String,
    val targetText: String,
    val timestamp: Long = System.currentTimeMillis(),
    val asrLatencyMs: Long = 0,
    val ttsLatencyMs: Long = 0,
    val playbackLatencyMs: Long = 0,
    val ttsRtf: Float = 0f,
    val isProcessing: Boolean = false
)

/**
 * Main interpretation screen — minimalistic chat UI.
 *
 * - Frosted‑glass top bar with adaptive colors
 * - Chat bubbles with fade-in entrance animations
 * - Pill-shaped bottom bar with spring-animated mic button
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InterpretScreen(
    modelManager: OnnxModelManager,
    onNavigateToProfile: () -> Unit,
    onNavigateToSettings: () -> Unit,
    viewModel: InterpretViewModel = viewModel()
) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    val messages by viewModel.messages.collectAsState()

    var sourceDialect by remember { mutableStateOf("四川话") }
    var targetLanguage by remember { mutableStateOf("普通话") }
    var showDialectSheet by remember { mutableStateOf(false) }

    val targetLanguageCode = AsrEngine.SUPPORTED_LANGUAGES
        .firstOrNull { it.first == targetLanguage }
        ?.second ?: "Chinese"

    val pipeline = remember {
        PipelineOrchestrator(
            asrEngine = AsrEngine(modelManager),
            ttsEngine = TtsEngine(modelManager),
            audioRecorder = AudioRecorder(),
            audioPlayer = AudioPlayer()
        )
    }

    val pipelineState by pipeline.state.collectAsState()
    val amplitude by pipeline.amplitude.collectAsState()
    val telemetry by pipeline.telemetry.collectAsState()
    val isRunning = pipelineState != PipelineState.IDLE

    val epName = when (modelManager.selectedProvider.collectAsState().value) {
        OnnxModelManager.ExecutionProvider.NNAPI -> "NPU"
        OnnxModelManager.ExecutionProvider.CPU -> "CPU"
    }

    // Adaptive colors
    val bg = AppColors.bg()
    val surface = AppColors.surface()
    val text = AppColors.text()
    val secondary = AppColors.textSecondary()
    val accent = AppColors.accent()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    LaunchedEffect(Unit) {
        pipeline.events.collect { event ->
            viewModel.onPipelineEvent(event)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            pipeline.release()
            viewModel.clearConversation()
        }
    }

    Scaffold(
        containerColor = bg,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "方言传译",
                            style = MaterialTheme.typography.titleMedium,
                            color = text,
                            fontWeight = FontWeight.SemiBold
                        )
                        val subtitle = when (pipelineState) {
                            PipelineState.IDLE -> "$sourceDialect → $targetLanguage"
                            PipelineState.LOADING -> "加载模型中…"
                            PipelineState.LISTENING -> "监听中 · $epName"
                            PipelineState.RECOGNIZING -> "识别中…"
                            PipelineState.SYNTHESIZING -> "合成中…"
                            PipelineState.PLAYING -> "播放中…"
                        }
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isRunning) LiveGreen else secondary
                        )

                        val telemetryText = remember(telemetry) {
                            if (telemetry.asrCount == 0L && telemetry.ttsCount == 0L) ""
                            else "ASR ${telemetry.lastAsrMs}ms · TTS ${telemetry.lastTtsMs}ms · RTF ${"%.2f".format(telemetry.lastRtf)}"
                        }
                        if (telemetryText.isNotBlank()) {
                            Text(
                                telemetryText,
                                style = MaterialTheme.typography.labelSmall,
                                color = secondary.copy(alpha = 0.6f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontSize = 10.sp
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { showDialectSheet = true }) {
                        Icon(Icons.Default.Translate, "方言", tint = accent)
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToProfile) {
                        Icon(Icons.Default.RecordVoiceOver, "声音档案", tint = text.copy(alpha = 0.7f))
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.MoreVert, "更多", tint = text.copy(alpha = 0.7f))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = surface.copy(alpha = 0.92f)
                )
            )
        },
        bottomBar = {
            MinimalBottomBar(
                isRunning = isRunning,
                amplitude = amplitude,
                sourceDialect = sourceDialect,
                targetLanguage = targetLanguage,
                onToggle = {
                    if (isRunning) pipeline.stop()
                    else pipeline.start(scope, targetLanguage = targetLanguageCode)
                }
            )
        }
    ) { paddingValues ->
        if (messages.isEmpty()) {
            // Empty state with floating icon
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                EmptyStateContent(sourceDialect, targetLanguage, epName)
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                userScrollEnabled = true
            ) {
                items(
                    items = messages,
                    key = { it.id }
                ) { message ->
                    AnimatedVisibility(
                        visible = true,
                        enter = fadeIn(tween(350)) + slideInVertically(
                            initialOffsetY = { it / 3 },
                            animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)
                        ),
                    ) {
                        ChatBubblePair(message = message)
                    }
                }
            }
        }
    }

    // Dialect picker bottom sheet
    if (showDialectSheet) {
        DialectBottomSheet(
            sourceDialect = sourceDialect,
            targetLanguage = targetLanguage,
            onSourceChange = { sourceDialect = it },
            onTargetChange = { targetLanguage = it },
            onDismiss = { showDialectSheet = false }
        )
    }
}

// ============================
// Empty state with gentle float
// ============================

@Composable
private fun EmptyStateContent(
    sourceDialect: String,
    targetLanguage: String,
    epName: String
) {
    val infiniteTransition = rememberInfiniteTransition(label = "float")
    val offsetY by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = -8f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "floatY"
    )

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            Icons.Default.Forum,
            null,
            tint = AppColors.textSecondary().copy(alpha = 0.2f),
            modifier = Modifier
                .size(64.dp)
                .offset(y = offsetY.dp)
        )
        Spacer(Modifier.height(20.dp))
        Text(
            "开始说话，翻译将出现在这里",
            style = MaterialTheme.typography.bodyMedium,
            color = AppColors.textSecondary().copy(alpha = 0.5f),
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "$sourceDialect → $targetLanguage · $epName",
            style = MaterialTheme.typography.labelSmall,
            color = AppColors.textTertiary()
        )
    }
}

private val EaseInOutSine = CubicBezierEasing(0.37f, 0f, 0.63f, 1f)

// ============================
// Chat bubbles — frosted, rounded
// ============================

@Composable
private fun ChatBubblePair(message: ChatMessage) {
    val timeStr = remember(message.timestamp) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.timestamp))
    }
    val accent = AppColors.accent()
    val text = AppColors.text()
    val secondary = AppColors.textSecondary()

    Column(modifier = Modifier.fillMaxWidth()) {
        // Source text — left (incoming)
        if (message.sourceText.isNotBlank()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth(0.82f)
                    .padding(end = 48.dp, bottom = 3.dp)
            ) {
                Box(
                    modifier = Modifier
                        .shadow(1.dp, RoundedCornerShape(RadiusBubble, RadiusBubble, RadiusBubble, 6.dp))
                        .clip(RoundedCornerShape(RadiusBubble, RadiusBubble, RadiusBubble, 6.dp))
                        .background(AppColors.bubbleSource())
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Column {
                        Text(
                            message.sourceText,
                            style = MaterialTheme.typography.bodyLarge,
                            color = text
                        )
                        Row(
                            modifier = Modifier.align(Alignment.End),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (message.asrLatencyMs > 0) {
                                Text(
                                    "${message.asrLatencyMs}ms",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = secondary,
                                    fontSize = 10.sp
                                )
                            }
                            Text(
                                timeStr,
                                style = MaterialTheme.typography.labelSmall,
                                color = secondary,
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            }
        }

        // Target text — right (outgoing)
        if (message.targetText.isNotBlank() || message.isProcessing) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 48.dp, top = 3.dp),
                horizontalArrangement = Arrangement.End
            ) {
                Box(
                    modifier = Modifier
                        .shadow(1.dp, RoundedCornerShape(RadiusBubble, RadiusBubble, 6.dp, RadiusBubble))
                        .clip(RoundedCornerShape(RadiusBubble, RadiusBubble, 6.dp, RadiusBubble))
                        .background(AppColors.bubbleTarget())
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Column {
                        if (message.isProcessing && message.targetText.isBlank()) {
                            TypingIndicator()
                        } else {
                            Text(
                                message.targetText,
                                style = MaterialTheme.typography.bodyLarge,
                                color = text
                            )
                        }
                        Row(
                            modifier = Modifier.align(Alignment.End),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (message.ttsLatencyMs > 0) {
                                Text(
                                    "TTS ${message.ttsLatencyMs}ms",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = secondary,
                                    fontSize = 10.sp
                                )
                            }
                            if (message.ttsRtf > 0) {
                                Text(
                                    "RTF ${"%.1f".format(message.ttsRtf)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = secondary,
                                    fontSize = 10.sp
                                )
                            }
                            Text(
                                timeStr,
                                style = MaterialTheme.typography.labelSmall,
                                color = secondary,
                                fontSize = 10.sp
                            )
                            if (!message.isProcessing) {
                                Icon(
                                    Icons.Default.DoneAll,
                                    null,
                                    tint = accent,
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ============================
// Typing indicator — smooth wave
// ============================

@Composable
private fun TypingIndicator() {
    val infiniteTransition = rememberInfiniteTransition(label = "typing")
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(3) { index ->
            val delay = index * 160
            val scale by infiniteTransition.animateFloat(
                initialValue = 0.6f, targetValue = 1.1f,
                animationSpec = infiniteRepeatable(
                    tween(500, delayMillis = delay, easing = EaseInOutSine),
                    RepeatMode.Reverse
                ), label = "dot$index"
            )
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .scale(scale)
                    .clip(CircleShape)
                    .background(AppColors.textSecondary().copy(alpha = 0.4f))
            )
        }
    }
}

// ============================
// Bottom bar — minimal pill
// ============================

@Composable
private fun MinimalBottomBar(
    isRunning: Boolean,
    amplitude: Float,
    sourceDialect: String,
    targetLanguage: String,
    onToggle: () -> Unit
) {
    val accent = AppColors.accent()
    val surface = AppColors.surface()
    val secondary = AppColors.textSecondary()
    val bg = AppColors.bg()

    // Mic button scale
    val micScale by animateFloatAsState(
        targetValue = if (isRunning) 1f + amplitude * 0.25f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 400f),
        label = "mic"
    )

    // Ring expansion animation
    val ringAlpha by animateFloatAsState(
        targetValue = if (isRunning) 1f else 0f,
        animationSpec = tween(400),
        label = "ringAlpha"
    )

    Surface(
        color = surface.copy(alpha = 0.95f),
        shadowElevation = 0.dp,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Status area
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(RadiusPill))
                    .background(bg)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isRunning) {
                    WaveformVisualizer(
                        amplitude = amplitude,
                        isActive = true,
                        modifier = Modifier
                            .weight(1f)
                            .height(28.dp)
                    )
                } else {
                    Icon(
                        Icons.Default.Translate,
                        null,
                        tint = secondary.copy(alpha = 0.5f),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "$sourceDialect → $targetLanguage",
                        style = MaterialTheme.typography.bodyMedium,
                        color = secondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(Modifier.width(10.dp))

            // Mic button with outer ring
            Box(contentAlignment = Alignment.Center) {
                // Concentric ring
                if (ringAlpha > 0f) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .scale(micScale * 1.1f)
                            .clip(CircleShape)
                            .background(
                                if (isRunning) RecordingRed.copy(alpha = 0.12f * ringAlpha)
                                else Color.Transparent
                            )
                    )
                }
                FilledIconButton(
                    onClick = onToggle,
                    modifier = Modifier
                        .size(48.dp)
                        .scale(micScale),
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (isRunning) RecordingRed else accent
                    )
                ) {
                    Icon(
                        if (isRunning) Icons.Default.Stop else Icons.Default.Mic,
                        if (isRunning) "停止" else "开始",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

// ============================
// Dialect picker bottom sheet
// ============================

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun DialectBottomSheet(
    sourceDialect: String,
    targetLanguage: String,
    onSourceChange: (String) -> Unit,
    onTargetChange: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val accent = AppColors.accent()
    val surface = AppColors.surface()
    val text = AppColors.text()
    val secondary = AppColors.textSecondary()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = surface,
        dragHandle = { BottomSheetDefaults.DragHandle(color = secondary.copy(alpha = 0.3f)) }
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text(
                "源方言",
                style = MaterialTheme.typography.labelMedium,
                color = secondary,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(10.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AsrEngine.CHINESE_DIALECTS.forEach { (label, _) ->
                    FilterChip(
                        selected = label == sourceDialect,
                        onClick = { onSourceChange(label) },
                        label = { Text(label, fontSize = 13.sp) },
                        shape = RoundedCornerShape(RadiusPill),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = accent.copy(alpha = 0.15f),
                            selectedLabelColor = accent
                        )
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            Text(
                "目标语言",
                style = MaterialTheme.typography.labelMedium,
                color = secondary,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(10.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AsrEngine.SUPPORTED_LANGUAGES.forEach { (label, _) ->
                    FilterChip(
                        selected = label == targetLanguage,
                        onClick = { onTargetChange(label) },
                        label = { Text(label, fontSize = 13.sp) },
                        shape = RoundedCornerShape(RadiusPill),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = accent.copy(alpha = 0.15f),
                            selectedLabelColor = accent
                        )
                    )
                }
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}
