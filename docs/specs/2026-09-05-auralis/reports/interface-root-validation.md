# Central verification integration

Manifest/schema/fetch/validate, iOS ASR, Pipeline, and UI source choice.
Metadata: `../runtime-cache.json` (local; unpublished trees under `$AURALIS_CACHE`).

ASR hash-checked under `$AURALIS_CACHE/models/asr`.
Original 15 test WAVs/transcripts under `asr/test_wavs`, Python `asr/venv/bin/python`.
No implicit `/private/tmp` fallback. Model import from release TAR is now reproducible via `source.archive`.

ASR language hints: real paired auto / code / canonical comparison worsened Chinese/Cantonese recognition when forced; default AUTO selected. `result.lang` truly empty in pinned sherpa, so output unknown, not Chinese. iOS UI source choice supplies MT sourceLanguage independently. Keep this distinction on Android.

Central runner gate: `--suite PATH` (schemaVersion=1, name, purpose=smoke|benchmark, asr/mt/tts.cases + optional limits).
`--runner-python asr=...` / `tts=...`; `--mt-library PATH`; `--json-report PATH` persists all fresh invocation logs/reports.
Missing limits => quality blocked. Hash bad or actual input layout incomplete => no native execution. ASR=6 files/TTS=35 fixed current protocol. ASR full15 newly ran; all recorded. New native task tests use explicit boundary stubs only.

iOS full-source Catalyst typecheck uses the real ORT Clang headers and two native module maps. `ShapeStyle .appColor` was corrected to `Color.appColor`.

MT 已经由 fetch 从原始输入真执行转换后安装到cache/models/mt。shared mt.json source.transform保留原始93e025...，files固定派生e429...，runtimeRevision固定1e411...；Android HY_MT_GGUF常量已同步stq43文件名。schema/fixtures Python+Swift/Kotlin规则同步，Gradle需最终重跑。
