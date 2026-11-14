import Foundation

enum PromptSendProgress {
    static let visibleBodyThreshold: Int64 = 32_768

    static func percent(bytesSent: Int64, bytesTotal: Int64) -> Int {
        guard bytesTotal > 0 else { return 0 }
        let raw = Int((Double(bytesSent) / Double(bytesTotal) * 100).rounded(.down))
        return min(99, max(0, raw))
    }

    static func shouldShow(bytesTotal: Int64, hasAttachments: Bool) -> Bool {
        hasAttachments || bytesTotal >= visibleBodyThreshold
    }

    static func label(percent: Int) -> String {
        percent <= 0 ? "Sending…" : "Sending \(percent)%"
    }
}

/// Lets the screen that started a prompt send ask for a fresh attempt while it is stuck (#1002).
/// Install it with `PromptSendControl.$current.withValue(_:)` around the send; the uploader binds
/// `retry` while it runs a send that can be retried.
nonisolated final class PromptSendControl: @unchecked Sendable {
    @TaskLocal static var current: PromptSendControl?

    private let lock = NSLock()
    private var retryHandler: (@Sendable () -> Void)?

    var canRetry: Bool {
        lock.withLock { retryHandler != nil }
    }

    func bind(retry: @escaping @Sendable () -> Void) {
        lock.withLock { retryHandler = retry }
    }

    func unbind() {
        lock.withLock { retryHandler = nil }
    }

    func retry() {
        let handler = lock.withLock { retryHandler }
        handler?()
    }
}
