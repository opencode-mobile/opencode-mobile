import Foundation

enum ProjectsAPIError: Error {
    case invalidResponse
    case httpStatus(Int, String?)
}

struct ProjectsAPIClient: Sendable {
    let connection: ServerConnection
    private let decoder = JSONDecoder()

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
        let data = try await data(for: connection.authorizedRequest(path: path, directory: directory))
        return try decoder.decode(T.self, from: data)
    }

    private func data(for request: URLRequest) async throws -> Data {
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse else {
            throw ProjectsAPIError.invalidResponse
        }
        guard (200 ..< 300).contains(http.statusCode) else {
            let message = String(data: data, encoding: .utf8)
            throw ProjectsAPIError.httpStatus(http.statusCode, message)
        }
        return data
    }
}
