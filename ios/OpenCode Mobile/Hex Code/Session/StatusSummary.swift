import Foundation

/// One line of Now: a paragraph, or a bullet (its marker removed). The text keeps its formatting
/// (backticks, `**`).
struct NowLine: Equatable, Sendable, Codable {
    var text: String
    var isBullet = false
}

/// The status card on home: the latest request's topic, what was answered, found or done for it
/// (lines and bullets), what the user might say next, and links. The to-do row isn't the
/// summary's: it is the agent's own list (`TodoList`).
struct StatusCard: Equatable, Sendable, Codable {
    var title: String
    /// Now, line by line; never empty.
    var lines: [NowLine]
    /// Nil when the reply had no `Next:` line, or said there is none.
    var next: String?
    /// The tiles under Next: the reply's `Link:` lines, then links left in Now and Next.
    var links: [CardLink] = []

    /// Now's lines, one per line of text.
    var now: String { lines.map(\.text).joined(separator: "\n") }
    var hasBullets: Bool { lines.contains(where: \.isBullet) }

    private enum CodingKeys: String, CodingKey { case title, now, lines, next, links }

    init(title: String, now: String, next: String? = nil, links: [CardLink] = []) {
        self.init(title: title, lines: [NowLine(text: now)], next: next, links: links)
    }

    init(title: String, lines: [NowLine], next: String? = nil, links: [CardLink] = []) {
        self.title = title
        self.lines = lines
        self.next = next
        self.links = links
    }

    /// Cards saved before Now had lines read their sentence as one line, and those saved before
    /// links were kept read without them; steps saved by older versions are ignored.
    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        title = try container.decode(String.self, forKey: .title)
        if let lines = try container.decodeIfPresent([NowLine].self, forKey: .lines), !lines.isEmpty {
            self.lines = lines
        } else {
            lines = [NowLine(text: try container.decode(String.self, forKey: .now))]
        }
        next = try container.decodeIfPresent(String.self, forKey: .next)
        links = try container.decodeIfPresent([CardLink].self, forKey: .links) ?? []
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(title, forKey: .title)
        try container.encode(lines, forKey: .lines)
        try container.encodeIfPresent(next, forKey: .next)
        try container.encode(links, forKey: .links)
    }

    /// A session that never ran: no request. The title is today's long date in English, day before
    /// month, whatever the phone's region ("Thursday 5 November").
    static func empty(on date: Date, timeZone: TimeZone = .current) -> StatusCard {
        StatusCard(
            title: longDate(date, timeZone: timeZone),
            now: String(localized: "home.summary.empty.now", defaultValue: "New session. Nothing running yet."),
            next: String(localized: "home.summary.empty.next", defaultValue: "Say what to do.")
        )
    }

    /// No card yet, and the server answered with no text because the agent is working.
    static func working(on date: Date, timeZone: TimeZone = .current) -> StatusCard {
        StatusCard(
            title: longDate(date, timeZone: timeZone),
            now: String(localized: "home.summary.working.now", defaultValue: "The agent is working."),
            next: String(localized: "home.summary.working.next", defaultValue: "The summary updates when this run ends.")
        )
    }

    /// "Thursday 5 November": weekday, day and month in English, in that order, on every phone.
    static func longDate(_ date: Date, timeZone: TimeZone) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_GB")
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.timeZone = timeZone
        formatter.dateFormat = "EEEE d MMMM"
        return formatter.string(from: date)
    }
}

/// What a summary reply becomes: a card, or the reply as plain text when it doesn't match the shape.
enum StatusSummary: Equatable, Sendable, Codable {
    case card(StatusCard)
    case fallback(String)

    /// Clipping (app plan, "Clipping"), in characters shown (grapheme clusters, formatting's
    /// symbols not counted). To-do items clip at `TodoList.itemLimit`.
    static let titleLimit = 40
    static let nextLimit = 60
    static let fallbackLimit = 300
    /// Now isn't cut by characters (the prompt keeps it short, and Now size makes it fit): at most
    /// 5 lines, 4 of them bullets; extra ones are dropped.
    static let maxNowLines = 5
    static let maxBullets = 4

    /// What a `Next:` line says when there is nothing to suggest (any case, optional full stop).
    static let noNextWords: Set<String> = ["", "none", "n/a", "-", "—", "(none)"]

    /// Reads a reply to `SessionPrompts.Summary` (app plan, "Reading the reply"). Each line is
    /// trimmed; code fences and blank lines are ignored. The first line starting `## ` is the
    /// title. The first line starting `Now:` (any case) and the lines after it, up to the next
    /// `Next:`, `Link:` or `## ` line, are Now: a line starting `- `, `* `, `• `, `1. ` or `1) `
    /// is a bullet (marker removed), any other a plain line. The first `Next:` line is the
    /// follow-up; the "none" words mean no Next. `Link:` lines with an http(s) URL are links.
    /// Other lines are ignored. Without a title or a `Now:` line (with something in it) it is a
    /// fallback: the lines joined with spaces, runs of spaces collapsed.
    static func parse(_ reply: String) -> StatusSummary {
        let lines = reply
            .split(whereSeparator: \.isNewline)
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty && !$0.hasPrefix("```") }
        var title: String?
        var nowLines: [String]?
        var inNow = false
        var next: String?
        var sawNext = false
        var linkLines: [CardLink] = []
        for line in lines {
            if let value = line.value(after: "## ") {
                if title == nil, !value.isEmpty { title = value }
                inNow = false
            } else if let value = line.value(afterLabel: "now:") {
                if nowLines == nil {
                    nowLines = value.isEmpty ? [] : [value]
                    inNow = true
                } else {
                    inNow = false
                }
            } else if let value = line.value(afterLabel: "next:") {
                if !sawNext {
                    sawNext = true
                    next = value
                }
                inNow = false
            } else if let value = line.value(afterLabel: "link:") {
                if let link = CardLinks.fromLinkLine(value) { linkLines.append(link) }
                inNow = false
            } else if inNow {
                nowLines?.append(line)
            }
        }
        guard let title, let nowLines, !nowLines.isEmpty else {
            let text = lines
                .filter { $0.value(afterLabel: "link:") == nil }
                .joined(separator: " ")
                .split(whereSeparator: \.isWhitespace)
                .joined(separator: " ")
            return .fallback(clip(text, fallbackLimit))
        }
        // Links left in Now or Next become tiles and read as their label (or host) in the line.
        var links = Array(linkLines.prefix(CardLinks.maxLinkLines))
        var now: [NowLine] = []
        for raw in nowLines {
            let line = nowLine(raw)
            let pulled = CardLinks.pull(from: line.text)
            links += pulled.links
            now.append(NowLine(text: pulled.text, isBullet: line.isBullet))
        }
        let pulledNext = next.flatMap { isNoNext($0) ? nil : CardLinks.pull(from: $0) }
        links += pulledNext?.links ?? []
        return .card(StatusCard(
            title: InlineMarkdown.clip(title, titleLimit),
            lines: limitNow(now),
            next: pulledNext.map { InlineMarkdown.clip($0.text, nextLimit) },
            links: CardLinks.unique(links)
        ))
    }

    /// A Now line: a bullet when it starts with a marker, else a plain line.
    static func nowLine(_ line: String) -> NowLine {
        for marker in ["- ", "* ", "• "] where line.hasPrefix(marker) {
            return NowLine(text: line.dropFirst(marker.count).trimmingCharacters(in: .whitespaces), isBullet: true)
        }
        if let marker = line.range(of: #"^\d+[.)]\s+"#, options: .regularExpression) {
            return NowLine(text: String(line[marker.upperBound...]), isBullet: true)
        }
        return NowLine(text: line)
    }

    static func isNoNext(_ value: String) -> Bool {
        var word = value.trimmingCharacters(in: .whitespaces).lowercased()
        if word.hasSuffix(".") { word.removeLast() }
        return noNextWords.contains(word)
    }

    /// Now's limits: bullets after the fourth dropped, then lines after the fifth.
    static func limitNow(_ lines: [NowLine]) -> [NowLine] {
        var bullets = 0
        let kept = lines.filter { line in
            guard line.isBullet else { return true }
            bullets += 1
            return bullets <= maxBullets
        }
        return Array(kept.prefix(maxNowLines))
    }

    /// The prototype's `clip`: at most `limit` characters, cut at the last space past half way when
    /// there is one, without trailing spaces or `,.;:`, and ending in "…".
    static func clip(_ text: String, _ limit: Int) -> String {
        guard text.count > limit else { return text }
        var cut = String(text.prefix(limit - 1))
        if let space = cut.lastIndex(of: " "), Double(cut.distance(from: cut.startIndex, to: space)) > Double(limit) * 0.5 {
            cut = String(cut[..<space])
        }
        while let last = cut.last, last.isWhitespace || ",.;:".contains(last) {
            cut.removeLast()
        }
        return cut + "…"
    }
}

private extension String {
    /// The rest of the line after `prefix`, trimmed; nil when the line doesn't start with it.
    func value(after prefix: String) -> String? {
        guard hasPrefix(prefix) else { return nil }
        return dropFirst(prefix.count).trimmingCharacters(in: .whitespaces)
    }

    /// The same for a label matched in any case, such as `now:`.
    func value(afterLabel label: String) -> String? {
        guard lowercased().hasPrefix(label) else { return nil }
        return dropFirst(label.count).trimmingCharacters(in: .whitespaces)
    }
}

/// The quiet label next to ↻: "updating…" while a request runs, "Couldn't update" after a failed
/// one (the last card stays), else how long ago the card was made: "now" under 45 s, then "3m ago".
enum SummaryRefreshLabel: Equatable, Sendable {
    case updating
    case failed
    case now
    case minutesAgo(Int)
    /// No card has been made yet.
    case none

    static func make(updatedAt: Date?, at date: Date, isUpdating: Bool, failed: Bool) -> SummaryRefreshLabel {
        if isUpdating { return .updating }
        if failed { return .failed }
        guard let updatedAt else { return .none }
        let seconds = date.timeIntervalSince(updatedAt)
        if seconds < 45 { return .now }
        return .minutesAgo(Int((seconds / 60).rounded()))
    }

    var text: String {
        switch self {
        case .updating: String(localized: "home.summary.updating", defaultValue: "updating…")
        case .failed: String(localized: "home.summary.failed", defaultValue: "Couldn’t update")
        case .now: String(localized: "home.summary.updated.now", defaultValue: "now")
        case let .minutesAgo(minutes): String(localized: "home.summary.updated.ago", defaultValue: "\(minutes)m ago")
        case .none: ""
        }
    }
}
