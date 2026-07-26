import Foundation

enum LiveStoreRefreshPolicy {
    nonisolated static let messageDebounceMs = 300
    nonisolated static let sessionDebounceMs = 500
    nonisolated static let statusDebounceMs = 300
    nonisolated static let workingPollIntervalMs = 10_000
}
