package com.dialect.interpreter.ui.screens

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dialect.interpreter.DialectApp
import com.dialect.interpreter.data.ModelRepository
import com.dialect.interpreter.data.ModelRepository.PackageState
import com.dialect.interpreter.ui.theme.AppColors
import com.dialect.interpreter.ui.theme.RadiusCard

/**
 * Settings screen (spec U01/U02): honest runtime/model information. The
 * execution-provider claim is "CPU (ONNX Runtime)" until a device comparison
 * justifies anything else (spec: no speculative acceleration labels).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onNavigateToSetup: () -> Unit,
) {
    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext as DialectApp
    val container = appContext.container
    val modelRepository = container.modelRepository
    val packageStates by modelRepository.packageStates.collectAsStateWithLifecycle()

    val socName = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else Build.HARDWARE
    }

    Scaffold(
        containerColor = AppColors.bg(),
        topBar = {
            TopAppBar(
                title = { Text("设置", color = AppColors.text(), fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = AppColors.accent())
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = AppColors.surface().copy(alpha = 0.92f)
                ),
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SettingsSection("运行环境") {
                InfoRow(Icons.Filled.Memory, "运算设备", "本机 CPU")
                InfoRow(Icons.Filled.Smartphone, "SoC", socName.ifBlank { "—" })
                InfoRow(Icons.Filled.Info, "处理方式", "全部在本机离线完成")
            }

            SettingsSection("模型") {
                InfoRow(Icons.Filled.Storage, "磁盘占用", "${modelRepository.getDownloadedSize() / 1_000_000} MB")
                for (pkg in ModelRepository.PACKAGE_IDS) {
                    val state = packageStates[pkg]
                    val label = when (pkg) {
                        ModelRepository.PACKAGE_ASR -> "语音识别"
                        ModelRepository.PACKAGE_MT -> "机器翻译"
                        else -> "语音合成"
                    }
                    val value = when (state?.state) {
                        PackageState.READY -> "就绪"
                        PackageState.INSTALLED -> "已安装（未验证）"
                        PackageState.INSTALLING -> "安装中"
                        PackageState.VERIFYING -> "校验中"
                        PackageState.RUNTIME_UNAVAILABLE -> "运行时不可用"
                        PackageState.FAILED -> "安装失败"
                        else -> "未安装"
                    }
                    InfoRow(
                        when (pkg) {
                            ModelRepository.PACKAGE_ASR -> Icons.Filled.Hearing
                            ModelRepository.PACKAGE_MT -> Icons.Filled.Translate
                            else -> Icons.Filled.RecordVoiceOver
                        },
                        label,
                        value,
                    )
                }
                TextButton(onClick = onNavigateToSetup) { Text("管理模型安装") }
            }

            SettingsSection("模型来源") {
                InfoRow(Icons.Filled.Code, "ASR", "Qwen3-ASR-0.6B（候选）")
                InfoRow(Icons.Filled.Code, "MT", "Hy-MT-1.5-1.8B（1.25-bit 候选）")
                InfoRow(Icons.Filled.Code, "TTS", "Qwen3-TTS-12Hz-0.6B（候选）")
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = AppColors.textSecondary(),
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.8.sp,
            modifier = Modifier.padding(start = 16.dp, bottom = 6.dp),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(RadiusCard))
                .background(AppColors.surface())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun InfoRow(icon: ImageVector, label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                icon, null,
                tint = AppColors.textSecondary().copy(alpha = 0.7f),
                modifier = Modifier.size(18.dp),
            )
            Text(label, style = MaterialTheme.typography.bodyMedium, color = AppColors.text())
        }
        Text(value, style = MaterialTheme.typography.bodyMedium, color = AppColors.textSecondary())
    }
}
