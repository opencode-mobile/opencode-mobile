import Foundation
import Observation

/// Asks the agent's model for a summary of today's session. Round 2 implements it with
/// `POST /api/session/{id}/generate` and the chosen model and level.
protocol SummaryGenerating: Sendable {
    func generateSummary(prompt: String) async throws -> String
}

/// The status summary on home and its ↻. Refreshed when the session opens or reconnects, when the
/// agent's turn ends and on ↻; never on a timer, and never two at once: a refresh asked for while
/// one runs waits for that one instead of starting another.
@MainActor
@Observable
final class StatusSummaryModel {
    private(set) var summary: StatusSummary?
    private(set) var updatedAt: Date?
    private(set) var isUpdating = false
    /// The last request failed; the last card stays.
    private(set) var failed = false

    @ObservationIgnored var generator: (any SummaryGenerating)?
    @ObservationIgnored private let now: () -> Date
    @ObservationIgnored private var inFlight: Task<Void, Never>?

    init(generator: (any SummaryGenerating)? = nil, now: @escaping () -> Date = Date.init) {
        self.generator = generator
        self.now = now
    }

    /// An empty session: the long-date card, with no request.
    func showEmpty(locale: Locale = .current, timeZone: TimeZone = .current) {
        summary = .card(.empty(on: now(), locale: locale, timeZone: timeZone))
        updatedAt = now()
        failed = false
    }

    /// Asks for a new summary, or waits for the one already on its way.
    func refresh() async {
        if let inFlight {
            await inFlight.value
            return
        }
        guard let generator else { return }
        isUpdating = true
        let task = Task { await self.request(generator) }
        inFlight = task
        await task.value
    }

    private func request(_ generator: any SummaryGenerating) async {
        defer {
            isUpdating = false
            inFlight = nil
        }
        do {
            let reply = try await generator.generateSummary(prompt: SessionPrompts.Summary.text)
            summary = StatusSummary.parse(reply)
            updatedAt = now()
            failed = false
        } catch {
            failed = true
        }
    }

    /// The label next to ↻ at `date`.
    func refreshLabel(at date: Date) -> SummaryRefreshLabel {
        SummaryRefreshLabel.make(updatedAt: updatedAt, at: date, isUpdating: isUpdating, failed: failed)
    }
}
