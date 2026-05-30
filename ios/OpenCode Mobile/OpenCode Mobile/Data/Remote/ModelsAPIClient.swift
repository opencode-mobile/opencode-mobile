import Foundation

enum ModelsAPIError: Error {
    case invalidResponse
    case httpStatus(Int, String?)
    case noActiveConnection
}

struct ModelsAPIClient: Sendable {
    private let connectionProvider: @Sendable () -> ServerConnection?
    private let decoder = JSONDecoder()

    init(connectionProvider: @escaping @Sendable () -> ServerConnection?) {
        self.connectionProvider = connectionProvider
    }

    func listProviders(directory: String? = nil) async throws -> ConfigProvidersResponseDTO {
        let connection = try requireConnection()
        let request = connection.authorizedRequest(path: "config/providers", directory: directory)
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse else {
            throw ModelsAPIError.invalidResponse
        }
        guard (200 ..< 300).contains(http.statusCode) else {
            throw ModelsAPIError.httpStatus(http.statusCode, String(data: data, encoding: .utf8))
        }
        return try decoder.decode(ConfigProvidersResponseDTO.self, from: data)
    }

    private func requireConnection() throws -> ServerConnection {
        guard let connection = connectionProvider() else {
            throw ModelsAPIError.noActiveConnection
        }
        return connection
    }
}
