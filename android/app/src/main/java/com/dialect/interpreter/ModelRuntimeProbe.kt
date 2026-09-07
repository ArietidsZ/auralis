package com.dialect.interpreter

import android.content.Context
import com.dialect.interpreter.data.ModelRepository
import com.dialect.interpreter.data.ModelManifests
import com.dialect.interpreter.inference.AsrEngine
import com.dialect.interpreter.inference.HyMtTranslationEngine
import com.dialect.interpreter.inference.OnnxModelManager
import com.dialect.interpreter.inference.TtsEngine
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Real, isolated load probe (review item 1): loads each package's engine from a
 * dedicated [OnnxModelManager] whose cached sessions are never shared with a
 * live session, then releases everything before returning. A missing/invalid
 * runtime returns false — never a fabricated ready state.
 *
 * Cost note (reports/lane-B.md): the probe performs a full engine load per
 * package and only runs when files verify first, i.e. on startup probes and
 * after installs. If profiling shows the load is too expensive, lane A should
 * provide a lightweight runtime-check port (interface-B B1.4) instead of us
 * weakening the gate.
 */
class ModelRuntimeProbe(private val context: Context) : ModelRepository.RuntimeReadinessProbe {

    override suspend fun isRuntimeReady(packageId: String): Boolean = withContext(Dispatchers.IO) {
        // Dedicated manager per probe: sessions cached here are closed before
        // returning and can never collide with a live session's handles.
        val manager = OnnxModelManager(context)
        try {
            when (packageId) {
                ModelRepository.PACKAGE_ASR -> probeAsr(manager)
                ModelRepository.PACKAGE_MT -> probeMt(manager)
                ModelRepository.PACKAGE_TTS -> probeTts(manager)
                else -> false
            }
        } finally {
            manager.releaseAll()
        }
    }

    private suspend fun probeAsr(manager: OnnxModelManager): Boolean {
        val engine = AsrEngine(manager)
        return try {
            engine.load()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        } finally {
            runCatching { engine.release() }
        }
    }

    private suspend fun probeMt(manager: OnnxModelManager): Boolean {
        val engine = HyMtTranslationEngine(
            modelFile = File(
                manager.getModelsDir(),
                "${OnnxModelManager.MT_DIR}/${OnnxModelManager.HY_MT_GGUF}"
            )
        )
        return try {
            engine.load()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        } finally {
            runCatching { engine.release() }
        }
    }

    private suspend fun probeTts(manager: OnnxModelManager): Boolean {
        val engine = TtsEngine(manager)
        return try {
            val file = File(manager.getModelsDir(), "tts/manifest.json")
            val manifest = ModelManifests.parse(if (file.isFile) file.readText() else
                context.assets.open("models/tts.json").bufferedReader().use { it.readText() })
            require(manifest.packageId == "tts" && manifest.apiContractVersion in setOf("1", "2")) {
                "Unsupported TTS package API"
            }
            engine.load()
            // load() now reads config/tables and leaves graph handles lazy.
            // A readiness probe must still open real ORT sessions. Release
            // each graph before the next, so probing never holds two talkers.
            val graphs = linkedMapOf(
                "speaker_encoder" to (setOf("mel_spectrogram") to setOf("speaker_embedding")),
                "code_predictor" to (setOf("inputs_embeds", "generation_steps", "past_keys", "past_values") to
                    setOf("logits", "present_keys", "present_values")),
            )
            if (manifest.apiContractVersion == "2") {
                graphs["talker"] = setOf("inputs_embeds", "attention_mask", "position_ids", "past_keys", "past_values") to
                    setOf("logits", "last_hidden_state", "present_keys", "present_values")
                graphs["reference_encoder"] = setOf("pcm") to setOf("codes")
                graphs["vocoder"] = setOf("codes", "conv_state", "past_keys", "past_values", "position") to
                    setOf("waveform", "conv_state_out", "present_keys", "present_values", "position_out")
            } else {
                graphs["talker_prefill"] = setOf("inputs_embeds", "attention_mask", "position_ids") to setOf("logits", "hidden_states")
                graphs["talker_decode"] = setOf("inputs_embeds", "attention_mask", "position_ids", "past_keys", "past_values") to
                    setOf("logits", "hidden_states", "present_keys", "present_values")
                graphs["vocoder"] = setOf("codes") to setOf("waveform")
            }
            require(manifest.roles.keys == graphs.keys) { "TTS role set does not match the package API" }
            for ((role, names) in graphs) {
                try {
                    val session = manager.loadRoleSession("tts", manifest.roles, role)
                    require(session.inputNames == names.first && session.outputNames.containsAll(names.second)) {
                        "TTS graph I/O differs from its API: $role"
                    }
                } finally { manager.releaseRole("tts", manifest.roles, role) }
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        } finally {
            runCatching { engine.release() }
        }
    }
}
