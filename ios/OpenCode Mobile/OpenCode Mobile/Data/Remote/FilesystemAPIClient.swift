import Foundation

struct FilesystemAPIClient: Sendable {
    private let connectionProvider: @Sendable () -> ServerConnection?
    private let decoder = JSONDecoder()

    init(connectionProvider: @escaping @Sendable () -> ServerConnection?) {
        self.connectionProvider = connectionProvider
    }

    init(fixedConnection: ServerConnection) {
        self.connectionProvider = { fixedConnection }
    }

    func getPaths() async throws -> PathDTO {
        try await get(path: "path")
    }

    // Lists absoluteDirectory via path=. + directory= so paths outside the boot worktree work.
    func listFiles(absoluteDirectory: String) async throws -> [FileNodeDTO] {
        guard let connection = connectionProvider() else {
            throw ProjectsAPIError.noActiveConnection
        }
        var request = connection.authorizedRequest(path: "file", directory: absoluteDirectory)
        if let url = request.url, var components = URLComponents(url: url, resolvingAgainstBaseURL: false) {
            var items = components.queryItems ?? []
            items.append(URLQueryItem(name: "path", value: "."))
            components.queryItems = items
            request.url = components.url
        }
        let data = try await data(for: request)
        return try decoder.decode([FileNodeDTO].self, from: data)
    }

    private func get<T: Decodable>(path: String) async throws -> T {
        guard let connection = connectionProvider() else {
            throw ProjectsAPIError.noActiveConnection
        }
        let data = try await data(for: connection.authorizedRequest(path: path))
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
