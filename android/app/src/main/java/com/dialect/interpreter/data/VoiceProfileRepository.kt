package com.dialect.interpreter.data

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Manages voice profiles (reference audio for voice cloning).
 * Voice profiles are stored as WAV files in internal storage.
 */
class VoiceProfileRepository(private val context: Context) {

    companion object {
        private const val TAG = "VoiceProfileRepo"
        private const val PROFILES_DIR = "voice_profiles"
        private const val SAMPLE_RATE = 16000
    }

    data class VoiceProfile(
        val id: String,
        val name: String,
        val createdAt: Long,
        val durationMs: Long,
        val audioPath: String
    )

    private val profilesDir: File
        get() = File(context.filesDir, PROFILES_DIR).also { it.mkdirs() }

    /**
     * Save a voice profile from PCM float data.
     */
    fun saveProfile(name: String, audioData: FloatArray): VoiceProfile {
        val id = "profile_${System.currentTimeMillis()}"
        val audioFile = File(profilesDir, "$id.wav")

        // Convert float to 16-bit WAV
        writeWavFile(audioFile, audioData, SAMPLE_RATE)

        val profile = VoiceProfile(
            id = id,
            name = name,
            createdAt = System.currentTimeMillis(),
            durationMs = (audioData.size.toLong() * 1000) / SAMPLE_RATE,
            audioPath = audioFile.absolutePath
        )

        // Save metadata
        val metaFile = File(profilesDir, "$id.meta")
        writeMetaFile(metaFile, profile)

        Log.i(TAG, "Profile saved: $name (${profile.durationMs}ms)")
        return profile
    }

    /**
     * Get all saved voice profiles.
     */
    fun getProfiles(): List<VoiceProfile> {
        return profilesDir.listFiles()
            ?.filter { it.extension == "wav" }
            ?.mapNotNull { wavFile ->
                val id = wavFile.nameWithoutExtension
                val metaFile = File(profilesDir, "$id.meta")
                if (metaFile.exists()) {
                    parseMetaFile(id, wavFile, metaFile)
                } else null
            }
            ?.sortedByDescending { it.createdAt }
            ?: emptyList()
    }

    private fun writeMetaFile(metaFile: File, profile: VoiceProfile) {
        val payload = JSONObject()
            .put("version", 1)
            .put("id", profile.id)
            .put("name", profile.name)
            .put("createdAt", profile.createdAt)
            .put("durationMs", profile.durationMs)
            .toString()

        val temp = File(metaFile.parentFile, "${metaFile.name}.tmp")
        temp.writeText(payload)

        if (metaFile.exists() && !metaFile.delete()) {
            throw IllegalStateException("Failed to replace profile metadata: ${metaFile.absolutePath}")
        }

        if (!temp.renameTo(metaFile)) {
            temp.copyTo(metaFile, overwrite = true)
            temp.delete()
        }
    }

    private fun parseMetaFile(id: String, wavFile: File, metaFile: File): VoiceProfile? {
        return runCatching {
            val content = metaFile.readText().trim()

            if (content.startsWith("{")) {
                val json = JSONObject(content)
                VoiceProfile(
                    id = id,
                    name = json.optString("name", "Unknown"),
                    createdAt = json.optLong("createdAt", 0L),
                    durationMs = json.optLong("durationMs", 0L),
                    audioPath = wavFile.absolutePath
                )
            } else {
                val lines = content.lines()
                VoiceProfile(
                    id = id,
                    name = lines.getOrElse(0) { "Unknown" },
                    createdAt = lines.getOrElse(1) { "0" }.toLongOrNull() ?: 0,
                    durationMs = lines.getOrElse(2) { "0" }.toLongOrNull() ?: 0,
                    audioPath = wavFile.absolutePath
                )
            }
        }.onFailure {
            Log.w(TAG, "Failed to parse profile metadata: ${metaFile.absolutePath}")
        }.getOrNull()
    }

    /**
     * Load audio data from a voice profile.
     */
    fun loadProfileAudio(profile: VoiceProfile): FloatArray {
        val file = File(profile.audioPath)
        if (!file.exists()) throw IllegalStateException("Profile audio not found: ${profile.audioPath}")
        return readWavFile(file)
    }

    /**
     * Delete a voice profile.
     */
    fun deleteProfile(id: String) {
        File(profilesDir, "$id.wav").delete()
        File(profilesDir, "$id.meta").delete()
        Log.i(TAG, "Profile deleted: $id")
    }

    /**
     * Write PCM float data to a WAV file.
     */
    private fun writeWavFile(file: File, audioData: FloatArray, sampleRate: Int) {
        val numSamples = audioData.size
        val bitsPerSample = 16
        val numChannels = 1
        val byteRate = sampleRate * numChannels * bitsPerSample / 8
        val blockAlign = numChannels * bitsPerSample / 8
        val dataSize = numSamples * blockAlign

        FileOutputStream(file).use { fos ->
            val buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)

            // RIFF header
            buffer.put("RIFF".toByteArray())
            buffer.putInt(36 + dataSize)
            buffer.put("WAVE".toByteArray())

            // fmt chunk
            buffer.put("fmt ".toByteArray())
            buffer.putInt(16) // chunk size
            buffer.putShort(1) // PCM format
            buffer.putShort(numChannels.toShort())
            buffer.putInt(sampleRate)
            buffer.putInt(byteRate)
            buffer.putShort(blockAlign.toShort())
            buffer.putShort(bitsPerSample.toShort())

            // data chunk
            buffer.put("data".toByteArray())
            buffer.putInt(dataSize)

            for (sample in audioData) {
                val intSample = (sample.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                buffer.putShort(intSample)
            }

            fos.write(buffer.array())
        }
    }

    /**
     * Read a WAV file into PCM float data.
     */
    private fun readWavFile(file: File): FloatArray {
        val bytes = file.readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        // Skip WAV header (44 bytes)
        buffer.position(44)

        val numSamples = (bytes.size - 44) / 2
        val audioData = FloatArray(numSamples)
        for (i in 0 until numSamples) {
            audioData[i] = buffer.short / 32768f
        }
        return audioData
    }
}
