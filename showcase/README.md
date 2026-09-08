# Auralis motion showcase

A standalone 18-second Remotion composition. It illustrates interface states with a simulated conversation; it is not a recording of model inference or a performance benchmark. The composition reuses the canonical SVG from `docs/brand` and system fonts. No voice recordings or model weights are included.

```sh
cd showcase
npm ci
npm run check
npm run still
npm run render
```

The CLI accepts `--browser-executable /path/to/chrome` after `npm run render --` when Chrome is already installed. Rendering defaults to one worker. `out/` and `node_modules/` are generated and ignored.

For the reduced-motion variant, pass `--props '{"reducedMotion":true}'`. System font availability can change glyph metrics between render hosts; inspect a still before exporting.

This source is Apache-2.0. Remotion 4.0.522 retains its separate [Remotion License](https://github.com/remotion-dev/remotion/blob/v4.0.522/LICENSE.md); rendering-tool dependencies are not part of the native app runtime.
