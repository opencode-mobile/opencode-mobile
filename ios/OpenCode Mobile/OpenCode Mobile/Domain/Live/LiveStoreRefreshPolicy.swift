import Foundation

enum LiveStoreRefreshPolicy {
    nonisolated static let messageDebounceMs = 300
    nonisolated static let sessionDebounceMs = 500
    nonisolated static let statusDebounceMs = 300
    nonisolated static let workingPollIntervalMs = 10_000
    nonisolated static let subscriberStopTimeoutMs = 5_000

    /// Ceiling for `StoreInvalidationBus.invalidateAndWait`. Chosen as a UX ceiling
    /// below the 60s REST request timeout: the ack only gates a pull-to-refresh
    /// spinner, and a minute of spinning on a stalled radio is worse than dropping
    /// the spinner while the request finishes in the background. The store still
    /// emits when the slow response lands, so the list catches up either way.
    nonisolated static let invalidationAckTimeoutMs = 15_000
}
