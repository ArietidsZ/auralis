import SwiftUI

/// Vertical bar waveform driven by the live capture RMS (motion spec
/// "Input level").
///
/// One drawing surface: bar heights are computed in the `Canvas` draw phase
/// from a single shared level — no per-frame layout and no per-bar springs.
/// The displayed level follows the real microphone RMS through the bounded
/// logarithmic map in `WaveformLevel` (quiet speech stays readable; non-finite
/// input falls back to the static rest outline). Dynamics: each new amplitude
/// retargets a ~0.2 s ease that interpolates the level at frame rate and stops
/// once settled — animation runs only while a change is in flight, so an idle
/// meter schedules no work (no TimelineView, no timers, no EMA state machine).
/// Under reduced motion the level snaps.
struct WaveformView: View {
    let amplitude: Float
    let isActive: Bool

    private let barWidth: CGFloat = 4
    private let barSpacing: CGFloat = 4

    /// Fixed outer size — only the draw phase changes between updates.
    private let height: CGFloat = 28

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    /// Mapped level of the current real amplitude. There is no per-emission
    /// state to advance: repeated identical RMS values simply keep the same
    /// level (nothing to animate), and any change retargets the ease below.
    private var level: Float {
        WaveformLevel.levelFraction(amplitude: amplitude, isActive: isActive)
    }

    var body: some View {
        AnimatableBars(level: level, barWidth: barWidth, barSpacing: barSpacing)
            .frame(maxWidth: .infinity)
            .frame(height: height)
            .animation(reduceMotion ? nil : .easeOut(duration: 0.2), value: level)
            // Decorative input meter: the session subtitle already carries the
            // state, so VoiceOver users are not handed a churn of unlabeled bars.
            .accessibilityHidden(true)
    }
}

/// Single-surface bar drawing with the level exposed as `animatableData`:
/// SwiftUI interpolates it per frame while an ease is in flight and stops
/// when it completes — frame-rate-smooth bars with zero idle work.
private struct AnimatableBars: View, Animatable {
    var level: Float
    let barWidth: CGFloat
    let barSpacing: CGFloat

    var animatableData: Float {
        get { level }
        set { level = newValue }
    }

    var body: some View {
        Canvas { context, size in
            drawBars(in: &context, size: size)
        }
    }

    private func drawBars(in context: inout GraphicsContext, size: CGSize) {
        let count = WaveformLevel.barCount
        let total = CGFloat(count) * barWidth + CGFloat(count - 1) * barSpacing
        var x = (size.width - total) / 2
        let gradient = Gradient(colors: [Color.appAccent, Color.appAccent.opacity(0.35)])

        for index in 0..<count {
            let fraction = CGFloat(WaveformLevel.barHeightFraction(level: level, index: index))
            let barHeight = max(fraction * size.height, barWidth)
            let rect = CGRect(x: x, y: size.height - barHeight, width: barWidth, height: barHeight)
            context.fill(
                Path(roundedRect: rect, cornerRadius: 2),
                with: .linearGradient(
                    gradient,
                    startPoint: CGPoint(x: rect.midX, y: rect.minY),
                    endPoint: CGPoint(x: rect.midX, y: rect.maxY)
                )
            )
            x += barWidth + barSpacing
        }
    }
}
