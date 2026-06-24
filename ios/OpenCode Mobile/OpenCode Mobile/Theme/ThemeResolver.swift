import CoreGraphics
import Foundation

struct ThemeResolver {

    static func resolve(variant: ThemeVariant, isDark: Bool) -> ResolvedTokens {
        let p = variant.palette
        let overrides = variant.overrides ?? [:]
        let neutral = p.neutral.normalizedHex
        let ink = p.ink.normalizedHex
        let primary = p.primary.normalizedHex
        let interactive = (p.interactive ?? p.primary).normalizedHex
        let success = p.success.normalizedHex
        let warning = p.warning.normalizedHex
        let error = p.error.normalizedHex
        let info = p.info.normalizedHex
        let diffAdd = (p.diffAdd ?? p.success).normalizedHex
        let diffDelete = (p.diffDelete ?? p.error).normalizedHex

        var tokens: [String: String] = [:]

        // Background / surfaces
        tokens["background-base"] = surfaceBase(neutral: neutral, isDark: isDark)
        tokens["surface-base"] = surfaceBase(neutral: neutral, isDark: isDark)
        tokens["surface-base-hover"] = isDark ? "#FFFFFF0D" : "#0000000A"
        tokens["surface-raised-base"] = surfaceRaisedBase(neutral: neutral, isDark: isDark)
        tokens["surface-raised-base-hover"] = surfaceRaisedBaseHover(neutral: neutral, isDark: isDark)
        tokens["surface-interactive-weak"] = surfaceInteractiveWeak(interactive: interactive, neutral: neutral, isDark: isDark)
        tokens["surface-success-base"] = surfaceStatusBase(color: success, neutral: neutral, isDark: isDark)
        tokens["surface-critical-base"] = surfaceStatusBase(color: error, neutral: neutral, isDark: isDark)

        // Text
        tokens["text-strong"] = textStrong(ink: ink, isDark: isDark)
        tokens["text-base"] = mix(ink, neutral, isDark ? 0.42 : 0.45)
        tokens["text-weak"] = mix(ink, neutral, isDark ? 0.58 : 0.62)
        tokens["text-weaker"] = mix(ink, neutral, isDark ? 0.72 : 0.78)
        tokens["text-interactive-base"] = interactive
        tokens["text-on-brand-base"] = readableText(fill: primary)

        // Borders
        tokens["border-weak-base"] = mix(neutral, ink, isDark ? 0.06 : 0.12)
        tokens["border-weaker-base"] = mix(neutral, ink, isDark ? 0.03 : 0.07)

        // Icons
        tokens["icon-base"] = mix(ink, neutral, isDark ? 0.55 : 0.62)
        tokens["icon-weak-base"] = mix(ink, neutral, isDark ? 0.78 : 0.82)
        tokens["icon-strong-base"] = textStrong(ink: ink, isDark: isDark)
        tokens["icon-invert-base"] = isDark ? "#1C1C1C" : "#FFFFFF"

        // Buttons
        tokens["button-primary-base"] = textStrong(ink: ink, isDark: isDark)
        tokens["button-secondary-base"] = isDark ? surfaceBase(neutral: neutral, isDark: isDark) : "#FCFCFC"
        tokens["button-secondary-hover"] = surfaceRaisedBaseHover(neutral: neutral, isDark: isDark)

        // Status seeds (also used as direct tokens)
        tokens["interactive"] = interactive
        tokens["success"] = success
        tokens["warning"] = warning
        tokens["error"] = error
        tokens["info"] = info
        tokens["diff-add"] = diffAdd
        tokens["diff-delete"] = diffDelete

        // Syntax
        tokens["syntax-comment"] = mix(ink, neutral, 0.55)
        tokens["syntax-keyword"] = info
        tokens["syntax-string"] = "#00CEB9"
        tokens["syntax-primitive"] = isDark ? mix(interactive, "#FFFFFF", 0.42) : interactive
        tokens["syntax-property"] = isDark ? primary : info
        tokens["syntax-type"] = warning
        tokens["syntax-success"] = success
        tokens["syntax-warning"] = warning
        tokens["syntax-info"] = info
        tokens["syntax-constant"] = isDark ? "#93E9F6" : "#007B80"
        tokens["syntax-critical"] = isDark ? primary : "#FF8C00"
        tokens["syntax-diff-delete"] = isDark ? primary : "#FF8C00"
        tokens["syntax-diff-unknown"] = info

        // Markdown
        tokens["markdown-h1"] = textStrong(ink: ink, isDark: isDark)
        tokens["markdown-h2"] = textStrong(ink: ink, isDark: isDark)
        tokens["markdown-h3"] = textStrong(ink: ink, isDark: isDark)
        tokens["markdown-link-text"] = interactive
        tokens["markdown-link-underline"] = interactive
        tokens["markdown-code-text"] = textStrong(ink: ink, isDark: isDark)
        tokens["markdown-code-bg"] = isDark ? "#FFFFFF14" : "#0000000A"
        tokens["markdown-quote-text"] = mix(ink, neutral, isDark ? 0.42 : 0.45)
        tokens["markdown-quote-bar"] = interactive
        tokens["markdown-list-marker"] = mix(ink, neutral, isDark ? 0.42 : 0.45)
        tokens["markdown-image"] = mix(ink, neutral, isDark ? 0.58 : 0.62)
        tokens["markdown-hr"] = mix(neutral, ink, isDark ? 0.03 : 0.07)
        tokens["markdown-strong"] = textStrong(ink: ink, isDark: isDark)
        tokens["markdown-em"] = mix(ink, neutral, isDark ? 0.42 : 0.45)

        // Apply overrides
        for (key, value) in overrides {
            tokens[key] = value.normalizedHex
        }

        return ResolvedTokens(tokens: tokens)
    }

    // MARK: - Hex Utilities

    static func normalizeColor(_ value: String) -> String {
        value.normalizedHex
    }

    static func hexToRgb(_ hex: String) -> (r: CGFloat, g: CGFloat, b: CGFloat) {
        let clean = normalizeColor(hex).replacingOccurrences(of: "#", with: "").prefix(6)
        guard clean.count == 6 else { return (128, 128, 128) }
        let scanner = Scanner(string: String(clean))
        var rgb: UInt64 = 0
        scanner.scanHexInt64(&rgb)
        return (
            CGFloat((rgb >> 16) & 0xFF),
            CGFloat((rgb >> 8) & 0xFF),
            CGFloat(rgb & 0xFF)
        )
    }

    static func rgbToHex(r: CGFloat, g: CGFloat, b: CGFloat) -> String {
        let clamp: (CGFloat) -> Int = { max(0, min(255, Int(round($0)))) }
        return String(format: "#%02X%02X%02X", clamp(r), clamp(g), clamp(b))
    }

    static func mix(_ a: String, _ b: String, _ amount: CGFloat) -> String {
        let (r1, g1, b1) = hexToRgb(a)
        let (r2, g2, b2) = hexToRgb(b)
        return rgbToHex(
            r: r1 + (r2 - r1) * amount,
            g: g1 + (g2 - g1) * amount,
            b: b1 + (b2 - b1) * amount
        )
    }

    static func readableText(fill: String) -> String {
        let (r, g, b) = hexToRgb(fill)
        let luminance = (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255
        return luminance > 0.55 ? "#171311" : "#FFFFFF"
    }

    // MARK: - Private generation helpers

    private static func textStrong(ink: String, isDark: Bool) -> String {
        mix(ink, isDark ? "#FFFFFF" : "#000000", isDark ? 0.04 : 0.02)
    }

    private static func surfaceBase(neutral: String, isDark: Bool) -> String {
        mix(neutral, isDark ? "#000000" : "#FFFFFF", isDark ? 0.10 : 0.22)
    }

    private static func surfaceRaisedBase(neutral: String, isDark: Bool) -> String {
        mix(neutral, isDark ? "#FFFFFF" : "#000000", isDark ? 0.02 : 0.03)
    }

    private static func surfaceRaisedBaseHover(neutral: String, isDark: Bool) -> String {
        mix(neutral, isDark ? "#FFFFFF" : "#000000", isDark ? 0.05 : 0.07)
    }

    private static func surfaceInteractiveWeak(interactive: String, neutral: String, isDark: Bool) -> String {
        mix(interactive, isDark ? neutral : "#FFFFFF", isDark ? 0.78 : 0.94)
    }

    private static func surfaceStatusBase(color: String, neutral: String, isDark: Bool) -> String {
        mix(color, isDark ? neutral : "#FFFFFF", isDark ? 0.82 : 0.86)
    }
}
