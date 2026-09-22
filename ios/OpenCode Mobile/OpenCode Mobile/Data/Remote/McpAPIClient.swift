import Foundation

nonisolated enum McpAPIError: Error {
    case invalidResponse
    case httpStatus(Int, String?)
    case noActiveConnection
}

nonisolated struct McpAPIClient: Sendable {
    private let connectionProvider: @Sendable () -> ServerConnection?
    private let urlSession: URLSession
    private let decoder = JSONDecoder()

    init(
        connectionProvider: @escaping @Sendable () -> ServerConnection?,
        urlSession: URLSession = OpenCodeURLSessions.rest
    ) {
        self.connectionProvider = connectionProvider
        self.urlSession = urlSession
    }

    func listServers(directory: String? = nil) async throws -> [String: McpStatusDTO] {
        try await get(path: "mcp", directory: directory)
    }

    func listServersV2(directory: String? = nil) async throws -> [V2McpServerDTO] {
        let envelope: APIListEnvelope<V2McpServerDTO> = try await get(
            path: "api/mcp",
            locationDirectory: directory
        )
        return envelope.data
    }

    private func requireConnection() throws -> ServerConnection {
        guard let connection = connectionProvider() else {
            throw McpAPIError.noActiveConnection
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
            throw McpAPIError.invalidResponse
        }
        OpenCodeAPITrace.finish(request, statusCode: http.statusCode, startedAt: startedAt)
        guard (200 ..< 300).contains(http.statusCode) else {
            let message = String(data: data, encoding: .utf8)
            throw McpAPIError.httpStatus(http.statusCode, message)
        }
        return data
    }
}
