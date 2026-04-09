import SwiftUI

// MARK: - Apple-Inspired Adaptive Color System

/// Namespace for design tokens and adaptive colors.
enum AppStyle {
    // MARK: Spacing & Radius
    static let radiusBubble: CGFloat = 20
    static let radiusCard: CGFloat = 16
    static let radiusPill: CGFloat = 28
    static let radiusSmall: CGFloat = 10
}

// MARK: - Adaptive Colors

extension Color {
    init(hex: UInt, alpha: Double = 1.0) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xFF) / 255.0,
            green: Double((hex >> 8) & 0xFF) / 255.0,
            blue: Double(hex & 0xFF) / 255.0,
            opacity: alpha
        )
    }

    // Accent — System Indigo
    static let appAccent = Color(UIColor { trait in
        trait.userInterfaceStyle == .dark
            ? UIColor(red: 0.49, green: 0.48, blue: 1.0, alpha: 1)    // #7D7AFF
            : UIColor(red: 0.345, green: 0.337, blue: 0.839, alpha: 1) // #5856D6
    })

    // Backgrounds
    static let appBg = Color(UIColor.systemGroupedBackground)
    static let appSurface = Color(UIColor.secondarySystemGroupedBackground)
    static let appSurfaceSecondary = Color(UIColor.tertiarySystemGroupedBackground)

    // Text
    static let appText = Color(UIColor.label)
    static let appTextSecondary = Color(UIColor.secondaryLabel)
    static let appTextTertiary = Color(UIColor.tertiaryLabel)

    // Chat bubbles
    static let appBubbleSource = Color(UIColor { trait in
        trait.userInterfaceStyle == .dark
            ? UIColor.secondarySystemBackground
            : UIColor(red: 0.95, green: 0.95, blue: 0.97, alpha: 1)  // #F2F2F7
    })
    static let appBubbleTarget = Color(UIColor { trait in
        trait.userInterfaceStyle == .dark
            ? UIColor(red: 0.49, green: 0.48, blue: 1.0, alpha: 0.12)
            : UIColor(red: 0.345, green: 0.337, blue: 0.839, alpha: 0.08)
    })

    // Functional (semantic)
    static let appRecordingRed = Color(hex: 0xFF3B30)
    static let appAmber = Color(hex: 0xFF9500)
    static let appGreen = Color(hex: 0x34C759)
    static let appLiveGreen = Color(hex: 0x30D158)
    static let appError = Color(hex: 0xFF3B30)
}

// MARK: - Typography — SF Pro Rounded

extension Font {
    static let appDisplaySmall = Font.system(size: 28, weight: .bold, design: .rounded)
    static let appHeadlineMedium = Font.system(size: 22, weight: .semibold, design: .rounded)
    static let appTitleLarge = Font.system(size: 20, weight: .semibold, design: .rounded)
    static let appTitleMedium = Font.system(size: 17, weight: .semibold, design: .rounded)
    static let appTitleSmall = Font.system(size: 15, weight: .medium, design: .rounded)
    static let appBodyLarge = Font.system(size: 17, design: .default)
    static let appBodyMedium = Font.system(size: 15, design: .default)
    static let appBodySmall = Font.system(size: 13, design: .default)
    static let appLabelLarge = Font.system(size: 15, weight: .medium)
    static let appLabelMedium = Font.system(size: 12)
    static let appLabelSmall = Font.system(size: 11)
}
