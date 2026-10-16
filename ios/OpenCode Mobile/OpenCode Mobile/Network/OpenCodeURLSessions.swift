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
        previous.invalidateAndCancel()
    }

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
        return URLSession(configuration: configuration)
    }
}
