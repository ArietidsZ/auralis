// hymt_concurrency.c — cancel during generate; sticky until owner release+reload.
// Owner joins generate before release. Does not probe stale-pointer ABA.
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include "hymt_core.h"

static hymt_handle* g_handle = NULL;
static int g_fail = 0;

static void* translate_thread(void* arg) {
    (void)arg;
    char err[256] = {0};
    char* out = NULL;
    const char* text =
        "这份报告涵盖了一季度销售、市场反馈与供应链的调整情况，请各部门关注重点问题。";
    int rc = hymt_translate(g_handle, text, "Chinese", "English",
                            NULL, 0, &out, err, sizeof(err));
    fprintf(stderr, "concurrency translate rc=%d err=%s out_len=%zu\n",
            rc, err, out ? strlen(out) : 0);
    if (rc != HYMT_OK && rc != HYMT_ERR_ABORTED) {
        fprintf(stderr, "FAIL: unexpected translate status %d\n", rc);
        g_fail = 1;
    }
    hymt_free_string(out);
    return NULL;
}

static void* cancel_thread(void* arg) {
    (void)arg;
    usleep(80 * 1000);
    hymt_cancel(g_handle);
    return NULL;
}

int main(int argc, char** argv) {
    if (argc < 2) {
        fprintf(stderr, "usage: %s <model.gguf>\n", argv[0]);
        return 2;
    }
    char err[512] = {0};
    int rc = hymt_load(argv[1], &g_handle, err, sizeof(err));
    if (rc != HYMT_OK || g_handle == NULL) {
        fprintf(stderr, "load failed: %s\n", err);
        return 1;
    }

    pthread_t t1, t2;
    pthread_create(&t1, NULL, translate_thread, NULL);
    pthread_create(&t2, NULL, cancel_thread, NULL);
    pthread_join(t1, NULL);
    pthread_join(t2, NULL);

    char* after = NULL;
    rc = hymt_translate(g_handle, "你好", "zh", "en", NULL, 0, &after, err, sizeof(err));
    fprintf(stderr, "sticky post-cancel rc=%d\n", rc);
    if (rc != HYMT_ERR_ABORTED) {
        fprintf(stderr, "FAIL: cancel must stick until release\n");
        g_fail = 1;
    }
    hymt_free_string(after);

    hymt_release(g_handle);
    g_handle = NULL;
    hymt_release(NULL);
    hymt_cancel(NULL);

    rc = hymt_load(argv[1], &g_handle, err, sizeof(err));
    if (rc != HYMT_OK) {
        fprintf(stderr, "reload failed: %s\n", err);
        return 1;
    }
    rc = hymt_translate(g_handle, "你好", "zh", "en", NULL, 0, &after, err, sizeof(err));
    fprintf(stderr, "after reload rc=%d out=%s\n", rc, after ? after : "(null)");
    if (rc != HYMT_OK || after == NULL || after[0] == 0) {
        fprintf(stderr, "FAIL: reload must clear sticky cancel\n");
        g_fail = 1;
    }
    hymt_free_string(after);
    hymt_release(g_handle);
    g_handle = NULL;

    fprintf(stderr, g_fail ? "CONCURRENCY FAILED\n" : "CONCURRENCY PASSED\n");
    return g_fail ? 1 : 0;
}
