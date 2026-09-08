# Auralis

Offline speech translation. / 离线方言传译。

The name is always **Auralis**. Keep the wordmark in title case. The repository slug is `auralis`; internal package and module identifiers are not display names.

The mark combines an A with two listening arcs. Pine is the background, mint is the accent, and the letter uses warm white. These colors match both native applications.

| Token | Color |
|---|---|
| Pine | `#142128` |
| Mint | `#63B3A4` |
| Paper | `#F6F8F7` |
| Light-mode accent | `#216B62` |

[mark.svg](mark.svg) is the canonical geometry. Run `python3 scripts/build_brand_assets` to regenerate the Android foreground/monochrome vectors and iOS icon sizes. The iOS icon stays opaque and square; the operating system supplies its mask. Use system fonts in the applications, and preserve native text scaling.

Avoid claims such as “real time,” “production certified,” or “best accuracy” without the corresponding published measurements. The first release is a developer preview.

The [native motion rules](../specs/2026-09-08-quality/motion.md) and [Remotion showcase](../../showcase/README.md) use this same visual system.
