// hymt_jni.cpp — thin JNI shim over hymt_core.
//
// Contract (NativeHyMtRuntime.kt):
//   nativeCreate(modelPath)                       -> handle (0 = failure)
//   nativeTranslate(handle, text, src, tgt, ctx)  -> translated text
//   nativeCancel(handle)                          -> set abort flag (quick)
//   nativeRelease(handle)                         -> free model+context
//
//  - C++ exceptions never cross the JNI boundary.
//  - Java strings go through real UTF-8 byte arrays, NOT modified-UTF8.

#include <jni.h>
#include <cstring>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

#include "hymt_core.h"

namespace {

constexpr const char* kIllegalState = "java/lang/IllegalStateException";

void throwIllegalState(JNIEnv* env, const char* message) {
    if (env->ExceptionCheck()) return;
    jclass cls = env->FindClass(kIllegalState);
    if (cls != nullptr) env->ThrowNew(cls, message);
}

jclass findClassOrThrow(JNIEnv* env, const char* name) {
    jclass cls = env->FindClass(name);
    if (cls == nullptr) throw std::runtime_error("class lookup failed");
    return cls;
}

std::string jstringToUtf8(JNIEnv* env, jstring jstr) {
    if (jstr == nullptr) throw std::invalid_argument("null Java string");
    jclass stringCls = findClassOrThrow(env, "java/lang/String");
    jmethodID getBytes = env->GetMethodID(stringCls, "getBytes", "(Ljava/lang/String;)[B");
    if (getBytes == nullptr) throw std::runtime_error("String.getBytes not found");
    jstring charset = env->NewStringUTF("UTF-8");
    if (charset == nullptr) throw std::runtime_error("charset alloc failed");
    auto* bytes = static_cast<jbyteArray>(env->CallObjectMethod(jstr, getBytes, charset));
    env->DeleteLocalRef(charset);
    env->DeleteLocalRef(stringCls);
    if (bytes == nullptr || env->ExceptionCheck()) {
        throw std::runtime_error("String.getBytes failed");
    }
    const jsize len = env->GetArrayLength(bytes);
    std::string out;
    out.resize(static_cast<size_t>(len));
    if (len > 0) {
        env->GetByteArrayRegion(bytes, 0, len, reinterpret_cast<jbyte*>(out.data()));
    }
    env->DeleteLocalRef(bytes);
    return out;
}

jstring utf8ToJstring(JNIEnv* env, const char* utf8) {
    if (utf8 == nullptr) utf8 = "";
    const auto len = static_cast<jsize>(std::strlen(utf8));
    jclass stringCls = findClassOrThrow(env, "java/lang/String");
    jmethodID ctor = env->GetMethodID(stringCls, "<init>", "([BLjava/lang/String;)V");
    if (ctor == nullptr) throw std::runtime_error("String constructor not found");
    jstring charset = env->NewStringUTF("UTF-8");
    if (charset == nullptr) throw std::runtime_error("charset alloc failed");
    auto* bytes = env->NewByteArray(len);
    if (bytes == nullptr) throw std::runtime_error("byte array alloc failed");
    if (len > 0) {
        env->SetByteArrayRegion(bytes, 0, len, reinterpret_cast<const jbyte*>(utf8));
    }
    auto* result = static_cast<jstring>(env->NewObject(stringCls, ctor, bytes, charset));
    env->DeleteLocalRef(bytes);
    env->DeleteLocalRef(charset);
    env->DeleteLocalRef(stringCls);
    if (result == nullptr || env->ExceptionCheck()) {
        throw std::runtime_error("String construction failed");
    }
    return result;
}

struct NativeStrFree {
    void operator()(char* p) const noexcept { hymt_free_string(p); }
};

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_dialect_interpreter_inference_NativeHyMtRuntime_nativeCreate(
        JNIEnv* env, jobject /*thiz*/, jstring modelPath) {
    try {
        const std::string path = jstringToUtf8(env, modelPath);
        char err[512] = {0};
        hymt_handle* handle = nullptr;
        const int rc = hymt_load(path.c_str(), &handle, err, sizeof(err));
        if (rc != HYMT_OK) {
            throwIllegalState(env, err[0] ? err : "Hy-MT model load failed");
            return 0;
        }
        return reinterpret_cast<jlong>(handle);
    } catch (const std::exception& e) {
        throwIllegalState(env, e.what());
        return 0;
    } catch (...) {
        throwIllegalState(env, "unknown native error in nativeCreate");
        return 0;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_dialect_interpreter_inference_NativeHyMtRuntime_nativeTranslate(
        JNIEnv* env, jobject /*thiz*/, jlong handlePtr, jstring text,
        jstring sourceLanguage, jstring targetLanguage, jobjectArray context) {
    try {
        if (handlePtr == 0) {
            throwIllegalState(env, "Hy-MT native runtime is not loaded");
            return nullptr;
        }
        const std::string input = jstringToUtf8(env, text);
        const std::string src = jstringToUtf8(env, sourceLanguage);
        const std::string tgt = jstringToUtf8(env, targetLanguage);
        std::vector<std::string> ctxStorage;
        std::vector<const char*> ctxPtrs;
        if (context != nullptr) {
            const jsize n = env->GetArrayLength(context);
            ctxStorage.reserve(static_cast<size_t>(n));
            for (jsize i = 0; i < n; ++i) {
                auto* item = static_cast<jstring>(env->GetObjectArrayElement(context, i));
                if (item == nullptr) {
                    throwIllegalState(env, "Hy-MT context line is null");
                    return nullptr;
                }
                ctxStorage.push_back(jstringToUtf8(env, item));
                env->DeleteLocalRef(item);
            }
        }
        ctxPtrs.reserve(ctxStorage.size());
        for (const auto& line : ctxStorage) ctxPtrs.push_back(line.c_str());

        char err[512] = {0};
        char* out = nullptr;
        const int rc = hymt_translate(
                reinterpret_cast<hymt_handle*>(handlePtr),
                input.c_str(), src.c_str(), tgt.c_str(),
                ctxPtrs.empty() ? nullptr : ctxPtrs.data(), ctxPtrs.size(),
                &out, err, sizeof(err));
        std::unique_ptr<char, NativeStrFree> owned(out);
        if (rc != HYMT_OK) {
            throwIllegalState(env, err[0] ? err : "Hy-MT translate failed");
            return nullptr;
        }
        return utf8ToJstring(env, owned.get());
    } catch (const std::exception& e) {
        throwIllegalState(env, e.what());
        return nullptr;
    } catch (...) {
        throwIllegalState(env, "unknown native error in nativeTranslate");
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_dialect_interpreter_inference_NativeHyMtRuntime_nativeCancel(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong handlePtr) {
    if (handlePtr != 0) {
        hymt_cancel(reinterpret_cast<hymt_handle*>(handlePtr));
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_dialect_interpreter_inference_NativeHyMtRuntime_nativeRelease(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong handlePtr) {
    if (handlePtr != 0) {
        hymt_release(reinterpret_cast<hymt_handle*>(handlePtr));
    }
}
