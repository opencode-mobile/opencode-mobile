import Foundation

@MainActor
protocol PermissionLiveStore: AnyObject, Sendable {
    func observePermissions(directory: String) -> AsyncStream<[PermissionRequest]>
    func clearCache()
}
