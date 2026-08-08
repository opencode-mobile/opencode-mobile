import Foundation

nonisolated enum CommandsAPIError: Error {
    case invalidResponse
    case httpStatus(Int, String?)
    case noActiveConnection
}

nonisolated struct CommandsAPIClient: Sendable {
    private let connectionProvider: @Sendable () -> ServerConnection?
    private let decoder = JSONDecoder()
    private let encoder = JSONEncoder()

    init(connectionProvider: @escaping @Sendable () -> ServerConnection?) {
        self.connectionProvider = connectionProvider
    }

    func listCommands(directory: String? = nil) async throws -> [CommandDTO] {
        try await get(path: "command", directory: directory)
    }

    func runCommand(sessionID: String, body: RunCommandRequestDTO, directory: String? = nil) async throws {
        var request = try requireConnection().authorizedRequest(
            path: "session/\(sessionID)/command",
            method: "POST",
            directory: directory
        )
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try encoder.encode(body)
        _ = try await data(for: request)
    }

    private func requireConnection() throws -> ServerConnection {
        guard let connection = connectionProvider() else {
            throw CommandsAPIError.noActiveConnection
        }
        return connection
    }

    private func get<T: Decodable>(path: String, directory: String? = nil) async throws -> T {
        let data = try await data(for: try requireConnection().authorizedRequest(path: path, directory: directory))
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
            throw CommandsAPIError.invalidResponse
        }
        OpenCodeAPITrace.finish(request, statusCode: http.statusCode, startedAt: startedAt)
        guard (200 ..< 300).contains(http.statusCode) else {
            let message = String(data: data, encoding: .utf8)
            throw CommandsAPIError.httpStatus(http.statusCode, message)
        }
        return data
    }
}
