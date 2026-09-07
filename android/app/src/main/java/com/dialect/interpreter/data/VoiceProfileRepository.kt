package com.dialect.interpreter.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.SecureRandom
import java.util.Locale

/**
 * Voice profile storage (spec 03 U04, spec 02 R02 resolver input).
 *
 * Safety invariants:
 *  - IDs are UUIDs (legacy `profile_<millis>` still readable); every audio and
 *    metadata path is derived from the validated ID, never from metadata content.
 *  - Audio + metadata are written atomically and only become visible together;
 *    a failed save leaves no half profile.
 *  - Reference audio stays in app-private storage (backup excluded in Manifest);
 *    no audio content or transcription is logged.
 *  - Delete validates the ID, removes the audio, metadata and any derived
 *    embedding file, and reports retryable failures.
 */
class VoiceProfileRepository(
    private val context: Context,
    private val embeddingExtractor: SpeakerEmbeddingExtractor? = null,
) {

    companion object {
        private const val TAG = "VoiceProfileRepo"
        private const val PROFILES_DIR = "voice_profiles"

        /** Minimum reference duration the UI advertises and save enforces. */
        const val MIN_DURATION_MS = 3_000L
        const val REFERENCE_SAMPLE_RATE = 16_000

        /** Embedding sanity gate (review item 7): non-empty, finite, non-zero. */
        fun sanitizeEmbedding(embedding: FloatArray?): FloatArray? = embedding?.takeIf { arr ->
            arr.isNotEmpty() && arr.all { it.isFinite() } && arr.any { it != 0f }
        }

        private val UUID_REGEX = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
        private val LEGACY_ID_REGEX = Regex("^profile_[0-9]{1,16}$")
    }

    @Serializable
    data class VoiceProfile(
        val id: String,
        val name: String,
        val createdAt: Long,
        val durationMs: Long,
        val sampleRate: Int = REFERENCE_SAMPLE_RATE,
        val channels: Int = 1,
        val format: String = "wav/pcm16",
        val audioBytes: Long = 0,
    )

    /** Validated reference handed to the session/clone path (spec R02 resolver). */
    class VoiceReference(
        val profileId: String,
        val pcm: FloatArray,
        val sampleRate: Int,
    )

    /**
     * Extracts a speaker embedding from validated reference PCM. Implemented by
     * the inference side (TTS speaker encoder); when not wired, cloning stays
     * disabled — the repository never fabricates an embedding.
     */
    fun interface SpeakerEmbeddingExtractor {
        suspend fun extract(referencePcm: FloatArray, profileId: String): FloatArray?
    }

    sealed interface SaveResult {
        data class Ok(val profile: VoiceProfile) : SaveResult
        data class Rejected(val reason: String) : SaveResult
        data class Failed(val error: String) : SaveResult
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _profilesFlow = MutableStateFlow<List<VoiceProfile>>(emptyList())

    /** Observable profile list; call [reloadProfiles] after mutations. */
    val profilesFlow: StateFlow<List<VoiceProfile>> = _profilesFlow.asStateFlow()

    suspend fun reloadProfiles() {
        _profilesFlow.value = getProfiles()
    }

    private val profilesDir: File
        get() = File(context.filesDir, PROFILES_DIR).also { it.mkdirs() }

    // ------------------------------------------------------------------ save

    suspend fun saveProfile(name: String, pcm: FloatArray, sampleRate: Int = REFERENCE_SAMPLE_RATE): SaveResult =
        withContext(Dispatchers.IO) {
            val trimmedName = name.trim()
            if (trimmedName.isEmpty()) return@withContext SaveResult.Rejected("档案名称不能为空")

            try {
                WavCodec.validateSamples(pcm)
            } catch (e: WavCodec.WavFormatException) {
                return@withContext SaveResult.Rejected(e.message ?: "音频数据无效")
            }
            if (WavCodec.isAllZero(pcm)) {
                return@withContext SaveResult.Rejected("录音内容为静音，不能作为声音参考")
            }
            if (sampleRate != REFERENCE_SAMPLE_RATE) {
                return@withContext SaveResult.Rejected("仅支持 ${REFERENCE_SAMPLE_RATE / 1000}kHz 采样率录音")
            }
            val durationMs = pcm.size.toLong() * 1000L / sampleRate
            if (durationMs < MIN_DURATION_MS) {
                return@withContext SaveResult.Rejected("参考音频至少 ${MIN_DURATION_MS / 1000} 秒")
            }

            val id = newId()
            val audioFile = File(profilesDir, "$id.wav")
            val metaFile = File(profilesDir, "$id.meta")

            try {
                val wavBytes = WavCodec.encodeMonoPcm16(pcm, sampleRate)
                WavCodec.writeAtomically(audioFile, wavBytes)
                val profile = VoiceProfile(
                    id = id,
                    name = trimmedName,
                    createdAt = System.currentTimeMillis(),
                    durationMs = durationMs,
                    sampleRate = sampleRate,
                    channels = 1,
                    format = "wav/pcm16",
                    audioBytes = audioFile.length(),
                )
                writeMetaAtomically(metaFile, profile)
                Log.i(TAG, "Profile saved: id=$id durationMs=$durationMs bytes=${profile.audioBytes}")
                SaveResult.Ok(profile)
            } catch (e: Exception) {
                // Roll back both files: a failed save must not leave a half profile.
                audioFile.delete()
                metaFile.delete()
                Log.w(TAG, "Profile save failed")
                SaveResult.Failed(e.message ?: "保存失败")
            }
        }

    private fun newId(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        val sb = StringBuilder(36)
        for (i in bytes.indices) {
            if (i == 4 || i == 6 || i == 8 || i == 10) sb.append('-')
            sb.append(String.format(Locale.US, "%02x", bytes[i]))
        }
        // Set version 4 / variant bits for a well-formed UUID v4.
        sb[14] = '4'
        return sb.toString()
    }

    // ------------------------------------------------------------------ read

    suspend fun getProfiles(): List<VoiceProfile> = withContext(Dispatchers.IO) {
        profilesDir.listFiles()
            ?.mapNotNull { file -> readProfileMeta(file) }
            ?.filter { File(profilesDir, "${it.id}.wav").exists() }
            ?.sortedByDescending { it.createdAt }
            ?: emptyList()
    }

    /**
     * Resolve a profile ID into validated reference PCM. Returns null when the
     * profile is missing or its audio fails validation — callers treat that as
     * "cloning not available" (never a zero-filled embedding, spec R02).
     *
     * Rate policy (review item 7): reference PCM must be 16 kHz mono. Channels
     * are faithfully downmixed; other sample rates are REJECTED (no invented
     * resampling) — in-app recordings are always 16 kHz.
     */
    suspend fun loadReference(profileId: String?): VoiceReference? = withContext(Dispatchers.IO) {
        if (profileId.isNullOrBlank() || !isValidId(profileId)) return@withContext null
        val audioFile = File(profilesDir, "$profileId.wav")
        if (!audioFile.exists()) return@withContext null
        // Size precheck before reading the whole file into memory.
        if (audioFile.length() <= 0 || audioFile.length() > WavCodec.MAX_BYTES) {
            return@withContext null
        }
        val pcm = try {
            val decoded = WavCodec.decode(audioFile.readBytes())
            if (decoded.sampleRate != REFERENCE_SAMPLE_RATE) {
                Log.w(TAG, "Reference id=$profileId has unsupported rate ${decoded.sampleRate}")
                return@withContext null
            }
            if (decoded.channels != 1) decoded.toMono() else decoded.samples
        } catch (e: Exception) {
            Log.w(TAG, "Reference audio invalid for id=$profileId")
            return@withContext null
        }
        if (pcm.any { !it.isFinite() }) {
            Log.w(TAG, "Reference id=$profileId contains non-finite samples")
            return@withContext null
        }
        if (pcm.isEmpty() || WavCodec.isAllZero(pcm)) return@withContext null
        VoiceReference(profileId = profileId, pcm = pcm, sampleRate = REFERENCE_SAMPLE_RATE)
    }

    /** Full preview decode (also validates before playback). */
    suspend fun loadPreviewAudio(profileId: String): Pair<FloatArray, Int>? = withContext(Dispatchers.IO) {
        if (!isValidId(profileId)) return@withContext null
        val audioFile = File(profilesDir, "$profileId.wav")
        if (!audioFile.isFile || audioFile.length() <= 0 || audioFile.length() > WavCodec.MAX_BYTES) {
            return@withContext null
        }
        try {
            val decoded = WavCodec.decode(audioFile.readBytes())
            val mono = if (decoded.channels != 1) decoded.toMono() else decoded.samples
            mono to decoded.sampleRate
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Spec R02 / interface-A B-request-1: resolve a profile id into a real
     * speaker embedding for TTS conditioning. Returns null — never a zero or
     * synthetic vector — when the id is invalid, the reference audio fails
     * validation, or no embedding extractor is wired (cloning disabled).
     */
    suspend fun resolveSpeakerEmbedding(profileId: String?): FloatArray? {
        if (profileId.isNullOrBlank() || !isValidId(profileId)) return null
        val extractor = embeddingExtractor ?: return null
        val reference = loadReference(profileId) ?: return null
        val embedding = try {
            extractor.extract(reference.pcm, reference.profileId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Embedding extraction failed for id=$profileId")
            null
        }
        return sanitizeEmbedding(embedding)
    }

    private fun readProfileMeta(metaCandidate: File): VoiceProfile? {
        if (!metaCandidate.name.endsWith(".meta")) return null
        val id = metaCandidate.nameWithoutExtension
        if (!isValidId(id)) return null
        val content = try {
            metaCandidate.readText().trim()
        } catch (e: Exception) {
            return null
        }
        if (content.isEmpty()) return null

        val parsed = parseMeta(content, id)
            ?: return null.also { Log.w(TAG, "Unreadable profile metadata: id=$id") }

        // Migration: after a successful read, rewrite legacy metadata in v2 form.
        if (parsed.second) {
            runCatching { writeMetaAtomically(metaCandidate, parsed.first) }
        }
        return parsed.first.copy(audioBytes = File(profilesDir, "$id.wav").length())
    }

    /**
     * Returns (profile, needsMigration). Accepts v2 JSON, v1 JSON
     * ({version:1, id, name, createdAt, durationMs}) and the legacy line format
     * (name\ncreatedAt\ndurationMs). Missing sample fields fall back to the
     * app's recorded defaults; nothing is invented beyond documented defaults.
     */
    private fun parseMeta(content: String, id: String): Pair<VoiceProfile, Boolean>? {
        if (content.startsWith("{")) {
            return try {
                val v2 = json.decodeFromString<VoiceProfile>(content)
                if (v2.id != id) return null
                v2 to false
            } catch (e: Exception) {
                try {
                    val v1 = json.decodeFromString<LegacyMetaV1>(content)
                    if (v1.id != id) return null
                    VoiceProfile(
                        id = id,
                        name = v1.name.ifBlank { "未命名" },
                        createdAt = v1.createdAt,
                        durationMs = v1.durationMs,
                        sampleRate = REFERENCE_SAMPLE_RATE,
                        channels = 1,
                        format = "wav/pcm16",
                    ) to true
                } catch (e2: Exception) {
                    null
                }
            }
        }
        // Legacy line format.
        val lines = content.lines()
        if (lines.size < 3) return null
        val name = lines[0].trim().ifBlank { "未命名" }
        val createdAt = lines[1].trim().toLongOrNull() ?: return null
        val durationMs = lines[2].trim().toLongOrNull() ?: return null
        return VoiceProfile(
            id = id,
            name = name,
            createdAt = createdAt,
            durationMs = durationMs,
            sampleRate = REFERENCE_SAMPLE_RATE,
            channels = 1,
            format = "wav/pcm16",
        ) to true
    }

    @Serializable
    private data class LegacyMetaV1(
        val version: Int = 1,
        val id: String = "",
        val name: String = "",
        val createdAt: Long = 0,
        val durationMs: Long = 0,
    )

    // ------------------------------------------------------------------ delete

    /** Deletes audio + metadata + derived embedding files for a validated ID. */
    suspend fun deleteProfile(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (!isValidId(id)) {
            return@withContext Result.failure(IllegalArgumentException("非法档案 ID"))
        }
        // Files are strictly derived from the validated ID within profilesDir.
        val candidates = listOf(
            File(profilesDir, "$id.wav"),
            File(profilesDir, "$id.meta"),
            File(profilesDir, "$id.embedding"),
        )
        val failed = candidates.filter { it.exists() && !it.delete() }
        if (failed.isEmpty()) {
            Log.i(TAG, "Profile deleted: id=$id")
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("部分文件删除失败，请重试"))
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun isValidId(id: String): Boolean =
        UUID_REGEX.matches(id) || LEGACY_ID_REGEX.matches(id)

    private fun writeMetaAtomically(metaFile: File, profile: VoiceProfile) {
        WavCodec.writeAtomically(metaFile, json.encodeToString(VoiceProfile.serializer(), profile).toByteArray())
    }
}
