#include <jni.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <exception>
#include <memory>
#include <new>
#include <numeric>
#include <limits>
#include <vector>

#include "sherpa-onnx/c-api/c-api.h"

namespace {
void fail(JNIEnv *env, const char *type, const char *message) {
  if (env->ExceptionCheck()) return;
  jclass cls = env->FindClass(type);
  if (cls) {
    env->ThrowNew(cls, message);
    env->DeleteLocalRef(cls);
  }
}
}  // namespace

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_k2fsa_sherpa_onnx_SherpaJni_resample(
    JNIEnv *env, jclass, jfloatArray input, jint input_rate, jint output_rate) {
  if (!input || input_rate < 8000 || input_rate > 192000 ||
      output_rate < 8000 || output_rate > 192000) {
    fail(env, "java/lang/IllegalArgumentException", "PCM and sample rates in 8000..192000 are required");
    return nullptr;
  }
  const jsize count = env->GetArrayLength(input);
  if (count == 0) {
    fail(env, "java/lang/IllegalArgumentException", "Reference PCM must not be empty");
    return nullptr;
  }
  if (static_cast<int64_t>(count) > static_cast<int64_t>(input_rate) * 30) {
    fail(env, "java/lang/IllegalArgumentException", "Speaker reference exceeds 30 seconds");
    return nullptr;
  }
  // Upstream GetNumOutputSamples stores Lcm(rate_in, rate_out) in int32_t.
  const int64_t lcm = static_cast<int64_t>(input_rate) /
                      std::gcd(input_rate, output_rate) * output_rate;
  if (lcm > std::numeric_limits<int32_t>::max()) {
    fail(env, "java/lang/IllegalArgumentException", "Sample-rate ratio exceeds resampler tick range");
    return nullptr;
  }
  try {
    std::vector<float> samples(count);
    if (count) env->GetFloatArrayRegion(input, 0, count, samples.data());
    if (env->ExceptionCheck()) return nullptr;
    for (float sample : samples) {
      if (!std::isfinite(sample)) {
        fail(env, "java/lang/IllegalArgumentException", "Reference PCM must be finite");
        return nullptr;
      }
    }
    if (input_rate == output_rate) return input;

    using Resampler = std::unique_ptr<const SherpaOnnxLinearResampler,
                                     decltype(&SherpaOnnxDestroyLinearResampler)>;
    using Output = std::unique_ptr<const SherpaOnnxResampleOut,
                                  decltype(&SherpaOnnxLinearResamplerResampleFree)>;
    // Fixed mobile protocol: midpoint of soxr HQ's passband/stopband, 64 zeros.
    const float cutoff = static_cast<float>(0.9568718266 * 0.5 * std::min(input_rate, output_rate));
    Resampler resampler(SherpaOnnxCreateLinearResampler(input_rate, output_rate, cutoff, 64),
                        SherpaOnnxDestroyLinearResampler);
    if (!resampler) {
      fail(env, "java/lang/IllegalStateException", "Could not create reference resampler");
      return nullptr;
    }
    Output output(SherpaOnnxLinearResamplerResample(resampler.get(), samples.data(), count, 1),
                  SherpaOnnxLinearResamplerResampleFree);
    const int64_t expected = (static_cast<int64_t>(count) * output_rate + input_rate - 1) / input_rate;
    if (!output || output->n != expected || (output->n && !output->samples)) {
      fail(env, "java/lang/IllegalStateException", "Unexpected reference resampler output length");
      return nullptr;
    }
    for (int32_t i = 0; i < output->n; ++i) {
      if (!std::isfinite(output->samples[i])) {
        fail(env, "java/lang/IllegalStateException", "Reference resampler produced non-finite PCM");
        return nullptr;
      }
    }
    jfloatArray result = env->NewFloatArray(output->n);
    if (result && output->n) env->SetFloatArrayRegion(result, 0, output->n, output->samples);
    return result;
  } catch (const std::bad_alloc &) {
    fail(env, "java/lang/OutOfMemoryError", "Reference resampling allocation failed");
  } catch (const std::exception &error) {
    fail(env, "java/lang/IllegalStateException", error.what());
  }
  return nullptr;
}
