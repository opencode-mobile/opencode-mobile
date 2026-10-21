import Foundation

enum OpenCodeURLSessions {
    private static let lock = NSLock()
    private static var restSession: URLSession = makeRestSession()

    /// Current REST pool. Clients that omit an injected session must read this at request time.
    static var rest: URLSession {
        lock.lock()
        defer { lock.unlock() }
        return restSession
    }

    /// Installs a new pool, then cancels in-flight work on the previous one.
    static func replaceRest() {
        let replacement = makeRestSession()
        lock.lock()
        let previous = restSession
        restSession = replacement
        lock.unlock()
        // A caller may have read `previous` but not yet created its task, and creating a task on an
        // invalidated session raises an uncatchable NSGenericException (#777). Cancel in-flight work
        // now; invalidate once such stragglers have had time to start.
        previous.getAllTasks { tasks in tasks.forEach { $0.cancel() } }
        DispatchQueue.global(qos: .utility).asyncAfter(deadline: .now() + invalidationGracePeriod) {
            previous.invalidateAndCancel()
        }
    }

    private static let invalidationGracePeriod: TimeInterval = 5

    /// Nil means the live pool. An injected session stays fixed for tests and the connection probe.
    static func restResolver(injected: URLSession?) -> @Sendable () -> URLSession {
        if let injected {
            return { injected }
        }
        return { OpenCodeURLSessions.rest }
    }

    private static func makeRestSession() -> URLSession {
        let configuration = URLSessionConfiguration.default
        configuration.timeoutIntervalForRequest = 15
        configuration.timeoutIntervalForResource = 60
        configuration.waitsForConnectivity = false
        // Authenticated responses never touch Library/Caches, and redirects go through RedirectPolicy.
        configuration.urlCache = nil
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        return URLSession(configuration: configuration, delegate: RedirectPolicyDelegate.shared, delegateQueue: nil)
    }
}
