import Foundation

/// `/btw` side questions, like the TUI and desktop app: ask the session's model about the
/// conversation so far and read the answer without adding anything to the session.
nonisolated enum SideQuestion {
    static let command = "/btw"

    /// What the TUI and desktop put in front of the question. The session's tools are exposed to
    /// the model but no tool loop runs, so the model is told not to call any.
    static let instructions = [
        "The user is asking a quick side question about the conversation so far.",
        "Answer directly and concisely in markdown from what you already know.",
        "Do not call any tools and do not take any actions.",
    ].joined(separator: " ")

    /// The `generate` prompt: the instruction, a blank line, then the trimmed question.
    static func prompt(_ question: String) -> String {
        instructions + "\n\n" + question.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// The question after a leading `/btw`, trimmed; empty for a bare `/btw`. Nil when the draft is
    /// not a `/btw` command (`/btwx`, or `/btw` later in the text).
    static func question(inDraft text: String) -> String? {
        let leading = text.drop(while: \.isWhitespace)
        guard leading.hasPrefix(command) else { return nil }
        let rest = leading.dropFirst(command.count)
        guard let first = rest.first else { return "" }
        guard first.isWhitespace else { return nil }
        return rest.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// The draft after a leading `/btw` and the whitespace after it, kept as typed (trailing
    /// spaces stay). Nil when the draft does not start with the command and whitespace.
    static func draftAfterCommand(_ text: String) -> String? {
        guard entersMode(text) else { return nil }
        return String(text.drop(while: \.isWhitespace).dropFirst(command.count).drop(while: \.isWhitespace))
    }

    /// Typing `/btw` and then whitespace switches the composer into side-question mode.
    static func entersMode(_ text: String) -> Bool {
        let leading = text.drop(while: \.isWhitespace)
        guard leading.hasPrefix(command) else { return false }
        return leading.dropFirst(command.count).first?.isWhitespace == true
    }
}

/// One `/btw` question and where it stands. Answers live in memory only.
nonisolated struct SideQuestionEntry: Identifiable, Equatable, Sendable {
    enum Status: Equatable, Sendable {
        case pending
        case answered(String)
        /// The request failed and asking again may work.
        case failed
    }

    let id: UUID
    let sessionID: String
    let question: String
    var status: Status

    var answer: String? {
        if case let .answered(text) = status { return text }
        return nil
    }
}

nonisolated enum SideQuestionEvent: Equatable, Sendable {
    /// Entries for the session changed (asked, answered, failed, retried or removed).
    case changed(sessionID: String)
    case answered(sessionID: String, id: UUID)
    /// The request failed. A `retryable` entry stays as `.failed`; any other is already removed,
    /// since asking again cannot succeed (the server has no `generate` route for it).
    case failed(sessionID: String, id: UUID, question: String, retryable: Bool, message: String?)
}

/// In-memory `/btw` requests and unread answers for the active connection, keyed by session id.
/// It outlives Session Chat, so a request keeps running and its answer waits after the user
/// leaves the session. Switching Connection Profile clears it; killing the app drops it.
@MainActor
final class SideQuestionStore {
    typealias Ask = @Sendable (_ sessionID: String, _ question: String) async throws -> String

    private let ask: Ask
    private var entries: [SideQuestionEntry] = []
    private var tasks: [UUID: Task<Void, Never>] = [:]
    private var listeners: [UUID: (SideQuestionEvent) -> Void] = [:]

    init(ask: @escaping Ask) {
        self.ask = ask
    }

    convenience init(sessionRepository: any SessionRepository) {
        self.init { sessionID, question in
            try await sessionRepository.askSideQuestion(sessionID: sessionID, question: question)
        }
    }

    /// The session's entries, oldest first.
    func entries(sessionID: String) -> [SideQuestionEntry] {
        entries.filter { $0.sessionID == sessionID }
    }

    func entry(id: UUID) -> SideQuestionEntry? {
        entries.first { $0.id == id }
    }

    /// Starts a request. Returns nil and does nothing for a blank question.
    @discardableResult
    func ask(sessionID: String, question: String) -> UUID? {
        let trimmed = question.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        let entry = SideQuestionEntry(id: UUID(), sessionID: sessionID, question: trimmed, status: .pending)
        entries.append(entry)
        start(entry)
        emit(.changed(sessionID: sessionID))
        return entry.id
    }

    func retry(_ id: UUID) {
        guard let index = entries.firstIndex(where: { $0.id == id }), entries[index].status == .failed else { return }
        entries[index].status = .pending
        start(entries[index])
        emit(.changed(sessionID: entries[index].sessionID))
    }

    /// Forgets an entry, cancelling its request if it is still running.
    func remove(_ id: UUID) {
        guard let index = entries.firstIndex(where: { $0.id == id }) else { return }
        let sessionID = entries[index].sessionID
        tasks.removeValue(forKey: id)?.cancel()
        entries.remove(at: index)
        emit(.changed(sessionID: sessionID))
    }

    /// Drops every request and answer: the active Connection Profile changed.
    func clearCache() {
        let sessionIDs = Set(entries.map(\.sessionID))
        tasks.values.forEach { $0.cancel() }
        tasks.removeAll()
        entries.removeAll()
        for sessionID in sessionIDs {
            emit(.changed(sessionID: sessionID))
        }
    }

    func observe(_ listener: @escaping (SideQuestionEvent) -> Void) -> UUID {
        let id = UUID()
        listeners[id] = listener
        return id
    }

    func stopObserving(_ id: UUID) {
        listeners[id] = nil
    }

    private func start(_ entry: SideQuestionEntry) {
        let ask = ask
        tasks[entry.id] = Task { [weak self] in
            let result: Result<String, Error>
            do {
                result = try .success(await ask(entry.sessionID, entry.question))
            } catch {
                result = .failure(error)
            }
            guard !Task.isCancelled else { return }
            self?.finish(entry.id, with: result)
        }
    }

    private func finish(_ id: UUID, with result: Result<String, Error>) {
        tasks[id] = nil
        guard let index = entries.firstIndex(where: { $0.id == id }) else { return }
        let entry = entries[index]
        switch result {
        case let .success(text):
            entries[index].status = .answered(text)
            emit(.changed(sessionID: entry.sessionID))
            emit(.answered(sessionID: entry.sessionID, id: id))
        case let .failure(error):
            let retryable = Self.isRetryable(error)
            if retryable {
                entries[index].status = .failed
            } else {
                entries.remove(at: index)
            }
            emit(.changed(sessionID: entry.sessionID))
            emit(.failed(
                sessionID: entry.sessionID,
                id: id,
                question: entry.question,
                retryable: retryable,
                message: Self.message(for: error)
            ))
        }
    }

    /// A 404 means the server has no `generate` route for this session, so Retry cannot help.
    nonisolated static func isRetryable(_ error: Error) -> Bool {
        if case SessionError.http(statusCode: 404, message: _) = error { return false }
        return true
    }

    nonisolated private static func message(for error: Error) -> String? {
        guard case let SessionError.http(_, message) = error else { return nil }
        return message
    }

    private func emit(_ event: SideQuestionEvent) {
        for listener in listeners.values {
            listener(event)
        }
    }
}
