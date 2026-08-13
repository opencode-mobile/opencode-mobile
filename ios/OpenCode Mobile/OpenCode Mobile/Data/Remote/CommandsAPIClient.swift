import Foundation

nonisolated enum CommandsAPIError: Error {
    case invalidResponse
    case httpStatus(Int, String?)
    case noActiveConnection
}

nonisolated struct CommandsAPIClient: Sendable {
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

    func listCommands(directory: String? = nil) async throws -> [CommandDTO] {
        try await get(path: "command", directory: directory)
    }

    func listCommandsV2(directory: String? = nil) async throws -> [V2CommandDTO] {
        let envelope: APIListEnvelope<V2CommandDTO> = try await get(
            path: "api/command",
            locationDirectory: directory
        )
        return envelope.data
    }

    func runCommand(sessionID: String, body: RunCommandRequestDTO, directory: String? = nil) async throws {
        try await post(path: "session/\(sessionID)/command", body: body, directory: directory)
    }

    func runCommandV2(sessionID: String, body: RunCommandRequestDTO, directory: String? = nil) async throws {
        try await post(
            path: "api/session/\(sessionID)/command",
            body: body,
            locationDirectory: directory
        )
    }

    private func requireConnection() throws -> ServerConnection {
        guard let connection = connectionProvider() else {
            throw CommandsAPIError.noActiveConnection
        }
        return connection
    }

    private func get<T: Decodable>(
        path: String,
        directory: String? = nil,
        locationDirectory: String? = nil
    ) async throws -> T {
        let data = try await data(
            for: try requireConnection().authorizedRequest(
                path: path,
                directory: directory,
                locationDirectory: locationDirectory
            )
        )
        return try decoder.decode(T.self, from: data)
    }

    private func post<B: Encodable>(
        path: String,
        body: B,
        directory: String? = nil,
        locationDirectory: String? = nil
    ) async throws {
        var request = try requireConnection().authorizedRequest(
            path: path,
            method: "POST",
            directory: directory,
            locationDirectory: locationDirectory
        )
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try encoder.encode(body)
        _ = try await data(for: request)
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
