import Foundation
import Observation
import OpenCodeKit
import os

/// One question asked on the side, and how it went.
struct AskEntry: Equatable, Sendable, Identifiable, Codable {
    enum State: Equatable, Sendable, Codable {
        /// "Reading the session…".
        case asking
        case answered(String)
        /// The reply had no text (the model tried to use a tool).
        case empty
        /// A `503`, any other error, or the timeout.
        case failed
    }

    var id = UUID()
    var question: String
    var state: State
    /// When it was asked (or asked again), for its age.
    var askedAt: Date

    var answer: String? {
        if case let .answered(answer) = state { return answer }
        return nil
    }
}

/// Where the Ask history is kept: the shown answer and the last closed ones, for one session.
@MainActor
protocol AskHistoryStoring: AnyObject {
    func load() -> SavedAsks?
    func save(_ saved: SavedAsks)
}

/// The Ask message and its history as kept on the phone, for today's session only.
struct SavedAsks: Equatable, Codable, Sendable {
    var sessionID: String
    /// The answered message shown on home, if any.
    var shown: AskEntry?
    /// Closed answers, newest first, at most `AskModel.historyLimit`.
    var history: [AskEntry]
}

@MainActor
final class AskDefaults: AskHistoryStoring {
    static let key = "hexcode.ask.history"
    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    func load() -> SavedAsks? {
        defaults.data(forKey: Self.key).flatMap { try? JSONDecoder().decode(SavedAsks.self, from: $0) }
    }

    func save(_ saved: SavedAsks) {
        if let data = try? JSONEncoder().encode(saved) { defaults.set(data, forKey: Self.key) }
    }
}

/// Ask on the side (app plan, "Ask on the side"): a question about the session, answered by
/// `POST /api/session/{id}/generate` with the Ask prompt. Nothing is added to the session and the
/// agent isn't interrupted; it runs at any time, alongside a summary refresh. One message shows at
/// once: a new Ask moves an answered one into history and abandons an unanswered one (its reply is
/// ignored). History is phone-only: the shown answer and the last 5 closed ones, saved for today's
/// session.
@MainActor
@Observable
final class AskModel {
    static let timeout: Duration = .seconds(60)
    static let historyLimit = 5

    /// The message on home; nil for none.
    private(set) var shown: AskEntry?
    /// The shown message is open (the whole answer) rather than folded.
    private(set) var isOpen = false
    /// Closed answers, newest first.
    private(set) var history: [AskEntry] = []

    /// Asks the session's model; nil until today's session opens.
    @ObservationIgnored var generate: (@Sendable (String) async throws -> String)?
    /// The session is gone (`404`): setup runs again.
    @ObservationIgnored var onSessionGone: () -> Void = {}
    @ObservationIgnored private let store: (any AskHistoryStoring)?
    @ObservationIgnored private let now: () -> Date
    @ObservationIgnored private let timeout: Duration
    @ObservationIgnored private var sessionID: String?

    init(store: (any AskHistoryStoring)? = nil, timeout: Duration = AskModel.timeout, now: @escaping () -> Date = Date.init) {
        self.store = store
        self.timeout = timeout
        self.now = now
    }

    /// Today's session opened: its saved answers come back; another session's are forgotten.
    func open(sessionID: String) {
        guard sessionID != self.sessionID else { return }
        self.sessionID = sessionID
        isOpen = false
        if let saved = store?.load(), saved.sessionID == sessionID {
            shown = saved.shown
            history = Array(saved.history.prefix(Self.historyLimit))
        } else {
            shown = nil
            history = []
            save()
        }
    }

    /// Asks `question`. An answered message goes into history first; an unanswered one is dropped.
    func ask(_ question: String) {
        let question = question.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !question.isEmpty else { return }
        keepShown()
        let entry = AskEntry(question: question, state: .asking, askedAt: now())
        shown = entry
        isOpen = false
        send(entry)
    }

    /// Try again: the same question, asked again.
    func retry() {
        guard var entry = shown, entry.state == .empty || entry.state == .failed else { return }
        entry.state = .asking
        entry.askedAt = now()
        shown = entry
        send(entry)
    }

    /// ✕: an answered message goes into history; an unanswered or failed one is dropped.
    func close() {
        keepShown()
        isOpen = false
        save()
    }

    /// A tap on the folded answer.
    func expand() {
        guard shown?.answer != nil else { return }
        isOpen = true
    }

    /// Fold (`chevD`).
    func fold() {
        isOpen = false
    }

    /// A row in Details: it leaves history and shows open; the one shown before goes into history.
    func openFromHistory(_ id: UUID) {
        guard let index = history.firstIndex(where: { $0.id == id }) else { return }
        let entry = history.remove(at: index)
        keepShown()
        shown = entry
        isOpen = true
        save()
    }

    /// The answer to copy, from the open message.
    var answerToCopy: String? { shown?.answer }

    // MARK: Private

    /// The shown message leaves: into history when answered.
    private func keepShown() {
        guard let entry = shown else { return }
        shown = nil
        guard entry.answer != nil else { return }
        history.removeAll { $0.id == entry.id }
        history.insert(entry, at: 0)
        history = Array(history.prefix(Self.historyLimit))
    }

    private func send(_ entry: AskEntry) {
        guard let generate else {
            finish(entry.id, Reply.failed)
            return
        }
        let prompt = SessionPrompts.Ask.prompt(question: entry.question)
        let timeout = timeout
        HexLog.summary.info("ask: request")
        Task { [weak self] in
            let result = await Self.answer(prompt: prompt, generate: generate, timeout: timeout)
            self?.finish(entry.id, result)
        }
    }

    private nonisolated enum Reply: Sendable {
        case text(String)
        case gone
        case failed
    }

    private func finish(_ id: UUID, _ reply: Reply) {
        // Abandoned or closed meanwhile: its reply is ignored.
        guard var entry = shown, entry.id == id, entry.state == .asking else { return }
        switch reply {
        case let .text(text):
            let answer = text.trimmingCharacters(in: .whitespacesAndNewlines)
            entry.state = answer.isEmpty ? .empty : .answered(answer)
            HexLog.summary.info("ask: \(answer.isEmpty ? "empty reply" : "answered", privacy: .public)")
        case .failed:
            entry.state = .failed
            HexLog.summary.info("ask: failed")
        case .gone:
            // The session is gone: the message goes, and setup runs again.
            shown = nil
            onSessionGone()
            return
        }
        shown = entry
        save()
    }

    private func save() {
        guard let sessionID else { return }
        store?.save(SavedAsks(sessionID: sessionID, shown: shown?.answer != nil ? shown : nil, history: history))
    }

    /// The reply, `.gone` for a `404`, or `.failed` after another error or the timeout. The
    /// timeout doesn't wait for the request to notice its cancellation.
    private nonisolated static func answer(
        prompt: String,
        generate: @escaping @Sendable (String) async throws -> String,
        timeout: Duration
    ) async -> Reply {
        let race = FirstReply()
        return await withCheckedContinuation { continuation in
            race.install(continuation)
            let request = Task {
                do {
                    race.answer(.text(try await generate(prompt)))
                } catch let SessionStateAPIError.httpStatus(status, _) where status == 404 {
                    race.answer(.gone)
                } catch {
                    race.answer(.failed)
                }
            }
            Task {
                try? await Task.sleep(for: timeout)
                if race.answer(.failed) { request.cancel() }
            }
        }
    }

    /// Resumes once, with whichever reply comes first.
    private nonisolated final class FirstReply: @unchecked Sendable {
        private let lock = NSLock()
        private var continuation: CheckedContinuation<Reply, Never>?

        func install(_ continuation: CheckedContinuation<Reply, Never>) {
            lock.lock()
            self.continuation = continuation
            lock.unlock()
        }

        /// True when this reply was the first.
        @discardableResult
        func answer(_ reply: Reply) -> Bool {
            lock.lock()
            let continuation = continuation
            self.continuation = nil
            lock.unlock()
            continuation?.resume(returning: reply)
            return continuation != nil
        }
    }
}

/// The age next to an Ask: "now" under 45 s, then whole minutes ("3m"), as ↻.
enum AskAge {
    static func text(since date: Date, at now: Date) -> String {
        let seconds = now.timeIntervalSince(date)
        guard seconds >= 45 else { return String(localized: "home.summary.updated.now", defaultValue: "now") }
        return String(localized: "ask.age.minutes", defaultValue: "\(max(1, Int(seconds / 60)))m")
    }
}
