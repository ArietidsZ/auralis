// hymt_core.h — minimal C ABI for Hy-MT GGUF translation.
//
// Handle ownership (one owner, spec 02 R05):
//  - hymt_load returns a fresh handle. The owner stores it and nils its copy
//    after hymt_release.
//  - hymt_translate / hymt_release take the per-handle generate mutex.
//    Release waits for in-flight generate, then frees. hymt_release(NULL)
//    is a no-op. Double-release of a non-NULL pointer after free is
//    caller-contract undefined (allocator ABA). Do not cancel/translate
//    after release.
//  - hymt_cancel is an atomic flag store. It never frees. NULL is a no-op.
//    The owner must still hold the handle; cancel of a released pointer is
//    undefined. Callers that race cancel with release (e.g. Swift onCancel)
//    serialize on the owner side, not via a process-wide live set.
//  - Cancel is sticky until hymt_release. Recovery is release + hymt_load
//    (new handle). A late cancel must not be swallowed by the next utterance
//    on the same handle.
//  - UTF-8 inputs are rejected, never repaired. Output strings are heap
//    UTF-8 freed with hymt_free_string.

#ifndef HYMT_CORE_H
#define HYMT_CORE_H

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

enum hymt_status {
    HYMT_OK = 0,
    HYMT_ERR_INVALID = 1,
    HYMT_ERR_ABORTED = 2,
    HYMT_ERR_FAILED = 3
};

typedef struct hymt_handle hymt_handle;

const char* hymt_runtime_revision(void);

int hymt_load(const char* model_path_utf8, hymt_handle** out,
              char* err, size_t err_len);

int hymt_translate(hymt_handle* h,
                   const char* utf8_text,
                   const char* utf8_source_language,
                   const char* utf8_target_language,
                   const char* const* utf8_context, size_t n_context,
                   char** out_text,
                   char* err, size_t err_len);

void hymt_cancel(hymt_handle* h);

void hymt_release(hymt_handle* h);

void hymt_free_string(char* s);

#ifdef __cplusplus
}
#endif

#endif
