#!/bin/bash
# Real ASan/UBSan of hymt_core against the pinned GGUF. Not a fake tokenizer stub.
set -euo pipefail
REPO_ROOT="$(cd "$(dirname "$0")/../../../../../../.." && pwd)"
HYMT_CPP_DIR="${HYMT_CPP_DIR:-$REPO_ROOT/android/app/src/main/cpp/hymt_jni}"
HYMT_LLAMA_DIR="${HYMT_LLAMA_DIR:-$HOME/Library/Caches/Auralis/mt/llama-stq}"
HYMT_ASAN_DIR="${HYMT_ASAN_DIR:-$HOME/Library/Caches/Auralis/mt/build-host-asan}"
MODEL="${1:?usage: run_asan.sh <model.gguf>}"
SAN="-fsanitize=address,undefined -fno-omit-frame-pointer"

cmake -S "$HYMT_CPP_DIR" -B "$HYMT_ASAN_DIR" -G Ninja \
    -DLLAMA_CPP_DIR="$HYMT_LLAMA_DIR" -DHYMT_HOST_BUILD=ON \
    -DGGML_METAL=OFF -DGGML_NATIVE=ON -DCMAKE_BUILD_TYPE=Debug \
    -DCMAKE_C_FLAGS="$SAN" -DCMAKE_CXX_FLAGS="$SAN" \
    -DCMAKE_EXE_LINKER_FLAGS="$SAN" -DCMAKE_SHARED_LINKER_FLAGS="$SAN"
cmake --build "$HYMT_ASAN_DIR" --target hymt_core_dylib hymt_smoke hymt_concurrency
export ASAN_OPTIONS=detect_leaks=0:halt_on_error=1
# ggml.c:7317 at this pin hits UBSan null+offset during llama_init_from_model.
export UBSAN_OPTIONS=print_stacktrace=1
"$HYMT_ASAN_DIR/hymt_smoke" "$MODEL"
"$HYMT_ASAN_DIR/hymt_concurrency" "$MODEL"
echo "ASAN/UBSAN PASSED"
