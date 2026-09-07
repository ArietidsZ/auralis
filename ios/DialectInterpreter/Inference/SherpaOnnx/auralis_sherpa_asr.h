#pragma once

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/** sherpa-onnx version string, or NULL if the C API is not linked. */
const char *AuralisSherpaVersion(void);

/** ORT version linked into sherpa-onnx. */
const char *AuralisSherpaOnnxruntimeVersion(void);

/** 1 if SherpaOnnxGetVersionStr is resolvable. */
int32_t AuralisSherpaAvailable(void);

typedef struct AuralisSherpaAsr AuralisSherpaAsr;

/**
 * Create an official sherpa-onnx Qwen3-ASR offline recognizer.
 * feature_dim is 128; max_total_len/max_new_tokens as passed.
 * Returns NULL on failure. Destroy with AuralisSherpaAsrDestroy.
 */
AuralisSherpaAsr *AuralisSherpaAsrCreate(
    const char *conv_frontend,
    const char *encoder,
    const char *decoder,
    const char *tokenizer,
    int32_t num_threads,
    int32_t max_total_len,
    int32_t max_new_tokens);

void AuralisSherpaAsrDestroy(AuralisSherpaAsr *asr);

/**
 * Transcribe mono float PCM. language may be NULL.
 * Returns a malloc'd UTF-8 string (empty string on silence); NULL on failure.
 * Free with AuralisSherpaStringFree. If detected_language is non-NULL, it
 * receives a separately allocated detected language (or NULL when unknown).
 * It must also be freed with AuralisSherpaStringFree.
 */
char *AuralisSherpaAsrTranscribe(
    AuralisSherpaAsr *asr,
    const float *samples,
    int32_t n_samples,
    int32_t sample_rate,
    const char *language,
    char **detected_language);

void AuralisSherpaStringFree(char *s);

#ifdef __cplusplus
}
#endif
