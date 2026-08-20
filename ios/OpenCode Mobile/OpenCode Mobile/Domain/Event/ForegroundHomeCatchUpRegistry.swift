import Foundation

/// Home registers a REST catch-up handler while visible.
nonisolated final class ForegroundHomeCatchUpRegistry: @unchecked Sendable {
    private let lock = NSLock()
    private var handler: (@Sendable () async -> Void)?

    func setHandler(_ handler: (@Sendable () async -> Void)?) {
        lock.lock()
        self.handler = handler
        lock.unlock()
    }

    func catchUp() async {
        lock.lock()
        let handler = self.handler
        lock.unlock()
        await handler?()
    }
}
