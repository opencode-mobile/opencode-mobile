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
    /// The newest artifact of the latest run: the first tile (app plan, "Artifacts (M8)"). Read with
    /// the summary and saved with the card, so Nothing new keeps it.
    var artifact: CardArtifact?

    /// Now's lines, one per line of text.
    var now: String { lines.map(\.text).joined(separator: "\n") }
    var hasBullets: Bool { lines.contains(where: \.isBullet) }

    private enum CodingKeys: String, CodingKey { case title, now, lines, next, links, artifact }

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
        artifact = try container.decodeIfPresent(CardArtifact.self, forKey: .artifact)
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(title, forKey: .title)
        try container.encode(lines, forKey: .lines)
        try container.encodeIfPresent(next, forKey: .next)
        try container.encode(links, forKey: .links)
        try container.encodeIfPresent(artifact, forKey: .artifact)
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

    /// A session that never ran, once a message is sent to it or it is busy, until its first
    /// summary: under "Still working · <long date>". Never "Nothing running yet" while it works.
    static func sent(on date: Date, timeZone: TimeZone = .current) -> StatusCard {
        StatusCard(
            title: longDate(date, timeZone: timeZone),
            now: String(localized: "home.summary.sent.now", defaultValue: "Working on your message.")
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

    /// No card yet (app plan, "Cold start"): today's date over what the app is doing, so the card
    /// area is never empty while today's session opens and its first summary is written.
    static func catchingUp(_ step: CatchingUp, on date: Date, timeZone: TimeZone = .current) -> StatusCard {
        StatusCard(title: longDate(date, timeZone: timeZone), now: step.text)
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

/// What the date card says while there is no card yet: connecting, opening today's session, or
/// writing (or failing to write) its first summary.
enum CatchingUp: Equatable, Sendable {
    case connecting
    case opening
    case summarising
    case failed

    /// Setup still finding today's session is Connecting; once found, the summary request on its way
    /// is Summarising and a failed one Failed; in between (the state not read yet) it is Opening.
    /// Nil where the card area shows something else: Couldn't open today's session, Signed out.
    static func step(setup: TodaySession.Setup, isUpdating: Bool, failed: Bool) -> CatchingUp? {
        switch setup {
        case .opening: .connecting
        case .ready: isUpdating ? .summarising : failed ? .failed : .opening
        case .failed, .signedOut: nil
        }
    }

    var text: String {
        switch self {
        case .connecting: String(localized: "home.summary.catching_up.connecting", defaultValue: "Reaching your computer…")
        case .opening: String(localized: "home.summary.catching_up.opening", defaultValue: "Opening today’s session…")
        case .summarising: String(localized: "home.summary.catching_up.summarising", defaultValue: "Summarising the last run…")
        case .failed: String(localized: "home.summary.catching_up.failed", defaultValue: "Couldn’t summarise the last run.")
        }
    }
}

/// What a summary reply becomes: a card, or the reply as plain text when it doesn't match the shape.
enum StatusSummary: Equatable, Sendable, Codable {
    case card(StatusCard)
    /// Only on cards saved by older versions: replies are always read as a card now, and this
    /// shows as one too (`asCard`).
    case fallback(String)

    /// Now isn't cut by characters (the prompt keeps it short, and Now size makes it fit): at most
    /// 5 lines, 4 of them bullets; extra ones are dropped. Nothing on the card is cut.
    static let maxNowLines = 5
    static let maxBullets = 4

    /// What a `Next:` line says when there is nothing to suggest (any case, optional full stop).
    static let noNextWords: Set<String> = ["", "none", "n/a", "-", "—", "(none)"]

    /// How much of the asked-for shape a reply had.
    enum Shape: Equatable, Sendable {
        case full
        /// A title, but no `Now:`: the lines after the title were read as Now.
        case noNow
        /// `Now:`, but no title.
        case noTitle
        /// Neither: every line was read as Now, under "Status".
        case none
    }

    /// The card, whatever the summary is: an old saved fallback shows as a "Status" card.
    var asCard: StatusCard {
        switch self {
        case let .card(card): card
        case let .fallback(text): StatusCard(title: Self.statusTitle, lines: [NowLine(text: text)])
        }
    }

    static var statusTitle: String {
        String(localized: "home.summary.fallback.title", defaultValue: "Status")
    }

    static func parse(_ reply: String) -> StatusSummary {
        read(reply).summary
    }

    /// A label a reply's line may start with (app plan, "Reading the reply").
    enum Label: Equatable, Sendable {
        case now
        case next
        case link
    }

    /// Any case, with or without bold, then `:`, or `-` / `—` before a space or the end.
    private static let labelPattern = try! NSRegularExpression(
        pattern: #"^(?:\*\*|__)?\s*(now|status|summary|tl;?dr|next steps?|next|follow[- ]up|suggested|links?|source)\s*(?:\*\*|__)?\s*(?::|[-—–](?=\s|$))\s*(?:\*\*|__)?\s*(.*)$"#,
        options: [.caseInsensitive]
    )

    /// The label a line starts with, and what follows it.
    static func label(of line: String) -> (label: Label, value: String)? {
        let range = NSRange(line.startIndex..., in: line)
        guard let match = labelPattern.firstMatch(in: line, range: range),
              let word = Range(match.range(at: 1), in: line),
              let rest = Range(match.range(at: 2), in: line)
        else { return nil }
        let value = String(line[rest]).trimmingCharacters(in: .whitespaces)
        switch line[word].lowercased() {
        case "now", "status", "summary", "tl;dr", "tldr": return (.now, value)
        case "links", "link", "source": return (.link, value)
        default: return (.next, value)
        }
    }

    /// The title a line gives: one to three `#`; or, as the first line, at most 60 characters in
    /// bold.
    static func headingTitle(_ line: String) -> String? {
        if let heading = line.range(of: #"^#{1,3}\s+"#, options: .regularExpression) {
            let value = String(line[heading.upperBound...]).trimmingCharacters(in: .whitespaces)
            return value.isEmpty ? nil : value
        }
        return nil
    }

    static func boldTitle(_ line: String) -> String? {
        guard let match = line.range(of: #"^(\*\*|__)(.+?)(\*\*|__):?$"#, options: .regularExpression), match == line.startIndex ..< line.endIndex else { return nil }
        var inner = line.dropFirst(2).dropLast(line.hasSuffix(":") ? 3 : 2).trimmingCharacters(in: .whitespaces)
        if inner.hasSuffix(":") { inner.removeLast() }
        return !inner.isEmpty && inner.count <= 60 ? inner : nil
    }

    /// A link on a line of its own: `[label](url)` or a bare http(s) URL.
    static func linkLine(_ line: String) -> CardLink? {
        if let match = line.range(of: #"^!?\[([^\]]+)\]\((https?://[^)\s]+)\)[.,]?$"#, options: .regularExpression), match.lowerBound == line.startIndex {
            let pulled = CardLinks.pull(from: line)
            return pulled.links.first
        }
        if line.range(of: #"^https?://\S+$"#, options: .regularExpression) != nil {
            return CardLinks.pull(from: line).links.first
        }
        return nil
    }

    /// Reads a reply to `SessionPrompts.Summary` at best effort (app plan, "Reading the reply",
    /// "Fallback"): whatever parts can be recognised show, and it is always a card, never raw text.
    /// Each line is trimmed; code fences and blank lines are ignored. Labels match in any case,
    /// with or without bold, with `:`, `-` or `—` after them, and with variants (`Status`,
    /// `Summary`, `TL;DR` for Now; `Next step(s)`, `Follow-up`, `Suggested` for Next; `Links`,
    /// `Source` for links). The title is the first line with one to three `#`, else a short bold
    /// first line, else a short first line followed by a Now line. Now is the Now line and every
    /// line after it that isn't a label, with lines after the title and any other unrecognised
    /// lines kept as plain lines, unless they come after Next. With neither a title nor a Now line
    /// the card is "Status" and nothing is dropped. Markers `- `, `* `, `+ `, `• `, `1. `, `1) `
    /// make bullets. Links come from Link lines, link-only lines and links inside lines.
    static func read(_ reply: String) -> (summary: StatusSummary, shape: Shape) {
        let lines = reply
            .split(whereSeparator: \.isNewline)
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty && !$0.hasPrefix("```") && $0.range(of: #"^#+$"#, options: .regularExpression) == nil }
        // The title.
        var titleIndex: Int?
        var title: String?
        if let index = lines.firstIndex(where: { headingTitle($0) != nil }) {
            titleIndex = index
            title = headingTitle(lines[index])
        } else if let first = lines.first, label(of: first) == nil, let bold = boldTitle(first) {
            titleIndex = 0
            title = bold
        } else if lines.count > 1, lines[0].count <= 60, label(of: lines[0]) == nil, label(of: lines[1])?.label == .now {
            titleIndex = 0
            title = lines[0]
        }
        var nowLines: [String] = []
        var sawNow = false
        var next: String?
        var sawNext = false
        var afterNext = false
        var linkLines: [CardLink] = []
        for (index, line) in lines.enumerated() {
            if index == titleIndex { continue }
            // Chatter before the title isn't part of the card.
            if let titleIndex, index < titleIndex { continue }
            if let (kind, value) = label(of: line) {
                switch kind {
                case .now:
                    if !sawNow {
                        sawNow = true
                        if !value.isEmpty { nowLines.append(value) }
                    } else if !value.isEmpty {
                        nowLines.append(value)
                    }
                    afterNext = false
                case .next:
                    if !sawNext {
                        sawNext = true
                        next = value
                    }
                    afterNext = true
                case .link:
                    if let link = CardLinks.fromLinkLine(value) ?? linkLine(value), !ArtifactLinks.isArtifact(link.url) { linkLines.append(link) }
                }
            } else if let link = linkLine(line) {
                // An artifact's address is the agent's guess; the artifact tile covers it.
                if !ArtifactLinks.isArtifact(link.url) { linkLines.append(link) }
            } else if !afterNext {
                // Unrecognised lines stay, as plain lines of Now; a second heading loses its `#`.
                nowLines.append(headingTitle(line) ?? line)
            }
        }
        let shape: Shape = switch (title != nil, sawNow) {
        case (true, true): .full
        case (true, false): .noNow
        case (false, true): .noTitle
        case (false, false): .none
        }
        let rawNow = shape == .none ? nowLines.map { $0.replacingOccurrences(of: #"^#+\s*"#, with: "", options: .regularExpression) } : nowLines
        // Links left in Now or Next become tiles and read as their label (or host) in the line.
        var links = Array(linkLines.prefix(CardLinks.maxLinkLines))
        var now: [NowLine] = []
        for raw in rawNow {
            let line = nowLine(raw)
            let pulled = CardLinks.pull(from: line.text)
            links += pulled.links.filter { !ArtifactLinks.isArtifact($0.url) }
            now.append(NowLine(text: pulled.text, isBullet: line.isBullet))
        }
        let pulledNext = next.flatMap { isNoNext($0) ? nil : CardLinks.pull(from: $0) }
        links += (pulledNext?.links ?? []).filter { !ArtifactLinks.isArtifact($0.url) }
        var cardTitle = title ?? (shape == .none ? statusTitle : "")
        if now.isEmpty {
            // Only a title is left: it shows as Now, with no label above it.
            now = [NowLine(text: title ?? "")]
            cardTitle = ""
        }
        let card = StatusCard(
            title: cardTitle,
            // With no shape at all, nothing is dropped.
            lines: shape == .none ? now : limitNow(now),
            next: pulledNext?.text,
            links: CardLinks.unique(links)
        )
        return (.card(card), shape)
    }

    /// A Now line: a bullet when it starts with a marker, else a plain line.
    static func nowLine(_ line: String) -> NowLine {
        for marker in ["- ", "* ", "+ ", "• "] where line.hasPrefix(marker) {
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

    /// ↻'s accessibility value: "Updated 3 minutes ago", "Updated just now" or "Couldn't update".
    var spoken: String {
        switch self {
        case .updating, .failed, .none:
            return text
        case .now:
            let when = String(localized: "a11y.refresh.now", defaultValue: "just now")
            return String(localized: "a11y.refresh.value", defaultValue: "Updated \(when)")
        case let .minutesAgo(minutes):
            let formatter = RelativeDateTimeFormatter()
            formatter.unitsStyle = .full
            let when = formatter.localizedString(fromTimeInterval: -Double(minutes) * 60)
            return String(localized: "a11y.refresh.value", defaultValue: "Updated \(when)")
        }
    }
}
