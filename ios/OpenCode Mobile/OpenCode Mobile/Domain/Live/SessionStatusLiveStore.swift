import Foundation

@MainActor
protocol SessionStatusLiveStore: AnyObject, Sendable {
    func observeActivity(directory: String) -> AsyncStream<SessionActivitySnapshot>
    func clearCache()
    func invalidate(directory: String?) async
    func setOptimistic(
        directory: String,
        sessionID: String,
        activity: SessionActivity,
        ttlMs: Int64
    )
}

extension SessionStatusLiveStore {
    func invalidate(directory: String?) async {}

    func setOptimistic(
        directory: String,
        sessionID: String,
        activity: SessionActivity,
        ttlMs: Int64
    ) {}

    func setOptimistic(
        directory: String,
        sessionID: String,
        activity: SessionActivity
    ) {
        setOptimistic(
            directory: directory,
            sessionID: sessionID,
            activity: activity,
            ttlMs: SessionActivityPolicy.optimisticTtlMs
        )
    }
}
