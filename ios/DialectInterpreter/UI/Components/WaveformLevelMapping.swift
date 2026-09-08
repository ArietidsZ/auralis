import Foundation

/// Shared, bounded mapping from a real microphone RMS level to waveform bar
/// heights.
///
/// This is the implementation of the "Input level" row of the motion spec
/// (docs/specs/2026-09-08-quality/motion.md): seven bars from real RMS,
/// logarithmically compressed so quiet speech stays readable, drawn on one
/// surface with no per-bar springs. The constants and the static height map
/// mirror the Android `WaveformVisualizer.kt` exactly, so both platforms
/// render identical bar shapes for the same capture level; the retarget ease
/// in `WaveformView` (~0.2 s) plays the role of Android's retargeting spring.
///
/// Everything here is pure and SwiftUI-free so it can be unit tested without
/// rendering.
enum WaveformLevel {

    // MARK: Shared visual numbers (parity with android WaveformVisualizer.kt)

    /// Number of bars. The view and the mapping must agree on this.
    static let barCount = 7

    /// Silence / not-listening outline as a fraction of the full height.
    static let restLevel: Float = 0.12

    /// Perceptual window edges for the logarithmic map: ~0.005 RMS is near the
    /// noise floor, ~0.5 RMS is loud speech. Ordinary conversation
    /// (0.01–0.1 RMS) spreads across the lower two-thirds of the meter instead
    /// of collapsing into a flat floor.
    static let quietFloor: Float = 0.005
    static let loudCeil: Float = 0.5

    /// Hard visual floor so the capsules stay visible at rest.
    static let minFraction: Float = 0.06

    // MARK: Mapping

    /// Maps a real RMS capture level to the shared bar level in [0, 1] using
    /// log10 compression between `quietFloor` and `loudCeil`
    /// (0.01 RMS → ≈0.15, 0.1 RMS → ≈0.65).
    ///
    /// Boundary semantics:
    /// - `isActive == false` → `restLevel` (static decoration; never a
    ///   fabricated activity signal).
    /// - Non-finite RMS (NaN / ±inf) → `restLevel` (safe fallback; the meter
    ///   never renders garbage or crashes).
    /// - `rms <= 0` → 0 (silence is a real, finite measurement, not garbage).
    /// - Out-of-range levels clamp to [0, 1].
    static func levelFraction(amplitude: Float, isActive: Bool) -> Float {
        if !isActive || !amplitude.isFinite { return restLevel }
        guard amplitude > 0 else { return 0 }
        let span = log10(loudCeil) - log10(quietFloor)
        let t = (log10(amplitude) - log10(quietFloor)) / span
        if t.isNaN { return 0 }
        return min(max(t, 0), 1)
    }

    /// Static center-weighted bar window `w(i) = 0.55 + 0.45·sin(π·i/(n−1))`:
    /// a fixed shape with no time phase (edges 0.55, center 1.0). A
    /// `barCount` of 1 clamps the divisor to 1, so the window degenerates to
    /// `w(0) = 0.55` (`sin(0) == 0`) instead of dividing by zero — matching
    /// the Android implementation and its test.
    static func barWindow(index: Int, barCount: Int = WaveformLevel.barCount) -> Float {
        let divisor = max(barCount - 1, 1)
        return 0.55 + 0.45 * sin(Float.pi * Float(index) / Float(divisor))
    }

    /// Height fraction of bar `index` for a shared `level` in [0, 1]: the
    /// static window scaled between the rest outline and full height, bounded
    /// below by `minFraction` and above by 1 so bars never vanish or overflow.
    static func barHeightFraction(level: Float, index: Int,
                                  barCount: Int = WaveformLevel.barCount) -> Float {
        let scaled = barWindow(index: index, barCount: barCount)
            * (restLevel + level * (1 - restLevel))
        return min(max(scaled, minFraction), 1)
    }
}
