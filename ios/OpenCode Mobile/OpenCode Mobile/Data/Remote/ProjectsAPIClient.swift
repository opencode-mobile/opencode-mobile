import Foundation

enum ProjectsAPIError: Error {
    case invalidResponse
    case httpStatus(Int, String?)
    case noActiveConnection
}

struct ProjectsAPIClient: Sendable {
    private let connectionProvider: @Sendable () -> ServerConnection?
    private let decoder = JSONDecoder()

    init(connectionProvider: @escaping @Sendable () -> ServerConnection?) {
        self.connectionProvider = connectionProvider
    }

    init(fixedConnection: ServerConnection) {
        self.connectionProvider = { fixedConnection }
    }

    func listProjects() async throws -> [ProjectDTO] {
        try await get(path: "project")
    }

    func getCurrentProject(directory: String? = nil) async throws -> ProjectDTO {
        try await get(path: "project/current", directory: directory)
    }

    func getHealth() async throws -> HealthDTO {
        try await get(path: "global/health")
    }

    private func get<T: Decodable>(path: String, directory: String? = nil) async throws -> T {
        guard let connection = connectionProvider() else {
            throw ProjectsAPIError.noActiveConnection
        }
        let data = try await data(for: connection.authorizedRequest(path: path, directory: directory))
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
            throw ProjectsAPIError.invalidResponse
        }
        OpenCodeAPITrace.finish(request, statusCode: http.statusCode, startedAt: startedAt)
        guard (200 ..< 300).contains(http.statusCode) else {
            let message = String(data: data, encoding: .utf8)
            throw ProjectsAPIError.httpStatus(http.statusCode, message)
        }
        return data
    }
}
