package com.dialect.interpreter.ui.screens

import android.os.Build
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dialect.interpreter.data.ModelRepository
import com.dialect.interpreter.inference.OnnxModelManager
import com.dialect.interpreter.ui.theme.*

/**
 * Settings screen — iOS-style inset grouped list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    modelManager: OnnxModelManager,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val modelRepo = remember { ModelRepository(context.applicationContext) }
    val epState by modelManager.selectedProvider.collectAsState()
    val socName = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else Build.HARDWARE
    }

    val bg = AppColors.bg()
    val surface = AppColors.surface()
    val text = AppColors.text()
    val secondary = AppColors.textSecondary()
    val accent = AppColors.accent()

    Scaffold(
        containerColor = bg,
        topBar = {
            TopAppBar(
                title = {
                    Text("设置", color = text, fontWeight = FontWeight.SemiBold)
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
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SettingsSection("推理引擎", accent) {
                InfoRow(Icons.Default.Memory, "执行提供者",
                    if (epState == OnnxModelManager.ExecutionProvider.NNAPI) "NNAPI (NPU)" else "CPU")
                InfoRow(Icons.Default.Smartphone, "SoC", socName.ifBlank { "—" })
                InfoRow(Icons.Default.Speed, "优化", "NNAPI + GraphOpt")
            }

            SettingsSection("模型", accent) {
                InfoRow(Icons.Default.Storage, "磁盘占用", "${modelRepo.getDownloadedSize() / 1_000_000} MB")
                InfoRow(Icons.Default.Hearing, "ASR", if (modelRepo.areAsrModelsReady()) "就绪" else "未解压")
                InfoRow(Icons.Default.RecordVoiceOver, "TTS", if (modelRepo.areTtsModelsReady()) "就绪" else "未解压")
                InfoRow(Icons.Default.Compress, "量化", "INT4 Block-wise")
            }

            SettingsSection("关于", accent) {
                InfoRow(Icons.Default.Info, "版本", "1.0.0")
                InfoRow(Icons.Default.Code, "ASR", "Qwen3-ASR-0.6B")
                InfoRow(Icons.Default.Code, "TTS", "Qwen3-TTS-12Hz-0.6B")
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    accentColor: androidx.compose.ui.graphics.Color,
    content: @Composable ColumnScope.() -> Unit
) {
    Column {
        // Section header — small caps secondary
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = AppColors.textSecondary(),
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.8.sp,
            modifier = Modifier.padding(start = 16.dp, bottom = 6.dp)
        )
        // Card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(RadiusCard))
                .background(AppColors.surface())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            content()
        }
    }
}

@Composable
private fun InfoRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(
                icon, null,
                tint = AppColors.textSecondary().copy(alpha = 0.7f),
                modifier = Modifier.size(18.dp)
            )
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.text()
            )
        }
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = AppColors.textSecondary()
        )
    }
}
