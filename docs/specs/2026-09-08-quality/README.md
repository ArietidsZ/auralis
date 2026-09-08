# Auralis runtime quality and motion

Baseline: `v0.1.0-preview.1` / `7e2cb57927d6290c089dce1c33a72bbc9902b6cc`.

This pass reduces repeated inference copies and acquisition reads, bounds iOS capture memory, and adds restrained native motion. Changes are selected against the baseline while preserving model outputs, sampling, cancellation and package integrity.

| Area | Selected change | Evidence |
|---|---|---|
| Android TTS | Bounded top-k selection, shared repetition mask, mel/FFT work reduction, prepared-reference ownership and cleanup | [Fixed-seed equivalence and JVM measurements](reports/android-runtime.md) |
| iOS TTS | Direct ORT KV feedback, shared scoped tensor reads, checked shape arithmetic | [Runtime tests and full-model WAV equality](reports/ios-runtime.md) |
| iOS capture | Ten-second utterance bound and eight-chunk callback queue | [PCM continuity and overflow checks](reports/capture.md) |
| Model tools | Hash during acquisition; clone local files when safe; remove repeated hash wrappers | [Failure-path tests and measured I/O ablation](reports/model-tooling.md) |
| Native UI | State, turn, press and live-level motion with reduced-motion support | [Native implementation and verification](reports/native-motion.md) |
| Showcase | One 18-second Remotion composition using the canonical brand | [Render and reduced-motion evidence](reports/showcase.md) |

Small units are retained where they remove repetition or expose a useful boundary. Extra strategies, ownership frameworks, digest caches and losing benchmark candidates are omitted. The area reports distinguish structural savings, measured host results and limits.

Run `python3 scripts/verify --mode fast` for contracts, Python and Swift host checks. Android and iOS CI also build their pinned native dependencies and run platform build/test checks. Showcase CI installs the lockfile and type-checks its separate TypeScript source.

Model weights, precision, pinned revisions and shared-package readiness remain fixed. Host/emulator results do not qualify physical-device latency, memory or energy. The published preview artifacts remain associated with their original tag; this source pass does not silently replace them.
