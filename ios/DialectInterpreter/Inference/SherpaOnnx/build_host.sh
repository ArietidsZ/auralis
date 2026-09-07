#!/usr/bin/env bash
# Host (macosx) build of the ASR C API wrapper against sherpa-onnx 1.13.7.
# Catalyst compile (-c, no link) is also attempted. iphoneos SDK is listed
# blocked — this machine has no iPhoneOS.sdk.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$ROOT/../../../.." && pwd)"
SHERPA_LIB="${SHERPA_LIB:-/private/tmp/auralis-models/asr-venv/lib/python3.12/site-packages/sherpa_onnx/lib}"
OUT="${OUT:-/private/tmp/auralis-models/asr/sherpa-host}"
mkdir -p "$OUT"

clang -std=c11 -O2 \
  -I "$ROOT" \
  -c "$ROOT/auralis_sherpa_asr.c" -o "$OUT/auralis_sherpa_asr.macosx.o"

clang -std=c11 -O2 \
  -I "$ROOT" \
  "$ROOT/auralis_sherpa_asr.c" "$ROOT/host_smoke.c" \
  -L "$SHERPA_LIB" -lsherpa-onnx-c-api \
  -Wl,-rpath,"$SHERPA_LIB" \
  -o "$OUT/host_smoke"

echo "host_smoke -> $OUT/host_smoke"

SDK26="${SDK26:-/Library/Developer/CommandLineTools/SDKs/MacOSX26.5.sdk}"
if [[ -d "$SDK26" ]]; then
  clang -std=c11 -O2 \
    -target arm64-apple-ios17.0-macabi \
    -isysroot "$SDK26" \
    -iframework "$SDK26/System/iOSSupport/System/Library/Frameworks" \
    -I "$ROOT" \
    -c "$ROOT/auralis_sherpa_asr.c" \
    -o "$OUT/auralis_sherpa_asr.macabi.o"
  echo "catalyst -c -> $OUT/auralis_sherpa_asr.macabi.o"
else
  echo "MacOSX26.5.sdk missing; catalyst compile skipped"
fi

if [[ ! -d /Library/Developer/CommandLineTools/SDKs/iPhoneOS.sdk ]] && \
   ! xcrun --sdk iphoneos --show-sdk-path >/dev/null 2>&1; then
  echo "BLOCKED: iphoneos SDK not present; device/sim sherpa xcframework not built"
fi
