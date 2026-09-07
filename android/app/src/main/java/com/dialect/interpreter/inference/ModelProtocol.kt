package com.dialect.interpreter.inference

/**
 * Pure model-protocol validation (spec R05): load-time checks of input/output
 * names, dtypes and ranks extracted from an ORT session. Kept free of ONNX
 * Runtime and Android types so the rules are unit-testable and engines cannot
 * silently run a mismatched model ("缺协议时明确 unsupported").
 */
object ModelProtocol {

    data class TensorSpec(
        val names: Set<String>,
        val dtypeName: String?,
        val rank: Int?
    )

    class UnsupportedModelException(message: String) : IllegalStateException(message)

    private fun unsupported(message: String): Nothing =
        throw UnsupportedModelException(message)

    /**
     * ASR encoder: exactly one float input `audio_features` [1, n_mels, frames]
     * and one float output [1, seq, hidden].
     */
    fun validateAsrEncoder(inputs: TensorSpec, outputs: TensorSpec) {
        if (inputs.names != setOf("audio_features")) {
            unsupported("ASR encoder inputs ${inputs.names} do not match protocol {audio_features}")
        }
        if (inputs.dtypeName != "FLOAT") {
            unsupported("ASR encoder input dtype ${inputs.dtypeName} is not FLOAT")
        }
        if (inputs.rank != 3) {
            unsupported(
                "ASR encoder input rank ${inputs.rank} does not match [1, n_mels, frames]"
            )
        }
        validateFloatSingleOutput(outputs, "ASR encoder")
    }

    /**
     * ASR decoder: `input_ids` (INT64) + `encoder_hidden_states` (FLOAT) in,
     * one float logits output out.
     */
    fun validateAsrDecoder(inputs: Map<String, TensorSpec>, outputs: TensorSpec) {
        val inputIds = inputs["input_ids"]
            ?: unsupported("ASR decoder inputs ${inputs.keys} miss input_ids")
        if (inputIds.dtypeName != "INT64") {
            unsupported("ASR decoder input_ids dtype ${inputIds.dtypeName} is not INT64")
        }
        val hidden = inputs["encoder_hidden_states"]
            ?: unsupported("ASR decoder inputs ${inputs.keys} miss encoder_hidden_states")
        if (hidden.dtypeName != "FLOAT") {
            unsupported(
                "ASR decoder encoder_hidden_states dtype ${hidden.dtypeName} is not FLOAT"
            )
        }
        validateFloatSingleOutput(outputs, "ASR decoder")
    }

    /**
     * TTS module: named float inputs, at least one output. Exact per-module
     * input names are asserted by the engine when it builds the tensors.
     */
    fun validateTtsModule(moduleName: String, inputs: TensorSpec, outputs: TensorSpec) {
        if (inputs.names.isEmpty()) unsupported("$moduleName has no inputs")
        if (outputs.names.isEmpty()) unsupported("$moduleName has no outputs")
    }

    /**
     * Speaker-embedding sanity: a real reference voice has non-trivial energy.
     * All-zero or NaN embeddings are rejected instead of silently synthesizing
     * garbage (spec C04: no zero-reference default voice).
     */
    fun requireValidSpeakerEmbedding(embedding: FloatArray) {
        if (embedding.isEmpty()) unsupported("Speaker embedding is empty")
        var sumSq = 0.0
        for (v in embedding) {
            if (v.isNaN() || v.isInfinite()) {
                unsupported("Speaker embedding contains NaN/Infinity")
            }
            sumSq += (v.toDouble() * v.toDouble())
        }
        if (sumSq < 1e-6) unsupported("Speaker embedding is all-zero")
    }

    private fun validateFloatSingleOutput(outputs: TensorSpec, moduleName: String) {
        if (outputs.names.size != 1) {
            unsupported("$moduleName must expose exactly one output, got ${outputs.names}")
        }
        if (outputs.dtypeName != "FLOAT") {
            unsupported("$moduleName output dtype ${outputs.dtypeName} is not FLOAT")
        }
        if (outputs.rank != 3) {
            unsupported("$moduleName output rank ${outputs.rank} does not match [batch, seq, dim]")
        }
    }
}
