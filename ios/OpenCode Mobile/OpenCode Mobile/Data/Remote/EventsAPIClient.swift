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
    private let sessionLock = NSLock()
    private var ownedSession: URLSession?
    private var lastIdentity: String?

    init(connectionProvider: @escaping @Sendable () -> ServerConnection?) {
        self.connectionProvider = connectionProvider
        self.injectedSession = nil
    }

    init(
        connectionProvider: @escaping @Sendable () -> ServerConnection?,
        urlSession: URLSession
    ) {
        self.connectionProvider = connectionProvider
        self.injectedSession = urlSession
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

        let startedAt = OpenCodeAPITrace.start(request)
        let session = streamingSession(for: connection)
        let bytes: URLSession.AsyncBytes
        let response: URLResponse
        do {
            (bytes, response) = try await session.bytes(for: request)
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            OpenCodeAPITrace.failure(request, error: error, startedAt: startedAt)
            return .transport(error)
        }
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
            onHTTPResponse: onHTTPResponse,
            onActivity: onActivity,
            onDataLine: onDataLine
        )
    }

    private func streamingSession(for connection: ServerConnection) -> URLSession {
        if let injectedSession {
            return injectedSession
        }
        let identity = Self.streamingSessionKey(for: connection)
        sessionLock.lock()
        defer { sessionLock.unlock() }
        if let ownedSession, lastIdentity == identity {
            return ownedSession
        }
        ownedSession?.invalidateAndCancel()
        let session = Self.makeStreamingSession()
        ownedSession = session
        lastIdentity = identity
        return session
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
