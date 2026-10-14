import Foundation

/// Acknowledges finished runs the user is looking at, so the server-wide unread state
/// (v2 `time.viewed`) clears on every device. Session Chat focuses and releases it.
@MainActor
protocol SessionViewReporter: AnyObject {
    func focus(sessionId: String)
    /// Clears focus only while `sessionId` still owns it (the next Session Chat may already
    /// have focused itself).
    func release(sessionId: String)
    /// Profile switch or cache clear: drop focus, watermarks, and any pending retry.
    func clearCache()
}

@MainActor
final class EmptySessionViewReporter: SessionViewReporter {
    func focus(sessionId _: String) {}
    func release(sessionId _: String) {}
    func clearCache() {}
}

/// Mirrors the official TUI: posts `view` only while focused, only when unread, once per
/// idle watermark (recorded before the request). Failures retry with 250 ms doubling backoff
/// (cap 5 s) for a bounded number of attempts, then wait for the next trigger: focus, a run
/// ending, the focused session's idle watermark changing, or a reconnect.
/// v1 servers have no viewed state, so every entry point is a no-op there.
@MainActor
final class DefaultSessionViewReporter: SessionViewReporter {
    static let initialRetryDelayMs = 250
    static let maxRetryDelayMs = 5_000
    /// Retries after the first failure: 250, 500, 1000, 2000, 4000 ms.
    static let maxRetries = 5

    private enum AttemptOutcome {
        case finished
        case retry
    }

    private let sessionRepository: any SessionRepository
    private let sessionLiveStore: any SessionLiveStore
    private let capabilities: @Sendable () -> ServerCapabilities
    private let sleep: @Sendable (Int) async throws -> Void

    private var focusedSessionId: String?
    /// Idle watermark already acknowledged (or in flight) per session.
    private var acknowledgedIdle: [String: Int64] = [:]
    private var evaluation: Task<Void, Never>?
    private var evaluationGeneration: UInt64 = 0
    private var knownSessionTask: Task<Void, Never>?
    private var eventTask: Task<Void, Never>?

    init(
        sessionRepository: any SessionRepository,
        sessionLiveStore: any SessionLiveStore,
        eventStream: any ForegroundEventStream,
        capabilities: @escaping @Sendable () -> ServerCapabilities,
        sleep: @escaping @Sendable (Int) async throws -> Void = { try await Task.sleep(for: .milliseconds($0)) }
    ) {
        self.sessionRepository = sessionRepository
        self.sessionLiveStore = sessionLiveStore
        self.capabilities = capabilities
        self.sleep = sleep
        eventTask = Task.detached { [weak self, eventStream] in
            for await event in eventStream.events() {
                // Filter off-main; only run ends and reconnects hop to the reporter.
                switch event.type {
                case "session.idle", "stream.reconnected", "server.connected":
                    break
                default:
                    continue
                }
                guard let reporter = self else { return }
                await reporter.onEvent(type: event.type, sessionID: event.sessionID)
            }
        }
    }

    deinit {
        eventTask?.cancel()
        evaluation?.cancel()
        knownSessionTask?.cancel()
    }

    func focus(sessionId: String) {
        guard focusedSessionId != sessionId else { return }
        focusedSessionId = sessionId
        stopKnownSessionObservation()
        evaluate(sessionId: sessionId)
    }

    func release(sessionId: String) {
        guard focusedSessionId == sessionId else { return }
        focusedSessionId = nil
        stopKnownSessionObservation()
        cancelEvaluation()
    }

    func clearCache() {
        focusedSessionId = nil
        acknowledgedIdle.removeAll()
        stopKnownSessionObservation()
        cancelEvaluation()
    }

    private func onEvent(type: String, sessionID: String?) {
        guard let focused = focusedSessionId else { return }
        switch type {
        case "session.idle":
            // A run finished while the chat is open: it must end up viewed, never unread.
            guard sessionID == focused else { return }
            evaluate(sessionId: focused)
        case "stream.reconnected", "server.connected":
            evaluate(sessionId: focused)
        default:
            break
        }
    }

    /// The Home list's copy of the focused session is only a re-check trigger: a new idle
    /// watermark there re-runs the server fetch, it never feeds the posted watermark.
    private func observeKnownSession(sessionId: String) {
        stopKnownSessionObservation()
        let stream = sessionLiveStore.observeKnownSession(sessionId: sessionId)
        knownSessionTask = Task { [weak self] in
            var lastIdle: Int64??
            for await session in stream {
                guard let self, self.focusedSessionId == sessionId else { return }
                defer { lastIdle = .some(session.idleAtEpochMs) }
                // The first sighting coincides with focus, which already evaluated.
                guard let previous = lastIdle, previous != session.idleAtEpochMs else { continue }
                guard session.isServerUnread, let idle = session.idleAtEpochMs else { continue }
                if let acknowledged = self.acknowledgedIdle[sessionId], acknowledged >= idle { continue }
                self.evaluate(sessionId: sessionId)
            }
        }
    }

    private func stopKnownSessionObservation() {
        knownSessionTask?.cancel()
        knownSessionTask = nil
    }

    private func cancelEvaluation() {
        evaluationGeneration &+= 1
        evaluation?.cancel()
        evaluation = nil
    }

    /// Each trigger starts over with a fresh retry budget and backoff.
    private func evaluate(sessionId: String) {
        // Capabilities are read per trigger: a chat opened before the protocol probe finished
        // starts acknowledging on the following `server.connected`.
        guard capabilities().supportsSessionViewed else { return }
        if knownSessionTask == nil, focusedSessionId == sessionId {
            observeKnownSession(sessionId: sessionId)
        }
        cancelEvaluation()
        let generation = evaluationGeneration
        evaluation = Task { [weak self] in
            await self?.run(sessionId: sessionId, generation: generation)
        }
    }

    private func isCurrent(sessionId: String, generation: UInt64) -> Bool {
        !Task.isCancelled && evaluationGeneration == generation && focusedSessionId == sessionId
    }

    private func run(sessionId: String, generation: UInt64) async {
        var delayMs = Self.initialRetryDelayMs
        var retries = 0
        while isCurrent(sessionId: sessionId, generation: generation) {
            switch await attempt(sessionId: sessionId, generation: generation) {
            case .finished:
                return
            case .retry:
                guard retries < Self.maxRetries else {
                    DiagnosticLog.info(.session, "view ack gave up session=\(sessionId)")
                    return
                }
                retries += 1
                DiagnosticLog.info(.session, "view ack retry session=\(sessionId) delayMs=\(delayMs)")
                do {
                    try await sleep(delayMs)
                } catch {
                    return
                }
                delayMs = min(delayMs * 2, Self.maxRetryDelayMs)
            }
        }
    }

    private func attempt(sessionId: String, generation: UInt64) async -> AttemptOutcome {
        // Always read the server: a stale cached idle could record the wrong watermark and
        // suppress the real acknowledgement.
        let session: Session
        do {
            session = try await sessionRepository.getSession(id: sessionId, directory: nil)
        } catch {
            return Self.isMissing(error) ? .finished : .retry
        }
        guard isCurrent(sessionId: sessionId, generation: generation) else { return .finished }
        // Home lists root sessions only; a subagent child has no unread row to clear.
        guard session.parentID == nil, session.isServerUnread, let idle = session.idleAtEpochMs else {
            return .finished
        }
        if let acknowledged = acknowledgedIdle[sessionId], acknowledged >= idle {
            return .finished
        }
        acknowledgedIdle[sessionId] = idle
        do {
            try await sessionRepository.markSessionViewed(sessionId: sessionId, idleAtEpochMs: idle)
            // Home is correct before the `session.viewed` echo arrives.
            sessionLiveStore.applySessionViewed(sessionId: sessionId, idleAtEpochMs: idle)
            DiagnosticLog.info(.session, "view ack session=\(sessionId) idle=\(idle)")
            return .finished
        } catch {
            if acknowledgedIdle[sessionId] == idle {
                acknowledgedIdle.removeValue(forKey: sessionId)
            }
            if Self.isMissing(error) { return .finished }
            return .retry
        }
    }

    private static func isMissing(_ error: Error) -> Bool {
        if case let SessionError.http(statusCode, _) = error, statusCode == 404 { return true }
        return false
    }
}
