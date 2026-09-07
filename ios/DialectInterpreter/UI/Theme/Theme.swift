import SwiftUI

// MARK: - Auralis colors and native typography

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

    private init(light: UInt, dark: UInt) {
        self.init(UIColor { trait in
            let hex = trait.userInterfaceStyle == .dark ? dark : light
            return UIColor(red: CGFloat((hex >> 16) & 0xFF) / 255,
                           green: CGFloat((hex >> 8) & 0xFF) / 255,
                           blue: CGFloat(hex & 0xFF) / 255, alpha: 1)
        })
    }

    // Generated from the canonical brand assets, including dark mode.
    static let appAccent = Color("AccentColor")
    static let appOnAccent = Color(light: 0xFFFFFF, dark: 0x0F191D)

    // Backgrounds
    static let appBg = Color(light: 0xF6F8F7, dark: 0x0F191D)
    static let appSurface = Color(light: 0xFFFFFF, dark: 0x142128)

    // Text
    static let appText = Color(light: 0x17232A, dark: 0xF6F8F7)
    static let appTextSecondary = Color(light: 0x5A6B66, dark: 0x9FB3AD)
    static let appTextTertiary = Color(UIColor.tertiaryLabel)

    // Chat bubbles
    static let appBubbleSource = Color(light: 0xECF1EF, dark: 0x1C2F35)
    static let appBubbleTarget = Color(light: 0xDCEEE9, dark: 0x17332E)

    // Functional (semantic)
    static let appRecordingRed = Color(hex: 0xC23A32)
    static let appAmber = Color(light: 0x8A5A00, dark: 0xE8B45A)
    static let appGreen = Color(light: 0x1B6E4A, dark: 0x7BD3A8)
    static let appLiveGreen = appGreen
    static let appError = Color(light: 0xB3261E, dark: 0xE8907E)
}

// MARK: - Typography — SF Pro Rounded

extension Font {
    static let appDisplaySmall = Font.system(.title, design: .rounded).bold()
    static let appHeadlineMedium = Font.system(.title2, design: .rounded).weight(.semibold)
    static let appTitleLarge = Font.system(.title3, design: .rounded).weight(.semibold)
    static let appTitleMedium = Font.system(.headline, design: .rounded)
    static let appTitleSmall = Font.system(.subheadline, design: .rounded).weight(.medium)
    static let appBodyLarge = Font.body
    static let appBodyMedium = Font.subheadline
    static let appBodySmall = Font.footnote
    static let appLabelLarge = Font.subheadline.weight(.medium)
    static let appLabelMedium = Font.caption
    static let appLabelSmall = Font.caption2
}
