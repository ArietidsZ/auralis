package com.dialect.interpreter.data

import android.content.Context
import android.os.StatFs
import android.util.Log
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import com.google.android.play.core.assetpacks.AssetPackLocation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest

/**
 * Model package installation and readiness (spec 01 C03, spec 03 U02).
 *
 * Package states: Missing → Installing → Verifying → Installed → Ready, with
 * RuntimeUnavailable and Failed branches. Installed means bytes verified on
 * disk; Ready additionally requires a verified manifest and a passing runtime
 * probe. Draft/manifest-only manifests can never become Ready — the UI shows
 * "模型尚未验证".
 *
 * Install safety (C03):
 *  - per-package serial installs (single mutex), staging directory on the same
 *    volume, free-space precheck, streaming copy with size verification,
 *    disk-hash verification of the staged copy, atomic directory swap;
 *  - failures only clean this run's staging and always keep the previous
 *    installed version; crash leftovers are recovered on [refresh];
 *  - paths are validated by [ModelPathGuard]; symlinks escaping the models root
 *    are rejected.
 *
 * Sources: Play Asset Delivery packs first, then the plain app assets entry for
 * development builds. Neither fakes a download: when artifacts are absent the
 * package stays Missing with the exact reason.
 */
class ModelRepository(
    private val context: Context,
    private val runtimeProbe: RuntimeReadinessProbe,
) {
    companion object {
        private const val TAG = "ModelRepository"

        /** Layout kept compatible with the inference layer (`dialect_models/<pkg>/…`). */
        const val MODELS_DIR = "dialect_models"
        private const val STAGING_DIR = ".staging"
        private const val BACKUP_DIR = ".backup"

        const val PACKAGE_ASR = "asr"
        const val PACKAGE_MT = "mt"
        const val PACKAGE_TTS = "tts"
        val PACKAGE_IDS = listOf(PACKAGE_ASR, PACKAGE_MT, PACKAGE_TTS)

        private const val SPACE_MARGIN_BYTES = 64L * 1024 * 1024
        private const val COPY_BUFFER_BYTES = 256 * 1024
    }

    enum class PackageState { MISSING, INSTALLING, VERIFYING, INSTALLED, RUNTIME_UNAVAILABLE, READY, FAILED }

    data class PackageInstallState(
        val packageId: String,
        val state: PackageState = PackageState.MISSING,
        /** Manifest status when one was parsed (draft → "模型尚未验证"). */
        val manifestVerified: Boolean? = null,
        val expectedBytes: Long? = null,
        val receivedBytes: Long = 0,
        val installedBytes: Long = 0,
        val message: String? = null,
    )

    /**
     * Real load probe: true → the package's engine loads on this device;
     * false → runtime unavailable (missing native/ONNX runtime, protocol
     * mismatch). Implemented by [ModelRuntimeProbe].
     */
    fun interface RuntimeReadinessProbe {
        suspend fun isRuntimeReady(packageId: String): Boolean
    }

    private val modelsRoot: File
        get() = File(context.filesDir, MODELS_DIR).also { it.mkdirs() }

    private val assetPackManager = AssetPackManagerFactory.getInstance(context)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val installMutex = Mutex()
    private val installJobs = HashMap<String, Job>()

    private val modelAccess = ModelAccessGate()

    /** The caller holds this lease until native inference handles are closed. */
    fun acquireForSession(): AutoCloseable = modelAccess.acquireUse()

    private val _packageStates = MutableStateFlow(
        PACKAGE_IDS.associateWith { PackageInstallState(it) }
    )
    val packageStates: StateFlow<Map<String, PackageInstallState>> = _packageStates

    // ------------------------------------------------------------------ probe

    /** Startup probe: recover crash leftovers, then verify every package. */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        installMutex.withLock {
            // Recovery can rename directories, so it must not race an install
            // or any session still shutting down. Keep the last known status
            // when files are in use; the next idle refresh will revalidate.
            val change = modelAccess.tryAcquireChange() ?: return@withLock
            change.use {
                val packageFiles = ModelPackageFiles(modelsRoot)
                for (pkg in PACKAGE_IDS) {
                    val state = try {
                        packageFiles.recover(pkg)
                        probePackage(pkg)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        PackageInstallState(pkg, PackageState.FAILED, message = "模型校验或恢复失败: ${e.message}")
                    }
                    _packageStates.update { it + (pkg to state) }
                }
            }
        }
    }

    private suspend fun probePackage(pkg: String): PackageInstallState {
        val finalDir = File(modelsRoot, pkg)
        val manifestFile = File(finalDir, "manifest.json")
        if (!finalDir.isDirectory || !manifestFile.exists()) {
            return PackageInstallState(pkg, PackageState.MISSING, installedBytes = dirSize(finalDir))
        }

        val manifest = try {
            ModelManifests.parse(manifestFile.readText())
        } catch (e: ModelManifests.ContractError) {
            return PackageInstallState(pkg, PackageState.FAILED, message = "清单无效 [${e.code}]: ${e.message}")
        } catch (e: Exception) {
            return PackageInstallState(pkg, PackageState.FAILED, message = "清单不可读: ${e.message}")
        }
        if (manifest.packageId != pkg) {
            return PackageInstallState(pkg, PackageState.FAILED, message = "清单 packageId=${manifest.packageId} 与安装目标 $pkg 不符")
        }
        if (manifest.files.isEmpty()) {
            return PackageInstallState(
                pkg, PackageState.MISSING, manifestVerified = manifest.isVerified,
                message = "清单未定义任何文件（模型尚未发布）",
            )
        }

        // Startup verification: sizes AND hashes, every time (review item 4 —
        // no marker may let same-size corruption or a re-pinned manifest pass).
        for (file in manifest.files) {
            val rel = file.path.removePrefix("$pkg/")
            val target = ModelPathGuard.safeResolve(finalDir, rel)
                ?: return PackageInstallState(pkg, PackageState.FAILED, message = "非法文件路径: ${file.path}")
            if (!target.isFile || (file.sizeBytes != null && target.length() != file.sizeBytes)) {
                return PackageInstallState(pkg, PackageState.FAILED, message = "文件缺失或大小不符: ${file.path}")
            }
            if (file.sha256 != null) {
                val actual = sha256(target)
                if (!actual.equals(file.sha256, ignoreCase = true)) {
                    return PackageInstallState(pkg, PackageState.FAILED, message = "文件哈希不符: ${file.path}")
                }
            }
        }

        try {
            ModelManifests.verifyBuildRecord(manifest, finalDir)
        } catch (e: Exception) {
            return PackageInstallState(pkg, PackageState.FAILED, message = "构建来源记录无效: ${e.message}")
        }
        val installedBytes = dirSize(finalDir)
        val blocked = ModelManifests.validateForReady(manifest)
        if (blocked != null) {
            return PackageInstallState(
                pkg, PackageState.INSTALLED, manifestVerified = manifest.isVerified,
                installedBytes = installedBytes, message = blocked,
            )
        }
        return if (runtimeProbe.isRuntimeReady(pkg)) {
            PackageInstallState(pkg, PackageState.READY, manifestVerified = true, installedBytes = installedBytes)
        } else {
            PackageInstallState(
                pkg, PackageState.RUNTIME_UNAVAILABLE, manifestVerified = true,
                installedBytes = installedBytes, message = "模型文件完整，但运行时加载失败",
            )
        }
    }

    // ------------------------------------------------------------------ install

    /** Installs one package from PAD/app assets. Cancel via [cancelInstall]. */
    fun installPackage(pkg: String) {
        require(pkg in PACKAGE_IDS) { "Unknown model package: $pkg" }
        synchronized(installJobs) {
            if (installJobs[pkg]?.isActive == true) return
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    runInstall(pkg)
                } finally {
                    synchronized(installJobs) { installJobs.remove(pkg) }
                }
            }
            installJobs[pkg] = job
            job.start()
        }
    }

    fun cancelInstall(pkg: String) {
        synchronized(installJobs) { installJobs[pkg] }?.cancel()
    }

    private suspend fun runInstall(pkg: String) {
        val previousState = _packageStates.value[pkg] ?: PackageInstallState(pkg)
        setState(pkg) { it.copy(state = PackageState.INSTALLING, receivedBytes = 0, message = null) }

        try {
            installMutex.withLock {
                val change = modelAccess.tryAcquireChange()
                    ?: throw InstallException("模型仍在使用：请等待会话停止后再安装")
                change.use { doInstall(pkg) }
            }
        } catch (e: CancellationException) {
            cleanupStaging(pkg)
            setState(pkg) {
                // Cancelled mid-install: keep whatever the previous probe found.
                previousState.copy(message = "已取消")
            }
            throw e
        } catch (e: InstallException) {
            cleanupStaging(pkg)
            setState(pkg) { it.copy(state = PackageState.FAILED, message = e.message) }
        } catch (e: Exception) {
            cleanupStaging(pkg)
            Log.w(TAG, "Install of $pkg failed", e)
            setState(pkg) { it.copy(state = PackageState.FAILED, message = "安装失败: ${e.message}") }
        }
    }

    private class InstallException(message: String) : Exception(message)

    private suspend fun doInstall(pkg: String) = withContext(Dispatchers.IO) {
        val source = resolveSource(pkg)
        if (source == null) {
            throw InstallException("找不到模型来源：资产包与应用内置资源均未提供 $pkg 文件")
        }

        // 1. Manifest from the source (never from a previous install).
        val manifestContent = source.readManifest(pkg)
            ?: throw InstallException("来源中没有 $pkg 模型清单（构建可能未打包模型产物）")
        val manifest = try {
            ModelManifests.parse(manifestContent)
        } catch (e: ModelManifests.ContractError) {
            throw InstallException("清单无效 [${e.code}]: ${e.message}")
        }
        if (manifest.packageId != pkg) {
            throw InstallException("清单 packageId=${manifest.packageId} 与安装目标 $pkg 不符")
        }
        if (manifest.files.isEmpty()) {
            throw InstallException("清单未定义任何文件（模型尚未发布），无法安装")
        }
        val totalBytes = manifest.files.sumOf { it.sizeBytes ?: 0L }
        setState(pkg) { it.copy(expectedBytes = totalBytes, manifestVerified = manifest.isVerified) }

        // 2. Free space precheck (staging and final copy share the same volume).
        if (totalBytes > 0) {
            val available = StatFs(modelsRoot.path).availableBytes
            if (available < totalBytes * 2 + SPACE_MARGIN_BYTES) {
                throw InstallException(
                    "剩余空间不足：需要约 ${(totalBytes / 1_000_000)}MB，可用 ${(available / 1_000_000)}MB"
                )
            }
        }

        // 3. Copy into staging (same volume), verifying sizes; cancel-aware.
        val staging = File(modelsRoot, "$STAGING_DIR/$pkg")
        staging.deleteRecursively()
        staging.mkdirs()
        var copied = 0L
        for (file in manifest.files) {
            ensureActive()
            val rel = file.path.removePrefix("$pkg/")
            val dst = ModelPathGuard.safeResolve(staging, rel)
                ?: throw InstallException("非法文件路径: ${file.path}")
            dst.parentFile?.mkdirs()
            val bytesWritten = copyAndReport(source, file.path, dst, pkg, copied)
            if (file.sizeBytes != null && bytesWritten != file.sizeBytes) {
                throw InstallException("文件大小不符: ${file.path}")
            }
            copied += bytesWritten
        }

        // 4. Verify the staged bytes from disk (hash + size), then place manifest.
        setState(pkg) { it.copy(state = PackageState.VERIFYING) }
        for (file in manifest.files) {
            ensureActive()
            val rel = file.path.removePrefix("$pkg/")
            val staged = ModelPathGuard.safeResolve(staging, rel)
                ?: throw InstallException("非法文件路径: ${file.path}")
            if (!staged.isFile) throw InstallException("暂存文件丢失: ${file.path}")
            if (file.sizeBytes != null && staged.length() != file.sizeBytes) {
                throw InstallException("暂存文件大小不符: ${file.path}")
            }
            if (file.sha256 != null && !sha256(staged).equals(file.sha256, ignoreCase = true)) {
                throw InstallException("文件哈希不符: ${file.path}（来源损坏或被篡改）")
            }
        }
        ModelManifests.verifyBuildRecord(manifest, staging)
        File(staging, "manifest.json").writeText(manifestContent)

        // 5. Atomic swap: never delete the current package before the new one
        //    is complete; restore the backup if the rename fails.
        ModelPackageFiles(modelsRoot).activate(pkg)

        setState(pkg) { it.copy(receivedBytes = copied) }

        // 6. Re-probe the freshly installed package for the authoritative state.
        val probed = probePackage(pkg)
        _packageStates.update { it + (pkg to probed) }
        Log.i(TAG, "Package $pkg installed (${copied / 1_000_000}MB)")
    }

    private suspend fun copyAndReport(
        source: ModelSource,
        relativePath: String,
        dst: File,
        pkg: String,
        copiedBefore: Long,
    ): Long {
        val input = source.open(relativePath)
            ?: throw InstallException("来源缺少文件: $relativePath")
        var written = 0L
        input.use { stream ->
            FileOutputStream(dst).use { output ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    kotlin.coroutines.coroutineContext.ensureActive() // honors install cancellation between chunks
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    written += read
                    if (written % (4L * COPY_BUFFER_BYTES) < COPY_BUFFER_BYTES) {
                        setState(pkg) { it.copy(receivedBytes = copiedBefore + written) }
                    }
                }
                output.flush()
                output.fd.sync()
            }
        }
        return written
    }

    private fun cleanupStaging(pkg: String) {
        runCatching { File(modelsRoot, "$STAGING_DIR/$pkg").deleteRecursively() }
    }

    private fun setState(pkg: String, transform: (PackageInstallState) -> PackageInstallState) {
        _packageStates.update { current ->
            current + (pkg to transform(current[pkg] ?: PackageInstallState(pkg)))
        }
    }

    // ------------------------------------------------------------------ sources

    /** Pack delivery first, then the plain app-assets dev entry. */
    private fun resolveSource(pkg: String): ModelSource? {
        val packLocation: AssetPackLocation? = runCatching {
            assetPackManager.getPackLocation("asset_pack_$pkg")
        }.getOrNull()
        val packPath = packLocation?.assetsPath()
        if (packPath != null && File(packPath).isDirectory) {
            return DirectorySource(File(packPath))
        }
        if (hasAppAssets(pkg)) {
            return AppAssetSource(context)
        }
        return null
    }

    private fun hasAppAssets(pkg: String): Boolean =
        manifestCandidatePaths(pkg).any { path ->
            runCatching { context.assets.open(path).use { it.read() >= 0 } }.getOrDefault(false)
        }

    // ------------------------------------------------------------------ misc

    fun getDownloadedSize(): Long = PACKAGE_IDS.sumOf { dirSize(File(modelsRoot, it)) }

    /** Deletes all installed packages (Settings "重新安装" path); serialized with installs. */
    suspend fun deleteAllModels() = withContext(Dispatchers.IO) {
        installMutex.withLock {
            val change = modelAccess.tryAcquireChange()
                ?: throw IllegalStateException("模型仍在使用：请等待会话停止后再删除")
            change.use {
                PACKAGE_IDS.forEach { File(modelsRoot, it).deleteRecursively() }
                File(modelsRoot, BACKUP_DIR).deleteRecursively()
                File(modelsRoot, STAGING_DIR).deleteRecursively()
                _packageStates.update { current ->
                    current.mapValues { PackageInstallState(it.key) }
                }
            }
        }
    }

    private fun dirSize(dir: File?): Long =
        dir?.takeIf { it.isDirectory }?.walkTopDown()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/** A read-only location that can provide model files by relative path. */
private interface ModelSource {
    fun open(relativePath: String): InputStream?
    fun readManifest(pkg: String): String?
}

private fun manifestCandidatePaths(pkg: String): List<String> =
    // "models/<pkg>.json" is the shared-manifest layout (interface-C); the others
    // cover legacy pack layouts.
    listOf("models/$pkg.json", "$pkg/manifest.json", "$pkg.json")

/** Files on disk (Play Asset Delivery pack directory or any folder — testable). */
private class DirectorySource(private val root: File) : ModelSource {
    override fun open(relativePath: String): InputStream? {
        val file = ModelPathGuard.safeResolve(root, relativePath) ?: return null
        if (!file.isFile) return null
        return runCatching { file.inputStream() }.getOrNull()
    }

    override fun readManifest(pkg: String): String? =
        manifestCandidatePaths(pkg).firstNotNullOfOrNull { path ->
            val file = ModelPathGuard.safeResolve(root, path) ?: return@firstNotNullOfOrNull null
            if (file.isFile) runCatching { file.readText() }.getOrNull() else null
        }
}

/** Android app assets (development/sideload entry). */
private class AppAssetSource(private val context: Context) : ModelSource {
    override fun open(relativePath: String): InputStream? =
        runCatching { context.assets.open(relativePath) }.getOrNull()

    override fun readManifest(pkg: String): String? =
        manifestCandidatePaths(pkg).firstNotNullOfOrNull { path ->
            runCatching {
                context.assets.open(path).bufferedReader().use { it.readText() }
            }.getOrNull()
        }
}
