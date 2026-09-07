# interface-mt-review — MT runner CLI for central validate_models

Owner: hymt_jni C ABI, `libhymt_jni.so`, `HyMtNativeBridge.swift`,
`TranslationEngine.swift`, `convert/mt_runner.py`.
Does not own: validate_models.py, schema, fetch, Gradle, Pipeline/UI, ASR/TTS.
This file is the integration contract. Manifest hash/size/runtimeRevision remain root-owned.

## CLI (stable)

```text
python3 convert/mt_runner.py \
  --lib PATH \
  --model PATH \
  [--json-report PATH] \
  [--cases PATH] \
  [--llama-cli PATH]
```

| flag | required | meaning |
|---|---|---|
| `--lib` | no (file must exist or exit 2) | `libhymt_core.dylib` |
| `--model` | no (file must exist or exit 2) | remapped STQ1_0 GGUF (tensor type id 43) |
| `--json-report` | no | write the same JSON the process prints |
| `--cases` | no | JSON **list** of objects; default built-in 10 bidirectional sentences |
| `--llama-cli` | no | if missing, `cliComparison.available=false`; never fake CLI output |

Persistent cache (do not use `/private/tmp`):

```
$AURALIS_CACHE/mt/
  llama-stq/                         # git 1e411d8f5a1e23525fa3265dfb4bd76265465397
  models/Hy-MT1.5-1.8B-1.25bit.gguf  # upstream sha256 93e025c9…
  models/Hy-MT1.5-1.8B-1.25bit-stq43.gguf  # remapped e42935e2…
  build-host/libhymt_core.dylib
  reports/
```

Defaults: `--lib` `$HYMT_LIB` or `.../mt/build-host/libhymt_core.dylib`;
`--model` `$HYMT_MODEL` or `.../mt/models/Hy-MT1.5-1.8B-1.25bit-stq43.gguf`.
`--json-report` should be under `.../mt/reports/`.

Do not pass the unremapped HF GGUF (`type id 42`). Loader at pinned revision treats 42 as Q2_0.

## Exit codes

| code | meaning |
|---|---|
| 0 | library loaded, revision pin matched, every case native `status=ok`. Not a quality/CER gate. |
| 1 | native load/translate/revision failure, or any case not OK |
| 2 | missing `--lib` file or missing `--model` file |
| 3 | reserved for contract/schema (this runner does not emit 3) |
| 4 | bad arguments (`--cases` not a non-empty JSON list, etc.) |

Missing model is 2, not 0. Draft manifests are not this runner's job.

## stdout JSON (schemaVersion `"1"`)

One object, UTF-8, `ensure_ascii=false`. llama.cpp logs go to stderr.

```json
{
  "schemaVersion": "1",
  "runner": "mt_runner",
  "runtimeRevision": "1e411d8f5a1e23525fa3265dfb4bd76265465397",
  "modelPath": "<abs>",
  "modelSha256": "<64 hex>",
  "library": "<abs>",
  "nThreads": 4,
  "sampling": "greedy",
  "hardware": {"sysname": "Darwin", "machine": "arm64", "cpuCount": 12, "loadavg": [0, 0, 0]},
  "interference": {"otherLlamaPids": [], "note": "one handle, sequential cases, llama n_threads=4 inside the process"},
  "loadMs": 0.0,
  "cases": [
    {
      "id": "zh-en-museum",
      "text": "...",
      "sourceLanguage": "Chinese",
      "targetLanguage": "English",
      "context": ["..."],
      "status": "ok",
      "output": "...",
      "err": "",
      "latencyMs": 0.0
    }
  ],
  "cliComparison": {"available": false, "notes": "llama-cli not invoked"}
}
```

`status` is `ok` | `invalid` | `aborted` | `failed` (C `hymt_status`).
`sampling` is greedy argmax. Official HY-MT card uses temperature 0.7 / top_k 20 /
top_p 0.6 / repetition_penalty 1.05 — those numbers are not this runner.

`--cases` item fields: `id`, `text`, `sourceLanguage`, `targetLanguage`, optional `context` (list of strings).

## C ABI (`hymt_core.h`)

```
hymt_runtime_revision() -> const char*          // must equal 1e411d8f5a1e23525fa3265dfb4bd76265465397
hymt_load(path, hymt_handle**, err, err_len) -> int
hymt_translate(h, text, src, tgt, context, n, char**, err, err_len) -> int
hymt_cancel(h)
hymt_release(h)     // NULL no-op; caller nils its copy
hymt_free_string(s)
```

`0 OK / 1 INVALID / 2 ABORTED / 3 FAILED`. Abort is per `hymt_translate` (consumed on return).
Session stop must still `hymt_release` / Swift `unload()`.

## GGUF transform (for root manifest, not applied here)

| | sha256 | bytes |
|---|---|---|
| upstream HF | `93e025c93cc082e73a3f142b757623a8b9cf541c020a8013ca4ee669556860ab` | 461860704 |
| remapped type 42→43 | `e42935e2c143be4c579109ef3a096b0b00796af2527eaa09be76331832d4c971` | 461860704 |

Tool: `android/app/src/main/cpp/hymt_jni/tools/fix_stq_type_id.py`
Tool requires `--expect-src-sha256`. Production pair is pinned: if source is `93e025c9…` the output **must** be `e42935e2…` (224 type-id bytes). Header-only parse; blob hashed/copied in 1MiB chunks.

## iOS engine / Clang module

`import hymt_core` from `android/app/src/main/cpp/hymt_jni/module.modulemap` + `hymt_core.h`.
Host smoke: `swiftc -I android/app/src/main/cpp/hymt_jni`.
**Root iOS target:** add that directory to `SWIFT_INCLUDE_PATHS` (and keep linking `libhymt_core`). Do not use `@_silgen_name`.

`HyMtTranslationEngine.unload()` releases `OwnedHandle` then the shared `ModelPackageLease`.
Cancel is sticky until unload; Pipeline `TranslationStage.release` must `await engine.unload()`.
`onCancel` calls `OwnedHandle.cancel()` (lock + niled pointer), not a captured raw handle.

`--llama-cli` this pin is `.../mt/build-host/bin/llama-completion` (not `llama-cli`).
