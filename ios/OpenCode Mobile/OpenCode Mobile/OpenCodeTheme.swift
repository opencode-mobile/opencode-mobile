import SwiftUI
import UIKit

enum OpenCodeColors {
    static let backgroundPrimary = dynamic(light: 0xF8F8F8, dark: 0x121212)
    static let backgroundSecondary = dynamic(light: 0xF3F3F3, dark: 0x191919)
    static let backgroundElevated = dynamic(light: 0xFCFCFC, dark: 0x151515)
    static let surfacePrimary = dynamic(light: 0xFFFFFF, dark: 0x1C1C1C)
    static let surfaceSecondary = dynamic(light: 0xF3F3F3, dark: 0x282727)
    static let surfaceInset = dynamic(light: 0xF0F0F0, dark: 0x202020)
    static let textPrimary = dynamic(light: 0x171717, dark: 0xEDEDED)
    static let textSecondary = dynamic(light: 0x6F6F6F, dark: 0xA0A0A0)
    static let textTertiary = dynamic(light: 0x8F8F8F, dark: 0x707070)
    static let textDisabled = dynamic(light: 0xC7C7C7, dark: 0x505050)
    static let textInteractive = dynamic(light: 0x034CFF, dark: 0xC0D4FB)
    static let borderSubtle = dynamic(light: 0xE5E5E5, dark: 0x282828)
    static let borderDefault = dynamic(light: 0x000000, lightAlpha: 0.16, dark: 0x4D4C4A)
    static let iconDefault = dynamic(light: 0x8F8F8F, dark: 0x7E7E7E)
    static let iconStrong = dynamic(light: 0x171717, dark: 0xEDE8E4)
    static let statusSuccess = dynamic(light: 0x12C905, dark: 0x089B00)
    static let statusWarning = dynamic(light: 0xFBDD46, dark: 0xC78B05)
    static let statusError = dynamic(light: 0xFC533A, dark: 0xCB321C)
    static let statusInfo = dynamic(light: 0xA753AE, dark: 0xCD73D5)

    private static func dynamic(
        light: UInt32,
        lightAlpha: CGFloat = 1,
        dark: UInt32,
        darkAlpha: CGFloat = 1
    ) -> Color {
        Color(
            UIColor { traits in
                if traits.userInterfaceStyle == .dark {
                    return UIColor(rgb: dark, alpha: darkAlpha)
                }
                return UIColor(rgb: light, alpha: lightAlpha)
            }
        )
    }
}

struct OpenCodeThemeModifier: ViewModifier {
    func body(content: Content) -> some View {
        content
            .tint(OpenCodeColors.textInteractive)
            .foregroundStyle(OpenCodeColors.textPrimary)
            .background(OpenCodeColors.backgroundPrimary)
    }
}

extension View {
    func openCodeTheme() -> some View {
        modifier(OpenCodeThemeModifier())
    }
}

private extension UIColor {
    convenience init(rgb: UInt32, alpha: CGFloat = 1) {
        self.init(
            red: CGFloat((rgb >> 16) & 0xFF) / 255,
            green: CGFloat((rgb >> 8) & 0xFF) / 255,
            blue: CGFloat(rgb & 0xFF) / 255,
            alpha: alpha
        )
    }
}
