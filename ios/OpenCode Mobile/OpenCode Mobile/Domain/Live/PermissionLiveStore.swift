import Foundation

@MainActor
protocol PermissionLiveStore: AnyObject, Sendable {
    func observePermissions(directory: String) -> AsyncStream<[PermissionRequest]>
    func clearCache()
    func invalidate(directory: String?)
}

extension PermissionLiveStore {
    func invalidate(directory: String?) {}
}
