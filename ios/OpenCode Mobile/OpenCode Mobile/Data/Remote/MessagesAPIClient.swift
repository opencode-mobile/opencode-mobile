import Foundation

nonisolated enum MessagesAPIError: Error {
    case invalidResponse
    case httpStatus(Int, String?)
    case noActiveConnection
}

protocol MessagesAPI: Sendable {
    func listMessages(sessionID: String, directory: String?) async throws -> [MessageDTO]
    func listMessagesV2(sessionID: String, directory: String?) async throws -> [V2SessionMessageDTO]
    func getMessage(sessionID: String, messageID: String, directory: String?) async throws -> MessageDTO
    func sendMessage(sessionID: String, body: SendMessageRequestDTO, directory: String?) async throws -> MessageDTO
    func sendPromptAsync(sessionID: String, body: SendMessageRequestDTO, directory: String?) async throws
    func sendPromptV2(sessionID: String, body: V2PromptRequestDTO, directory: String?) async throws
    func switchSessionAgent(sessionID: String, agent: String, directory: String?) async throws
    func switchSessionModel(sessionID: String, body: V2SwitchModelRequestDTO, directory: String?) async throws
}

extension MessagesAPI {
    func listMessagesV2(sessionID: String, directory: String?) async throws -> [V2SessionMessageDTO] {
        []
    }

    func sendPromptV2(sessionID: String, body: V2PromptRequestDTO, directory: String?) async throws {}

    func switchSessionAgent(sessionID: String, agent: String, directory: String?) async throws {}

    func switchSessionModel(sessionID: String, body: V2SwitchModelRequestDTO, directory: String?) async throws {}
}

nonisolated struct MessagesAPIClient: MessagesAPI, Sendable {
    private let connectionProvider: @Sendable () -> ServerConnection?
    private let urlSession: URLSession
    private let decoder = JSONDecoder()
    private let encoder = JSONEncoder()

    init(
        connectionProvider: @escaping @Sendable () -> ServerConnection?,
        urlSession: URLSession = .shared
    ) {
        self.connectionProvider = connectionProvider
        self.urlSession = urlSession
    }

    private func requireConnection() throws -> ServerConnection {
        guard let connection = connectionProvider() else {
            throw MessagesAPIError.noActiveConnection
        }
        return connection
    }

    func listMessages(sessionID: String, directory: String? = nil) async throws -> [MessageDTO] {
        try await get(path: "session/\(sessionID)/message", directory: directory)
    }

    func listMessagesV2(sessionID: String, directory: String? = nil) async throws -> [V2SessionMessageDTO] {
        var messages: [V2SessionMessageDTO] = []
        var cursor: String?
        var pages = 0
        repeat {
            // Cursor tokens already encode order. Sending `order` with `cursor`
            // returns InvalidCursorError 400 on live v2.
            var query: [String: String] = [:]
            if let cursor {
                query["cursor"] = cursor
            } else {
                query["order"] = "asc"
            }
            let envelope: APIListEnvelope<V2SessionMessageDTO> = try await get(
                path: "api/session/\(sessionID)/message",
                directory: nil,
                query: query
            )
            messages.append(contentsOf: envelope.data)
            cursor = envelope.cursor?.next.flatMap { $0.isEmpty ? nil : $0 }
            pages += 1
        } while cursor != nil && pages < Self.maxV2MessageListPages
        return messages
    }

    func getMessage(sessionID: String, messageID: String, directory: String? = nil) async throws -> MessageDTO {
        try await get(path: "session/\(sessionID)/message/\(messageID)", directory: directory)
    }

    func sendMessage(sessionID: String, body: SendMessageRequestDTO, directory: String? = nil) async throws -> MessageDTO {
        try await post(path: "session/\(sessionID)/message", body: body, directory: directory)
    }

    func sendPromptAsync(sessionID: String, body: SendMessageRequestDTO, directory: String? = nil) async throws {
        var request = try requireConnection().authorizedRequest(path: "session/\(sessionID)/prompt_async", method: "POST", directory: directory)
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try encoder.encode(body)
        _ = try await data(for: request)
    }

    func sendPromptV2(sessionID: String, body: V2PromptRequestDTO, directory: String? = nil) async throws {
        try await postNoContent(path: "api/session/\(sessionID)/prompt", body: body, directory: nil)
    }

    func switchSessionAgent(sessionID: String, agent: String, directory: String? = nil) async throws {
        try await postNoContent(
            path: "api/session/\(sessionID)/agent",
            body: V2SwitchAgentRequestDTO(agent: agent),
            directory: nil
        )
    }

    func switchSessionModel(sessionID: String, body: V2SwitchModelRequestDTO, directory: String? = nil) async throws {
        try await postNoContent(path: "api/session/\(sessionID)/model", body: body, directory: nil)
    }

    private func postNoContent<B: Encodable>(path: String, body: B, directory: String?) async throws {
        var request = try requireConnection().authorizedRequest(path: path, method: "POST", directory: directory)
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try encoder.encode(body)
        _ = try await data(for: request)
    }

    private func get<T: Decodable>(
        path: String,
        directory: String? = nil,
        query: [String: String] = [:]
    ) async throws -> T {
        var request = try requireConnection().authorizedRequest(path: path, directory: directory)
        if !query.isEmpty, let url = request.url,
           var components = URLComponents(url: url, resolvingAgainstBaseURL: false) {
            var items = components.queryItems ?? []
            query.forEach { name, value in
                items.append(URLQueryItem(name: name, value: value))
            }
            components.queryItems = items
            request.url = components.url
        }
        let data = try await data(for: request)
        return try decoder.decode(T.self, from: data)
    }

    private func post<T: Decodable, B: Encodable>(path: String, body: B, directory: String? = nil) async throws -> T {
        var request = try requireConnection().authorizedRequest(path: path, method: "POST", directory: directory)
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try encoder.encode(body)
        return try await send(request)
    }

    private func send<T: Decodable>(_ request: URLRequest) async throws -> T {
        let data = try await data(for: request)
        return try decoder.decode(T.self, from: data)
    }

    private func data(for request: URLRequest) async throws -> Data {
        let startedAt = OpenCodeAPITrace.start(request)
        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await urlSession.data(for: request)
        } catch {
            OpenCodeAPITrace.failure(request, error: error, startedAt: startedAt)
            throw error
        }
        guard let http = response as? HTTPURLResponse else {
            OpenCodeAPITrace.invalidResponse(request, startedAt: startedAt)
            throw MessagesAPIError.invalidResponse
        }
        OpenCodeAPITrace.finish(request, statusCode: http.statusCode, startedAt: startedAt)
        guard (200 ..< 300).contains(http.statusCode) else {
            let message = String(data: data, encoding: .utf8)
            throw MessagesAPIError.httpStatus(http.statusCode, message)
        }
        return data
    }

    private static let maxV2MessageListPages = 20
}
