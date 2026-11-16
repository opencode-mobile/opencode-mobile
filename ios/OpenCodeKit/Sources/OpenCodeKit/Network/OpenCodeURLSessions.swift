import Foundation

public enum OpenCodeURLSessions {
    private static let lock = NSLock()
    private static var restSession: URLSession = makeRestSession()
    private static var longOperationSession: URLSession = makeLongOperationSession()

    /// Current REST pool. Clients that omit an injected session must read this at request time.
    public static var rest: URLSession {
        lock.lock()
        defer { lock.unlock() }
        return restSession
    }

    /// REST pool for calls that may answer after `rest`'s 60 s resource cap (compaction, generate).
    /// Each request sets its own `timeoutInterval`; this pool adds no total limit on top of it.
    public static var longOperation: URLSession {
        lock.lock()
        defer { lock.unlock() }
        return longOperationSession
    }

    /// Installs new pools, then cancels in-flight work on the previous ones.
    public static func replaceRest() {
        let replacement = makeRestSession()
        let longReplacement = makeLongOperationSession()
        lock.lock()
        let previous = [restSession, longOperationSession]
        restSession = replacement
        longOperationSession = longReplacement
        lock.unlock()
        // A caller may have read `previous` but not yet created its task, and creating a task on an
        // invalidated session raises an uncatchable NSGenericException (#777). Cancel in-flight work
        // now; invalidate once such stragglers have had time to start.
        for session in previous {
            session.getAllTasks { tasks in tasks.forEach { $0.cancel() } }
        }
        DispatchQueue.global(qos: .utility).asyncAfter(deadline: .now() + invalidationGracePeriod) {
            previous.forEach { $0.invalidateAndCancel() }
        }
    }

    private static let invalidationGracePeriod: TimeInterval = 5

    /// Nil means the live pool. An injected session stays fixed for tests and the connection probe.
    public static func restResolver(injected: URLSession?) -> @Sendable () -> URLSession {
        if let injected {
            return { injected }
        }
        return { OpenCodeURLSessions.rest }
    }

    /// Like `restResolver`, for `longOperation`. An injected session still wins.
    public static func longOperationResolver(injected: URLSession?) -> @Sendable () -> URLSession {
        if let injected {
            return { injected }
        }
        return { OpenCodeURLSessions.longOperation }
    }

    private static func makeRestSession() -> URLSession {
        makeSession(resourceTimeout: 60)
    }

    private static func makeLongOperationSession() -> URLSession {
        makeSession(resourceTimeout: longOperationResourceTimeout)
    }

    /// URLSession's own default (7 days): finite, and far beyond any request's budget.
    public static let longOperationResourceTimeout: TimeInterval = 7 * 24 * 60 * 60

    private static func makeSession(resourceTimeout: TimeInterval) -> URLSession {
        let configuration = URLSessionConfiguration.default
        configuration.timeoutIntervalForRequest = 15
        configuration.timeoutIntervalForResource = resourceTimeout
        configuration.waitsForConnectivity = false
        // Authenticated responses never touch Library/Caches, and redirects go through RedirectPolicy.
        configuration.urlCache = nil
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        return URLSession(configuration: configuration, delegate: RedirectPolicyDelegate.shared, delegateQueue: nil)
    }
}
