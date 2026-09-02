import Foundation

@MainActor
protocol SessionLiveStore: AnyObject, Sendable {
    func observeSessions(directory: String) -> AsyncStream<SessionListSnapshot>
    func clearCache()
    /// Requests a forced refresh and waits, bounded, for the observation to report back.
    ///
    /// Returning means "stop waiting", not "the store now holds fresh data": the wait
    /// also ends when the observation is torn down, the refresh fails, the ack ceiling
    /// elapses, or the calling task is cancelled. Callers may use it to release a
    /// spinner; they must not treat it as a freshness guarantee.
    func invalidate(directory: String?) async
}

extension SessionLiveStore {
    func invalidate(directory: String?) async {}
}
