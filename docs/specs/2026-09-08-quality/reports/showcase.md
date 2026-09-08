# Motion showcase verification

The standalone [Remotion source](../../../../showcase/README.md) renders an 18-second, 1600 × 900, 30 fps interface demonstration. It uses the canonical Auralis mark and Pine/Mint/Paper palette. Conversation and meter activity are simulated and labelled; no speech recording, model weights or performance claims are included.

`npm run check` passed. The final H.264 export contains 540 frames, no audio stream, and is 577,138 bytes. SHA-256: `80afe5a1f34c5af9b95f1bdac5a6e97d0d1586765c8250fa921e8f368de591bf`.

A poster and six-frame contact sheet were visually reviewed for text clipping, overlap, hierarchy and brand consistency. With `reducedMotion=true`, frames 110 and 130 are byte-identical; the normal variant changes over the same interval. The [small evidence record](showcase-evidence.json) preserves these hashes and video metadata.

Selection: one composition, existing SVG, system fonts and ordinary React styles. No component framework, web service, downloaded font or native-app JavaScript dependency. Each render uses one worker. Generated media and node_modules remain outside Git; the lockfile and source are checked in CI. [setup-node v4.4.0](https://github.com/actions/setup-node/blob/v4.4.0/README.md) is pinned to its verified official commit.

Remotion retains its separate license, linked in the source README. The Auralis composition source is Apache-2.0. Native Compose/SwiftUI motion is validated separately.
