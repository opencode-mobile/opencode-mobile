import Foundation

/// Top-of-timeline Syncing pill copy while a cached or catching-up timeline waits for network confirmation.
enum SessionMessageSyncChrome {
    static var statusLabel: String {
        String(localized: "timeline.syncing", defaultValue: "Syncing…")
    }
}
