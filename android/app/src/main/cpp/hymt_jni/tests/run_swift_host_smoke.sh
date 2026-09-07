#!/bin/bash
# run_swift_host_smoke.sh — host Swift smoke for the iOS MT bridge (hymt_core).
# Builds libhymt_core.dylib (if missing) with Metal disabled, compiles
# HyMtNativeBridge.swift (iOS source) + HyMtHostSmokeMain.swift with plain
# swiftc, and runs REAL translations against the pinned Hy-MT GGUF.
#
# Usage: run_swift_host_smoke.sh <model.gguf>
# Env:   HYMT_CPP_DIR  (default: repo android/app/src/main/cpp/hymt_jni)
#        HYMT_LLAMA_DIR (default: /private/tmp/auralis-runtime-20260906/llama-stq)
#        HYMT_BUILD_DIR (default: /private/tmp/auralis-runtime-20260906/build-host)
set -euo pipefail
REPO_ROOT="$(cd "$(dirname "$0")/../../../../../../.." && pwd)"
HYMT_CPP_DIR="${HYMT_CPP_DIR:-$REPO_ROOT/android/app/src/main/cpp/hymt_jni}"
HYMT_LLAMA_DIR="${HYMT_LLAMA_DIR:-$HOME/Library/Caches/Auralis/mt/llama-stq}"
HYMT_BUILD_DIR="${HYMT_BUILD_DIR:-$HOME/Library/Caches/Auralis/mt/build-host}"
MODEL="${1:?usage: run_swift_host_smoke.sh <model.gguf>}"

if [ ! -f "$HYMT_BUILD_DIR/libhymt_core.dylib" ]; then
    echo "-- building libhymt_core.dylib (GGML_METAL=OFF, CPU) --"
    cmake -S "$HYMT_CPP_DIR" -B "$HYMT_BUILD_DIR" -G Ninja \
        -DLLAMA_CPP_DIR="$HYMT_LLAMA_DIR" -DHYMT_HOST_BUILD=ON \
        -DGGML_METAL=OFF -DGGML_NATIVE=ON -DCMAKE_BUILD_TYPE=Release
    cmake --build "$HYMT_BUILD_DIR" --target hymt_core_dylib
fi

echo "-- compiling Swift host smoke --"
OUT="$HYMT_BUILD_DIR/hymt_swift_smoke"
# Host scope: Foundation-only closure. The canImport(OnnxRuntimeBindings)
# branch (production modelsDir default) is NOT compiled here — it is covered by
# the reviewer's real-Catalyst type-check with the full project.
swiftc -O -I "$HYMT_CPP_DIR" \
    "$HYMT_CPP_DIR/tests/main.swift" \
    "$REPO_ROOT/ios/DialectInterpreter/Inference/HyMtNativeBridge.swift" \
    "$REPO_ROOT/ios/DialectInterpreter/Inference/TranslationEngine.swift" \
    "$REPO_ROOT/ios/DialectInterpreter/Data/PackageVerifier.swift" \
    "$REPO_ROOT/ios/DialectInterpreter/Data/SharedContracts.swift" \
    "$REPO_ROOT/ios/DialectInterpreter/Data/ModelRepository.swift" \
    -o "$OUT" \
    -L "$HYMT_BUILD_DIR" -lhymt_core -Xlinker -rpath -Xlinker "$HYMT_BUILD_DIR"

echo "-- running (real model, CPU, no Metal) --"
exec "$OUT" "$MODEL"
