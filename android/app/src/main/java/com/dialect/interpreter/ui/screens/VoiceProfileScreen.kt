package com.dialect.interpreter.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dialect.interpreter.audio.AudioPlayer
import com.dialect.interpreter.audio.AudioRecorder
import com.dialect.interpreter.data.VoiceProfileRepository
import com.dialect.interpreter.ui.components.WaveformVisualizer
import com.dialect.interpreter.ui.theme.*
import kotlinx.coroutines.launch

/**
 * Voice profile screen — avatar ring, swipe-to-delete, clean recording bottom sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceProfileScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val profileRepo = remember { VoiceProfileRepository(appContext) }
    val audioRecorder = remember { AudioRecorder() }
    val audioPlayer = remember { AudioPlayer() }

    var profiles by remember { mutableStateOf(profileRepo.getProfiles()) }
    var isRecording by remember { mutableStateOf(false) }
    var recordingProgress by remember { mutableFloatStateOf(0f) }
    var showRecordSheet by remember { mutableStateOf(false) }
    var profileName by remember { mutableStateOf("") }
    var recordedAudio by remember { mutableStateOf<FloatArray?>(null) }
    var amplitude by remember { mutableFloatStateOf(0f) }

    val bg = AppColors.bg()
    val surface = AppColors.surface()
    val text = AppColors.text()
    val secondary = AppColors.textSecondary()
    val accent = AppColors.accent()

    LaunchedEffect(Unit) { audioRecorder.amplitude.collect { amplitude = it } }
    DisposableEffect(Unit) { onDispose { audioRecorder.stopRecording(); audioPlayer.release() } }

    Scaffold(
        containerColor = bg,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("声音档案", color = text, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${profiles.size} 个档案",
                            style = MaterialTheme.typography.labelSmall,
                            color = secondary
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = accent)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = surface.copy(alpha = 0.92f)
                )
            )
        },
        bottomBar = {
            // Bottom pinned add button (instead of FAB)
            Surface(
                color = surface.copy(alpha = 0.95f),
                tonalElevation = 1.dp
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                ) {
                    Button(
                        onClick = { showRecordSheet = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        shape = RoundedCornerShape(RadiusPill),
                        colors = ButtonDefaults.buttonColors(containerColor = accent)
                    ) {
                        Icon(Icons.Default.Mic, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("录制新档案", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    ) { padding ->
        if (profiles.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.RecordVoiceOver, null,
                        tint = secondary.copy(alpha = 0.2f),
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(Modifier.height(20.dp))
                    Text(
                        "还没有声音档案",
                        style = MaterialTheme.typography.titleMedium,
                        color = secondary.copy(alpha = 0.5f)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "录制 3 秒参考音频即可克隆你的声音",
                        style = MaterialTheme.typography.bodySmall,
                        color = secondary.copy(alpha = 0.3f),
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(profiles, key = { it.id }) { profile ->
                    var playing by remember { mutableStateOf(false) }

                    SwipeToDismissBox(
                        state = rememberSwipeToDismissBoxState(
                            confirmValueChange = { value ->
                                if (value == SwipeToDismissBoxValue.EndToStart) {
                                    profileRepo.deleteProfile(profile.id)
                                    profiles = profileRepo.getProfiles()
                                    true
                                } else false
                            }
                        ),
                        backgroundContent = {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(RadiusCard))
                                    .background(ErrorRed)
                                    .padding(horizontal = 20.dp),
                                contentAlignment = Alignment.CenterEnd
                            ) {
                                Icon(Icons.Default.Delete, "删除", tint = Color.White)
                            }
                        },
                        enableDismissFromStartToEnd = false,
                        enableDismissFromEndToStart = true,
                        content = {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(RadiusCard))
                                    .background(surface)
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                // Avatar with ring border
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .border(
                                            2.dp,
                                            Brush.linearGradient(listOf(accent, accent.copy(alpha = 0.4f))),
                                            CircleShape
                                        )
                                        .clip(CircleShape)
                                        .background(accent.copy(alpha = 0.08f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        profile.name.first().toString(),
                                        color = accent,
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                }

                                // Info
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        profile.name,
                                        color = text,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        "${profile.durationMs / 1000}秒 参考音频",
                                        color = secondary,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }

                                // Play button
                                FilledIconButton(
                                    onClick = {
                                        scope.launch {
                                            playing = true
                                            audioPlayer.play(profileRepo.loadProfileAudio(profile), 16000)
                                            playing = false
                                        }
                                    },
                                    modifier = Modifier.size(38.dp),
                                    shape = CircleShape,
                                    colors = IconButtonDefaults.filledIconButtonColors(
                                        containerColor = accent.copy(alpha = 0.12f),
                                        contentColor = accent
                                    )
                                ) {
                                    Icon(
                                        if (playing) Icons.Default.Stop else Icons.Default.PlayArrow,
                                        "试听",
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    )
                }
            }
        }
    }

    // Recording bottom sheet
    if (showRecordSheet) {
        ModalBottomSheet(
            onDismissRequest = {
                if (!isRecording) {
                    showRecordSheet = false; recordedAudio = null; profileName = ""
                }
            },
            containerColor = surface,
            dragHandle = {
                BottomSheetDefaults.DragHandle(color = secondary.copy(alpha = 0.3f))
            }
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    if (recordedAudio == null) "录制参考音频" else "保存档案",
                    style = MaterialTheme.typography.titleMedium,
                    color = text,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(20.dp))

                if (recordedAudio == null) {
                    Text(
                        "请朗读一段话（至少 3 秒）",
                        style = MaterialTheme.typography.bodyMedium,
                        color = secondary,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(20.dp))
                    WaveformVisualizer(amplitude, isRecording, Modifier.height(48.dp).fillMaxWidth())

                    if (isRecording) {
                        Spacer(Modifier.height(16.dp))
                        LinearProgressIndicator(
                            progress = { recordingProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = RecordingRed,
                            trackColor = RecordingRed.copy(alpha = 0.12f)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "${(recordingProgress * 5).toInt()}s / 5s",
                            color = RecordingRed,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }

                    Spacer(Modifier.height(20.dp))
                    Button(
                        onClick = {
                            scope.launch {
                                isRecording = true
                                val t0 = System.currentTimeMillis()
                                launch {
                                    while (isRecording) {
                                        recordingProgress = ((System.currentTimeMillis() - t0) / 5000f).coerceIn(0f, 1f)
                                        kotlinx.coroutines.delay(50)
                                    }
                                }
                                recordedAudio = audioRecorder.recordFixedDuration(5000L)
                                isRecording = false; recordingProgress = 0f
                            }
                        },
                        enabled = !isRecording,
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        shape = RoundedCornerShape(RadiusPill),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isRecording) RecordingRed else accent
                        )
                    ) {
                        Text(
                            if (isRecording) "录制中…" else "开始录制",
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                } else {
                    OutlinedTextField(
                        value = profileName,
                        onValueChange = { profileName = it },
                        label = { Text("名称") },
                        placeholder = { Text("例如：我的声音") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                    Spacer(Modifier.height(20.dp))
                    Button(
                        onClick = {
                            if (profileName.isNotBlank()) {
                                recordedAudio?.let { profileRepo.saveProfile(profileName, it) }
                                profiles = profileRepo.getProfiles()
                                showRecordSheet = false; recordedAudio = null; profileName = ""
                            }
                        },
                        enabled = profileName.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        shape = RoundedCornerShape(RadiusPill),
                        colors = ButtonDefaults.buttonColors(containerColor = accent)
                    ) {
                        Text("保存", fontWeight = FontWeight.SemiBold)
                    }
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
