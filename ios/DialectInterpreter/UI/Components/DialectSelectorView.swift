import SwiftUI

/// Dialect/language selector sheet with adaptive colors.
struct DialectSelectorView: View {
    @Binding var sourceDialect: String
    @Binding var targetLanguage: String
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    // Source dialect section
                    VStack(alignment: .leading, spacing: 10) {
                        Text("源方言".uppercased())
                            .font(.system(size: 11, weight: .medium))
                            .foregroundStyle(.appTextSecondary)
                            .tracking(0.8)

                        FlowLayout(spacing: 8) {
                            ForEach(AsrEngine.chineseDialects, id: \.0) { label, _ in
                                ChipButton(
                                    text: label,
                                    isSelected: label == sourceDialect
                                ) {
                                    withAnimation(.spring(response: 0.3, dampingFraction: 0.7)) {
                                        sourceDialect = label
                                    }
                                }
                            }
                        }
                    }

                    // Target language section
                    VStack(alignment: .leading, spacing: 10) {
                        Text("目标语言".uppercased())
                            .font(.system(size: 11, weight: .medium))
                            .foregroundStyle(.appTextSecondary)
                            .tracking(0.8)

                        FlowLayout(spacing: 8) {
                            ForEach(AsrEngine.supportedLanguages, id: \.0) { label, _ in
                                ChipButton(
                                    text: label,
                                    isSelected: label == targetLanguage
                                ) {
                                    withAnimation(.spring(response: 0.3, dampingFraction: 0.7)) {
                                        targetLanguage = label
                                    }
                                }
                            }
                        }
                    }
                }
                .padding(20)
            }
            .background(Color.appBg)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("完成") { dismiss() }
                        .foregroundStyle(.appAccent)
                }
            }
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
    }
}

/// Capsule chip button with spring animation.
private struct ChipButton: View {
    let text: String
    let isSelected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(text)
                .font(.system(size: 13, weight: isSelected ? .medium : .regular))
                .padding(.horizontal, 14)
                .padding(.vertical, 7)
                .background(isSelected ? Color.appAccent.opacity(0.15) : Color.appSurface)
                .foregroundStyle(isSelected ? .appAccent : .appText)
                .clipShape(Capsule())
                .overlay(
                    Capsule()
                        .strokeBorder(isSelected ? Color.appAccent.opacity(0.4) : Color.clear, lineWidth: 1)
                )
        }
        .buttonStyle(.plain)
    }
}

/// Flow layout for wrapping chips.
struct FlowLayout: Layout {
    var spacing: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        arrangeSubviews(proposal: proposal, subviews: subviews).size
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let result = arrangeSubviews(proposal: proposal, subviews: subviews)
        for (index, position) in result.positions.enumerated() {
            subviews[index].place(
                at: CGPoint(x: bounds.minX + position.x, y: bounds.minY + position.y),
                proposal: .unspecified
            )
        }
    }

    private struct ArrangementResult {
        var size: CGSize
        var positions: [CGPoint]
    }

    private func arrangeSubviews(proposal: ProposedViewSize, subviews: Subviews) -> ArrangementResult {
        let maxWidth = proposal.width ?? .infinity
        var positions: [CGPoint] = []
        var x: CGFloat = 0
        var y: CGFloat = 0
        var rowHeight: CGFloat = 0
        var maxX: CGFloat = 0

        for subview in subviews {
            let size = subview.sizeThatFits(.unspecified)
            if x + size.width > maxWidth && x > 0 {
                x = 0
                y += rowHeight + spacing
                rowHeight = 0
            }
            positions.append(CGPoint(x: x, y: y))
            rowHeight = max(rowHeight, size.height)
            x += size.width + spacing
            maxX = max(maxX, x)
        }

        return ArrangementResult(
            size: CGSize(width: maxX, height: y + rowHeight),
            positions: positions
        )
    }
}
