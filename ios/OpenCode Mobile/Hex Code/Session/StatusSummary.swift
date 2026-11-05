import Foundation

/// The status card on home: the task, what the agent does now, what comes next, and the plan.
struct StatusCard: Equatable, Sendable {
    struct Step: Equatable, Sendable {
        var text: String
        var done: Bool
    }

    var title: String
    var now: String
    /// Nil when the reply had no `Next:` line.
    var next: String?
    var steps: [Step] = []

    var doneCount: Int { steps.filter(\.done).count }

    /// A session that never ran: no request. The title is today's long date in English, day before
    /// month, whatever the phone's region ("Thursday 5 November").
    static func empty(on date: Date, timeZone: TimeZone = .current) -> StatusCard {
        StatusCard(
            title: longDate(date, timeZone: timeZone),
            now: String(localized: "home.summary.empty.now", defaultValue: "New session. Nothing running yet."),
            next: String(localized: "home.summary.empty.next", defaultValue: "Say what to do.")
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
enum StatusSummary: Equatable, Sendable {
    case card(StatusCard)
    case fallback(String)

    /// Clipping, as the prototype: title 40, Now 90, Next 60, steps 40, fallback 300 characters
    /// (grapheme clusters).
    static let titleLimit = 40
    static let nowLimit = 90
    static let nextLimit = 60
    static let stepLimit = 40
    static let fallbackLimit = 300
    /// The prompt asks for at most 8 steps; more are dropped from the end.
    static let maxSteps = 8

    /// Reads a reply to `SessionPrompts.Summary`. Each line is trimmed; code fences and blank lines
    /// are ignored, and so is any other line (such as text before the title). The first line
    /// starting `## ` is the title, the first lines starting `Now:` and `Next:` (in any case) the
    /// sentence and what follows, and lines starting `- [x]`, `- [X]` or `- [ ]` the steps (the
    /// first 8). A label with nothing after it counts as missing. Without a title or a `Now:` line
    /// it is a fallback: the lines joined with spaces, runs of spaces collapsed.
    static func parse(_ reply: String) -> StatusSummary {
        var title: String?
        var now: String?
        var next: String?
        var steps: [StatusCard.Step] = []
        let lines = meaningfulLines(reply)
        for line in lines {
            if let value = line.value(after: "## ") {
                if title == nil, !value.isEmpty { title = value }
            } else if let value = line.value(afterLabel: "now:") {
                if now == nil, !value.isEmpty { now = value }
            } else if let value = line.value(afterLabel: "next:") {
                if next == nil, !value.isEmpty { next = value }
            } else if let value = line.value(after: "- [x]") ?? line.value(after: "- [X]") {
                if !value.isEmpty { steps.append(.init(text: clip(value, stepLimit), done: true)) }
            } else if let value = line.value(after: "- [ ]") {
                if !value.isEmpty { steps.append(.init(text: clip(value, stepLimit), done: false)) }
            }
        }
        guard let title, let now else {
            let text = lines.joined(separator: " ").split(whereSeparator: \.isWhitespace).joined(separator: " ")
            return .fallback(clip(text, fallbackLimit))
        }
        return .card(StatusCard(
            title: clip(title, titleLimit),
            now: clip(now, nowLimit),
            next: next.map { clip($0, nextLimit) },
            steps: Array(steps.prefix(maxSteps))
        ))
    }

    /// The reply's lines, trimmed, without blank lines and code fences.
    private static func meaningfulLines(_ reply: String) -> [String] {
        reply
            .split(whereSeparator: \.isNewline)
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty && !$0.hasPrefix("```") }
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
