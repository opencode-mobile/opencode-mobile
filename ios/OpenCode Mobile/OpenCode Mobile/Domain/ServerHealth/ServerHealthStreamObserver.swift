import Foundation

/// Narrow seam the foreground event stream reports failed attempts through.
nonisolated protocol ServerHealthStreamObserver: Sendable {
    func onStreamFailure(_ category: ServerHealthFailureCategory)
}

nonisolated struct NoOpServerHealthStreamObserver: ServerHealthStreamObserver {
    func onStreamFailure(_: ServerHealthFailureCategory) {}
}

/// Hands stream failures to whoever listens, so the stream does not depend on the monitor.
nonisolated final class ServerHealthStreamFailureRelay: ServerHealthStreamObserver, @unchecked Sendable {
    private let lock = NSLock()
    private var continuations: [UUID: AsyncStream<ServerHealthFailureCategory>.Continuation] = [:]

    func onStreamFailure(_ category: ServerHealthFailureCategory) {
        lock.lock()
        let subscribers = Array(continuations.values)
        lock.unlock()
        for continuation in subscribers {
            continuation.yield(category)
        }
    }

    func failures() -> AsyncStream<ServerHealthFailureCategory> {
        let id = UUID()
        let (stream, continuation) = AsyncStream.makeStream(of: ServerHealthFailureCategory.self)
        lock.lock()
        continuations[id] = continuation
        lock.unlock()
        continuation.onTermination = { [weak self] _ in
            guard let self else { return }
            self.lock.lock()
            self.continuations.removeValue(forKey: id)
            self.lock.unlock()
        }
        return stream
    }
}

extension ServerHealthFailureCategory {
    /// How a finished SSE attempt counts. Nil for a cancelled attempt, which is not a failure.
    nonisolated static func streamOutcome(_ outcome: SseAttemptOutcome) -> ServerHealthFailureCategory? {
        switch outcome {
        case .ended:
            return .server
        case let .http(status):
            return httpStatus(status)
        case let .transport(error):
            guard let error else { return .network }
            return transportError(error)
        }
    }

    nonisolated static func httpStatus(_ status: Int) -> ServerHealthFailureCategory {
        switch status {
        case 401, 403: .auth
        case 500 ..< 600: .server
        default: .other
        }
    }

    /// Nil when the error is a cancellation.
    nonisolated static func transportError(_ error: Error) -> ServerHealthFailureCategory? {
        if error is CancellationError { return nil }
        if let handshake = error as? SseHandshakeError {
            return handshake.reason == .timeout ? .timeout : .other
        }
        if error is SseConnectBudgetExceeded { return .timeout }
        if error is DecodingError { return .other }
        let failure = TransportFailure.captured(from: error)
        if failure.urlErrorDomain == NSURLErrorDomain {
            switch failure.urlErrorCode {
            case NSURLErrorCancelled: return nil
            case NSURLErrorTimedOut: return .timeout
            default: return .network
            }
        }
        return .network
    }
}

/// Set once the idle watchdog reported a heartbeat stall, so the attempt's outcome is not
/// reported a second time.
nonisolated final class ServerHealthAttemptReport: @unchecked Sendable {
    private let lock = NSLock()
    private var reported = false

    /// True the first time only.
    func claim() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        if reported { return false }
        reported = true
        return true
    }
}
