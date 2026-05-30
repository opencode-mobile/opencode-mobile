import Foundation

enum LiveStoreRefreshPolicy {
    static let messageDebounceMs = 300
    static let sessionDebounceMs = 500
    static let statusDebounceMs = 300
    static let workingPollIntervalMs = 10_000
}
