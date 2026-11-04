import SwiftUI
import UIKit

public enum OpenCodeColors {

    // MARK: - Background & Surface

    public static var backgroundBase: Color { resolve("background-base") }
    public static var surfaceBase: Color { resolve("surface-base") }
    public static var surfaceBaseHover: Color { resolve("surface-base-hover") }
    public static var surfaceRaisedBase: Color { resolve("surface-raised-base") }
    public static var surfaceRaisedBaseHover: Color { resolve("surface-raised-base-hover") }
    public static var surfaceInteractiveWeak: Color { resolve("surface-interactive-weak") }
    public static var surfaceSuccessBase: Color { resolve("surface-success-base") }
    public static var surfaceCriticalBase: Color { resolve("surface-critical-base") }

    // MARK: - Text

    public static var textStrong: Color { resolve("text-strong") }
    public static var textBase: Color { resolve("text-base") }
    public static var textWeak: Color { resolve("text-weak") }
    public static var textWeaker: Color { resolve("text-weaker") }
    public static var textInteractiveBase: Color { resolve("text-interactive-base") }
    public static var textOnBrandBase: Color { resolve("text-on-brand-base") }

    // MARK: - Border

    public static var borderWeakBase: Color { resolve("border-weak-base") }
    public static var borderWeakerBase: Color { resolve("border-weaker-base") }

    // MARK: - Icon

    public static var iconBase: Color { resolve("icon-base") }
    public static var iconWeakBase: Color { resolve("icon-weak-base") }
    public static var iconStrongBase: Color { resolve("icon-strong-base") }
    public static var iconInvertBase: Color { resolve("icon-invert-base") }

    // MARK: - Button

    public static var buttonPrimaryBase: Color { resolve("button-primary-base") }
    public static var buttonSecondaryBase: Color { resolve("button-secondary-base") }
    public static var buttonSecondaryHover: Color { resolve("button-secondary-hover") }

    // MARK: - Status

    public static var interactive: Color { resolve("interactive") }
    public static var success: Color { resolve("success") }
    public static var warning: Color { resolve("warning") }
    public static var error: Color { resolve("error") }
    public static var info: Color { resolve("info") }
    public static var diffAdd: Color { resolve("diff-add") }
    public static var diffDelete: Color { resolve("diff-delete") }

    // MARK: - Syntax

    public static var syntaxComment: Color { resolve("syntax-comment") }
    public static var syntaxKeyword: Color { resolve("syntax-keyword") }
    public static var syntaxString: Color { resolve("syntax-string") }
    public static var syntaxFunction: Color { resolve("syntax-function") }
    public static var syntaxNumber: Color { resolve("syntax-number") }
    public static var syntaxAttribute: Color { resolve("syntax-attribute") }
    public static var syntaxProperty: Color { resolve("syntax-property") }
    public static var syntaxType: Color { resolve("syntax-type") }
    public static var syntaxVariable: Color { resolve("syntax-variable") }
    public static var syntaxOperator: Color { resolve("syntax-operator") }
    public static var syntaxPunctuation: Color { resolve("syntax-punctuation") }
    public static var syntaxSuccess: Color { resolve("syntax-success") }
    public static var syntaxWarning: Color { resolve("syntax-warning") }
    public static var syntaxInfo: Color { resolve("syntax-info") }
    public static var syntaxConstant: Color { resolve("syntax-constant") }
    public static var syntaxCritical: Color { resolve("syntax-critical") }
    public static var syntaxDiffDelete: Color { resolve("syntax-diff-delete") }
    public static var syntaxDiffUnknown: Color { resolve("syntax-diff-unknown") }

    // MARK: - Code surface

    /// The color of a highlighted code role, from the one table in `OpenCodeCodeColors`.
    public static func code(_ role: NativeCodeTokenRole) -> Color {
        resolveCode { OpenCodeCodeColors.hex(for: role, in: $0) }
    }

    /// Plain code text: the syntax preset's editor foreground, else the theme's strong text.
    public static var codeText: Color {
        resolveCode { OpenCodeCodeColors.textHex(in: $0) }
    }

    /// Code surface fill: the syntax preset's editor background, else `fallback` (the
    /// surface's own theme background, or clear where code sits on the page).
    public static func codeBackground(or fallback: Color) -> Color {
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

    public static var markdownH1: Color { resolve("markdown-h1") }
    public static var markdownH2: Color { resolve("markdown-h2") }
    public static var markdownH3: Color { resolve("markdown-h3") }
    public static var markdownLinkText: Color { resolve("markdown-link-text") }
    public static var markdownLinkUnderline: Color { resolve("markdown-link-underline") }
    public static var markdownCodeText: Color { resolve("markdown-code-text") }
    public static var markdownCodeBackground: Color { resolve("markdown-code-bg") }
    public static var markdownQuoteText: Color { resolve("markdown-quote-text") }
    public static var markdownQuoteBar: Color { resolve("markdown-quote-bar") }
    public static var markdownListMarker: Color { resolve("markdown-list-marker") }
    public static var markdownImage: Color { resolve("markdown-image") }
    public static var markdownHr: Color { resolve("markdown-hr") }
    public static var markdownStrong: Color { resolve("markdown-strong") }
    public static var markdownEm: Color { resolve("markdown-em") }

    // MARK: - List item letter badge (no desktop equivalent, hardcoded)

    public static let listItemLetterVioletFill = dynamic(light: 0x4C3D5C, dark: 0x3A2F47)
    public static let listItemLetterVioletTint = dynamic(light: 0xE2D5F5, dark: 0xD8C8EE)
    public static let listItemLetterTealFill = dynamic(light: 0x2D4A42, dark: 0x243832)
    public static let listItemLetterTealTint = dynamic(light: 0xB8F5E0, dark: 0xA8EDD8)
    public static let listItemLetterAmberFill = dynamic(light: 0x5C3D28, dark: 0x4A3224)
    public static let listItemLetterAmberTint = dynamic(light: 0xFFB86C, dark: 0xFFAA55)
    public static let listItemLetterBlueFill = dynamic(light: 0x2A3A55, dark: 0x1E2D42)
    public static let listItemLetterBlueTint = dynamic(light: 0xA8D4FF, dark: 0x9EC8FF)
    public static let listItemLetterRoseFill = dynamic(light: 0x5C2D3D, dark: 0x4A2533)
    public static let listItemLetterRoseTint = dynamic(light: 0xFFB8D4, dark: 0xFFA8CC)
    public static let listItemLetterRedFill = dynamic(light: 0x5C2D2D, dark: 0x4A2424)
    public static let listItemLetterRedTint = dynamic(light: 0xFFC8C8, dark: 0xFFB8B8)
    public static let listItemLetterGreenFill = dynamic(light: 0x2D4A3D, dark: 0x243832)
    public static let listItemLetterGreenTint = dynamic(light: 0xB8F5D8, dark: 0xA8EDC8)
    public static let listItemLetterIndigoFill = dynamic(light: 0x2D3D5C, dark: 0x24304A)
    public static let listItemLetterIndigoTint = dynamic(light: 0xB8C8FF, dark: 0xA8B8FF)

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

public struct OpenCodeThemeModifier: ViewModifier {
    public func body(content: Content) -> some View {
        content
            .tint(OpenCodeColors.textInteractiveBase)
            .foregroundStyle(OpenCodeColors.textStrong)
            .background(OpenCodeColors.backgroundBase)
    }

    public init() {}
}

extension View {
    public func openCodeTheme() -> some View {
        modifier(OpenCodeThemeModifier())
    }
}

extension Color {
    public init(hex: String) {
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
