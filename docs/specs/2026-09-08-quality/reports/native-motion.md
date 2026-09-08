# Native motion verification — 2026-09-08

Baseline: `v0.1.0-preview.1` (`7e2cb579`). Both apps use native Compose/SwiftUI primitives and the existing Auralis palette. Inference, sampling and model-readiness contracts are unchanged.

## Selected behavior

| Surface | Implementation |
|---|---|
| Session status | Short fade and small vertical shift; direct replacement under reduced motion. Compose size animation is disabled. |
| Conversation | Stable turn IDs drive insertion/placement effects; updates within an existing turn do not replay insertion. Automatic scrolling follows the last ID even when the 200-item list is full. |
| Main action | Small press scale and dimming; reduced motion suppresses scale. Playback is labelled as playback and hides the live capture meter. |
| Input meter | One shared scalar drives seven bars on one drawing surface. A logarithmic display window makes small normalized RMS values distinguishable. |
| Idle/reduced motion | No idle TimelineView, floating empty-state loop or independent bar springs. System preference changes are respected after Android resumes and through the SwiftUI environment. |

The meter uses a display window of 0.005…0.5 normalized RMS, not a calibrated sound-pressure measurement. Android reads its animated scalar in Canvas drawing; SwiftUI interpolates one `animatableData` scalar and draws through Canvas. Reduced motion snaps to the current measured level. The [Apple Animatable contract](https://developer.apple.com/documentation/swiftui/animatable) supplies interpolation without a timer; [Compose ContentTransform](https://developer.android.com/reference/kotlin/androidx/compose/animation/ContentTransform) permits a null size transform while retaining fade/slide.

The always-dark Android header now uses Mint `#63B3A4` on Pine `#142128` (calculated contrast 6.663:1). Its earlier light-theme accent produced 2.617:1. Light-surface meters keep the light-surface accent.

## Simplification and corrections

- Removed the old layout animation for each Android bar and the idle SwiftUI waveform timeline.
- Rejected an initial per-emission EMA: at a low update rate it stepped visibly and identical consecutive measurements could leave it short of the target. Native scalar interpolation replaces that state machine.
- Replaced a Compose lifecycle observer wrapper with one Activity state value refreshed in `onResume`.
- Removed the unused `StatusIndicatorView` after confirming it had no references. The Xcode project uses filesystem-synchronized groups; no project entry remained to remove.
- Replaced one-millisecond reduced-motion transitions with direct content/`EnterTransition.None`/`ExitTransition.None`.
- One flag now arms the three typing dots. A live reduced-motion change disarms the effect; ring and press animations also receive nil when motion is reduced.
- Replaced a test that enumerated more than 100 million adjacent Floats with a bounded grid plus neighboring values at both clamp boundaries. The eight waveform cases use the project's existing Swift Testing framework.
- Removed the stale Settings label `ONNX Runtime 1.22`; the screen now describes the useful fact, local CPU execution, without a duplicated dependency version.

## Verification

| Check | Result |
|---|---|
| Android unit/build | 169 tests, zero failures/errors, one explicit benchmark skip; debug APK built |
| Android UI-only API 35 AVD | Normal and 200% text layouts, Settings forward/back navigation, and system Settings return with animations disabled checked; no crash log entries |
| iOS all-source Catalyst typecheck | Exit 0 using the installed real 26.5 SDK, UIKit/SwiftUI and ORT headers; no UIKit shim |
| Swift waveform tests | Eight real Swift Testing cases pass: bounds, quiet-level readability, monotonicity, inactive/non-finite input and bar geometry |
| Integrated platform CI | Required on the final branch head before merge |

[Small evidence record](native-motion-evidence.json) includes the tested Android APK hash, screenshot hashes, Swift source/test hashes and contrast calculation. Seven screenshots and XML hierarchies remain privately under `$AURALIS_CACHE/quality-2026-09-08/native-motion-root/`; normal and large-text interpretation/settings images were visually inspected. Those screenshots precede only the final removal of the stale static Settings version label.

The original model-filled AVD lacked installation space. UI checks used a separate empty userdata image with the current debug APK; no model readiness was bypassed, and recording stayed disabled. Test settings were restored, the emulator stopped and disposable images removed. Original model/profile data was preserved.

Local Android lint could not resolve missing cached metadata because Google Maven failed TLS; project repositories/dependencies were unchanged. Final GitHub CI supplies the actual lint result. Catalyst retained two pre-existing Bluetooth deprecation warnings. The host test recipe required explicit paths to the installed Swift Testing plugin/runtime; no test-library stubs were used.

Screenshots establish layout and destination state, not animation frame timing. Active microphone-meter motion, iOS visual rendering and physical-device performance remain unqualified. The separate [Remotion showcase](showcase.md) is labelled simulation and does not replace these gates.
