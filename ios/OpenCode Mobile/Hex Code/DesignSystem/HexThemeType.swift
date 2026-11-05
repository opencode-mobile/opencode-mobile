import SwiftUI
import UIKit

/// One step of Hex Code's type scale: the system font (or the system monospace) at a size in
/// points that scales with the user's text size, a weight, tracking in em and an optional line
/// height. Apply it with `.hexFont(_:)`, which also sets tracking, line spacing and case.
struct HexTextStyle: Equatable, Sendable {
    var size: CGFloat
    var weight: Font.Weight
    /// Letter spacing in em, as in the prototype (−0.028 em is −0.84 pt at 30 pt).
    var tracking: CGFloat = 0
    /// Line height as a multiple of the size; nil keeps the font's own.
    var lineHeight: CGFloat?
    var isMonospaced = false
    var uppercase = false

    /// The same step at another weight, for a selected row or a bold title in a row.
    func weight(_ weight: Font.Weight) -> HexTextStyle {
        var style = self
        style.weight = weight
        return style
    }

    /// The same step in the system monospace, for paths, commands and timers.
    func monospaced() -> HexTextStyle {
        var style = self
        style.isMonospaced = true
        return style
    }
}

extension HexTextStyle {
    /// 30 / semibold, line 1.18, −0.028 em. The agent's "now" line, first-run titles.
    static let display = HexTextStyle(size: 30, weight: .semibold, tracking: -0.028, lineHeight: 1.18)
    /// 26 / semibold, −0.025 em.
    static let title = HexTextStyle(size: 26, weight: .semibold, tracking: -0.025)
    /// 20 / semibold, −0.02 em. Page titles in the top bar of pickers and Settings (`.pk-top b`).
    /// Not in the spec yet: proposed.
    static let pageTitle = HexTextStyle(size: 20, weight: .semibold, tracking: -0.02)
    /// 24 / medium, line 1.3, −0.015 em. The draft.
    static let draft = HexTextStyle(size: 24, weight: .medium, tracking: -0.015, lineHeight: 1.3)
    /// 17 / semibold, −0.015 em. The home top bar's state.
    static let headline = HexTextStyle(size: 17, weight: .semibold, tracking: -0.015)
    /// 15 / regular, line 1.45.
    static let body = HexTextStyle(size: 15, weight: .regular, lineHeight: 1.45)
    /// 15 / semibold. Primary buttons, sheet titles.
    static let bodyStrong = HexTextStyle(size: 15, weight: .semibold)
    /// 14.5 / regular. Rows, chunk rows, first-run subtitles.
    static let row = HexTextStyle(size: 14.5, weight: .regular)
    /// 14.5 / medium. Regular buttons.
    static let button = HexTextStyle(size: 14.5, weight: .medium)
    /// 13.5 / medium. Toast titles, the goal line.
    static let callout = HexTextStyle(size: 13.5, weight: .medium)
    /// 13 / medium. Small buttons, segments.
    static let small = HexTextStyle(size: 13, weight: .medium)
    /// 12.5 / regular. Labels over the draft, hints, the model page's key line.
    static let caption = HexTextStyle(size: 12.5, weight: .regular)
    /// 12 / regular. Footers, fine print.
    static let footnote = HexTextStyle(size: 12, weight: .regular)
    /// 12 / semibold, upper case, +0.06 em. Section headings.
    static let sectionLabel = HexTextStyle(size: 12, weight: .semibold, tracking: 0.06, uppercase: true)
    /// 11 / medium. Bordered badges (`.badge`, `.rbadge`). Not in the spec yet: proposed.
    static let badge = HexTextStyle(size: 11, weight: .medium)
    /// 11.5 / regular, monospace. Timers, counts.
    static let mono = HexTextStyle(size: 11.5, weight: .regular, isMonospaced: true)
    /// 10.5 / regular, monospace. The `git` tag.
    static let monoSmall = HexTextStyle(size: 10.5, weight: .regular, isMonospaced: true)
}

extension View {
    /// Sets the font, tracking, line spacing and case of a Hex type step.
    func hexFont(_ style: HexTextStyle) -> some View {
        modifier(HexFontModifier(style: style))
    }
}

private struct HexFontModifier: ViewModifier {
    let style: HexTextStyle
    /// 100 at the default text size; follows Dynamic Type like body text.
    @ScaledMetric(relativeTo: .body) private var scale: CGFloat = 100

    func body(content: Content) -> some View {
        let size = style.size * scale / 100
        content
            .font(.system(size: size, weight: style.weight, design: style.isMonospaced ? .monospaced : .default))
            .tracking(style.tracking * size)
            .lineSpacing(lineSpacing(size: size))
            .textCase(style.uppercase ? .uppercase : nil)
    }

    private func lineSpacing(size: CGFloat) -> CGFloat {
        guard let lineHeight = style.lineHeight else { return 0 }
        let natural = UIFont.systemFont(ofSize: size, weight: style.weight.uiFontWeight).lineHeight
        return max(0, lineHeight * size - natural)
    }
}

private extension Font.Weight {
    var uiFontWeight: UIFont.Weight {
        switch self {
        case .ultraLight: .ultraLight
        case .thin: .thin
        case .light: .light
        case .medium: .medium
        case .semibold: .semibold
        case .bold: .bold
        case .heavy: .heavy
        case .black: .black
        default: .regular
        }
    }
}
