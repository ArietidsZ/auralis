package com.dialect.interpreter.data

import android.content.Context
import android.util.Log
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import com.google.android.play.core.assetpacks.AssetPackLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest

/**
 * Manages bundled model files delivered via Play Asset Delivery.
 *
 * Models are packaged as install-time asset packs:
 *   - asset_pack_asr: ASR encoder + decoder + tokenizer
 *   - asset_pack_tts: Speaker encoder + Talker LM + Vocoder + tokenizer
 *
 * On first run, assets are extracted to internal storage for ONNX Runtime access.
 */
class ModelRepository(private val context: Context) {

    companion object {
        private const val TAG = "ModelRepository"
        private const val MODELS_DIR = "dialect_models"
        private const val ASR_PACK = "asset_pack_asr"
        private const val TTS_PACK = "asset_pack_tts"
        private const val MT_PACK = "asset_pack_mt"
        private val OPTIONAL_MANIFEST_FILES = listOf(
            "asr/manifest.json",
            "tts/manifest.json",
            "mt/manifest.json"
        )

        val ASR_MODEL_FILES = listOf(
            "asr/asr_encoder_int4.onnx",
            "asr/asr_decoder_int4.onnx",
            "asr/tokenizer/tokenizer.json",
        )

        val TTS_MODEL_FILES = listOf(
            "tts/speaker_encoder_int4.onnx",
            "tts/talker_lm_int4.onnx",
            "tts/vocoder_int4.onnx",
            "tts/tokenizer/tokenizer.json",
        )

        val MT_MODEL_FILES = listOf(
            "mt/Hy-MT1.5-1.8B-1.25bit.gguf",
        )
    }

    private data class IntegrityRule(
        val path: String,
        val sizeBytes: Long?,
        val sha256: String?
    )

    data class ExtractionProgress(
        val totalFiles: Int = 0,
        val completedFiles: Int = 0,
        val currentFileName: String = "",
        val isComplete: Boolean = false,
        val error: String? = null
    )

    private val _extractionProgress = MutableStateFlow(ExtractionProgress())
    val extractionProgress: StateFlow<ExtractionProgress> = _extractionProgress

    private val assetPackManager = AssetPackManagerFactory.getInstance(context)
    private val json = Json { ignoreUnknownKeys = true }
    private val modelsDir: File
        get() = File(context.filesDir, MODELS_DIR).also { it.mkdirs() }

    /**
     * Check if models have been extracted to internal storage.
     */
    fun areModelsReady(): Boolean =
        areAsrModelsReady() && areTtsModelsReady() && areMtModelsReady() && verifyManifestSizesOnly()

    fun areAsrModelsReady(): Boolean = ASR_MODEL_FILES.all {
        File(modelsDir, it).let { f -> f.exists() && f.length() > 0 }
    }

    fun areTtsModelsReady(): Boolean = TTS_MODEL_FILES.all {
        File(modelsDir, it).let { f -> f.exists() && f.length() > 0 }
    }

    fun areMtModelsReady(): Boolean = MT_MODEL_FILES.all {
        File(modelsDir, it).let { f -> f.exists() && f.length() > 0 }
    }

    /**
     * Extract bundled model assets to internal storage.
     * Models are packaged as install-time asset packs and need to be copied
     * to the filesystem for ONNX Runtime to access them.
     */
    suspend fun extractBundledModels() = withContext(Dispatchers.IO) {
        val asrFiles = ASR_MODEL_FILES + "asr/manifest.json"
        val ttsFiles = TTS_MODEL_FILES + "tts/manifest.json"
        val mtFiles = MT_MODEL_FILES + "mt/manifest.json"
        val allFiles = asrFiles + ttsFiles + mtFiles
        _extractionProgress.value = ExtractionProgress(totalFiles = allFiles.size)

        try {
            // Try Play Asset Delivery first
            val asrLocation = assetPackManager.getPackLocation(ASR_PACK)
            val ttsLocation = assetPackManager.getPackLocation(TTS_PACK)
            val mtLocation = assetPackManager.getPackLocation(MT_PACK)

            if (asrLocation != null && ttsLocation != null && mtLocation != null) {
                extractFromAssetPack(asrLocation, asrFiles, "ASR")
                extractFromAssetPack(ttsLocation, ttsFiles, "TTS")
                extractFromAssetPack(mtLocation, mtFiles, "MT")
            } else {
                // Fallback: extract from app assets (for debug builds / sideloading)
                Log.i(TAG, "Asset packs not found, extracting from app assets")
                extractFromAppAssets(allFiles)
            }

            verifyIntegrityIfAvailable()

            _extractionProgress.value = _extractionProgress.value.copy(isComplete = true)
            Log.i(TAG, "Model extraction complete: ${getDownloadedSize() / 1_000_000}MB")

        } catch (e: Exception) {
            Log.e(TAG, "Extraction failed", e)
            _extractionProgress.value = _extractionProgress.value.copy(
                error = "模型提取失败: ${e.message}"
            )
            throw e
        }
    }

    /**
     * Extract models from a Play Asset Delivery asset pack.
     */
    private fun extractFromAssetPack(
        location: AssetPackLocation,
        files: List<String>,
        label: String
    ) {
        val assetsPath = location.assetsPath() ?: return
        Log.i(TAG, "Extracting $label from asset pack: $assetsPath")

        for (relativePath in files) {
            val srcFile = File(assetsPath, relativePath)
            val dstFile = File(modelsDir, relativePath)

            if (dstFile.exists() && dstFile.length() > 0) {
                Log.d(TAG, "  Skipping (exists): $relativePath")
                updateProgress(relativePath)
                continue
            }

            dstFile.parentFile?.mkdirs()

            if (srcFile.exists()) {
                copyFileAtomically(srcFile.inputStream(), dstFile)
            } else {
                // Some asset packs deliver as streams
                Log.w(TAG, "  File not found at pack path, trying asset manager: $relativePath")
            }

            Log.i(TAG, "  Extracted: $relativePath (${dstFile.length() / 1_000_000}MB)")
            updateProgress(relativePath)
        }
    }

    /**
     * Extract from app's bundled assets (fallback for debug/sideloaded APKs).
     * When sideloading, place model files in app/src/main/assets/
     */
    private fun extractFromAppAssets(files: List<String>) {
        val assetManager = context.assets

        for (relativePath in files) {
            val dstFile = File(modelsDir, relativePath)

            if (dstFile.exists() && dstFile.length() > 0) {
                updateProgress(relativePath)
                continue
            }

            dstFile.parentFile?.mkdirs()

            try {
                copyFileAtomically(assetManager.open(relativePath), dstFile)
                Log.i(TAG, "Extracted from assets: $relativePath (${dstFile.length() / 1_000_000}MB)")
            } catch (e: Exception) {
                Log.w(TAG, "Asset not found: $relativePath (expected for initial build)")
            }

            updateProgress(relativePath)
        }
    }

    private fun copyFileAtomically(input: InputStream, dstFile: File) {
        val tempFile = File(dstFile.parentFile, "${dstFile.name}.tmp")

        try {
            input.use { source ->
                FileOutputStream(tempFile).use { output ->
                    source.copyTo(output, bufferSize = 8192)
                    output.flush()
                }
            }

            if (tempFile.length() <= 0L) {
                throw IllegalStateException("Extracted file is empty: ${dstFile.name}")
            }

            if (dstFile.exists() && !dstFile.delete()) {
                throw IllegalStateException("Failed to replace existing file: ${dstFile.absolutePath}")
            }

            if (!tempFile.renameTo(dstFile)) {
                tempFile.copyTo(dstFile, overwrite = true)
                tempFile.delete()
            }
        } catch (e: Exception) {
            tempFile.delete()
            dstFile.delete()
            throw e
        }
    }

    private fun verifyIntegrityIfAvailable() {
        val rules = loadIntegrityRules()
        if (rules.isEmpty()) {
            Log.i(TAG, "No model manifest found, skipping integrity verification")
            return
        }

        for (rule in rules) {
            val file = File(modelsDir, rule.path)
            if (!file.exists() || file.length() <= 0L) {
                throw IllegalStateException("缺失模型文件: ${rule.path}")
            }

            rule.sizeBytes?.let { expected ->
                if (file.length() != expected) {
                    file.delete()
                    throw IllegalStateException(
                        "模型大小校验失败: ${rule.path} expected=${expected} actual=${file.length()}"
                    )
                }
            }

            rule.sha256?.takeIf { it.isNotBlank() }?.let { expectedHash ->
                val actualHash = sha256(file)
                if (!actualHash.equals(expectedHash, ignoreCase = true)) {
                    file.delete()
                    throw IllegalStateException("模型哈希校验失败: ${rule.path}")
                }
            }
        }

        Log.i(TAG, "Model integrity verification passed (${rules.size} entries)")
    }

    private fun verifyManifestSizesOnly(): Boolean {
        val rules = loadIntegrityRules()
        if (rules.isEmpty()) return true

        return rules.all { rule ->
            val file = File(modelsDir, rule.path)
            file.exists() && file.length() > 0L &&
                (rule.sizeBytes == null || file.length() == rule.sizeBytes)
        }
    }

    private fun loadIntegrityRules(): List<IntegrityRule> {
        val rules = mutableListOf<IntegrityRule>()

        for (relativeManifestPath in OPTIONAL_MANIFEST_FILES) {
            val manifestFile = File(modelsDir, relativeManifestPath)
            if (!manifestFile.exists() || manifestFile.length() <= 0L) continue

            val parsed = runCatching {
                parseManifestRules(manifestFile.readText())
            }.getOrElse { error ->
                Log.w(TAG, "Failed to parse model manifest $relativeManifestPath: ${error.message}")
                emptyList()
            }

            rules += parsed
        }

        return rules
            .asReversed()
            .distinctBy { it.path }
            .asReversed()
    }

    private fun parseManifestRules(content: String): List<IntegrityRule> {
        val root = json.parseToJsonElement(content).jsonObject
        val filesNode = root["files"] ?: return emptyList()

        return when (filesNode) {
            is JsonArray -> parseRulesFromArray(filesNode)
            is JsonObject -> parseRulesFromObject(filesNode)
            else -> emptyList()
        }
    }

    private fun parseRulesFromArray(array: JsonArray): List<IntegrityRule> {
        return array.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null

            val path = obj["path"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val sizeBytes =
                obj["size_bytes"]?.jsonPrimitive?.longOrNull
                    ?: obj["sizeBytes"]?.jsonPrimitive?.longOrNull
            val sha256 = obj["sha256"]?.jsonPrimitive?.contentOrNull

            IntegrityRule(path = path, sizeBytes = sizeBytes, sha256 = sha256)
        }
    }

    private fun parseRulesFromObject(obj: JsonObject): List<IntegrityRule> {
        return obj.mapNotNull { (path, value) ->
            when (value) {
                is JsonObject -> {
                    val sizeBytes =
                        value["size_bytes"]?.jsonPrimitive?.longOrNull
                            ?: value["sizeBytes"]?.jsonPrimitive?.longOrNull
                    val sha256 = value["sha256"]?.jsonPrimitive?.contentOrNull
                        ?: value["hash"]?.jsonPrimitive?.contentOrNull
                    IntegrityRule(path = path, sizeBytes = sizeBytes, sha256 = sha256)
                }

                else -> null
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun updateProgress(currentFile: String) {
        val current = _extractionProgress.value
        _extractionProgress.value = current.copy(
            completedFiles = current.completedFiles + 1,
            currentFileName = currentFile.substringAfterLast("/")
        )
    }

    /**
     * Get total size of extracted models on disk.
     */
    fun getDownloadedSize(): Long {
        return modelsDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    /**
     * Delete extracted models (to re-extract).
     */
    fun deleteModels() {
        modelsDir.deleteRecursively()
        Log.i(TAG, "Extracted models deleted")
    }
}
