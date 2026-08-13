import Foundation

nonisolated final class EventsAPIClient: Sendable {
    private static let defaultStreamingSession: URLSession = {
        let configuration = URLSessionConfiguration.default
        configuration.timeoutIntervalForRequest = TimeInterval.infinity
        configuration.timeoutIntervalForResource = TimeInterval.infinity
        configuration.waitsForConnectivity = true
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        configuration.urlCache = nil
        return URLSession(configuration: configuration)
    }()

    private let connectionProvider: @Sendable () -> ServerConnection?
    private let urlSession: URLSession

    init(connectionProvider: @escaping @Sendable () -> ServerConnection?) {
        self.connectionProvider = connectionProvider
        self.urlSession = Self.defaultStreamingSession
    }

    init(connectionProvider: @escaping @Sendable () -> ServerConnection?, urlSession: URLSession) {
        self.connectionProvider = connectionProvider
        self.urlSession = urlSession
    }

    func streamGlobalEvents(
        onHTTPResponse: (@Sendable (Int) async -> Void)? = nil,
        onDataLine: @escaping @Sendable (String) async -> Void
    ) async throws {
        guard let connection = connectionProvider() else {
            throw EventsAPIError.noActiveConnection
        }

        let normalizedPath = Self.subscribePath(for: connection.serverProtocol)
        let url = connection.apiBaseURL.appendingPathComponent(normalizedPath)
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
        let bytes: URLSession.AsyncBytes
        let response: URLResponse
        do {
            (bytes, response) = try await urlSession.bytes(for: request)
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

    var eventProtocolWireValue: String {
        (connectionProvider()?.serverProtocol ?? .v1).wireValue
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
