import Foundation

enum MessagesAPIError: Error {
    case invalidResponse
    case httpStatus(Int, String?)
}

struct MessagesAPIClient: Sendable {
    let connection: ServerConnection
    private let decoder = JSONDecoder()
    private let encoder = JSONEncoder()

    func listMessages(sessionID: String) async throws -> [MessageDTO] {
        try await get(path: "session/\(sessionID)/message")
    }

    func getMessage(sessionID: String, messageID: String) async throws -> MessageDTO {
        try await get(path: "session/\(sessionID)/message/\(messageID)")
    }

    func sendMessage(sessionID: String, body: SendMessageRequestDTO) async throws -> MessageDTO {
        try await post(path: "session/\(sessionID)/message", body: body)
    }

    func sendPromptAsync(sessionID: String, body: SendMessageRequestDTO) async throws {
        var request = connection.authorizedRequest(path: "session/\(sessionID)/prompt_async", method: "POST")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try encoder.encode(body)
        _ = try await data(for: request)
    }

    private func get<T: Decodable>(path: String) async throws -> T {
        let data = try await data(for: connection.authorizedRequest(path: path))
        return try decoder.decode(T.self, from: data)
    }

    private func post<T: Decodable, B: Encodable>(path: String, body: B) async throws -> T {
        var request = connection.authorizedRequest(path: path, method: "POST")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try encoder.encode(body)
        return try await send(request)
    }

    private func send<T: Decodable>(_ request: URLRequest) async throws -> T {
        let data = try await data(for: request)
        return try decoder.decode(T.self, from: data)
    }

    private func data(for request: URLRequest) async throws -> Data {
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse else {
            throw MessagesAPIError.invalidResponse
        }
        guard (200 ..< 300).contains(http.statusCode) else {
            let message = String(data: data, encoding: .utf8)
            throw MessagesAPIError.httpStatus(http.statusCode, message)
        }
        return data
    }
}
