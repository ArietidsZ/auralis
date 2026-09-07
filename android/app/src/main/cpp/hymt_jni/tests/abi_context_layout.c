/* Measure C ABI sizes for hymt_translate context pointer. Linked by abi_context_layout.swift. */
#include <stdio.h>
#include <stddef.h>

void hymt_abi_dump_context(const char* const* ctx, size_t n) {
    printf("C sizeof(char*)=%zu sizeof(char* const*)=%zu n=%zu ctx=%p\n",
           sizeof(char*), sizeof(const char* const*), n, (const void*)ctx);
    for (size_t i = 0; i < n; ++i) {
        const unsigned char* p = (const unsigned char*)ctx[i];
        printf("C ctx[%zu]=%p bytes=%02x %02x %02x %02x str=%s\n",
               i, (const void*)p,
               p ? p[0] : 0, p ? p[1] : 0, p ? p[2] : 0, p ? p[3] : 0,
               p ? (const char*)p : "(null)");
    }
    fflush(stdout);
}
