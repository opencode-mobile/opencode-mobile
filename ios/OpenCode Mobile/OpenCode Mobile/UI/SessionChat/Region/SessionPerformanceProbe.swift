import Foundation

enum SessionPerformanceProbe {
    static func emit(_ event: String) {
        CFNotificationCenterPostNotification(
            CFNotificationCenterGetDarwinNotifyCenter(),
            CFNotificationName("dev.opencode.mobile.performance.\(event)" as CFString),
            nil,
            nil,
            true
        )
    }
}
