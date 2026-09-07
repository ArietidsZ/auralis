#include <stdio.h>
#include <stdlib.h>

#include "auralis_sherpa_asr.h"
#include "c-api.h"

int main(int argc, char **argv) {
  printf("sherpa-onnx %s\n", AuralisSherpaVersion() ? AuralisSherpaVersion() : "(null)");
  printf("onnxruntime %s\n",
         AuralisSherpaOnnxruntimeVersion() ? AuralisSherpaOnnxruntimeVersion()
                                           : "(null)");
  printf("git %s\n", SherpaOnnxGetGitSha1() ? SherpaOnnxGetGitSha1() : "(null)");
  printf("available %d\n", AuralisSherpaAvailable());

  if (argc != 6) {
    fprintf(stderr, "usage: host_smoke conv encoder decoder tokenizer wav\n");
    return 4;
  }
  /* argv: conv encoder decoder tokenizer wav */
  AuralisSherpaAsr *asr = AuralisSherpaAsrCreate(
      argv[1], argv[2], argv[3], argv[4], 2, 512, 192);
  if (!asr) {
    fprintf(stderr, "AuralisSherpaAsrCreate failed\n");
    return 2;
  }
  const SherpaOnnxWave *wave = SherpaOnnxReadWave(argv[5]);
  if (!wave) {
    fprintf(stderr, "SherpaOnnxReadWave failed: %s\n", argv[5]);
    AuralisSherpaAsrDestroy(asr);
    return 4;
  }
  char *text = AuralisSherpaAsrTranscribe(
      asr, wave->samples, wave->num_samples, wave->sample_rate, NULL, NULL);
  const int failed = text == NULL;
  printf("wav %s samples %d sr %d\n", argv[5], wave->num_samples,
         wave->sample_rate);
  printf("text %s\n", text ? text : "(null)");
  AuralisSherpaStringFree(text);
  SherpaOnnxFreeWave((SherpaOnnxWave *)wave);
  AuralisSherpaAsrDestroy(asr);
  return failed ? 1 : 0;
}
