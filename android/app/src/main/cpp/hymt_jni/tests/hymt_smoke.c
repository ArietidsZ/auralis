// hymt_smoke.c — real-model smoke. Sticky cancel until release+reload.
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include "hymt_core.h"

static int failures = 0;
#define CHECK(cond, msg) do { \
    if (!(cond)) { fprintf(stderr, "FAIL: %s\n", msg); failures++; } \
    else { fprintf(stderr, "ok: %s\n", msg); } } while (0)

int main(int argc, char** argv) {
    if (argc < 2) {
        fprintf(stderr, "usage: %s <model.gguf>\n", argv[0]);
        return 2;
    }
    const char* revision = hymt_runtime_revision();
    fprintf(stderr, "runtime revision: %s\n", revision);
    CHECK(strcmp(revision, "1e411d8f5a1e23525fa3265dfb4bd76265465397") == 0,
          "pinned revision string matches");

    hymt_handle* h = NULL;
    char err[512] = {0};
    int rc = hymt_load(argv[1], &h, err, sizeof(err));
    fprintf(stderr, "hymt_load rc=%d err=%s\n", rc, err);
    CHECK(rc == HYMT_OK && h != NULL, "model load (real GGUF, CPU EP)");

    char* out = NULL;
    const char* ctx[] = {"The weather is nice today."};
    rc = hymt_translate(h, "今天下午我们去博物馆参观，好吗？", "Chinese", "English",
                        ctx, 1, &out, err, sizeof(err));
    fprintf(stderr, "translate1 rc=%d err=%s out=%s\n", rc, err, out ? out : "(null)");
    CHECK(rc == HYMT_OK && out != NULL, "translate sentence 1 returns OK");
    if (out) {
        CHECK(strlen(out) > 0, "output non-empty");
        CHECK(strcmp(out, "今天下午我们去博物馆参观，好吗？") != 0, "output differs from input");
    }

    char* out2 = NULL;
    rc = hymt_translate(h, "Hello, how are you?", "English", "Chinese", NULL, 0,
                        &out2, err, sizeof(err));
    fprintf(stderr, "translate2 rc=%d err=%s out=%s\n", rc, err, out2 ? out2 : "(null)");
    CHECK(rc == HYMT_OK && out2 != NULL, "translate sentence 2 (no context) returns OK");

    char* out_codes = NULL;
    rc = hymt_translate(h, "请把门关上。", "zh", "en", NULL, 0, &out_codes, err, sizeof(err));
    fprintf(stderr, "translate zh/en codes rc=%d out=%s\n", rc, out_codes ? out_codes : "(null)");
    CHECK(rc == HYMT_OK && out_codes != NULL && strlen(out_codes) > 0,
          "language codes zh/en produce a translation");

    hymt_free_string(out);
    hymt_free_string(out2);
    hymt_free_string(out_codes);

    char* out3 = NULL;
    const char bad[] = {'a', (char)0xC3, (char)0x28, 'b', 0};
    rc = hymt_translate(h, bad, "Chinese", "English", NULL, 0, &out3, err, sizeof(err));
    CHECK(rc == HYMT_ERR_INVALID && out3 == NULL, "invalid UTF-8 rejected with HYMT_ERR_INVALID");

    const char overlong[] = {(char)0xC0, (char)0x80, 0};
    rc = hymt_translate(h, overlong, "Chinese", "English", NULL, 0, &out3, err, sizeof(err));
    CHECK(rc == HYMT_ERR_INVALID && out3 == NULL, "overlong UTF-8 C0 80 rejected");

    rc = hymt_translate(NULL, "x", "a", "b", NULL, 0, &out3, err, sizeof(err));
    CHECK(rc == HYMT_ERR_INVALID, "null handle rejected");

    const char* badctx[] = {NULL};
    rc = hymt_translate(h, "ok", "Chinese", "English", badctx, 1, &out3, err, sizeof(err));
    CHECK(rc == HYMT_ERR_INVALID, "null context line rejected");

    hymt_cancel(h);
    char* aborted = NULL;
    rc = hymt_translate(h, "你好", "Chinese", "English", NULL, 0, &aborted, err, sizeof(err));
    CHECK(rc == HYMT_ERR_ABORTED && aborted == NULL, "cancel is sticky: next translate aborts");
    hymt_free_string(aborted);
    rc = hymt_translate(h, "谢谢", "Chinese", "English", NULL, 0, &aborted, err, sizeof(err));
    CHECK(rc == HYMT_ERR_ABORTED, "cancel stays until release");
    hymt_free_string(aborted);

    hymt_release(h);
    hymt_release(NULL);
    h = NULL;
    rc = hymt_load(argv[1], &h, err, sizeof(err));
    CHECK(rc == HYMT_OK && h != NULL, "reload after release");
    char* after = NULL;
    rc = hymt_translate(h, "谢谢", "Chinese", "English", NULL, 0, &after, err, sizeof(err));
    fprintf(stderr, "translate after reload rc=%d out=%s\n", rc, after ? after : "(null)");
    CHECK(rc == HYMT_OK && after != NULL, "fresh handle after reload is not aborted");
    hymt_free_string(after);
    hymt_release(h);

    fprintf(stderr, failures ? "SMOKE FAILED (%d)\n" : "SMOKE PASSED\n", failures);
    return failures ? 1 : 0;
}
