import SwiftUI
import UIKit

/// Hex Code's colours: two palettes, picked by the colour scheme in the environment (Appearance
/// in Settings: Auto, Light or Dark). Values from the prototype; see `hex-design-system.md`.
enum HexColor {
    /// Screen background, inset fields.
    static let background = pair(dark: 0x08090A, light: 0xFBFBFC)
    /// Sheets, cards, toasts.
    static let panel = pair(dark: 0x0F1011, light: 0xFFFFFF)
    /// Buttons, options, raised rows.
    static let panel2 = pair(dark: 0x151618, light: 0xF3F3F5)
    /// Selected segment, menus.
    static let panel3 = pair(dark: 0x1C1D1F, light: 0xFFFFFF)
    /// Borders, dividers.
    static let line = foreground(dark: 0.08, light: 0.08)
    /// Stronger borders, the sheet grab bar.
    static let line2 = foreground(dark: 0.13, light: 0.14)
    /// Pressed row.
    static let hover = foreground(dark: 0.045, light: 0.04)
    /// Primary text.
    static let text1 = pair(dark: 0xF7F8F8, light: 0x17181A)
    /// Secondary text, icons.
    static let text2 = pair(dark: 0x8A8F98, light: 0x5F636B)
    /// Labels, hints, captions. With Increase Contrast it takes `text2`'s value (3.1–3.5:1 is below
    /// what small text needs).
    static let text3 = pair(dark: 0x62666D, light: 0x8B8F97, increased: (dark: 0x8A8F98, light: 0x5F636B))
    /// Faint marks, empty progress. With Increase Contrast it takes `text3`'s value.
    static let text4 = pair(dark: 0x3E4146, light: 0xC9CCD1, increased: (dark: 0x62666D, light: 0x8B8F97))
    /// Connected, working, success.
    static let green = pair(dark: 0x4CB782, light: 0x2C9A66)
    /// Needs you, warnings, Now.
    static let amber = pair(dark: 0xF2994A, light: 0xD9771C)
    /// Needs-you background.
    static let amberSoft = pair(dark: 0xF2994A, light: 0xD9771C, darkAlpha: 0.10, lightAlpha: 0.09)
    /// Needs-you border (`.needs-hint`): amber 30 %.
    static let amberLine = pair(dark: 0xF2994A, light: 0xD9771C, darkAlpha: 0.30, lightAlpha: 0.30)
    /// Stop, errors, drop a take.
    static let red = pair(dark: 0xEB5757, light: 0xD94848)
    /// Stop hint.
    static let redSoft = pair(dark: 0xEB5757, light: 0xD94848, darkAlpha: 0.12, lightAlpha: 0.09)
    /// Accent, used rarely.
    static let indigo = pair(dark: 0x5E6AD2, light: 0x5E6AD2)
    /// Primary button and talk button fill.
    static let solid = pair(dark: 0xE6E7E9, light: 0x17181A)
    /// Text and icons on `solid`.
    static let onSolid = pair(dark: 0x08090A, light: 0xFFFFFF)
    /// Behind sheets.
    static let scrim = pair(dark: 0x000000, light: 0x000000, darkAlpha: 0.60, lightAlpha: 0.28)
    /// The stop warning card.
    static let stopBackground = pair(dark: 0x1B1112, light: 0xFFF4F3)
    /// Menus and toasts.
    static let shadow = pair(dark: 0x000000, light: 0x000000, darkAlpha: 0.55, lightAlpha: 0.14)

    /// The foreground at `percent` % opacity: white in dark, black in light. The prototype's
    /// `rgba(var(--fg-rgb), n)`, for chunk fills, round buttons and the search field.
    static func tint(_ percent: Double) -> Color {
        foreground(dark: percent / 100, light: percent / 100)
    }

    private static func pair(
        dark: UInt32,
        light: UInt32,
        darkAlpha: CGFloat = 1,
        lightAlpha: CGFloat = 1,
        increased: (dark: UInt32, light: UInt32)? = nil
    ) -> Color {
        Color(uiColor: UIColor { traits in
            let high = traits.accessibilityContrast == .high
            let darkValue = high ? increased?.dark ?? dark : dark
            let lightValue = high ? increased?.light ?? light : light
            return traits.userInterfaceStyle == .dark
                ? UIColor(rgb: darkValue, alpha: darkAlpha)
                : UIColor(rgb: lightValue, alpha: lightAlpha)
        })
    }

    /// The value a token takes in `traits`, for tests and the gallery.
    static func resolved(_ color: Color, dark: Bool, increasedContrast: Bool) -> UIColor {
        let traits = UITraitCollection(mutations: {
            $0.userInterfaceStyle = dark ? .dark : .light
            $0.accessibilityContrast = increasedContrast ? .high : .normal
        })
        return UIColor(color).resolvedColor(with: traits)
    }

    private static func foreground(dark: CGFloat, light: CGFloat) -> Color {
        pair(dark: 0xFFFFFF, light: 0x000000, darkAlpha: dark, lightAlpha: light)
    }
}

private extension UIColor {
    convenience init(rgb: UInt32, alpha: CGFloat) {
        self.init(
            red: CGFloat((rgb >> 16) & 0xFF) / 255,
            green: CGFloat((rgb >> 8) & 0xFF) / 255,
            blue: CGFloat(rgb & 0xFF) / 255,
            alpha: alpha
        )
    }
}
