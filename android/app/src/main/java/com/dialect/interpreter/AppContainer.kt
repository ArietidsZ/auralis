package com.dialect.interpreter

import android.content.Context
import com.dialect.interpreter.audio.AudioPlayer
import com.dialect.interpreter.audio.AudioRecorder
import com.dialect.interpreter.data.DialectCatalogLoader
import com.dialect.interpreter.data.ModelRepository
import com.dialect.interpreter.inference.AsrEngine
import com.dialect.interpreter.inference.HyMtTranslationEngine
import com.dialect.interpreter.inference.OnnxModelManager
import com.dialect.interpreter.inference.PipelineOrchestrator
import com.dialect.interpreter.inference.TranslationEngine
import com.dialect.interpreter.inference.TtsEngine
import java.io.File

/**
 * App-scoped dependency container (manual DI).
 *
 * Owns heavyweight runtime objects whose lifetime spans the process, not an
 * Activity or a configuration change. This replaces the previous
 * `DialectApp.instance` global singleton and the pattern of reading dependencies
 * off the Application via casts. (A Hilt migration is the documented end-state;
 * this container is the pragmatic first step that already decouples lifetimes.)
 */
class AppContainer(private val context: Context) {

    val modelManager: OnnxModelManager by lazy { OnnxModelManager(context) }

    val modelRepository: ModelRepository by lazy { ModelRepository(context) }

    val dialectCatalogLoader: DialectCatalogLoader by lazy { DialectCatalogLoader(context) }

    fun createTranslationEngine(): TranslationEngine {
        val modelFile = File(
            modelManager.getModelsDir(),
            "${OnnxModelManager.MT_DIR}/${OnnxModelManager.HY_MT_GGUF}"
        )
        return HyMtTranslationEngine(modelFile = modelFile)
    }

    /** Create a fresh pipeline bound to app-scoped runtime objects. */
    fun createPipeline(): PipelineOrchestrator = PipelineOrchestrator(
        asrEngine = AsrEngine(modelManager),
        translationEngine = createTranslationEngine(),
        ttsEngine = TtsEngine(modelManager),
        audioRecorder = AudioRecorder(context = context),
        audioPlayer = AudioPlayer()
    )
}