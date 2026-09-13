import Foundation

nonisolated protocol SyncCoordinator: AnyObject, Sendable {
    func register(
        key: ProjectionKey,
        loader: @escaping @Sendable () async throws -> Void
    ) -> SyncRegistration
    func unregister(_ registration: SyncRegistration)
    func observe(_ key: ProjectionKey) -> AsyncStream<ProjectionState>
    func ensureFresh(_ key: ProjectionKey) async
    func invalidate(_ key: ProjectionKey?)
    func bootstrap(_ reason: SyncBootstrapReason) async
    func markFocused(_ sessionKey: ProjectionKey?)
    func visible(_ key: ProjectionKey, _ isVisible: Bool)
}
