package com.dialect.interpreter.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dialect.interpreter.DialectApp
import com.dialect.interpreter.audio.AudioPlayer
import com.dialect.interpreter.audio.AudioRecorder
import com.dialect.interpreter.data.VoiceProfileRepository
import com.dialect.interpreter.ui.components.WaveformVisualizer
import com.dialect.interpreter.ui.theme.AppColors
import com.dialect.interpreter.ui.theme.RadiusCard
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Voice profile screen (spec 03 U04). Recording requires an explicit consent
 * step (own or authorized voice). Record / preview / re-record / save / delete
 * all surface real results; nothing plays or saves fabricated audio.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceProfileScreen(reduceMotion: Boolean, onBack: () -> Unit) {
    val appContext = LocalContext.current.applicationContext as DialectApp
    val repository = appContext.container.voiceProfileRepository
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    // Own recorder/player instances: cheap hardware wrappers owned by this
    // screen, released on dispose (sessions own separate instances, spec R01).
    val recorder = remember { AudioRecorder(appContext) }
    val player = remember { AudioPlayer(appContext) }

    val profiles by repository.profilesFlow.collectAsStateWithLifecycle()
    var amplitude by remember { mutableFloatStateOf(0f) }
    var playingId by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<VoiceProfileRepository.VoiceProfile?>(null) }
    var showRecordSheet by remember { mutableStateOf(false) }
    var recordingJob by remember { mutableStateOf<Job?>(null) }
    var isRecording by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { repository.reloadProfiles() }
    LaunchedEffect(recorder) {
        recorder.amplitude.collect { amplitude = it }
    }
    DisposableEffect(Unit) {
        onDispose {
            recordingJob?.cancel()
            recorder.stop()
            player.release()
        }
    }

    Scaffold(
        containerColor = AppColors.bg(),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("声音档案", color = AppColors.text(), fontWeight = FontWeight.SemiBold)
                        Text(
                            "${profiles.size} 个档案 · 仅存于本机",
                            style = MaterialTheme.typography.labelSmall,
                            color = AppColors.textSecondary(),
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = AppColors.accent())
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = AppColors.surface().copy(alpha = 0.92f)
                ),
            )
        },
        bottomBar = {
            Surface(
                color = AppColors.surface().copy(alpha = 0.95f),
                tonalElevation = 1.dp,
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
                        shape = RoundedCornerShape(26.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.accent()),
                    ) {
                        Icon(Icons.Filled.Mic, null, modifier = Modifier.size(18.dp))
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
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.RecordVoiceOver, null,
                        tint = AppColors.textSecondary().copy(alpha = 0.25f),
                        modifier = Modifier.size(64.dp),
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "还没有声音档案",
                        style = MaterialTheme.typography.titleMedium,
                        color = AppColors.textSecondary(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "录制 3 秒以上的参考音频，\n仅在应用内用于语音合成。",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.textSecondary().copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(profiles, key = { it.id }) { profile ->
                    ProfileCard(
                        profile = profile,
                        playing = playingId == profile.id,
                        onPlay = {
                            playingId = profile.id
                            scope.launch {
                                try {
                                    val audio = repository.loadPreviewAudio(profile.id)
                                    if (audio == null) {
                                        snackbar.showSnackbar("音频读取失败，文件可能已损坏")
                                    } else {
                                        player.play(audio.first, audio.second)
                                    }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    snackbar.showSnackbar("播放失败：${e.message ?: "未知错误"}")
                                } finally {
                                    playingId = null
                                }
                            }
                        },
                        onStopPlay = {
                            player.stop()
                            playingId = null
                        },
                        onDelete = { deleteTarget = profile },
                    )
                }
            }
        }
    }

    if (deleteTarget != null) {
        val target = deleteTarget!!
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除档案") },
            text = { Text("将删除「${target.name}」的参考音频与派生数据，且无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    scope.launch {
                        val result = repository.deleteProfile(target.id)
                        repository.reloadProfiles()
                        if (result.isFailure) snackbar.showSnackbar("删除失败，请重试")
                    }
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
        )
    }

    if (showRecordSheet) {
        RecordSheet(
            recorder = recorder,
            amplitudeProvider = { amplitude },
            reduceMotion = reduceMotion,
            onMessage = { message -> scope.launch { snackbar.showSnackbar(message) } },
            onSaved = {
                showRecordSheet = false
                scope.launch { repository.reloadProfiles() }
            },
            onDismiss = { showRecordSheet = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecordSheet(
    recorder: AudioRecorder,
    amplitudeProvider: () -> Float,
    reduceMotion: Boolean,
    onMessage: (String) -> Unit,
    onSaved: () -> Unit,
    onDismiss: () -> Unit,
) {
    val appContext = LocalContext.current.applicationContext as DialectApp
    val repository = appContext.container.voiceProfileRepository
    val scope = rememberCoroutineScope()

    var consentGiven by remember { mutableStateOf(false) }
    var isRecording by remember { mutableStateOf(false) }
    var chunks by remember { mutableStateOf<List<FloatArray>>(emptyList()) }
    var sampleCount by remember { mutableIntStateOf(0) }
    var amplitude by remember { mutableFloatStateOf(0f) }
    var profileName by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var recordingJob by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(Unit) {
        // Sheet-local amplitude mirror to avoid recomposing the whole screen.
        recorder.amplitude.collect { amplitude = it }
    }

    val durationMs = sampleCount * 1000L / AudioRecorder.SAMPLE_RATE
    val hasRecording = sampleCount > 0
    val tooShort = hasRecording && durationMs < VoiceProfileRepository.MIN_DURATION_MS

    fun startRecording() {
        chunks = emptyList()
        sampleCount = 0
        recordingJob = scope.launch {
            isRecording = true
            try {
                recorder.start(
                    onChunk = { chunk ->
                        chunks = chunks + chunk
                        sampleCount += chunk.size
                        amplitude = rmsOf(chunk)
                    }
                )
            } finally {
                isRecording = false
            }
        }
    }

    fun stopRecording() {
        recordingJob?.cancel()
        recorder.stop()
        isRecording = false
    }

    DisposableEffect(Unit) {
        onDispose { recordingJob?.cancel(); recorder.stop() }
    }

    ModalBottomSheet(
        onDismissRequest = {
            stopRecording()
            onDismiss()
        },
        containerColor = AppColors.surface(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                if (!hasRecording) "录制参考音频" else "保存档案",
                style = MaterialTheme.typography.titleMedium,
                color = AppColors.text(),
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(16.dp))

            if (!hasRecording) {
                // Consent step (spec U04): explicit, before any recording.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = consentGiven, onCheckedChange = { consentGiven = it })
                    Text(
                        "我确认这是本人声音，或已获得声音所有者的明确授权。",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.textSecondary(),
                    )
                }
                Spacer(Modifier.height(8.dp))
                WaveformVisualizer(
                    amplitude = if (isRecording) amplitude else amplitudeProvider(),
                    isActive = isRecording,
                    reduceMotion = reduceMotion,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                )
                Spacer(Modifier.height(16.dp))
                if (isRecording) {
                    Text(
                        "录制中… %.1fs".format(durationMs / 1000f),
                        color = AppColors.recordRed(),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { stopRecording() },
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        shape = RoundedCornerShape(26.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.recordRed()),
                    ) {
                        Icon(Icons.Filled.Stop, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("停止", fontWeight = FontWeight.SemiBold)
                    }
                } else {
                    Button(
                        onClick = { startRecording() },
                        enabled = consentGiven,
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        shape = RoundedCornerShape(26.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AppColors.accent(),
                            disabledContainerColor = AppColors.textSecondary().copy(alpha = 0.24f),
                        ),
                    ) {
                        Icon(Icons.Filled.Mic, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("开始录制", fontWeight = FontWeight.SemiBold)
                    }
                }
            } else {
                Text(
                    "已录制 %.1f 秒".format(durationMs / 1000f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (tooShort) AppColors.error() else AppColors.text(),
                )
                if (tooShort) {
                    Text(
                        "参考音频至少 ${VoiceProfileRepository.MIN_DURATION_MS / 1000} 秒，请重录。",
                        style = MaterialTheme.typography.labelSmall,
                        color = AppColors.error(),
                    )
                }
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = profileName,
                    onValueChange = { profileName = it },
                    label = { Text("档案名称") },
                    placeholder = { Text("例如：我的声音") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            profileName = ""
                            startRecording()
                        },
                        enabled = !saving,
                        modifier = Modifier.weight(1f).height(50.dp),
                    ) { Text("重录") }
                    Button(
                        onClick = {
                            saving = true
                            val pcm = concatChunks(chunks)
                            scope.launch {
                                when (val result = repository.saveProfile(profileName, pcm)) {
                                    is VoiceProfileRepository.SaveResult.Ok -> {
                                        saving = false
                                        onSaved()
                                    }
                                    is VoiceProfileRepository.SaveResult.Rejected -> {
                                        saving = false
                                        onMessage(result.reason)
                                    }
                                    is VoiceProfileRepository.SaveResult.Failed -> {
                                        saving = false
                                        onMessage(result.error)
                                    }
                                }
                            }
                        },
                        enabled = !saving && !tooShort && profileName.isNotBlank(),
                        modifier = Modifier.weight(1f).height(50.dp),
                    ) {
                        if (saving) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Text("保存", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

private fun rmsOf(chunk: FloatArray): Float {
    if (chunk.isEmpty()) return 0f
    var energy = 0.0
    for (s in chunk) energy += s.toDouble() * s
    return kotlin.math.sqrt(energy / chunk.size).toFloat()
}

private fun concatChunks(chunks: List<FloatArray>): FloatArray {
    val total = chunks.sumOf { it.size }
    val pcm = FloatArray(total)
    var offset = 0
    for (chunk in chunks) {
        chunk.copyInto(pcm, offset)
        offset += chunk.size
    }
    return pcm
}

@Composable
private fun ProfileCard(
    profile: VoiceProfileRepository.VoiceProfile,
    playing: Boolean,
    onPlay: () -> Unit,
    onStopPlay: () -> Unit,
    onDelete: () -> Unit,
) {
    val createdText = remember(profile.createdAt) {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(profile.createdAt))
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(RadiusCard))
            .background(AppColors.surface())
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .border(2.dp, AppColors.accent().copy(alpha = 0.5f), CircleShape)
                .clip(CircleShape)
                .background(AppColors.accent().copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                profile.name.firstOrNull()?.toString() ?: "·",
                color = AppColors.accent(),
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                profile.name,
                color = AppColors.text(),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                "${profile.durationMs / 1000}s · ${profile.sampleRate / 1000}kHz · $createdText",
                color = AppColors.textSecondary(),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        FilledIconButton(
            onClick = { if (playing) onStopPlay() else onPlay() },
            modifier = Modifier.size(48.dp),
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = AppColors.accent().copy(alpha = 0.12f),
                contentColor = AppColors.accent(),
            ),
        ) {
            Icon(
                if (playing) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                contentDescription = if (playing) "停止试听" else "试听",
                modifier = Modifier.size(20.dp),
            )
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(48.dp)) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = "删除档案",
                tint = AppColors.textSecondary(),
            )
        }
    }
}
