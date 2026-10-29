import SwiftUI
import UIKit

enum OpenCodeColors {

    // MARK: - Background & Surface

    static var backgroundBase: Color { resolve("background-base") }
    static var surfaceBase: Color { resolve("surface-base") }
    static var surfaceBaseHover: Color { resolve("surface-base-hover") }
    static var surfaceRaisedBase: Color { resolve("surface-raised-base") }
    static var surfaceRaisedBaseHover: Color { resolve("surface-raised-base-hover") }
    static var surfaceInteractiveWeak: Color { resolve("surface-interactive-weak") }
    static var surfaceSuccessBase: Color { resolve("surface-success-base") }
    static var surfaceCriticalBase: Color { resolve("surface-critical-base") }

    // MARK: - Text

    static var textStrong: Color { resolve("text-strong") }
    static var textBase: Color { resolve("text-base") }
    static var textWeak: Color { resolve("text-weak") }
    static var textWeaker: Color { resolve("text-weaker") }
    static var textInteractiveBase: Color { resolve("text-interactive-base") }
    static var textOnBrandBase: Color { resolve("text-on-brand-base") }

    // MARK: - Border

    static var borderWeakBase: Color { resolve("border-weak-base") }
    static var borderWeakerBase: Color { resolve("border-weaker-base") }

    // MARK: - Icon

    static var iconBase: Color { resolve("icon-base") }
    static var iconWeakBase: Color { resolve("icon-weak-base") }
    static var iconStrongBase: Color { resolve("icon-strong-base") }
    static var iconInvertBase: Color { resolve("icon-invert-base") }

    // MARK: - Button

    static var buttonPrimaryBase: Color { resolve("button-primary-base") }
    static var buttonSecondaryBase: Color { resolve("button-secondary-base") }
    static var buttonSecondaryHover: Color { resolve("button-secondary-hover") }

    // MARK: - Status

    static var interactive: Color { resolve("interactive") }
    static var success: Color { resolve("success") }
    static var warning: Color { resolve("warning") }
    static var error: Color { resolve("error") }
    static var info: Color { resolve("info") }
    static var diffAdd: Color { resolve("diff-add") }
    static var diffDelete: Color { resolve("diff-delete") }

    // MARK: - Syntax

    static var syntaxComment: Color { resolve("syntax-comment") }
    static var syntaxKeyword: Color { resolve("syntax-keyword") }
    static var syntaxString: Color { resolve("syntax-string") }
    static var syntaxFunction: Color { resolve("syntax-function") }
    static var syntaxNumber: Color { resolve("syntax-number") }
    static var syntaxAttribute: Color { resolve("syntax-attribute") }
    static var syntaxProperty: Color { resolve("syntax-property") }
    static var syntaxType: Color { resolve("syntax-type") }
    static var syntaxVariable: Color { resolve("syntax-variable") }
    static var syntaxOperator: Color { resolve("syntax-operator") }
    static var syntaxPunctuation: Color { resolve("syntax-punctuation") }
    static var syntaxSuccess: Color { resolve("syntax-success") }
    static var syntaxWarning: Color { resolve("syntax-warning") }
    static var syntaxInfo: Color { resolve("syntax-info") }
    static var syntaxConstant: Color { resolve("syntax-constant") }
    static var syntaxCritical: Color { resolve("syntax-critical") }
    static var syntaxDiffDelete: Color { resolve("syntax-diff-delete") }
    static var syntaxDiffUnknown: Color { resolve("syntax-diff-unknown") }

    // MARK: - Code surface

    /// The color of a highlighted code role, from the one table in `OpenCodeCodeColors`.
    static func code(_ role: NativeCodeTokenRole) -> Color {
        resolveCode { OpenCodeCodeColors.hex(for: role, in: $0) }
    }

    /// Plain code text: the syntax preset's editor foreground, else the theme's strong text.
    static var codeText: Color {
        resolveCode { OpenCodeCodeColors.textHex(in: $0) }
    }

    /// Code surface fill: the syntax preset's editor background, else `fallback` (the
    /// surface's own theme background, or clear where code sits on the page).
    static func codeBackground(or fallback: Color) -> Color {
        let fallback = UIColor(fallback)
        return Color(
            UIColor { traits in
                guard let hex = OpenCodeCodeColors.backgroundHex(in: tokens(for: traits)) else {
                    return fallback.resolvedColor(with: traits)
                }
                return UIColor(hex: hex)
            }
        )
    }

    // MARK: - Markdown

    static var markdownH1: Color { resolve("markdown-h1") }
    static var markdownH2: Color { resolve("markdown-h2") }
    static var markdownH3: Color { resolve("markdown-h3") }
    static var markdownLinkText: Color { resolve("markdown-link-text") }
    static var markdownLinkUnderline: Color { resolve("markdown-link-underline") }
    static var markdownCodeText: Color { resolve("markdown-code-text") }
    static var markdownCodeBackground: Color { resolve("markdown-code-bg") }
    static var markdownQuoteText: Color { resolve("markdown-quote-text") }
    static var markdownQuoteBar: Color { resolve("markdown-quote-bar") }
    static var markdownListMarker: Color { resolve("markdown-list-marker") }
    static var markdownImage: Color { resolve("markdown-image") }
    static var markdownHr: Color { resolve("markdown-hr") }
    static var markdownStrong: Color { resolve("markdown-strong") }
    static var markdownEm: Color { resolve("markdown-em") }

    // MARK: - List item letter badge (no desktop equivalent, hardcoded)

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
    static let listItemLetterRedFill = dynamic(light: 0x5C2D2D, dark: 0x4A2424)
    static let listItemLetterRedTint = dynamic(light: 0xFFC8C8, dark: 0xFFB8B8)
    static let listItemLetterGreenFill = dynamic(light: 0x2D4A3D, dark: 0x243832)
    static let listItemLetterGreenTint = dynamic(light: 0xB8F5D8, dark: 0xA8EDC8)
    static let listItemLetterIndigoFill = dynamic(light: 0x2D3D5C, dark: 0x24304A)
    static let listItemLetterIndigoTint = dynamic(light: 0xB8C8FF, dark: 0xA8B8FF)

    // MARK: - Resolver

    private static func tokens(for traits: UITraitCollection) -> [String: String] {
        traits.userInterfaceStyle == .dark
            ? ThemeManager.shared.resolvedDark.tokens
            : ThemeManager.shared.resolvedLight.tokens
    }

    private static func resolveCode(_ hex: @escaping @Sendable ([String: String]) -> String?) -> Color {
        Color(UIColor { traits in UIColor(hex: hex(tokens(for: traits)) ?? "#000000") })
    }

    private static func resolve(_ token: String) -> Color {
        Color(
            UIColor { traits in
                let isDark = traits.userInterfaceStyle == .dark
                let hex = isDark
                    ? ThemeManager.shared.resolvedDark[token]
                    : ThemeManager.shared.resolvedLight[token]
                return UIColor(hex: hex)
            }
        )
    }

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
            .tint(OpenCodeColors.textInteractiveBase)
            .foregroundStyle(OpenCodeColors.textStrong)
            .background(OpenCodeColors.backgroundBase)
    }
}

extension View {
    func openCodeTheme() -> some View {
        modifier(OpenCodeThemeModifier())
    }
}

extension Color {
    init(hex: String) {
        self.init(UIColor(hex: hex))
    }
}

private extension UIColor {
    convenience init(hex: String) {
        let clean = hex
            .replacingOccurrences(of: "#", with: "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        if clean.count >= 6 {
            let start = clean.startIndex
            let rEnd = clean.index(start, offsetBy: 2)
            let gEnd = clean.index(rEnd, offsetBy: 2)
            let bEnd = clean.index(gEnd, offsetBy: 2)
            let r = CGFloat(Int(clean[start..<rEnd], radix: 16) ?? 0) / 255
            let g = CGFloat(Int(clean[rEnd..<gEnd], radix: 16) ?? 0) / 255
            let b = CGFloat(Int(clean[gEnd..<bEnd], radix: 16) ?? 0) / 255
            var alpha: CGFloat = 1
            if clean.count >= 8 {
                let aEnd = clean.index(bEnd, offsetBy: 2)
                alpha = CGFloat(Int(clean[bEnd..<aEnd], radix: 16) ?? 255) / 255
            }
            self.init(red: r, green: g, blue: b, alpha: alpha)
        } else {
            self.init(red: 0, green: 0, blue: 0, alpha: 1)
        }
    }

    convenience init(rgb: UInt32, alpha: CGFloat = 1) {
        self.init(
            red: CGFloat((rgb >> 16) & 0xFF) / 255,
            green: CGFloat((rgb >> 8) & 0xFF) / 255,
            blue: CGFloat(rgb & 0xFF) / 255,
            alpha: alpha
        )
    }
}
