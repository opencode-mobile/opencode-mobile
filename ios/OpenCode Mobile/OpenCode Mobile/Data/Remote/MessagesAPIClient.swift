import Foundation

enum MessagesAPIError: Error {
    case invalidResponse
    case httpStatus(Int, String?)
    case noActiveConnection
}

struct MessagesAPIClient: Sendable {
    private let connectionProvider: @Sendable () -> ServerConnection?
    private let decoder = JSONDecoder()
    private let encoder = JSONEncoder()

    init(connectionProvider: @escaping @Sendable () -> ServerConnection?) {
        self.connectionProvider = connectionProvider
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

    private func get<T: Decodable>(path: String, directory: String? = nil) async throws -> T {
        let data = try await data(for: try requireConnection().authorizedRequest(path: path, directory: directory))
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
            (data, response) = try await URLSession.shared.data(for: request)
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
}
