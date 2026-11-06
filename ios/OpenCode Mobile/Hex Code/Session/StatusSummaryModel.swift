import Foundation
import Observation

/// Asks the session's model for a summary of today's session: `POST /api/session/{id}/generate`
/// with only the prompt (the session's model and level answer it).
protocol SummaryGenerating: Sendable {
    func generateSummary(prompt: String) async throws -> String
}

/// The last card of today's session, kept on the phone so it survives the app being closed.
struct SavedSummary: Equatable, Codable, Sendable {
    var sessionID: String
    var summary: StatusSummary
    var updatedAt: Date
    /// The session's date (`yyyy-MM-dd`) and project, so the card can show before setup finds the
    /// session. Missing on cards saved before they were kept.
    var day: String?
    var directory: String?
}

/// Where the last card is kept. Only one session's (today's) at a time.
@MainActor
protocol SummaryStoring: AnyObject {
    func load() -> SavedSummary?
    func save(_ saved: SavedSummary)
}

@MainActor
final class SummaryDefaults: SummaryStoring {
    static let key = "hexcode.summary.last"
    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    func load() -> SavedSummary? {
        defaults.data(forKey: Self.key).flatMap { try? JSONDecoder().decode(SavedSummary.self, from: $0) }
    }

    func save(_ saved: SavedSummary) {
        if let data = try? JSONEncoder().encode(saved) { defaults.set(data, forKey: Self.key) }
    }
}

/// The status summary on home and its ↻. Refreshed when the session opens or reconnects, when a
/// run ends and on ↻; never on a timer. A refresh asked for while one runs is dropped. A request
/// that fails, times out (60 s) or answers with no text keeps the last card and shows
/// "Couldn't update" — except an empty answer while the agent works (the server often gives one
/// then): the last card stays without the note, and the run's end refreshes it; with no card yet,
/// a "The agent is working." card shows instead. Each new card is saved for its session, and shown
/// at once (with its age) the next time that session opens.
@MainActor
@Observable
final class StatusSummaryModel {
    static let timeout: Duration = .seconds(60)

    private(set) var summary: StatusSummary?
    private(set) var updatedAt: Date?
    private(set) var isUpdating = false
    /// The last request failed; the last card stays.
    private(set) var failed = false

    @ObservationIgnored var generator: (any SummaryGenerating)?
    /// The session whose cards are saved; set when today's session opens.
    @ObservationIgnored private(set) var sessionID: String?
    @ObservationIgnored private var day: String?
    @ObservationIgnored private var directory: String?
    @ObservationIgnored private let store: (any SummaryStoring)?
    @ObservationIgnored private let timeZone: TimeZone
    /// Whether the agent is working now, asked when an empty answer comes back.
    @ObservationIgnored var isWorking: () -> Bool = { false }
    @ObservationIgnored private let now: () -> Date
    @ObservationIgnored private let timeout: Duration
    @ObservationIgnored private var inFlight: Task<Void, Never>?

    init(
        generator: (any SummaryGenerating)? = nil,
        store: (any SummaryStoring)? = nil,
        timeout: Duration = StatusSummaryModel.timeout,
        timeZone: TimeZone = .current,
        now: @escaping () -> Date = Date.init
    ) {
        self.generator = generator
        self.store = store
        self.timeout = timeout
        self.timeZone = timeZone
        self.now = now
    }

    /// Today's session opened: its saved card shows at once, with its age, until a refresh
    /// succeeds. A card saved for another session is not shown.
    func open(sessionID: String) {
        self.sessionID = sessionID
        if let saved = store?.load(), saved.sessionID == sessionID {
            clear()
            summary = saved.summary
            updatedAt = saved.updatedAt
        } else {
            // Setup found a different session than the card shown before it finished.
            clear()
        }
    }

    /// The app opened, before setup finds today's session: a card saved for a session of this date
    /// in this project shows at once. `open(sessionID:)` keeps it or removes it.
    func preopen(day: String, directory: String) {
        clear()
        self.day = day
        self.directory = directory
        guard let saved = store?.load(), saved.day == day, saved.directory == directory else { return }
        summary = saved.summary
        updatedAt = saved.updatedAt
    }

    /// A session that never ran: the long-date card, with no request. A request on its way is
    /// dropped.
    func showEmpty(timeZone: TimeZone = .current) {
        inFlight?.cancel()
        inFlight = nil
        isUpdating = false
        summary = .card(.empty(on: now(), timeZone: timeZone))
        updatedAt = now()
        failed = false
    }

    /// Forgets the card, for another session.
    func clear() {
        inFlight?.cancel()
        inFlight = nil
        isUpdating = false
        summary = nil
        updatedAt = nil
        failed = false
    }

    /// Asks for a new summary. Dropped while one is on its way.
    func refresh() async {
        guard inFlight == nil, let generator else { return }
        isUpdating = true
        let timeout = timeout
        let task = Task {
            let reply = await Self.generate(generator, timeout: timeout)
            guard !Task.isCancelled else { return }
            self.finish(reply)
        }
        inFlight = task
        await task.value
    }

    private func finish(_ reply: String?) {
        isUpdating = false
        inFlight = nil
        guard let reply, !reply.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            // No text while the agent works keeps the last card as it is (or, with none yet, says
            // the agent is working); anything else failed.
            let working = reply != nil && isWorking()
            failed = !working
            if working, summary == nil {
                summary = .card(.working(on: now(), timeZone: timeZone))
                updatedAt = now()
            }
            return
        }
        let parsed = StatusSummary.parse(reply)
        summary = parsed
        updatedAt = now()
        failed = false
        if let sessionID {
            store?.save(SavedSummary(sessionID: sessionID, summary: parsed, updatedAt: now(), day: day, directory: directory))
        }
    }

    /// The reply, or nil after an error or the timeout. The timeout doesn't wait for the request
    /// to notice its cancellation.
    private nonisolated static func generate(_ generator: any SummaryGenerating, timeout: Duration) async -> String? {
        let race = FirstAnswer()
        return await withCheckedContinuation { continuation in
            race.install(continuation)
            let request = Task {
                let reply = try? await generator.generateSummary(prompt: SessionPrompts.Summary.text)
                race.answer(reply)
            }
            Task {
                try? await Task.sleep(for: timeout)
                if race.answer(nil) { request.cancel() }
            }
        }
    }

    /// The label next to ↻ at `date`.
    func refreshLabel(at date: Date) -> SummaryRefreshLabel {
        SummaryRefreshLabel.make(updatedAt: updatedAt, at: date, isUpdating: isUpdating, failed: failed)
    }
}

/// Resumes once, with whichever answer comes first.
private nonisolated final class FirstAnswer: @unchecked Sendable {
    private let lock = NSLock()
    private var continuation: CheckedContinuation<String?, Never>?

    func install(_ continuation: CheckedContinuation<String?, Never>) {
        lock.lock()
        self.continuation = continuation
        lock.unlock()
    }

    /// True when this answer was the first.
    @discardableResult
    func answer(_ value: String?) -> Bool {
        lock.lock()
        let continuation = continuation
        self.continuation = nil
        lock.unlock()
        continuation?.resume(returning: value)
        return continuation != nil
    }
}
