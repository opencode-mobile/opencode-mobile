import Foundation

/// Pure hang state machine for unit tests and the runtime watchdog.
/// Tracks one outstanding main-thread ping and emits detect / recover events.
nonisolated struct MainThreadHangDetector: Sendable {
    enum Event: Equatable, Sendable {
        case hangDetected(overdueMilliseconds: Int64)
        case hangRecovered(durationMilliseconds: Int64)
    }

    private(set) var isHung = false
    private var pendingSentAtMilliseconds: Int64?
    private var hangStartedAtMilliseconds: Int64?

    var hasPendingPing: Bool { pendingSentAtMilliseconds != nil }

    mutating func notePingSent(at epochMilliseconds: Int64) {
        guard pendingSentAtMilliseconds == nil else { return }
        pendingSentAtMilliseconds = epochMilliseconds
    }

    mutating func notePingAcknowledged(at epochMilliseconds: Int64) -> Event? {
        pendingSentAtMilliseconds = nil
        guard isHung else { return nil }
        let started = hangStartedAtMilliseconds ?? epochMilliseconds
        isHung = false
        hangStartedAtMilliseconds = nil
        return .hangRecovered(durationMilliseconds: max(0, epochMilliseconds - started))
    }

    mutating func evaluate(
        now epochMilliseconds: Int64,
        thresholdMilliseconds: Int64
    ) -> Event? {
        guard let sentAt = pendingSentAtMilliseconds else { return nil }
        let overdue = epochMilliseconds - sentAt
        guard overdue >= thresholdMilliseconds else { return nil }
        guard !isHung else { return nil }
        isHung = true
        hangStartedAtMilliseconds = sentAt
        return .hangDetected(overdueMilliseconds: overdue)
    }
}
