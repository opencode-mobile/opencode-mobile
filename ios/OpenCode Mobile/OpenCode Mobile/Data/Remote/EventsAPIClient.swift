import Foundation

nonisolated final class EventsAPIClient: Sendable {
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
    private let protocolResolver: (@Sendable () async -> ServerProtocol)?
    private let injectedSession: URLSession?

    init(
        connectionProvider: @escaping @Sendable () -> ServerConnection?,
        protocolResolver: (@Sendable () async -> ServerProtocol)? = nil
    ) {
        self.connectionProvider = connectionProvider
        self.protocolResolver = protocolResolver
        self.injectedSession = nil
    }

    init(
        connectionProvider: @escaping @Sendable () -> ServerConnection?,
        urlSession: URLSession,
        protocolResolver: (@Sendable () async -> ServerProtocol)? = nil
    ) {
        self.connectionProvider = connectionProvider
        self.protocolResolver = protocolResolver
        self.injectedSession = urlSession
    }

    func streamGlobalEvents(
        onHTTPResponse: (@Sendable (Int) async -> Void)? = nil,
        onActivity: (@Sendable () async -> Void)? = nil,
        onDataLine: @escaping @Sendable (String) async -> Void
    ) async throws {
        try await streamSubscriptions(
            directories: [],
            onHTTPResponse: onHTTPResponse,
            onActivity: onActivity,
            onDataLine: onDataLine
        )
    }

    func streamSubscriptions(
        directories: [String],
        onHTTPResponse: (@Sendable (Int) async -> Void)? = nil,
        onActivity: (@Sendable () async -> Void)? = nil,
        onDataLine: @escaping @Sendable (String) async -> Void
    ) async throws {
        guard let connection = connectionProvider() else {
            throw EventsAPIError.noActiveConnection
        }
        if let protocolResolver {
            Task { _ = await protocolResolver() }
        }
        let resolvedProtocol = connection.serverProtocol ?? .v1
        let targets = SseSubscribeTargets.targets(protocol: resolvedProtocol, directories: directories)
        try await withThrowingTaskGroup(of: Void.self) { group in
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
            try await group.waitForAll()
        }
    }

    private func streamEvents(
        connection: ServerConnection,
        path: String,
        directory: String?,
        onHTTPResponse: (@Sendable (Int) async -> Void)?,
        onActivity: (@Sendable () async -> Void)?,
        onDataLine: @escaping @Sendable (String) async -> Void
    ) async throws {
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
        let ownedSession = injectedSession == nil
        let session = injectedSession ?? Self.makeStreamingSession()
        defer {
            if ownedSession {
                session.invalidateAndCancel()
            }
        }
        let bytes: URLSession.AsyncBytes
        let response: URLResponse
        do {
            (bytes, response) = try await session.bytes(for: request)
        } catch {
            OpenCodeAPITrace.failure(request, error: error, startedAt: startedAt)
            throw error
        }
        guard let http = response as? HTTPURLResponse else {
            OpenCodeAPITrace.invalidResponse(request, startedAt: startedAt)
            throw EventsAPIError.invalidResponse
        }
        OpenCodeAPITrace.finish(request, statusCode: http.statusCode, startedAt: startedAt)
        guard (200 ..< 300).contains(http.statusCode) else {
            throw EventsAPIError.httpStatus(http.statusCode)
        }
        await onHTTPResponse?(http.statusCode)

        var parser = SSEDataLineParser()
        for try await line in bytes.lines {
            // Comments, id/event fields, and incomplete data still prove the
            // socket is alive. TUI keeps the iterator open on those frames.
            await onActivity?()
            for payload in parser.ingest(line) {
                await onDataLine(payload)
            }
        }
        for payload in parser.finish() {
            await onDataLine(payload)
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

    func streamEvents(
        path: String,
        directory: String?,
        onHTTPResponse: (@Sendable (Int) async -> Void)? = nil,
        onActivity: (@Sendable () async -> Void)? = nil,
        onDataLine: @escaping @Sendable (String) async -> Void
    ) async throws {
        guard let connection = connectionProvider() else {
            throw EventsAPIError.noActiveConnection
        }
        if let protocolResolver {
            Task { _ = await protocolResolver() }
        }
        try await streamEvents(
            connection: connection,
            path: path,
            directory: directory,
            onHTTPResponse: onHTTPResponse,
            onActivity: onActivity,
            onDataLine: onDataLine
        )
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
            return isCompleteJSONPayload(dataBuffer) ? finish() : []
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

    private func isCompleteJSONPayload(_ payload: String) -> Bool {
        guard let data = payload.data(using: .utf8) else { return false }
        return (try? JSONSerialization.jsonObject(with: data)) != nil
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
