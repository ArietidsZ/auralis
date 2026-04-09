import SwiftUI

/// Minimal status pill with breathing dot animation.
struct StatusIndicatorView: View {
    let status: InterpretStatus

    enum InterpretStatus: String {
        case idle, connecting, listening, processing, speaking, error
    }

    private var statusInfo: (color: Color, label: String) {
        switch status {
        case .idle: return (.appTextSecondary, "就绪")
        case .connecting: return (.appAmber, "连接中…")
        case .listening: return (.appLiveGreen, "监听中")
        case .processing: return (.appAmber, "处理中")
        case .speaking: return (.appAccent, "播放中")
        case .error: return (.appError, "错误")
        }
    }

    @State private var isBreathing = false

    var body: some View {
        let info = statusInfo

        HStack(spacing: 6) {
            Circle()
                .fill(info.color)
                .frame(width: 6, height: 6)
                .scaleEffect(isBreathing && status != .idle ? 1.35 : 1.0)
                .animation(
                    status != .idle
                        ? .easeInOut(duration: 0.9).repeatForever(autoreverses: true)
                        : .default,
                    value: isBreathing
                )

            Text(info.label)
                .font(.appLabelSmall)
                .foregroundStyle(info.color)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 5)
        .background(info.color.opacity(0.10))
        .clipShape(Capsule())
        .animation(.easeInOut(duration: 0.3), value: status)
        .onAppear { isBreathing = true }
    }
}
