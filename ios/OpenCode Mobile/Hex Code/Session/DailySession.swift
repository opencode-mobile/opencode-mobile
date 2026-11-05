import Foundation

/// A session as the server lists it, with what the daily rule needs.
struct SessionListing: Equatable, Sendable {
    var id: String
    var title: String
    var directory: String
    var updatedAt: Date
}

/// Today's session: one per local date and project, titled with the date (`2026-11-03`). See "The
/// daily session" in the app plan.
enum DailySession {
    /// What to do on open (and on each return to the foreground).
    struct Decision: Equatable, Sendable {
        enum Action: Equatable, Sendable {
            /// Today's session exists: open it, nothing is created.
            case reuse(id: String)
            /// None yet: create one with this title, then add the message rules.
            case create(title: String)
        }

        var action: Action
        /// "New day, new session": only when the session is created because the date changed since
        /// the last session the app opened. Not on the first day after first run.
        var showsNewDayNotice: Bool
    }

    /// The local date as `yyyy-MM-dd`, the title of that day's session.
    static func title(for date: Date, timeZone: TimeZone = .current) -> String {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = timeZone
        let parts = calendar.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", parts.year ?? 0, parts.month ?? 0, parts.day ?? 0)
    }

    /// - Parameters:
    ///   - today: today's local date, `yyyy-MM-dd`.
    ///   - directory: the chosen project.
    ///   - lastOpenedDay: the date of the last session the app opened, nil before the first.
    ///   - sessions: the server's sessions.
    static func decide(today: String, directory: String, lastOpenedDay: String?, sessions: [SessionListing]) -> Decision {
        let project = normalized(directory)
        let todays = sessions.filter { $0.title == today && normalized($0.directory) == project }
        if let newest = todays.max(by: { $0.updatedAt < $1.updatedAt }) {
            return Decision(action: .reuse(id: newest.id), showsNewDayNotice: false)
        }
        let dateChanged = lastOpenedDay.map { $0 != today } ?? false
        return Decision(action: .create(title: today), showsNewDayNotice: dateChanged)
    }

    /// The same folder with or without a trailing slash.
    private static func normalized(_ directory: String) -> String {
        var path = directory
        while path.count > 1, path.hasSuffix("/") { path.removeLast() }
        return path
    }
}

/// The server calls the daily session needs. Round 2 implements it with OpenCode's API.
protocol DailySessionService: Sendable {
    /// Sessions in the project.
    func sessions(directory: String) async throws -> [SessionListing]
    /// Creates a session and returns its id.
    func createSession(title: String, directory: String) async throws -> String
    /// Adds `SessionPrompts.MessageRules` to the session.
    func addMessageRules(sessionID: String) async throws
    /// Opens the event stream for the session. It reconnects on its own, so it can't fail here.
    func openStream(sessionID: String) async
}

/// The date of the last session the app opened, kept on the phone.
@MainActor
protocol DailySessionMemory: AnyObject {
    var lastOpenedDay: String? { get set }
}

@MainActor
final class DailySessionDefaults: DailySessionMemory {
    private static let key = "hexcode.session.last_day"
    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    var lastOpenedDay: String? {
        get { defaults.string(forKey: Self.key) }
        set { defaults.set(newValue, forKey: Self.key) }
    }
}

/// Finds or creates today's session, adds the message rules to a new one, then opens the stream.
/// Silent: a failure in finding, creating or adding the rules is `.failed`, and Try again runs it
/// once more.
@MainActor
final class DailySessionSetup {
    enum Outcome: Equatable, Sendable {
        case opened(sessionID: String, created: Bool, showsNewDayNotice: Bool)
        case failed
    }

    private let service: any DailySessionService
    private let memory: any DailySessionMemory
    /// A session created on an earlier try whose rules didn't go in yet, and whether that try was
    /// the new day's.
    private var unfinished: (id: String, showsNewDayNotice: Bool)?

    init(service: any DailySessionService, memory: any DailySessionMemory) {
        self.service = service
        self.memory = memory
    }

    func open(today: String, directory: String) async -> Outcome {
        let decision: DailySession.Decision
        do {
            let sessions = try await service.sessions(directory: directory)
            decision = DailySession.decide(today: today, directory: directory, lastOpenedDay: memory.lastOpenedDay, sessions: sessions)
        } catch {
            return .failed
        }

        let id: String
        var created = false
        var notice = decision.showsNewDayNotice
        switch decision.action {
        case let .reuse(existing):
            id = existing
            if let unfinished, unfinished.id == existing {
                // Created on an earlier try that failed before its rules went in.
                created = true
                notice = unfinished.showsNewDayNotice
                guard await addRules(to: id, notice: notice) else { return .failed }
            }
        case let .create(title):
            do {
                id = try await service.createSession(title: title, directory: directory)
            } catch {
                return .failed
            }
            created = true
            guard await addRules(to: id, notice: notice) else { return .failed }
        }

        unfinished = nil
        memory.lastOpenedDay = today
        await service.openStream(sessionID: id)
        return .opened(sessionID: id, created: created, showsNewDayNotice: notice)
    }

    private func addRules(to id: String, notice: Bool) async -> Bool {
        do {
            try await service.addMessageRules(sessionID: id)
            return true
        } catch {
            unfinished = (id, notice)
            return false
        }
    }
}
