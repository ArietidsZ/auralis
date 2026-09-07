# Android native libraries

Build from the repository root:

```sh
python3 scripts/build_android_native --ndk /path/to/ndk/27.3.13750724
```

The script builds the pinned sherpa ASR JNI and Hy-MT C ABI/JNI libraries,
checks their exported entry points and 16 KB ELF alignment, then installs both
under `arm64-v8a/`. Gradle supplies the single ORT 1.24.2 AAR. No model weights
are downloaded by this native build. Use `--output DIR` for an isolated build.

Hy-MT base runtime: `1e411d8f5a1e23525fa3265dfb4bd76265465397`.
Its CMake recipe applies the audited graph-size arithmetic fix in a build-local
source copy; the upstream checkout stays untouched.

The 2026-09-07 reviewed stripped Hy-MT binary is 8,063,800 bytes,
SHA-256 `70d04be59d273254c8db45ef7423d9582b56e65257b59b62a632a6a11d663db5`.
It has run real translation, cancellation and reload in an Android emulator.
Builds on another host may have different binary hashes; source pins, build
recipe, artifact hashes and APK checks are recorded per build.

See `docs/specs/2026-09-05-auralis/reports/android-ort-integration.md` and
`reports/mt-graph-size-fix.md` under the same spec directory for current evidence.
Physical phones, a 16 KB page-size system and the minimum Android API remain
separate acceptance gates.
