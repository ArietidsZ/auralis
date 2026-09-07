package com.dialect.interpreter.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.dialect.interpreter.DialectApp
import com.dialect.interpreter.data.ModelRepository
import com.dialect.interpreter.data.ModelRepository.PackageInstallState
import com.dialect.interpreter.data.ModelRepository.PackageState
import com.dialect.interpreter.ui.theme.AppColors
import com.dialect.interpreter.ui.theme.RadiusCard

/**
 * Model setup screen (spec 03 U02): per-package download/verify/runtime state,
 * real byte counts, failure reasons, cancel and retry. Draft manifests are
 * labelled "模型尚未验证" and never shown as ready; nothing simulates a download.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(onBack: () -> Unit) {
    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext as DialectApp
    val modelRepository = appContext.container.modelRepository
    val packageStates by modelRepository.packageStates.collectAsStateWithLifecycle()
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    androidx.compose.runtime.LaunchedEffect(Unit) { modelRepository.refresh() }

    Scaffold(
        containerColor = AppColors.bg(),
        topBar = {
            TopAppBar(
                title = { Text("模型安装", color = AppColors.text(), fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = AppColors.accent())
                    }
                },
                actions = {
                    IconButton(onClick = { scope.launch { modelRepository.refresh() } }) {
                        Icon(Icons.Filled.Refresh, "刷新状态", tint = AppColors.accent())
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "模型随应用分发并在本机校验；校验通过前不会启用相应功能。",
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.textSecondary(),
            )
            for (pkg in ModelRepository.PACKAGE_IDS) {
                packageStates[pkg]?.let { state ->
                    PackageCard(
                        state = state,
                        onInstall = { modelRepository.installPackage(pkg) },
                        onCancel = { modelRepository.cancelInstall(pkg) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PackageCard(
    state: PackageInstallState,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
) {
    val title = when (state.packageId) {
        ModelRepository.PACKAGE_ASR -> "语音识别（ASR）"
        ModelRepository.PACKAGE_MT -> "机器翻译（MT）"
        ModelRepository.PACKAGE_TTS -> "语音合成（TTS）"
        else -> state.packageId
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(RadiusCard))
            .background(AppColors.surface())
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = AppColors.text())
                Text(
                    stateLabel(state),
                    style = MaterialTheme.typography.labelMedium,
                    color = stateColor(state.state),
                )
            }
            when (state.state) {
                PackageState.READY -> Icon(
                    Icons.Filled.CheckCircle, contentDescription = null,
                    tint = AppColors.success(), modifier = Modifier.size(22.dp),
                )
                PackageState.FAILED -> Icon(
                    Icons.Filled.ErrorOutline, contentDescription = null,
                    tint = AppColors.error(), modifier = Modifier.size(22.dp),
                )
                PackageState.INSTALLING, PackageState.VERIFYING -> {}
                PackageState.MISSING, PackageState.INSTALLED, PackageState.RUNTIME_UNAVAILABLE -> Icon(
                    Icons.Filled.Verified, contentDescription = null,
                    tint = AppColors.textSecondary().copy(alpha = 0.5f),
                    modifier = Modifier.size(22.dp),
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(bytesLine(state), style = MaterialTheme.typography.bodySmall, color = AppColors.textSecondary())

        if (state.manifestVerified == false) {
            Spacer(Modifier.height(4.dp))
            Text(
                "模型尚未验证",
                style = MaterialTheme.typography.labelMedium,
                color = AppColors.draftAmber(),
                fontWeight = FontWeight.Medium,
            )
        }

        state.message?.let { message ->
            Spacer(Modifier.height(4.dp))
            Text(message, style = MaterialTheme.typography.bodySmall, color = AppColors.textSecondary())
        }

        when (state.state) {
            PackageState.INSTALLING -> {
                Spacer(Modifier.height(8.dp))
                val fraction = state.expectedBytes?.takeIf { it > 0 }
                    ?.let { (state.receivedBytes.toFloat() / it).coerceIn(0f, 1f) }
                if (fraction != null) {
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth(),
                        color = AppColors.accent(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = AppColors.accent())
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onCancel, modifier = Modifier.height(44.dp)) { Text("取消") }
            }
            PackageState.VERIFYING -> {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = AppColors.accent())
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onCancel, modifier = Modifier.height(44.dp)) { Text("取消") }
            }
            PackageState.MISSING, PackageState.FAILED -> {
                Spacer(Modifier.height(8.dp))
                Button(onClick = onInstall, modifier = Modifier.height(44.dp)) {
                    Text(if (state.state == PackageState.FAILED) "重试安装" else "安装")
                }
            }
            PackageState.INSTALLED, PackageState.READY, PackageState.RUNTIME_UNAVAILABLE -> Unit
        }
    }
}

private fun stateLabel(state: PackageInstallState): String = when (state.state) {
    PackageState.MISSING -> "未安装"
    PackageState.INSTALLING -> "安装中…"
    PackageState.VERIFYING -> "校验中…"
    PackageState.INSTALLED -> "已安装（运行时未验证）"
    PackageState.RUNTIME_UNAVAILABLE -> "运行时不可用"
    PackageState.READY -> "就绪"
    PackageState.FAILED -> "安装失败"
}

private fun bytesLine(state: PackageInstallState): String {
    val installed = state.installedBytes
    return when (state.state) {
        PackageState.INSTALLING -> when (val expected = state.expectedBytes) {
            null, 0L -> "已复制 ${formatBytes(state.receivedBytes)}（总大小未知）"
            else -> "${formatBytes(state.receivedBytes)} / ${formatBytes(expected)}"
        }
        PackageState.MISSING -> when (val expected = state.expectedBytes) {
            null, 0L -> "需要安装（体积未知，清单未定稿）"
            else -> "需要安装，约 ${formatBytes(expected)}"
        }
        else -> if (installed > 0) "已占用 ${formatBytes(installed)}" else ""
    }
}

@Composable
private fun stateColor(state: PackageState) = when (state) {
    PackageState.READY -> AppColors.success()
    PackageState.FAILED, PackageState.RUNTIME_UNAVAILABLE -> AppColors.error()
    PackageState.MISSING -> AppColors.draftAmber()
    else -> AppColors.textSecondary()
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.2f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "%.0f KB".format(bytes / 1_000.0)
    else -> "$bytes B"
}
