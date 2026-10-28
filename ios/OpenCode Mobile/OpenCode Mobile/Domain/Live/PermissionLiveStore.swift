import Foundation

@MainActor
protocol PermissionLiveStore: AnyObject, Sendable {
    func observePermissions(directory: String) -> AsyncStream<[PermissionRequest]>
    func clearCache()
    func invalidate(directory: String?)
    /// Sessions currently known in `directory`. An indexed directory refetches only when a
    /// permission event names one of them, or a session already in its last permission list.
    /// A directory with no index still refetches on every permission event.
    func noteSessions(directory: String, sessionIDs: Set<String>)
    /// Drops every directory index. Observations with no index refetch on any permission event.
    func clearSessionIndex()
}

extension PermissionLiveStore {
    func invalidate(directory: String?) {}
    func noteSessions(directory: String, sessionIDs: Set<String>) {}
    func clearSessionIndex() {}
}
