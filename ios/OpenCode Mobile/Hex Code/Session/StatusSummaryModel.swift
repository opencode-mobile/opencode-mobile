import Foundation
import Observation

/// Asks the session's model for a summary of today's session: `POST /api/session/{id}/generate`
/// with only the prompt (the session's model and level answer it).
protocol SummaryGenerating: Sendable {
    func generateSummary(prompt: String) async throws -> String
}

/// The status summary on home and its ↻. Refreshed when the session opens or reconnects, when a
/// run ends and on ↻; never on a timer. A refresh asked for while one runs is dropped. A request
/// that fails, times out (60 s) or answers with no text keeps the last card and shows
/// "Couldn't update" — except an empty answer while the agent works (the server often gives one
/// then): the last card stays without the note, and the run's end refreshes it.
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
    /// Whether the agent is working now, asked when an empty answer comes back.
    @ObservationIgnored var isWorking: () -> Bool = { false }
    @ObservationIgnored private let now: () -> Date
    @ObservationIgnored private let timeout: Duration
    @ObservationIgnored private var inFlight: Task<Void, Never>?

    init(generator: (any SummaryGenerating)? = nil, timeout: Duration = StatusSummaryModel.timeout, now: @escaping () -> Date = Date.init) {
        self.generator = generator
        self.timeout = timeout
        self.now = now
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
            // No text while the agent works keeps the last card as it is; anything else failed.
            failed = !(reply != nil && isWorking())
            return
        }
        summary = StatusSummary.parse(reply)
        updatedAt = now()
        failed = false
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
