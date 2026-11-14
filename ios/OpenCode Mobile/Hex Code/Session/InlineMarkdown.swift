import Foundation

/// One stretch of summary text and how it is drawn.
struct InlineRun: Equatable, Sendable {
    enum Style: Equatable, Sendable {
        case plain
        /// `**text**`: semibold (bold at `display` and `nowLarge`).
        case bold
        /// Text in backticks: the system monospace at 0.84 of the size, on a faint tint.
        case code
    }

    var text: String
    var style: Style
}

/// The inline Markdown a summary uses (app plan, "Formatting"; the prototype's `md`): backticks
/// and `**bold**` are drawn; `[label](url)` and `![label](url)` keep their label; a leading `#` or
/// `>` goes. Everything else shows as written.
enum InlineMarkdown {
    /// The runs a piece of summary text draws as.
    static func runs(_ source: String) -> [InlineRun] {
        var text = source
        if let marker = text.range(of: #"^(#+|>)\s+"#, options: .regularExpression) {
            text.removeSubrange(marker)
        }
        var runs: [InlineRun] = []
        // Code first: nothing inside backticks is read as Markdown.
        for (index, part) in text.components(separatedBy: "`").enumerated() {
            let isCode = index % 2 == 1
            if isCode {
                runs.append(InlineRun(text: part, style: .code))
            } else {
                runs += prose(part)
            }
        }
        // An odd backtick count leaves the last one unpaired: show it as written.
        if text.filter({ $0 == "`" }).count % 2 == 1, let last = runs.indices.last, runs[last].style == .code {
            runs[last] = InlineRun(text: "`" + runs[last].text, style: .plain)
        }
        return merged(runs)
    }

    /// Links and images keep their label; `**bold**` becomes a bold run.
    private static func prose(_ text: String) -> [InlineRun] {
        let labelled = text.replacingOccurrences(of: #"!?\[([^\]]+)\]\([^)\s]*\)"#, with: "$1", options: .regularExpression)
        var runs: [InlineRun] = []
        var rest = Substring(labelled)
        while let open = rest.range(of: "**"),
              let close = rest[open.upperBound...].range(of: "**"),
              !rest[open.upperBound ..< close.lowerBound].isEmpty,
              !rest[open.upperBound ..< close.lowerBound].contains("*") {
            runs.append(InlineRun(text: String(rest[..<open.lowerBound]), style: .plain))
            runs.append(InlineRun(text: String(rest[open.upperBound ..< close.lowerBound]), style: .bold))
            rest = rest[close.upperBound...]
        }
        runs.append(InlineRun(text: String(rest), style: .plain))
        return runs
    }

    private static func merged(_ runs: [InlineRun]) -> [InlineRun] {
        var out: [InlineRun] = []
        for run in runs where !run.text.isEmpty {
            if let last = out.last, last.style == run.style {
                out[out.count - 1].text += run.text
            } else {
                out.append(run)
            }
        }
        return out
    }

    /// The characters the user sees.
    static func plain(_ source: String) -> String {
        runs(source).map(\.text).joined()
    }

    /// Back to text with only backticks and `**` left, which `runs` reads the same way.
    static func serialised(_ runs: [InlineRun]) -> String {
        runs.map { run in
            switch run.style {
            case .plain: run.text
            case .bold: "**\(run.text)**"
            case .code: "`\(run.text)`"
            }
        }.joined()
    }

    /// Clips to `limit` characters shown (the prototype's `clip`, counting what the user sees, not
    /// the symbols), keeping the formatting of what stays.
    static func clip(_ source: String, _ limit: Int) -> String {
        let runs = runs(source)
        let shown = runs.map(\.text).joined()
        guard shown.count > limit else { return serialised(runs) }
        let clipped = StatusSummary.clip(shown, limit)
        let keep = clipped.count - 1 // without the "…"
        var out: [InlineRun] = []
        var left = keep
        for run in runs where left > 0 {
            let part = String(run.text.prefix(left))
            out.append(InlineRun(text: part, style: run.style))
            left -= part.count
        }
        out.append(InlineRun(text: "…", style: .plain))
        return serialised(merged(out))
    }
}
