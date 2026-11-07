import Foundation

/// A session as the server lists it, with what the daily rule needs.
struct SessionListing: Equatable, Sendable {
    var id: String
    var title: String
    var directory: String
    var updatedAt: Date
    /// The start of the latest run (`time.busy`); nil for a session that never ran.
    var busySince: Date?
}

/// Today's session: one per local date and project, titled with the date (`2026-11-03`), or with the
/// date and a number for one started from Settings (`2026-11-03 (2)`). See "The
/// daily session" in the app plan.
enum DailySession {
    /// What to do on open (and on each return to the foreground).
    struct Decision: Equatable, Sendable {
        enum Action: Equatable, Sendable {
            /// Today's session exists: open it, nothing is created.
            case reuse(id: String)
            /// None yet: create one with this title.
            case create(title: String)
        }

        var action: Action
        /// "New day, new session": only when a session is created and the app last opened one on
        /// an earlier date. Not on the first day after first run, and not when today's session
        /// went missing and is created again.
        var showsNewDayNotice: Bool
    }

    /// The local date as `yyyy-MM-dd`, the title of that day's session.
    static func title(for date: Date, timeZone: TimeZone = .current) -> String {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = timeZone
        let parts = calendar.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", parts.year ?? 0, parts.month ?? 0, parts.day ?? 0)
    }

    /// The day's number in a session's title: 1 for the plain date (`2026-11-07`), n for the date,
    /// a space and n in brackets with n 2 or more (`2026-11-07 (2)`); nil for any other title.
    static func number(ofTitle title: String, today: String) -> Int? {
        if title == today { return 1 }
        let prefix = today + " ("
        guard title.hasPrefix(prefix), title.hasSuffix(")") else { return nil }
        let digits = title.dropFirst(prefix.count).dropLast()
        guard !digits.isEmpty, digits.allSatisfy(\.isASCII), digits.allSatisfy(\.isNumber),
              let number = Int(digits), number >= 2
        else { return nil }
        return number
    }

    /// Today's session among `sessions`, in the server's order (newest update first): the highest
    /// number among those with today's title and exactly this directory; among equal titles the
    /// first, the most recently updated. Never the most recently updated across numbers: an older
    /// session still running moves to the top of the list. The server compares directories as
    /// exact strings, so the phone does too.
    static func match(today: String, directory: String, in sessions: [SessionListing]) -> SessionListing? {
        var best: (session: SessionListing, number: Int)?
        for session in sessions where session.directory == directory {
            guard let number = number(ofTitle: session.title, today: today) else { continue }
            if best == nil || number > best!.number { best = (session, number) }
        }
        return best?.session
    }

    /// The title Start a new session gives: the next number after the highest found, or the plain
    /// date when there is none.
    static func nextTitle(today: String, after highest: SessionListing?) -> String {
        guard let highest, let number = number(ofTitle: highest.title, today: today) else { return today }
        return "\(today) (\(number + 1))"
    }

    /// True when the app last opened a session on an earlier date than `today`.
    static func isNewDay(lastOpenedDay: String?, today: String) -> Bool {
        guard let lastOpenedDay else { return false }
        return lastOpenedDay < today
    }

    /// - Parameters:
    ///   - today: today's local date, `yyyy-MM-dd`.
    ///   - directory: the chosen project, as saved at first run.
    ///   - lastOpenedDay: the date of the last session the app opened, nil before the first.
    ///   - sessions: the server's sessions, newest update first.
    static func decide(today: String, directory: String, lastOpenedDay: String?, sessions: [SessionListing]) -> Decision {
        if let found = match(today: today, directory: directory, in: sessions) {
            return Decision(action: .reuse(id: found.id), showsNewDayNotice: false)
        }
        return Decision(action: .create(title: today), showsNewDayNotice: isNewDay(lastOpenedDay: lastOpenedDay, today: today))
    }
}

/// One page of the session list, and the cursor to the next.
struct SessionListingPage: Equatable, Sendable {
    var sessions: [SessionListing]
    var next: String?
}

/// The server calls the daily session needs.
protocol DailySessionService: Sendable {
    /// Root, unarchived sessions in `directory` whose title contains `search`, newest update first;
    /// or the page after `cursor`.
    func sessionsPage(directory: String, search: String, limit: Int, cursor: String?) async throws -> SessionListingPage
    /// Creates a session in `directory` with the chosen model and level.
    func createSession(title: String, directory: String, model: ModelChoice) async throws -> SessionListing
    /// Puts `SessionPrompts.MessageRules` on the session. A repeat changes nothing.
    func putMessageRules(sessionID: String) async throws
}

/// The date and id of the last session the app opened, kept on the phone.
@MainActor
protocol DailySessionMemory: AnyObject {
    var lastOpenedDay: String? { get set }
    /// On a new day, the app reads yesterday's goal from it (app plan, "The goal" › New session).
    var lastSessionID: String? { get set }
}

@MainActor
final class DailySessionDefaults: DailySessionMemory {
    static let key = "hexcode.session.last_day"
    static let sessionKey = "hexcode.session.last_id"
    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    var lastOpenedDay: String? {
        get { defaults.string(forKey: Self.key) }
        set { defaults.set(newValue, forKey: Self.key) }
    }

    var lastSessionID: String? {
        get { defaults.string(forKey: Self.sessionKey) }
        set { defaults.set(newValue, forKey: Self.sessionKey) }
    }
}

/// Finds or creates today's session and puts the message rules on it, every time it opens. Silent:
/// a failure in finding, creating or putting the rules is `.failed`, and Try again runs it once
/// more. Try again finds the session again, so it never makes a second one, and still shows the
/// new-day notice when it was due.
@MainActor
final class DailySessionSetup {
    enum Outcome: Equatable, Sendable {
        case opened(SessionListing, created: Bool, showsNewDayNotice: Bool)
        case failed
    }

    static let pageSize = 20
    /// Pages followed at most while looking for today's session.
    static let maxPages = 25

    private let service: any DailySessionService
    private let memory: any DailySessionMemory
    /// A session created for a new day by an earlier try that failed before it finished.
    private var noticeDueFor: String?
    /// The session the app had open before the last open, when that open showed the new-day
    /// notice: yesterday's session, whose goal may still be running.
    private(set) var yesterdaySessionID: String?

    init(service: any DailySessionService, memory: any DailySessionMemory) {
        self.service = service
        self.memory = memory
    }

    /// Start a new session (Settings).
    enum NewSessionOutcome: Equatable, Sendable {
        case opened(SessionListing)
        /// Creating it failed: today's session is still open.
        case createFailed
        /// It was made, but its rules failed: as a setup failure, whose Try again finds it.
        case rulesFailed
    }

    /// Settings › Start a new session: the day's next numbered session, with the chosen model, and
    /// its rules. The old one is never changed. Its id becomes the last id; the last date doesn't
    /// change, so there is no new-day notice.
    func startNew(today: String, directory: String, model: ModelChoice) async -> NewSessionOutcome {
        let session: SessionListing
        do {
            let highest = try await find(today: today, directory: directory)
            session = try await service.createSession(
                title: DailySession.nextTitle(today: today, after: highest),
                directory: directory,
                model: model
            )
        } catch {
            return .createFailed
        }
        do {
            try await service.putMessageRules(sessionID: session.id)
        } catch {
            return .rulesFailed
        }
        memory.lastSessionID = session.id
        return .opened(session)
    }

    func open(today: String, directory: String, model: ModelChoice) async -> Outcome {
        let previous = memory.lastSessionID
        yesterdaySessionID = nil
        let session: SessionListing
        let created: Bool
        var notice: Bool
        do {
            if let found = try await find(today: today, directory: directory) {
                session = found
                created = false
                notice = noticeDueFor == found.id && DailySession.isNewDay(lastOpenedDay: memory.lastOpenedDay, today: today)
            } else {
                session = try await service.createSession(title: today, directory: directory, model: model)
                created = true
                notice = DailySession.isNewDay(lastOpenedDay: memory.lastOpenedDay, today: today)
                if notice { noticeDueFor = session.id }
            }
            try await service.putMessageRules(sessionID: session.id)
        } catch {
            return .failed
        }
        noticeDueFor = nil
        memory.lastOpenedDay = today
        memory.lastSessionID = session.id
        if notice, let previous, previous != session.id { yesterdaySessionID = previous }
        return .opened(session, created: created, showsNewDayNotice: notice)
    }

    /// The day's session with the highest number. Every full page is read (a higher number may be
    /// on a later one), up to 25; a page with fewer than 20, or without a cursor, is the end.
    private func find(today: String, directory: String) async throws -> SessionListing? {
        var cursor: String?
        var seen: [SessionListing] = []
        for _ in 0 ..< Self.maxPages {
            let page = try await service.sessionsPage(directory: directory, search: today, limit: Self.pageSize, cursor: cursor)
            seen += page.sessions
            guard page.sessions.count >= Self.pageSize, let next = page.next else { break }
            cursor = next
        }
        return DailySession.match(today: today, directory: directory, in: seen)
    }
}
