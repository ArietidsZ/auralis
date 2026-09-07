# continuation-mt-review — 2026-09-07 (post-review fixes)

Independent of lane-A. Artifacts under `/Users/arietids/Library/Caches/Auralis/mt`.
CLI contract: `reports/interface-mt-review.md`. Device/Gradle not run.

## Fixes this round

### 1. Handle ownership (Occam: removed gLive)

`gLive` / `gLiveMu` deleted. C ABI: one owner; `hymt_release(NULL)` no-op; cancel is an atomic store on a handle the owner still holds. Concurrent cancel vs release is **undefined in C**; Swift `OwnedHandle` serializes cancel/release with `NSLock` and nils before `hymt_release`. `onCancel` captures the box, not a raw pointer. JNI Kotlin already joins generate before release. No stale-pointer “didn’t crash” test.

### 2. Sticky cancel (restored)

`abortRequested` stays set until `hymt_release`. Recovery: release + load (new handle). Swift: `Task.checkCancellation()` before load, after load, after generate; cancelled task cannot return success. Engine next translate after cancel aborts until `unload()`. C/Swift smokes updated to that contract.

### 3. Model path

`modelURL` is not frozen at init `fileExists`. Each load re-resolves manifest role under lease. Failed load does not cache a permanent `loadError`.

### 4. Clang module

`android/app/src/main/cpp/hymt_jni/module.modulemap` exports `hymt_core.h`. Swift `import hymt_core`. Host: `swiftc -I …/hymt_jni` (this run compiled and executed). Root: add that path to `SWIFT_INCLUDE_PATHS`. `withExtendedLifetime` kept for strdup context boxes.

### 5. ja→zh + llama-completion

Pinned `llama-completion` greedy `--jinja -st --temp 0 --top-k 1`:

| ZH-template `{target}` | ja「今日は雨が降っています。」 |
|---|---|
| `Chinese` | Today, it is raining. **wrong language** |
| `中文` | 今天正在下雨。 |

Native after using 中文名 in ZH<=>XX instruction: `今天正在下雨。` **matches CLI**. `unknown`→zh same. Token budget without EOG now `HYMT_ERR_FAILED`, not a truncated OK.

zh-en museum **with context** still emits weather then museum (contextual template leak). Not claimed fixed.

### 6. STQ tool

`--expect-src-sha256` required. If source is `93e025c9…`, output must be `e42935e2…`. Header parsed without mapping 460MB; blob hashed/copied in 1MiB chunks. 9 unit tests OK.

## This-run host numbers (greedy, n_threads=4)

`mt_runner` exit 0. ja-zh CLI match true. loadMs 144. Cases include negation, numbers, context, unknown source. Not a quality gate. Contextual/en-zh “同时举行” still weak.

Android `.so` rebuilt: LOAD Align 0x4000, API 28, sha256 `4dfa0e6bc7d2a35d1d4dc00c4f65737817803a7d4efb2dee4fe4d58b037b1d4a`. Device untested.

C smoke / concurrency / Swift smoke PASSED this run after the semantic restore.
