import Foundation
import Observation
import os

/// Asks the session's model for a summary of today's session: `POST /api/session/{id}/generate`
/// with only the prompt (the session's model and level answer it).
protocol SummaryGenerating: Sendable {
    func generateSummary(prompt: String) async throws -> String
    /// The session's `time.updated` now (`GET /api/session/{id}`), read before each request; nil
    /// when there is no session to read.
    func sessionUpdated() async throws -> Int64?
}

extension SummaryGenerating {
    func sessionUpdated() async throws -> Int64? { nil }
}

/// What a card was made from (app plan, "Nothing new"): the session's `time.updated` read just
/// before the request, and whether the session was busy then.
struct SummaryBasis: Equatable, Codable, Sendable {
    var updated: Int64
    var busy: Bool
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
    /// Nil on cards saved before it was kept, or when the session couldn't be read: such a card
    /// is never current.
    var basis: SummaryBasis?
}

/// Where the last card is kept. Only one session's (today's) at a time.
@MainActor
protocol SummaryStoring: AnyObject {
    func load() -> SavedSummary?
    func save(_ saved: SavedSummary)
    /// Forgets the saved card (a new session, or Forget this computer).
    func remove()
}

extension SummaryStoring {
    func remove() {}
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

    func remove() {
        defaults.removeObject(forKey: Self.key)
    }
}

/// The status summary on home and its ↻. `TodaySession` asks for it when a run ends, on ↻, and on
/// open, foreground and reconnect only when something is new (`isCurrent`); never on a timer, and
/// never on its own during a run. A refresh asked for while one runs is dropped, except a run end:
/// that one waits and runs once when the current refresh finishes, so a mid-run card (from ↻ or a
/// goal round) never outlives the run. Before each
/// request it reads the session's `time.updated`, kept with the card as its basis. A request that
/// fails, times out (60 s) or answers with no text keeps the last card and its basis and shows
/// "Couldn't update" — except an empty answer while the agent works (the server often gives one
/// then): the last card stays without the note, and the run's end refreshes it; with no card yet,
/// a "The agent is working." card shows instead. A card made while the session is busy (from ↻)
/// has no Next: mid-run, the model suggests its own next step. Each new card is saved for its
/// session, and shown at once (with its age) the next time that session opens.
@MainActor
@Observable
final class StatusSummaryModel {
    static let timeout: Duration = .seconds(60)

    private(set) var summary: StatusSummary?
    private(set) var updatedAt: Date?
    private(set) var isUpdating = false
    /// The last request failed; the last card stays.
    private(set) var failed = false
    /// The card shown is the empty session's ("Nothing running yet").
    private(set) var showsEmpty = false

    @ObservationIgnored var generator: (any SummaryGenerating)?
    /// The card's artifact, read alongside each request (app plan, "Artifacts (M8)"); nil for none.
    @ObservationIgnored var artifactReader: (() async -> CardArtifact?)?
    /// What the card shown was made from; nil with no card, or none known.
    @ObservationIgnored private(set) var basis: SummaryBasis?
    /// A new card was made; `busy` when the session was busy then.
    @ObservationIgnored var onCard: (_ busy: Bool) -> Void = { _ in }
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
    /// A run ended while a refresh was on its way: one more refresh runs when it finishes.
    @ObservationIgnored private var runEndWaiting = false

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
            basis = saved.basis
        } else {
            // Setup found a different session than the card shown before it finished.
            clear()
        }
    }

    /// The app opened, before setup finds today's session: a card saved for a session of this date
    /// in this project shows at once; with `sessionID` (today's saved session), only that session's.
    /// `open(sessionID:)` keeps it or removes it.
    func preopen(day: String, directory: String, sessionID: String? = nil) {
        clear()
        self.day = day
        self.directory = directory
        guard let saved = store?.load(), saved.day == day, saved.directory == directory,
              sessionID == nil || saved.sessionID == sessionID
        else { return }
        summary = saved.summary
        updatedAt = saved.updatedAt
        basis = saved.basis
    }

    /// Nothing is new: the card shown was made while the session was idle, from this
    /// `time.updated`.
    func isCurrent(updated: Int64) -> Bool {
        guard summary != nil, let basis, !basis.busy else { return false }
        return basis.updated == updated
    }

    /// The app switched the model itself, which moved `time.updated`: the card stays current.
    func moveBasis(to updated: Int64) {
        guard var basis, let summary, let updatedAt, let sessionID else { return }
        basis.updated = updated
        self.basis = basis
        store?.save(SavedSummary(sessionID: sessionID, summary: summary, updatedAt: updatedAt, day: day, directory: directory, basis: basis))
    }

    /// The agent is working and there is no card yet: say so, with no request.
    func showWorkingIfEmpty() {
        if showsEmpty { return showWorkingOnMessage() }
        guard summary == nil else { return }
        summary = .card(.working(on: now(), timeZone: timeZone))
        updatedAt = now()
    }

    /// A message went to the empty session, or it is busy: "Working on your message." in place of
    /// the empty card, until the first summary arrives. Any other card stays.
    func showWorkingOnMessage() {
        guard showsEmpty else { return }
        showsEmpty = false
        summary = .card(.sent(on: now(), timeZone: timeZone))
        updatedAt = now()
    }

    /// A session that never ran: the long-date card, with no request. A request on its way is
    /// dropped.
    func showEmpty(timeZone: TimeZone = .current) {
        inFlight?.cancel()
        inFlight = nil
        runEndWaiting = false
        isUpdating = false
        summary = .card(.empty(on: now(), timeZone: timeZone))
        showsEmpty = true
        updatedAt = now()
        basis = nil
        failed = false
    }

    /// Start a new session: the saved card was the old session's.
    func forgetSaved() {
        store?.remove()
    }

    /// Forgets the card, for another session.
    func clear() {
        inFlight?.cancel()
        inFlight = nil
        runEndWaiting = false
        isUpdating = false
        summary = nil
        showsEmpty = false
        updatedAt = nil
        basis = nil
        failed = false
    }

    /// Asks for a new summary. Dropped while one is on its way, except a run end (`runEnd`): at
    /// most one waits, and runs as a normal refresh once the one on its way finishes.
    func refresh(runEnd: Bool = false) async {
        guard let generator else { return }
        guard inFlight == nil else {
            if runEnd {
                HexLog.summary.info("summary: the run ended during a refresh; refreshing again after it")
                runEndWaiting = true
            }
            return
        }
        isUpdating = true
        let timeout = timeout
        let busy = isWorking()
        HexLog.summary.info("summary: request (busy: \(busy))")
        let reader = artifactReader
        let task = Task {
            let updated = try? await generator.sessionUpdated()
            async let artifact = reader?()
            let reply = await Self.generate(generator, timeout: timeout)
            let found = await artifact
            guard !Task.isCancelled else { return }
            self.finish(reply, basis: updated.map { SummaryBasis(updated: $0, busy: busy) }, artifact: found ?? nil)
        }
        inFlight = task
        await task.value
        // A run that ended meanwhile gets its own card. Cleared or replaced meanwhile: it went.
        guard runEndWaiting, inFlight == nil, !task.isCancelled else { return }
        runEndWaiting = false
        await refresh(runEnd: true)
    }

    private func finish(_ reply: String?, basis: SummaryBasis?, artifact: CardArtifact? = nil) {
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
            } else if working {
                showWorkingOnMessage()
            }
            return
        }
        var (parsed, shape) = StatusSummary.read(reply)
        if shape != .full {
            // Read at best effort; nothing shows on the card.
            HexLog.summary.info("summary: reply without its full shape (\(String(describing: shape), privacy: .public))")
        }
        let busy = basis?.busy ?? isWorking()
        if busy, case var .card(card) = parsed {
            card.next = nil
            parsed = .card(card)
        }
        if case var .card(card) = parsed {
            card.artifact = artifact
            parsed = .card(card)
        }
        summary = parsed
        showsEmpty = false
        updatedAt = now()
        self.basis = basis
        failed = false
        if let sessionID {
            store?.save(SavedSummary(sessionID: sessionID, summary: parsed, updatedAt: now(), day: day, directory: directory, basis: basis))
        }
        onCard(busy)
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
