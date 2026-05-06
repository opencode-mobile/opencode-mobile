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

    // List item letter badge (component tokens — listItem.startDecorator.letter.*)
    static let listItemLetterVioletFill = dynamic(light: 0x4C3D5C, dark: 0x3A2F47)
    static let listItemLetterVioletTint = dynamic(light: 0xE2D5F5, dark: 0xD8C8EE)
    static let listItemLetterTealFill = dynamic(light: 0x2D4A42, dark: 0x243832)
    static let listItemLetterTealTint = dynamic(light: 0xB8F5E0, dark: 0xA8EDD8)
    static let listItemLetterAmberFill = dynamic(light: 0x5C3D28, dark: 0x4A3224)
    static let listItemLetterAmberTint = dynamic(light: 0xFFB86C, dark: 0xFFAA55)
    static let listItemLetterBlueFill = dynamic(light: 0x2A3A55, dark: 0x1E2D42)
    static let listItemLetterBlueTint = dynamic(light: 0xA8D4FF, dark: 0x9EC8FF)
    static let listItemLetterRoseFill = dynamic(light: 0x5C2D3D, dark: 0x4A2533)
    static let listItemLetterRoseTint = dynamic(light: 0xFFB8D4, dark: 0xFFA8CC)

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
