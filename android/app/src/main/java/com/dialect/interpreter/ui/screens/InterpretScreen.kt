package com.dialect.interpreter.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dialect.interpreter.DialectApp
import com.dialect.interpreter.data.DialectCatalogLoader
import com.dialect.interpreter.data.ModelRepository.PackageInstallState
import com.dialect.interpreter.data.ModelRepository.PackageState
import com.dialect.interpreter.data.VoiceProfileRepository
import com.dialect.interpreter.session.SessionPhase
import com.dialect.interpreter.session.TranscriptTurn
import com.dialect.interpreter.session.TurnStatus
import com.dialect.interpreter.session.WorkStage
import com.dialect.interpreter.ui.components.WaveformVisualizer
import com.dialect.interpreter.ui.theme.AppColors
import com.dialect.interpreter.ui.theme.RadiusBubble
import com.dialect.interpreter.ui.theme.RadiusCard
import com.dialect.interpreter.ui.theme.RadiusPill
import com.dialect.interpreter.ui.theme.RecordRed

/**
 * Main interpretation screen (spec 03 U01/U03): dark control area on top,
 * light conversation area below. Renders the session snapshot directly; no
 * transcript persistence, no source-text substitution for missing translations.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InterpretScreen(
    reduceMotion: Boolean,
    onNavigateToSetup: () -> Unit,
    onNavigateToProfile: () -> Unit,
    onNavigateToSettings: () -> Unit,
) {
    val appContext = LocalContext.current.applicationContext as DialectApp
    val container = appContext.container
    val viewModel: InterpretViewModel = viewModel(
        factory = remember {
            InterpretViewModelFactory(
                sessionFactory = container::createSessionController,
                settings = container.settingsRepository,
                modelRepository = container.modelRepository,
                voiceProfiles = container.voiceProfileRepository,
                catalogLoader = container.dialectCatalogLoader,
            )
        }
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val catalog = remember { loadCatalogSafe(container.dialectCatalogLoader) }
    val sourceLabel = catalog?.dialects
        ?.firstOrNull { it.id == uiState.sourceDialectId }?.displayLabel ?: uiState.sourceDialectId
    val targetLabel = catalog?.targetLanguages
        ?.firstOrNull { it.id == uiState.targetLanguageId }?.displayLabel ?: uiState.targetLanguageId
    val sourceOptions = catalog?.dialects?.map { it.id to it.displayLabel } ?: emptyList()
    val targetOptions = catalog?.targetLanguages?.map { it.id to it.displayLabel } ?: emptyList()
    val voiceOptions = remember { mutableStateOf<List<VoiceProfileRepository.VoiceProfile>>(emptyList()) }

    LaunchedEffect(Unit) {
        voiceOptions.value = container.voiceProfileRepository.getProfiles()
    }

    var showDialectSheet by rememberSaveable { mutableStateOf(false) }

    // ------------------------------------------------------------- permission
    val context = LocalContext.current
    var showRationale by remember { mutableStateOf(false) }
    var showPermanentlyDenied by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.startSession()
        } else {
            val activity = context as? Activity
            val canAskAgain = activity != null && ActivityCompat.shouldShowRequestPermissionRationale(
                activity, Manifest.permission.RECORD_AUDIO
            )
            if (canAskAgain) showRationale = true else showPermanentlyDenied = true
        }
    }

    fun requestOrExplainPermission() {
        val activity = context as? Activity
        val rationale = activity != null && ActivityCompat.shouldShowRequestPermissionRationale(
            activity, Manifest.permission.RECORD_AUDIO
        )
        if (rationale) showRationale = true else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    // Lifecycle (spec U03 + review item 6):
    //  - ON_STOP from a real backgrounding stops the session (前台会话);
    //  - a configuration change (rotation) is NOT a stop — the retained
    //    ViewModel keeps the session alive across it;
    //  - ON_RESUME re-checks permission; a revoked permission stops the session.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            val active = viewModel.uiState.value.isSessionActive
            when {
                event == Lifecycle.Event.ON_STOP && active -> {
                    val rotating = (context as? Activity)?.isChangingConfigurations == true
                    if (!rotating) viewModel.stopSession()
                }
                event == Lifecycle.Event.ON_RESUME && active -> {
                    val granted = ContextCompat.checkSelfPermission(
                        context, Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                    if (!granted) viewModel.stopSession()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // -------------------------------------------------------------- rendering
    val bg = AppColors.bg()
    val micEnabled = uiState.isSessionActive || (uiState.asrReady && !uiState.isBusyInstalling)

    Scaffold(
        containerColor = bg,
        topBar = {
            ControlHeader(
                uiState = uiState,
                reduceMotion = reduceMotion,
                onLanguageClick = { showDialectSheet = true },
                onProfileClick = onNavigateToProfile,
                onSettingsClick = onNavigateToSettings,
            )
        },
        bottomBar = {
            InterpretBottomBar(
                uiState = uiState,
                sourceLabel = sourceLabel,
                targetLabel = targetLabel,
                micEnabled = micEnabled,
                onMicClick = {
                    when {
                        uiState.isSessionActive -> viewModel.stopSession()
                        !micEnabled -> Unit
                        ContextCompat.checkSelfPermission(
                            context, Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED -> viewModel.startSession()
                        else -> requestOrExplainPermission()
                    }
                },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            InstallGateBanner(
                uiState = uiState,
                onNavigateToSetup = onNavigateToSetup,
            )
            TurnList(
                turns = uiState.session.turns,
                reduceMotion = reduceMotion,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    if (showDialectSheet) {
        DialectSheet(
            uiState = uiState,
            sourceOptions = sourceOptions,
            targetOptions = targetOptions,
            voiceOptions = voiceOptions.value,
            onSourceChange = viewModel::setSourceDialect,
            onTargetChange = viewModel::setTargetLanguage,
            onVoiceChange = viewModel::setVoiceProfile,
            onManageProfiles = {
                showDialectSheet = false
                onNavigateToProfile()
            },
            onDismiss = { showDialectSheet = false },
        )
    }

    if (showRationale) {
        AlertDialog(
            onDismissRequest = { showRationale = false },
            title = { Text("需要麦克风权限") },
            text = { Text("Auralis 需要使用麦克风听取你说的话，才能进行本地转写与翻译。所有处理都在本机离线完成。") },
            confirmButton = {
                TextButton(onClick = {
                    showRationale = false
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }) { Text("继续授权") }
            },
            dismissButton = {
                TextButton(onClick = { showRationale = false }) { Text("取消") }
            },
        )
    }

    if (showPermanentlyDenied) {
        AlertDialog(
            onDismissRequest = { showPermanentlyDenied = false },
            title = { Text("麦克风权限被拒绝") },
            text = { Text("录音需要麦克风权限。请到系统设置中手动开启，或先使用其它界面。") },
            confirmButton = {
                TextButton(onClick = {
                    showPermanentlyDenied = false
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:com.dialect.interpreter"))
                    )
                }) { Text("打开设置") }
            },
            dismissButton = {
                TextButton(onClick = { showPermanentlyDenied = false }) { Text("取消") }
            },
        )
    }
}

private fun loadCatalogSafe(loader: DialectCatalogLoader) = try {
    loader.load()
} catch (e: Exception) {
    null
}

// ============================================================
// Control header — always-dark area (spec U01)
// ============================================================

@Composable
private fun ControlHeader(
    uiState: InterpretUiState,
    reduceMotion: Boolean,
    onLanguageClick: () -> Unit,
    onProfileClick: () -> Unit,
    onSettingsClick: () -> Unit,
) {
    val onControl = AppColors.onControl()
    val onControlSecondary = AppColors.onControlSecondary()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppColors.controlSurface())
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Auralis",
                    style = MaterialTheme.typography.titleLarge,
                    color = onControl,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    sessionSubtitle(uiState),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (uiState.phase == SessionPhase.ACTIVE) AppColors.accent() else onControlSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onLanguageClick) {
                Icon(
                    Icons.Filled.Translate,
                    contentDescription = "语言与声音设置",
                    tint = onControl,
                )
            }
            IconButton(onClick = onProfileClick) {
                Icon(
                    Icons.Filled.RecordVoiceOver,
                    contentDescription = "声音档案",
                    tint = onControl,
                )
            }
            IconButton(onClick = onSettingsClick) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = "设置",
                    tint = onControl,
                )
            }
        }
        if (uiState.phase == SessionPhase.ACTIVE && uiState.session.captureActive) {
            Spacer(Modifier.height(8.dp))
            WaveformVisualizer(
                amplitude = uiState.amplitude,
                isActive = true,
                reduceMotion = reduceMotion,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(32.dp),
            )
        }
        uiState.session.problem?.let { problem ->
            Spacer(Modifier.height(8.dp))
            ProblemBanner(problem.message, problem.recoverable)
        }
    }
}

private fun sessionSubtitle(uiState: InterpretUiState): String {
    val stages = uiState.session.activeStages
    val stageText = when {
        stages.contains(WorkStage.PLAYBACK) -> "播放中"
        stages.contains(WorkStage.TTS) -> "合成中"
        stages.contains(WorkStage.MT) -> "翻译中"
        stages.contains(WorkStage.ASR) -> "识别中"
        else -> ""
    }
    return when (uiState.phase) {
        SessionPhase.IDLE -> "${uiState.sessionModeLabel} · 已停止"
        SessionPhase.STARTING -> "准备模型中…"
        SessionPhase.ACTIVE -> when {
            stageText.isNotEmpty() && uiState.session.captureActive -> "聆听中 · $stageText"
            stageText.isNotEmpty() -> "处理中 · $stageText"
            uiState.session.captureActive -> "聆听中"
            else -> "处理中"
        }
        SessionPhase.STOPPING -> "正在停止…"
        SessionPhase.FAILED -> "会话失败"
    }
}

@Composable
private fun ProblemBanner(message: String, recoverable: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(RadiusCard))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Warning,
            contentDescription = null,
            tint = AppColors.draftAmber(),
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            if (recoverable) "$message（可重试）" else message,
            style = MaterialTheme.typography.bodySmall,
            color = AppColors.onControl(),
        )
    }
}

// ============================================================
// Install gate banner — honest missing/draft state (spec U02)
// ============================================================

@Composable
private fun InstallGateBanner(uiState: InterpretUiState, onNavigateToSetup: () -> Unit) {
    if (uiState.asrReady) return
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        shadowElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.ErrorOutline,
                    contentDescription = null,
                    tint = AppColors.draftAmber(),
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "语音识别模型尚未就绪",
                    style = MaterialTheme.typography.titleSmall,
                    color = AppColors.text(),
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                when {
                    uiState.isBusyInstalling -> "模型安装中，完成后即可开始"
                    else -> "需要先安装并校验模型。本地处理，安装后可完全离线使用。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.textSecondary(),
            )
            if (!uiState.isBusyInstalling) {
                Spacer(Modifier.height(8.dp))
                Button(onClick = onNavigateToSetup, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("前往安装")
                }
            }
        }
    }
}

// ============================================================
// Turn list — light conversation area
// ============================================================

@Composable
private fun TurnList(
    turns: List<TranscriptTurn>,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    if (turns.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = AppColors.textSecondary().copy(alpha = 0.3f),
                    modifier = Modifier.size(48.dp),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "按下录音键开始对话\n原文与译文会显示在这里",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.textSecondary().copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                )
            }
        }
        return
    }

    val listState = rememberLazyListState()
    // Auto-scroll only when the user is already near the bottom (spec U03).
    val isNearBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= info.totalItemsCount - 2
        }
    }
    androidx.compose.runtime.LaunchedEffect(turns.size) {
        if (turns.isNotEmpty() && isNearBottom) {
            if (reduceMotion) listState.scrollToItem(turns.lastIndex)
            else listState.animateScrollToItem(turns.lastIndex)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(turns, key = { "${it.sessionId}-${it.id}" }) { turn ->
            TurnRow(turn = turn)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TurnRow(turn: TranscriptTurn) {
    val clipboard = LocalClipboardManager.current
    val haptics = LocalHapticFeedback.current

    Column(modifier = Modifier.fillMaxWidth()) {
        if (turn.sourceText.isNotBlank()) {
            Bubble(
                text = turn.sourceText,
                alignEnd = false,
                onCopy = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    clipboard.setText(AnnotatedString(turn.sourceText))
                },
            )
        }
        // translatedText == null ⇒ no translation exists yet (or none at all);
        // the source text is never substituted (spec U03, removed old fallback).
        if (turn.translatedText != null) {
            Bubble(
                text = turn.translatedText,
                alignEnd = true,
                onCopy = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    clipboard.setText(AnnotatedString(turn.translatedText))
                },
            )
        } else if (turn.status == TurnStatus.TRANSLATING || turn.status == TurnStatus.SYNTHESIZING) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(RadiusBubble))
                        .background(AppColors.bubbleTarget())
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                        .semantics { contentDescription = "翻译处理中" },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = AppColors.accent(),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (turn.status == TurnStatus.TRANSLATING) "翻译中…" else "合成中…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppColors.textSecondary(),
                    )
                }
            }
        }
        TurnStatusRow(turn)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(text: String, alignEnd: Boolean, onCopy: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (alignEnd) Arrangement.End else Arrangement.Start,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(RadiusBubble))
                .background(if (alignEnd) AppColors.bubbleTarget() else AppColors.bubbleSource())
                .combinedClickable(onClick = {}, onLongClick = onCopy)
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .semantics {
                    contentDescription = "$text（长按复制）"
                }
        ) {
            Text(
                text,
                style = MaterialTheme.typography.bodyLarge,
                color = AppColors.text(),
            )
        }
    }
}

@Composable
private fun TurnStatusRow(turn: TranscriptTurn) {
    val problem = turn.problem
    val label = when (turn.status) {
        TurnStatus.CAPTURED -> "语音已录入，等待识别"
        TurnStatus.RECOGNIZING -> "识别中…"
        TurnStatus.TRANSLATING, TurnStatus.SYNTHESIZING -> null // bubble shows it
        TurnStatus.PLAYING -> "播放中…"
        TurnStatus.COMPLETE -> when {
            problem?.code?.startsWith("tts") == true -> "译文完整 · 未播放"
            else -> "已播放"
        }
        TurnStatus.FAILED -> "失败：${problem?.message ?: "未知原因"}"
        TurnStatus.DROPPED -> "已跳过（队列已满）"
        TurnStatus.CANCELLED -> "已取消"
    } ?: return

    val color = when (turn.status) {
        TurnStatus.COMPLETE -> AppColors.success()
        TurnStatus.FAILED, TurnStatus.DROPPED -> AppColors.error()
        else -> AppColors.textSecondary()
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        horizontalArrangement = if (turn.status == TurnStatus.COMPLETE) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (turn.status == TurnStatus.COMPLETE) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(12.dp),
            )
            Spacer(Modifier.width(4.dp))
        }
        if (turn.status == TurnStatus.FAILED || turn.status == TurnStatus.DROPPED) {
            Icon(
                Icons.Filled.ErrorOutline,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(12.dp),
            )
            Spacer(Modifier.width(4.dp))
        }
        if (
            turn.status == TurnStatus.CAPTURED || turn.status == TurnStatus.RECOGNIZING ||
            turn.status == TurnStatus.PLAYING
        ) {
            CircularProgressIndicator(modifier = Modifier.size(10.dp), strokeWidth = 1.5.dp, color = color)
            Spacer(Modifier.width(4.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}

// ============================================================
// Bottom bar — status pill + mic
// ============================================================

@Composable
private fun InterpretBottomBar(
    uiState: InterpretUiState,
    sourceLabel: String,
    targetLabel: String,
    micEnabled: Boolean,
    onMicClick: () -> Unit,
) {
    val recording = uiState.isSessionActive
    val statusText = when {
        uiState.phase == SessionPhase.FAILED -> uiState.session.problem?.message ?: "会话失败"
        recording -> sessionSubtitle(uiState)
        !uiState.asrReady -> "语音识别模型未就绪"
        else -> "$sourceLabel → $targetLabel"
    }

    Surface(color = AppColors.surface(), tonalElevation = 1.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(RadiusPill))
                    .background(AppColors.bg())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (recording) {
                    Icon(
                        Icons.Filled.Mic,
                        contentDescription = null,
                        tint = AppColors.recordRed(),
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (uiState.phase == SessionPhase.FAILED) AppColors.error() else AppColors.textSecondary(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(10.dp))
            FilledIconButton(
                onClick = onMicClick,
                enabled = micEnabled,
                modifier = Modifier
                    .size(56.dp)
                    .semantics {
                        contentDescription = if (recording) "停止录音" else "开始录音"
                    },
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = if (recording) RecordRed else AppColors.accent(),
                    contentColor = if (recording) Color.White
                        else MaterialTheme.colorScheme.onPrimary,
                    disabledContainerColor = AppColors.textSecondary().copy(alpha = 0.24f),
                ),
            ) {
                Icon(
                    if (recording) Icons.Filled.Stop else Icons.Filled.Mic,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

private fun sourceLabel(uiState: InterpretUiState): String = uiState.sourceDialectId
private fun targetLabel(uiState: InterpretUiState): String = uiState.targetLanguageId

// ============================================================
// Dialect / language / voice sheet
// ============================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DialectSheet(
    uiState: InterpretUiState,
    sourceOptions: List<Pair<String, String>>,
    targetOptions: List<Pair<String, String>>,
    voiceOptions: List<VoiceProfileRepository.VoiceProfile>,
    onSourceChange: (String) -> Unit,
    onTargetChange: (String) -> Unit,
    onVoiceChange: (String?) -> Unit,
    onManageProfiles: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Config applies at the next session start (spec U03): chips are disabled
    // during an active session with an explicit explanation.
    val configLocked = uiState.isSessionActive

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = AppColors.surface()) {
        Column(modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            SectionLabel("源方言")
            ChipRow(
                options = sourceOptions,
                selected = uiState.sourceDialectId,
                enabled = !configLocked,
                onSelect = onSourceChange,
            )
            Spacer(Modifier.height(16.dp))
            SectionLabel("目标语言")
            ChipRow(
                options = targetOptions,
                selected = uiState.targetLanguageId,
                enabled = !configLocked,
                onSelect = onTargetChange,
            )
            Spacer(Modifier.height(16.dp))
            SectionLabel("声音（克隆）")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(RadiusCard))
                    .background(AppColors.bg())
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        uiState.voiceProfileName ?: "不使用克隆",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppColors.text(),
                    )
                    Text(
                        if (uiState.voiceProfileId == null) "使用标准输出，不克隆任何声音"
                        else "仅使用你已录制并获授权的声音档案",
                        style = MaterialTheme.typography.labelSmall,
                        color = AppColors.textSecondary(),
                    )
                }
                TextButton(onClick = { onVoiceChange(null) }) { Text("清除") }
                TextButton(onClick = onManageProfiles) { Text("管理") }
            }
            if (voiceOptions.isNotEmpty() && !configLocked) {
                Spacer(Modifier.height(8.dp))
                ChipRow(
                    options = voiceOptions.map { it.id to it.name },
                    selected = uiState.voiceProfileId ?: "",
                    enabled = true,
                    onSelect = { onVoiceChange(it.ifEmpty { null }) },
                )
            }
            if (configLocked) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "录音进行中：新配置将在停止后生效",
                    style = MaterialTheme.typography.labelSmall,
                    color = AppColors.draftAmber(),
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = AppColors.textSecondary(),
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(
    options: List<Pair<String, String>>,
    selected: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (id, label) ->
            FilterChip(
                selected = id == selected,
                onClick = { onSelect(id) },
                enabled = enabled,
                label = { Text(label) },
            )
        }
    }
}
