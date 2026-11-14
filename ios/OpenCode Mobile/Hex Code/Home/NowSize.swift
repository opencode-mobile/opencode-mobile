import Foundation

/// The type the status card's Now takes (app plan, "Now size"): it steps down with Now's effective
/// length (its characters plus 20 for each line after the first; with bullets at most
/// `nowMedium`), then further, down to `nowFit`, while the card doesn't fit between the top bar and
/// the dock; then whole lines go from the end. As the prototype's `nowTier` and `fitNow`.
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

    /// Each line after the first counts as 20 more characters.
    static let lineWeight = 20

    /// The size for Now's lines: by effective length, at most `nowMedium` with bullets.
    static func forLines(_ lines: [NowLine]) -> NowSize {
        let characters = lines.reduce(0) { $0 + InlineMarkdown.plain($1.text).count }
        let size = forLength(characters + lineWeight * max(0, lines.count - 1))
        return lines.contains(where: \.isBullet) ? max(size, .medium) : size
    }

    /// The sizes to try, largest first: from the one its lines give, down to `nowFit`.
    static func candidates(for lines: [NowLine]) -> [NowSize] {
        let start = forLines(lines)
        return allCases.filter { $0 >= start }
    }

    /// Rows of text the first line may take when nothing else fits (very large text sizes).
    static let lastResortRows = [8, 6, 4, 3, 2, 1]

    /// The layouts to try, in order: every size from the one its lines give down to `nowFit`
    /// with all lines, then at `nowFit` with one line fewer each time, down to the first; then,
    /// as a last resort, that first line in fewer and fewer rows, ending in "…". Each is measured
    /// as drawn; the first whose card fits is used; if none does, the last.
    static func layouts(for lines: [NowLine]) -> [NowLayout] {
        let sizes = candidates(for: lines).map { NowLayout(size: $0, lineCount: lines.count) }
        let fewer = stride(from: lines.count - 1, through: 1, by: -1).map { NowLayout(size: .fit, lineCount: $0) }
        let rows = lastResortRows.map { NowLayout(size: .fit, lineCount: 1, rowLimit: $0) }
        return sizes + fewer + rows
    }

    /// The first layout that fits, given a check; the last when none does.
    static func choose(for lines: [NowLine], fits: (NowLayout) -> Bool) -> NowLayout {
        let layouts = layouts(for: lines)
        return layouts.first(where: fits) ?? layouts[layouts.count - 1]
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

/// A size for Now and how many of its lines show.
struct NowLayout: Hashable, Sendable {
    var size: NowSize
    var lineCount: Int
    /// Rows of text the lines may take, ending in "…"; nil for as many as they need.
    var rowLimit: Int?

    /// The lines shown: the first `lineCount`, the last ending in "…" when some were dropped.
    func shown(_ lines: [NowLine]) -> [NowLine] {
        guard lineCount < lines.count, lineCount > 0 else { return lines }
        var kept = Array(lines.prefix(lineCount))
        var last = kept[kept.count - 1].text
        while let end = last.last, end.isWhitespace || ",.;:…".contains(end) {
            last.removeLast()
        }
        kept[kept.count - 1].text = last + "…"
        return kept
    }
}

