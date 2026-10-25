import Foundation

nonisolated enum ProtocolRecheckAction: Equatable, Sendable {
    /// The server now speaks this protocol; reopen the event streams on its paths.
    case retarget(ServerProtocol)
    /// The saved protocol still answers; the streams' own retries recover.
    case keepProtocol
    /// No protocol answers; show the server as offline until one does.
    case markUnreachable
    case none
}

/// When foreground SSE re-runs protocol detection, so a server upgraded (or downgraded) between
/// opencode v1 and v2 at the same address is followed instead of retried on the old paths forever.
nonisolated enum ProtocolRecheckPolicy {
    /// At most one re-check per window; foreground and network-path wakes reopen it.
    static let minInterval: Duration = .seconds(15)

    /// A failed attempt that hints the server speaks the other protocol: an HTTP error other than a
    /// rejected credential (opencode v2 answers the v1 event paths with 500), or a 2xx that never
    /// sent `server.connected` (an older v1 serves its web app on `/api/event`). A transport failure
    /// before any response is an outage, not a protocol change.
    static func suggestsProtocolChange(
        outcome: SseAttemptOutcome,
        httpOpened: Bool,
        serverConnected: Bool
    ) -> Bool {
        guard !serverConnected else { return false }
        switch outcome {
        case .http(401), .http(403):
            return false
        case .http:
            return true
        case .ended, .transport:
            return httpOpened
        }
    }

    static func isDue(
        now: ContinuousClock.Instant,
        lastRecheck: ContinuousClock.Instant?,
        minInterval: Duration = minInterval
    ) -> Bool {
        guard let lastRecheck else { return true }
        return now - lastRecheck >= minInterval
    }

    /// How long a request that arrives inside the window waits. It is deferred, not dropped: a
    /// v1 server also answers `/api/event`, so the recovery request may be the only hint of a swap.
    static func delayUntilDue(
        now: ContinuousClock.Instant,
        lastRecheck: ContinuousClock.Instant?,
        minInterval: Duration = minInterval
    ) -> Duration {
        guard let lastRecheck else { return .zero }
        return max(.zero, lastRecheck + minInterval - now)
    }

    /// `leaseWentLive` is true when an event stream connected while the check was in flight; a
    /// no-answer result is then stale and must not mark a working server offline.
    static func action(
        result: ProtocolRedetection,
        applied: ServerProtocol,
        leaseWentLive: Bool = false
    ) -> ProtocolRecheckAction {
        switch result {
        case let .detected(detected):
            detected == applied ? .keepProtocol : .retarget(detected)
        case .unreachable:
            leaseWentLive ? .none : .markUnreachable
        case .skipped:
            .none
        }
    }
}
