import SwiftUI

/// Apple-style vertical bar waveform visualizer.
/// 7 rounded capsule bars animate height with spring physics.
struct WaveformView: View {
    let amplitude: Float
    let isActive: Bool

    private let barCount = 7

    var body: some View {
        TimelineView(.animation) { timeline in
            let time = timeline.date.timeIntervalSinceReferenceDate
            let breathPhase = Float(time.truncatingRemainder(dividingBy: 2.4)) / 2.4 * 2 * .pi

            HStack(spacing: 4) {
                ForEach(0..<barCount, id: \.self) { index in
                    let normalizedIndex = Float(index) / Float(barCount - 1)
                    let fraction: CGFloat = {
                        if isActive {
                            let offset = sin(normalizedIndex * .pi + breathPhase * 1.5)
                            return CGFloat((amplitude * (0.5 + 0.5 * offset)).clamped(to: 0.15...1.0))
                        } else {
                            let idleWave = 0.12 + 0.08 * sin(breathPhase + Float(index) * 0.9)
                            return CGFloat(idleWave)
                        }
                    }()

                    RoundedRectangle(cornerRadius: 2)
                        .fill(
                            LinearGradient(
                                colors: [Color.appAccent, Color.appAccent.opacity(0.35)],
                                startPoint: .top,
                                endPoint: .bottom
                            )
                        )
                        .frame(width: 4, height: max(3, fraction * 40))
                        .animation(.spring(response: 0.35, dampingFraction: 0.45), value: fraction)
                }
            }
            .frame(maxWidth: .infinity)
        }
    }
}

// MARK: - Helpers

private extension Comparable {
    func clamped(to range: ClosedRange<Self>) -> Self {
        min(max(self, range.lowerBound), range.upperBound)
    }
}
