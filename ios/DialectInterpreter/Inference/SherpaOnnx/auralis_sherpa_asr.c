#include "auralis_sherpa_asr.h"

#include <stdlib.h>
#include <string.h>
#include <math.h>

#include "c-api.h"

struct AuralisSherpaAsr {
  const SherpaOnnxOfflineRecognizer *recognizer;
};

const char *AuralisSherpaVersion(void) { return SherpaOnnxGetVersionStr(); }

const char *AuralisSherpaOnnxruntimeVersion(void) {
  return SherpaOnnxGetOnnxruntimeVersionStr();
}

int32_t AuralisSherpaAvailable(void) {
  return SherpaOnnxGetVersionStr() != NULL ? 1 : 0;
}

AuralisSherpaAsr *AuralisSherpaAsrCreate(
    const char *conv_frontend,
    const char *encoder,
    const char *decoder,
    const char *tokenizer,
    int32_t num_threads,
    int32_t max_total_len,
    int32_t max_new_tokens) {
  if (!conv_frontend || !encoder || !decoder || !tokenizer) {
    return NULL;
  }

  SherpaOnnxOfflineRecognizerConfig config;
  memset(&config, 0, sizeof(config));
  config.feat_config.sample_rate = 16000;
  config.feat_config.feature_dim = 128;
  config.model_config.num_threads = num_threads > 0 ? num_threads : 2;
  config.model_config.debug = 0;
  config.model_config.provider = "cpu";
  config.model_config.qwen3_asr.conv_frontend = conv_frontend;
  config.model_config.qwen3_asr.encoder = encoder;
  config.model_config.qwen3_asr.decoder = decoder;
  config.model_config.qwen3_asr.tokenizer = tokenizer;
  config.model_config.qwen3_asr.max_total_len =
      max_total_len > 0 ? max_total_len : 512;
  config.model_config.qwen3_asr.max_new_tokens =
      max_new_tokens > 0 ? max_new_tokens : 192;
  config.model_config.qwen3_asr.temperature = 1e-6f;
  config.model_config.qwen3_asr.top_p = 0.8f;
  config.model_config.qwen3_asr.seed = 42;
  config.decoding_method = "greedy_search";

  const SherpaOnnxOfflineRecognizer *recognizer =
      SherpaOnnxCreateOfflineRecognizer(&config);
  if (!recognizer) {
    return NULL;
  }
  AuralisSherpaAsr *asr = (AuralisSherpaAsr *)malloc(sizeof(AuralisSherpaAsr));
  if (!asr) {
    SherpaOnnxDestroyOfflineRecognizer(recognizer);
    return NULL;
  }
  asr->recognizer = recognizer;
  return asr;
}

void AuralisSherpaAsrDestroy(AuralisSherpaAsr *asr) {
  if (!asr) {
    return;
  }
  if (asr->recognizer) {
    SherpaOnnxDestroyOfflineRecognizer(asr->recognizer);
  }
  free(asr);
}

char *AuralisSherpaAsrTranscribe(
    AuralisSherpaAsr *asr,
    const float *samples,
    int32_t n_samples,
    int32_t sample_rate,
    const char *language,
    char **detected_language) {
  if (detected_language) *detected_language = NULL;
  if (!asr || !asr->recognizer || !samples || n_samples <= 0 || sample_rate <= 0) {
    return NULL;
  }
  for (int32_t i = 0; i < n_samples; ++i) {
    if (!isfinite(samples[i])) return NULL;
  }
  const SherpaOnnxOfflineStream *stream =
      SherpaOnnxCreateOfflineStream(asr->recognizer);
  if (!stream) {
    return NULL;
  }
  if (language && language[0] != '\0') {
    SherpaOnnxOfflineStreamSetOption(stream, "language", language);
  }
  SherpaOnnxAcceptWaveformOffline(stream, sample_rate, samples, n_samples);
  SherpaOnnxDecodeOfflineStream(asr->recognizer, stream);
  const SherpaOnnxOfflineRecognizerResult *result =
      SherpaOnnxGetOfflineStreamResult(stream);
  char *out = NULL;
  if (result && result->text) {
    size_t n = strlen(result->text);
    out = (char *)malloc(n + 1);
    if (out) {
      memcpy(out, result->text, n + 1);
    }
    if (out && detected_language && result->lang && result->lang[0]) {
      *detected_language = strdup(result->lang);
      if (!*detected_language) {
        free(out);
        out = NULL;
      }
    }
  }
  if (result) {
    SherpaOnnxDestroyOfflineRecognizerResult(result);
  }
  SherpaOnnxDestroyOfflineStream(stream);
  return out;
}

void AuralisSherpaStringFree(char *s) { free(s); }
