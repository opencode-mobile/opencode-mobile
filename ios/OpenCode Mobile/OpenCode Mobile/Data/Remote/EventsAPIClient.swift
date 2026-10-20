import Foundation

nonisolated final class EventsAPIClient: @unchecked Sendable {
    private static func makeStreamingSession() -> URLSession {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = TimeInterval.infinity
        configuration.timeoutIntervalForResource = TimeInterval.infinity
        configuration.waitsForConnectivity = false
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        configuration.urlCache = nil
        return URLSession(configuration: configuration)
    }

    private let connectionProvider: @Sendable () -> ServerConnection?
    private let injectedSession: URLSession?
    private let onConnectBudgetExceeded: @Sendable () -> Void
    private let streamingSessionFactory: @Sendable () -> URLSession
    private let beforeDial: @Sendable () -> Void
    private let sessionLock = NSLock()
    private var ownedDials: [DialLease] = []
    private var installedToken: UInt64 = 0
    private var retiredBefore: UInt64 = 0

    /// `streamingSessionFactory` and `beforeDial` are test seams for owned streaming sessions.
    init(
        connectionProvider: @escaping @Sendable () -> ServerConnection?,
        onConnectBudgetExceeded: @escaping @Sendable () -> Void = {},
        streamingSessionFactory: (@Sendable () -> URLSession)? = nil,
        beforeDial: @escaping @Sendable () -> Void = {}
    ) {
        self.connectionProvider = connectionProvider
        self.injectedSession = nil
        self.onConnectBudgetExceeded = onConnectBudgetExceeded
        self.streamingSessionFactory = streamingSessionFactory ?? { EventsAPIClient.makeStreamingSession() }
        self.beforeDial = beforeDial
    }

    init(
        connectionProvider: @escaping @Sendable () -> ServerConnection?,
        urlSession: URLSession,
        onConnectBudgetExceeded: @escaping @Sendable () -> Void = {}
    ) {
        self.connectionProvider = connectionProvider
        self.injectedSession = urlSession
        self.onConnectBudgetExceeded = onConnectBudgetExceeded
        self.streamingSessionFactory = { urlSession }
        self.beforeDial = {}
    }

    deinit {
        // A running dial retains self, so no dial is mid-creation here.
        for dial in ownedDials {
            dial.retire()
        }
    }

    /// Drops older owned pools and publishes `token`. Injected test sessions are untouched.
    /// Same-token dials stay so concurrent leases do not cancel each other.
    func installFreshStreamingSession(token: UInt64) {
        guard injectedSession == nil else { return }
        let previous = installedTokenSnapshot()
        if previous < token {
            retireStreamingSession(token: previous)
        }
        let stale = mutateDials { dials, installed in
            if token < retiredBefore {
                return []
            }
            if token >= installed {
                installed = token
            }
            let stale = dials.filter { $0.token < token }
            dials.removeAll { $0.token < token }
            return stale
        }
        retire(stale)
    }

    /// Invalidates dials for `token` only while that token is still installed.
    /// A newer `start()` token therefore survives a late `stop()`.
    func retireStreamingSession(token: UInt64) {
        guard injectedSession == nil else { return }
        let stale = mutateDials { dials, installed in
            guard installed == token else { return [] }
            let stale = dials.filter { $0.token == token }
            dials.removeAll { $0.token == token }
            return stale
        }
        retire(stale)
    }

    /// `stop()` passes the generation it just published. Newer tokens survive.
    func retireStreamingSessions(olderThan generation: UInt64) {
        guard injectedSession == nil else { return }
        let stale = mutateDials { dials, _ in
            if generation > retiredBefore {
                retiredBefore = generation
            }
            let stale = dials.filter { $0.token < generation }
            dials.removeAll { $0.token < generation }
            return stale
        }
        retire(stale)
    }

    @discardableResult
    func streamGlobalEvents(
        onHTTPResponse: (@Sendable (Int) async -> Void)? = nil,
        onActivity: (@Sendable () async -> Void)? = nil,
        onDataLine: @escaping @Sendable (String) async -> Void
    ) async throws -> SseAttemptOutcome {
        try await streamSubscriptions(
            directories: [],
            onHTTPResponse: onHTTPResponse,
            onActivity: onActivity,
            onDataLine: onDataLine
        )
    }

    @discardableResult
    func streamSubscriptions(
        directories: [String],
        onHTTPResponse: (@Sendable (Int) async -> Void)? = nil,
        onActivity: (@Sendable () async -> Void)? = nil,
        onDataLine: @escaping @Sendable (String) async -> Void
    ) async throws -> SseAttemptOutcome {
        guard let connection = connectionProvider() else {
            throw EventsAPIError.noActiveConnection
        }
        let resolvedProtocol = connection.serverProtocol ?? .v1
        let targets = SseSubscribeTargets.targets(protocol: resolvedProtocol, directories: directories)
        var last: SseAttemptOutcome = .ended
        try await withThrowingTaskGroup(of: SseAttemptOutcome.self) { group in
            for target in targets {
                group.addTask {
                    try await self.streamEvents(
                        connection: connection,
                        path: target.path,
                        directory: target.directory,
                        onHTTPResponse: onHTTPResponse,
                        onActivity: onActivity,
                        onDataLine: onDataLine
                    )
                }
            }
            while let outcome = try await group.next() {
                last = outcome
            }
        }
        return last
    }

    private func streamEvents(
        connection: ServerConnection,
        path: String,
        directory: String?,
        connectBudget: Duration = .seconds(5),
        sessionToken: UInt64 = 0,
        onHTTPResponse: (@Sendable (Int) async -> Void)?,
        onActivity: (@Sendable () async -> Void)?,
        onDataLine: @escaping @Sendable (String) async -> Void
    ) async throws -> SseAttemptOutcome {
        var url = connection.apiBaseURL.appendingPathComponent(path)
        if let directory, !directory.isEmpty {
            var components = URLComponents(url: url, resolvingAgainstBaseURL: false)
            var items = components?.queryItems ?? []
            items.append(URLQueryItem(name: "directory", value: directory))
            components?.queryItems = items
            if let withQuery = components?.url {
                url = withQuery
            }
        }
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.cachePolicy = .reloadIgnoringLocalCacheData
        request.setValue("text/event-stream", forHTTPHeaderField: "Accept")
        request.setValue("no-cache", forHTTPHeaderField: "Cache-Control")
        request.setValue("identity", forHTTPHeaderField: "Accept-Encoding")
        if let password = connection.password, !password.isEmpty {
            let credentials = "\(connection.username):\(password)"
            let encoded = Data(credentials.utf8).base64EncodedString()
            request.setValue("Basic \(encoded)", forHTTPHeaderField: "Authorization")
        }
        // Do not shorten request.timeoutInterval. It resets on each body byte and would kill a quiet stream.

        let startedAt = OpenCodeAPITrace.start(request)
        let token = sessionToken == 0 ? installedTokenSnapshot() : sessionToken
        guard let dial = openDial(token: token) else {
            DiagnosticLog.info(.sse, "SSE dial rejected for retired token path=\(path)")
            return .transport(URLError(.cancelled))
        }
        defer { finishOwned(dial) }
        beforeDial()

        let race = ConnectRace()
        let bytesRef = TaskRef()
        let budgetTask = Task {
            do {
                try await Task.sleep(for: connectBudget)
            } catch is CancellationError {
                return
            } catch {
                return
            }
            guard race.claimBudget() else { return }
            bytesRef.cancel()
        }
        defer { budgetTask.cancel() }
        let bytesTask = Task { () throws -> SseAttemptOutcome in
            // Creating a task on an invalidated URLSession raises an uncatchable
            // NSGenericException (#745). beginDial() fails once the dial is retired and
            // otherwise defers invalidation to finishOwned until headers arrive.
            guard dial.beginDial() else {
                throw URLError(.cancelled)
            }
            let (bytes, response) = try await dial.session.bytes(for: request)
            guard dial.markStreaming() else {
                throw URLError(.cancelled)
            }
            guard race.markHeaders() else {
                throw SseConnectBudgetExceeded()
            }
            budgetTask.cancel()
            try Task.checkCancellation()
            return try await self.readEventStream(
                bytes: bytes,
                response: response,
                request: request,
                startedAt: startedAt,
                onHTTPResponse: onHTTPResponse,
                onActivity: onActivity,
                onDataLine: onDataLine
            )
        }
        bytesRef.store(bytesTask)
        dial.attach(bytesTask)

        do {
            return try await withTaskCancellationHandler {
                try await bytesTask.value
            } onCancel: {
                bytesTask.cancel()
                budgetTask.cancel()
            }
        } catch {
            budgetTask.cancel()
            bytesTask.cancel()
            // Parent cancellation wins over a budget that also fired. A budget-only
            // cancel of the bytes task is CancellationError and must not look like stop().
            try Task.checkCancellation()
            if race.budgetClaimed && !race.headersArrived {
                DiagnosticLog.warning(
                    .sse,
                    "SSE connect budget exceeded path=\(path) budgetMs=\(Self.budgetMilliseconds(connectBudget))"
                )
                onConnectBudgetExceeded()
                return .transport(SseConnectBudgetExceeded())
            }
            if dial.isRetired && !race.headersArrived {
                // Retired by token rotation, stall recovery or stop() before headers.
                // Same outcome an invalidateAndCancel() of a dialing task used to give.
                return .transport(URLError(.cancelled))
            }
            if error is CancellationError {
                throw CancellationError()
            }
            if race.headersArrived {
                throw error
            }
            OpenCodeAPITrace.failure(request, error: error, startedAt: startedAt)
            return .transport(error)
        }
    }

    private func readEventStream(
        bytes: URLSession.AsyncBytes,
        response: URLResponse,
        request: URLRequest,
        startedAt: Date,
        onHTTPResponse: (@Sendable (Int) async -> Void)?,
        onActivity: (@Sendable () async -> Void)?,
        onDataLine: @escaping @Sendable (String) async -> Void
    ) async throws -> SseAttemptOutcome {
        guard let http = response as? HTTPURLResponse else {
            OpenCodeAPITrace.invalidResponse(request, startedAt: startedAt)
            throw EventsAPIError.invalidResponse
        }
        OpenCodeAPITrace.finish(request, statusCode: http.statusCode, startedAt: startedAt)
        await onHTTPResponse?(http.statusCode)
        guard (200 ..< 300).contains(http.statusCode) else {
            return .http(http.statusCode)
        }

        var parser = SSEDataLineParser()
        do {
            var lineData = Data()
            for try await byte in bytes {
                if byte == 10 {
                    if lineData.last == 13 {
                        lineData.removeLast()
                    }
                    let line = String(data: lineData, encoding: .utf8) ?? ""
                    lineData.removeAll(keepingCapacity: true)
                    await onActivity?()
                    for payload in parser.ingest(line) {
                        await onDataLine(payload)
                    }
                } else {
                    lineData.append(byte)
                }
            }
            if !lineData.isEmpty {
                let line = String(data: lineData, encoding: .utf8) ?? ""
                for payload in parser.ingest(line) {
                    await onDataLine(payload)
                }
            }
            for payload in parser.finish() {
                await onDataLine(payload)
            }
            return .ended
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            return .transport(error)
        }
    }

    var eventSubscribePath: String {
        Self.subscribePath(for: connectionProvider()?.serverProtocol)
    }

    var eventProtocol: ServerProtocol {
        connectionProvider()?.serverProtocol ?? .v1
    }

    var eventProtocolWireValue: String {
        eventProtocol.wireValue
    }

    @discardableResult
    func streamEvents(
        path: String,
        directory: String?,
        connectBudget: Duration = .seconds(5),
        sessionToken: UInt64 = 0,
        onHTTPResponse: (@Sendable (Int) async -> Void)? = nil,
        onActivity: (@Sendable () async -> Void)? = nil,
        onDataLine: @escaping @Sendable (String) async -> Void
    ) async throws -> SseAttemptOutcome {
        guard let connection = connectionProvider() else {
            throw EventsAPIError.noActiveConnection
        }
        return try await streamEvents(
            connection: connection,
            path: path,
            directory: directory,
            connectBudget: connectBudget,
            sessionToken: sessionToken,
            onHTTPResponse: onHTTPResponse,
            onActivity: onActivity,
            onDataLine: onDataLine
        )
    }

    /// Nil when `token` was already retired, so callers never dial a dead session.
    @discardableResult
    func openStreamingSession(token: UInt64) -> URLSession? {
        openDial(token: token)?.session
    }

    private func openDial(token: UInt64) -> DialLease? {
        if let injectedSession {
            return DialLease(token: token, session: injectedSession, ownsSession: false)
        }
        sessionLock.lock()
        defer { sessionLock.unlock() }
        guard token >= retiredBefore else { return nil }
        let dial = DialLease(token: token, session: streamingSessionFactory(), ownsSession: true)
        ownedDials.append(dial)
        return dial
    }

    var ownedStreamingDialCount: Int {
        sessionLock.lock()
        defer { sessionLock.unlock() }
        return ownedDials.count
    }

    /// The dial's own exit. Always invalidates, even when a retire already
    /// dropped the dial from `ownedDials` and left invalidation to it.
    private func finishOwned(_ dial: DialLease) {
        sessionLock.lock()
        ownedDials.removeAll { $0 === dial }
        sessionLock.unlock()
        dial.finish()
    }

    private func installedTokenSnapshot() -> UInt64 {
        sessionLock.lock()
        defer { sessionLock.unlock() }
        return installedToken
    }

    private func mutateDials(
        _ body: (inout [DialLease], inout UInt64) -> [DialLease]
    ) -> [DialLease] {
        sessionLock.lock()
        let stale = body(&ownedDials, &installedToken)
        sessionLock.unlock()
        return stale
    }

    private func retire(_ dials: [DialLease]) {
        for dial in dials {
            dial.retire()
        }
    }

    private static func budgetMilliseconds(_ budget: Duration) -> Int {
        let (seconds, attoseconds) = budget.components
        return Int(seconds * 1_000 + attoseconds / 1_000_000_000_000_000)
    }

    static func streamingSessionKey(for connection: ServerConnection) -> String {
        let fingerprint = ConnectionProfileIdentity.credentialFingerprint(
            username: connection.username,
            password: connection.password ?? ""
        )
        return "\(connection.baseURL.absoluteString)|\(connection.username)|\(fingerprint)"
    }

    private static func subscribePath(for serverProtocol: ServerProtocol?) -> String {
        serverProtocol == .v2 ? "api/event" : "global/event"
    }

    private final class ConnectRace: @unchecked Sendable {
        private let lock = NSLock()
        private var headers = false
        private var budget = false

        /// False when the budget already won, so a late header mark cannot hide that cancel.
        func markHeaders() -> Bool {
            lock.lock()
            defer { lock.unlock() }
            if budget { return false }
            headers = true
            return true
        }

        /// True when this call won the race against headers.
        func claimBudget() -> Bool {
            lock.lock()
            defer { lock.unlock() }
            if headers { return false }
            budget = true
            return true
        }

        var budgetClaimed: Bool {
            lock.lock()
            defer { lock.unlock() }
            return budget
        }

        var headersArrived: Bool {
            lock.lock()
            defer { lock.unlock() }
            return headers
        }
    }

    /// One streaming dial and its owned URLSession. The session is invalidated
    /// exactly once, and never while the dial may be creating its task: a retire
    /// during `.dialing` cancels the dial instead and `finish()` invalidates.
    private final class DialLease: @unchecked Sendable {
        private enum Phase {
            case opened
            case dialing
            case streaming
            case finished
        }

        let token: UInt64
        let session: URLSession
        private let ownsSession: Bool
        private let lock = NSLock()
        private var phase: Phase = .opened
        private var retired = false
        private var invalidated = false
        private var task: Task<SseAttemptOutcome, Error>?

        init(token: UInt64, session: URLSession, ownsSession: Bool) {
            self.token = token
            self.session = session
            self.ownsSession = ownsSession
        }

        /// False once retired. After true, retire() leaves the session valid until headers.
        func beginDial() -> Bool {
            lock.lock()
            defer { lock.unlock() }
            guard !retired, phase == .opened else { return false }
            phase = .dialing
            return true
        }

        /// False when retired mid-dial. After true, the task exists and retire() may invalidate.
        func markStreaming() -> Bool {
            lock.lock()
            defer { lock.unlock() }
            guard !retired, phase == .dialing else { return false }
            phase = .streaming
            return true
        }

        func attach(_ task: Task<SseAttemptOutcome, Error>) {
            lock.lock()
            let cancelNow = retired
            if !cancelNow {
                self.task = task
            }
            lock.unlock()
            if cancelNow {
                task.cancel()
            }
        }

        func retire() {
            lock.lock()
            retired = true
            let dialing = phase == .dialing
            let invalidateNow = !dialing && claimInvalidation()
            let task = dialing ? task : nil
            lock.unlock()
            if invalidateNow {
                session.invalidateAndCancel()
            }
            task?.cancel()
        }

        func finish() {
            lock.lock()
            phase = .finished
            task = nil
            let invalidateNow = claimInvalidation()
            lock.unlock()
            if invalidateNow {
                session.invalidateAndCancel()
            }
        }

        var isRetired: Bool {
            lock.lock()
            defer { lock.unlock() }
            return retired
        }

        /// Caller holds `lock`.
        private func claimInvalidation() -> Bool {
            guard ownsSession, !invalidated else { return false }
            invalidated = true
            return true
        }
    }

    private final class TaskRef: @unchecked Sendable {
        private let lock = NSLock()
        private var task: Task<SseAttemptOutcome, Error>?

        func store(_ task: Task<SseAttemptOutcome, Error>) {
            lock.lock()
            self.task = task
            lock.unlock()
        }

        func cancel() {
            lock.lock()
            let task = task
            lock.unlock()
            task?.cancel()
        }
    }
}

nonisolated struct SSEDataLineParser {
    private var dataBuffer = ""

    mutating func ingest(_ line: String) -> [String] {
        let trimmed = line.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty {
            return finish()
        }
        if trimmed.hasPrefix(":") {
            // Keep-alive comments are not events. The stream loop still counts
            // the received line as activity so a quiet socket is not torn down.
            return []
        }
        if trimmed.hasPrefix("data:") {
            let payload = trimmed.dropFirst(5).trimmingCharacters(in: .whitespaces)
            if !dataBuffer.isEmpty {
                dataBuffer.append("\n")
            }
            dataBuffer.append(payload)
            return []
        }
        if trimmed.first == "{" {
            var payloads = finish()
            payloads.append(trimmed)
            return payloads
        }
        return []
    }

    mutating func finish() -> [String] {
        guard !dataBuffer.isEmpty else { return [] }
        let payload = dataBuffer
        dataBuffer = ""
        return [payload]
    }
}

nonisolated enum EventsAPIError: Error, LocalizedError {
    case invalidResponse
    case noActiveConnection
    case httpStatus(Int)

    var errorDescription: String? {
        switch self {
        case .invalidResponse:
            "Invalid SSE response"
        case .noActiveConnection:
            "No active server connection"
        case let .httpStatus(code):
            "SSE HTTP \(code)"
        }
    }
}
