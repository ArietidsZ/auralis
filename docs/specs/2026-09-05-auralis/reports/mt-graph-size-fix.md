# MT graph size 计算的未定义行为修复

旧 sanitizer 日志保留在 `~/Library/Caches/Auralis/mt/reports/asan_*`。它们含真实 UBSan 错误，不能因为 smoke 继续运行就报告 sanitizer 通过。本轮重新使用 `halt_on_error=1` 执行旧二进制，进程退出 -6：

```text
ggml.c:7317:33: runtime error: applying non-zero offset 96 to null pointer
```

## 修复

固定 llama.cpp `1e411d8f5a1e23525fa3265dfb4bd76265465397` 的 `ggml_graph_nbytes` 从空指针起算布局长度，`incr_ptr_aligned` 却采用 C 指针加法。CMake 对校验后的原文件生成构建目录副本，仅将 `(char *) ptr + size` 替换为 `(uintptr_t) ptr + size`，让计数使用无符号整数运算。`ggml-base` 只编译该副本。

没有修改上游 checkout，没有禁用 sanitizer 或过滤其诊断，没有改变模型格式或句柄接口。`hymt_runtime_revision()` 仍报告基础上游 commit；本地构建修复由 CMake 源、输入/输出源摘要和最终库摘要共同记录，不能把产物描述成未经修改的上游构建。

| 文件 | SHA-256 |
|---|---|
| 原始 ggml.c | `ac390bb9d652782ad511372920e1153b51164ff54f84110d629daca2ed1dd76b` |
| 构建副本 ggml-auralis.c | `82f74919593fab069862278a122bccf1338ae2b070faf67bae69e540827c30f3` |
| host libhymt_core.dylib | `5bee2b076400b2842da88fc65b7305ce3514e230f90fc708595f88464e342465` |
| stripped Android libhymt_jni.so | `70d04be59d273254c8db45ef7423d9582b56e65257b59b62a632a6a11d663db5` |

## 本轮执行

- 相同真实 GGUF、ASan+UBSan 构建：修复前 fatal；修复后 `hymt_smoke` 与 `hymt_concurrency` 均 exit 0，未出现 sanitizer 诊断。检查包含真实加载、翻译、UTF-8 拒绝、粘性取消、释放重载及并发。
- `ASAN_OPTIONS=detect_leaks=0` 因当前 macOS runtime 不支持该泄漏检查；`UBSAN_OPTIONS=halt_on_error=1:print_stacktrace=1`。没有泄漏检查通过的主张。
- 普通 host 与 Android 两个目标均真实重建。Android 去调试符号后 8,063,800 bytes，API 28，三个 LOAD 均 `0x4000`，四个 Kotlin JNI 入口均存在，NEEDED 仅 log/m/dl/c 系统库。
- Android 产物已交独占设备 lane，最终 APK 内字节、加载及取消/reload smoke 由其后续记录。这里不把 ELF 检查当成设备执行。

证据：`~/Library/Caches/Auralis/mt/reports/graph-size-fix/` 中的 before.log、两个 sanitizer 日志、构建日志、artifacts.json、libhymt_jni.json 及 readelf 输出。

## 选择

保留一个精确输入哈希和单行构建修复。相同 sanitizer 模型检查提供前后对照；无需全局指针注册表、定制 allocator 或 sanitizer 排除规则。上游 revision 改变时输入哈希门会要求重新审查此处，而不会静默套用文本替换。
