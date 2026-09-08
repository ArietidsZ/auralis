# Native motion

Auralis uses the existing Pine/Mint/Paper palette and system typography. Motion clarifies state changes and responds to real input. The separate Remotion composition is an interface demonstration, not a model-performance claim.

| Surface | Motion | Bound |
|---|---|---|
| Session status | Short fade and small vertical change | About160–220ms, no repeated layout work |
| New conversation item | Fade with small displacement and placement spring | Stable turn IDs, preserve reading/focus order |
| Main action | Small press scale and native feedback | Draw/compositing phase; no layout resize |
| Input level | Seven bars from real RMS, smoothed and compressed for quiet speech | One drawing surface, no independent per-bar springs |
| Idle and reduced motion | Static decoration; information remains current | No idle timeline or periodic task |

Use native Compose/SwiftUI primitives. Keep state logic and inference outputs unchanged. Keep playback distinct from listening; a decorative activity signal does not claim a measured output waveform. Reduced motion must suppress decorative movement and animated scrolling.

Selection checks: native compile, state readability at larger text sizes, reduced-motion behavior, and idle scheduling. Compare drawing-only level updates with the existing layout-per-bar design. Any remaining device/frame-time claims require measured evidence.
