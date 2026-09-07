#!/bin/bash
# Host-run session core tests (no Xcode, no platform SDK needed).
# Compiles the session core + its ports-fakes + the test harness with the
# host Swift toolchain and runs them. Exit code 0 = all pass.
set -euo pipefail
cd "$(dirname "$0")/../../.."   # repo root (ios/..)

SRC_IOS="ios/DialectInterpreter"
SRC_TESTS="ios/DialectInterpreterTests/SessionCoreTests"

OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
cat > "$OUT/main.swift" <<'EOF'
await SessionCoreTestHarness.runAll()
EOF

if ! swiftc \
  "$SRC_IOS/Inference/PipelineOrchestrator.swift" \
  "$SRC_IOS/Audio/VoiceActivityDetector.swift" \
  "$SRC_IOS/Audio/PlaybackOperation.swift" \
  "$SRC_IOS/UI/Screens/InterpretViewModel.swift" \
  "$SRC_TESTS/FakeStages.swift" \
  "$SRC_TESTS/SessionCoreTests.swift" \
  "$SRC_TESTS/PlaybackOperationTests.swift" \
  "$OUT/main.swift" \
  -o "$OUT/session-tests" \
  -module-cache-path "$OUT/module-cache" \
  -swift-version 5 2>"$OUT/build.log"; then
  echo "BUILD FAILED:"; cat "$OUT/build.log"; exit 2
fi
if [ -s "$OUT/build.log" ]; then
  echo "--- build warnings ---"; cat "$OUT/build.log"; echo "---"
fi

# Platform-checkable subset: the real AVAudioEngine player type-checks on
# macOS (AVAudioSession config is #if os(iOS)); no fake AVFoundation passes.
if ! swiftc -typecheck \
  "$SRC_IOS/Audio/PlaybackOperation.swift" \
  "$SRC_IOS/Audio/AudioPlayer.swift" \
  "$SRC_IOS/Audio/StreamPlaybackOperation.swift" \
  -module-cache-path "$OUT/module-cache" \
  -swift-version 5 2>"$OUT/av.log"; then
  echo "AVPLAYER TYPECHECK FAILED:"; cat "$OUT/av.log"; exit 3
fi
if [ -s "$OUT/av.log" ]; then
  echo "--- AudioPlayer typecheck notes ---"; cat "$OUT/av.log"; echo "---"
fi

"$OUT/session-tests"
