import Foundation
import CoreFoundation

enum SessionPerformanceProbe {
    static func emit(_ event: String) {
        guard ProcessInfo.processInfo.environment["OPENCODE_UI_BENCHMARK"] == "1" else { return }
        CFNotificationCenterPostNotification(
            CFNotificationCenterGetDarwinNotifyCenter(),
            CFNotificationName("dev.opencode.mobile.performance.\(event)" as CFString),
            nil,
            nil,
            true
        )
    }
}

@MainActor
protocol MessageLiveStore: AnyObject, Sendable {
    func observeMessages(sessionID: String, directory: String) -> AsyncStream<[Message]>
    func cachedMessages(sessionID: String, directory: String) -> [Message]?
    func clearCache()
}
