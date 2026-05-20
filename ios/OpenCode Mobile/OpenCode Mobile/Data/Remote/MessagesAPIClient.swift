import Foundation

enum MessagesAPIError: Error {
    case invalidResponse
    case httpStatus(Int, String?)
}

struct MessagesAPIClient: Sendable {
    let connection: ServerConnection
    private let decoder = JSONDecoder()
    private let encoder = JSONEncoder()

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
        var request = connection.authorizedRequest(path: "session/\(sessionID)/prompt_async", method: "POST", directory: directory)
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try encoder.encode(body)
        _ = try await data(for: request)
    }

    private func get<T: Decodable>(path: String, directory: String? = nil) async throws -> T {
        let data = try await data(for: connection.authorizedRequest(path: path, directory: directory))
        return try decoder.decode(T.self, from: data)
    }

    private func post<T: Decodable, B: Encodable>(path: String, body: B, directory: String? = nil) async throws -> T {
        var request = connection.authorizedRequest(path: path, method: "POST", directory: directory)
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
