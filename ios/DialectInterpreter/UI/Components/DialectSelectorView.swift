import SwiftUI

/// Dialect/language selector sheet with adaptive colors.
struct DialectSelectorView: View {
    @Binding var sourceDialect: String
    @Binding var targetLanguage: String
    @Environment(\.dismiss) private var dismiss
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    // Dialects come from the shared catalog (single source of truth, IDs stable).
    private var catalog: SharedContracts.DialectCatalog? { try? SharedContracts.loadCatalog() }

    /// Restrained selection highlight, on the shared status-change rhythm;
    /// reduced motion switches instantly.
    private var selectionAnimation: Animation? {
        reduceMotion ? nil : .easeOut(duration: 0.18)
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    // Source dialect section
                    VStack(alignment: .leading, spacing: 10) {
                        Text("源方言".uppercased())
                            .font(.appLabelSmall.weight(.medium))
                            .foregroundStyle(Color.appTextSecondary)
                            .tracking(0.8)

                        FlowLayout(spacing: 8) {
                            ForEach(catalog?.dialects ?? [], id: \.id) { dialect in
                                ChipButton(
                                    text: dialect.displayLabel,
                                    isSelected: dialect.displayLabel == sourceDialect
                                ) {
                                    withAnimation(selectionAnimation) {
                                        sourceDialect = dialect.displayLabel
                                    }
                                }
                            }
                        }
                    }

                    // Target language section
                    VStack(alignment: .leading, spacing: 10) {
                        Text("目标语言".uppercased())
                            .font(.appLabelSmall.weight(.medium))
                            .foregroundStyle(Color.appTextSecondary)
                            .tracking(0.8)

                        FlowLayout(spacing: 8) {
                            ForEach(catalog?.targetLanguages ?? [], id: \.id) { language in
                                ChipButton(
                                    text: language.displayLabel,
                                    isSelected: language.displayLabel == targetLanguage
                                ) {
                                    withAnimation(selectionAnimation) {
                                        targetLanguage = language.displayLabel
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
                        .foregroundStyle(Color.appAccent)
                }
            }
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
    }
}

/// Capsule chip button; selection highlight animates with the shared
/// restrained rhythm driven by the caller.
private struct ChipButton: View {
    let text: String
    let isSelected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(text)
                .font(.appBodySmall.weight(isSelected ? .medium : .regular))
                .padding(.horizontal, 14)
                .padding(.vertical, 7)
                .background(isSelected ? Color.appAccent.opacity(0.15) : Color.appSurface)
                .foregroundStyle(isSelected ? Color.appAccent : Color.appText)
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
