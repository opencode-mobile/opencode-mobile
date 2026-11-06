import Foundation

/// The type the status card's Now sentence takes (app plan, "Now size"): it steps down with the
/// sentence's length, then further, down to `nowFit`, while the card doesn't fit between the top
/// bar and the dock. As the prototype's `nowTier` and `fitNow`.
enum NowSize: Int, CaseIterable, Comparable, Sendable {
    /// Up to 90 characters.
    case display
    /// Up to 150.
    case large
    /// Up to 220.
    case medium
    /// Longer.
    case small
    /// Only when the card still doesn't fit.
    case fit

    static func < (lhs: NowSize, rhs: NowSize) -> Bool { lhs.rawValue < rhs.rawValue }

    /// The size for a Now sentence of this many characters (grapheme clusters, after clipping).
    static func forLength(_ count: Int) -> NowSize {
        switch count {
        case ...90: .display
        case ...150: .large
        case ...220: .medium
        default: .small
        }
    }

    /// Counts the characters shown, not the formatting's symbols.
    static func forText(_ text: String) -> NowSize { forLength(InlineMarkdown.plain(text).count) }

    /// The sizes to try, largest first: from the one its length gives, down to `nowFit`. The
    /// first whose card fits is used; if none does, the last, with the sentence ending in "…".
    static func candidates(for text: String) -> [NowSize] {
        let start = forText(text)
        return allCases.filter { $0 >= start }
    }

    /// The first size that fits, given a check; `fit` when none does.
    static func choose(for text: String, fits: (NowSize) -> Bool) -> NowSize {
        candidates(for: text).first(where: fits) ?? .fit
    }

    var style: HexTextStyle {
        switch self {
        case .display: .display
        case .large: .nowLarge
        case .medium: .nowMedium
        case .small: .nowSmall
        case .fit: .nowFit
        }
    }
}
