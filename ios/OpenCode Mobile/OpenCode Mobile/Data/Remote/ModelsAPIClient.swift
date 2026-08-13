import Foundation

nonisolated enum ModelsAPIError: Error {
    case invalidResponse
    case httpStatus(Int, String?)
    case noActiveConnection
}

nonisolated struct ModelsAPIClient: Sendable {
    private let connectionProvider: @Sendable () -> ServerConnection?
    private let urlSession: URLSession
    private let decoder = JSONDecoder()

    init(
        connectionProvider: @escaping @Sendable () -> ServerConnection?,
        urlSession: URLSession = .shared
    ) {
        self.connectionProvider = connectionProvider
        self.urlSession = urlSession
    }

    func listProviders(directory: String? = nil) async throws -> ConfigProvidersResponseDTO {
        try await get(path: "config/providers", directory: directory)
    }

    func listProvidersV2(directory: String? = nil) async throws -> [V2ProviderInfoDTO] {
        let envelope: APIListEnvelope<V2ProviderInfoDTO> = try await get(
            path: "api/provider",
            locationDirectory: directory
        )
        return envelope.data
    }

    func listModelsV2(directory: String? = nil) async throws -> [V2ModelInfoDTO] {
        let envelope: APIListEnvelope<V2ModelInfoDTO> = try await get(
            path: "api/model",
            locationDirectory: directory
        )
        return envelope.data
    }

    private func requireConnection() throws -> ServerConnection {
        guard let connection = connectionProvider() else {
            throw ModelsAPIError.noActiveConnection
        }
        return connection
    }

    private func get<T: Decodable>(
        path: String,
        directory: String? = nil,
        locationDirectory: String? = nil
    ) async throws -> T {
        let request = try requireConnection().authorizedRequest(
            path: path,
            directory: directory,
            locationDirectory: locationDirectory
        )
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
            throw ModelsAPIError.invalidResponse
        }
        OpenCodeAPITrace.finish(request, statusCode: http.statusCode, startedAt: startedAt)
        guard (200 ..< 300).contains(http.statusCode) else {
            throw ModelsAPIError.httpStatus(http.statusCode, String(data: data, encoding: .utf8))
        }
        return try decoder.decode(T.self, from: data)
    }
}
