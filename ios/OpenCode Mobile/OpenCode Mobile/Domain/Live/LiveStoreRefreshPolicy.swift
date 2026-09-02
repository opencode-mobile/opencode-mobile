import Foundation

enum LiveStoreRefreshPolicy {
    nonisolated static let messageDebounceMs = 300
    nonisolated static let sessionDebounceMs = 500
    nonisolated static let statusDebounceMs = 300
    nonisolated static let workingPollIntervalMs = 10_000
    nonisolated static let subscriberStopTimeoutMs = 5_000
    nonisolated static let invalidationAckTimeoutMs = 15_000
}
