import Foundation

nonisolated struct FilesystemAPIClient: Sendable {
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

    init(fixedConnection: ServerConnection, urlSession: URLSession = .shared) {
        self.connectionProvider = { fixedConnection }
        self.urlSession = urlSession
    }

    func getPaths() async throws -> PathDTO {
        try await get(path: "path")
    }

    // Lists absoluteDirectory via path=. + directory= so paths outside the boot worktree work.
    func listFiles(absoluteDirectory: String) async throws -> [FileNodeDTO] {
        try await get(path: "file", directory: absoluteDirectory, query: ["path": "."])
    }

    func getLocationV2() async throws -> V2LocationInfoDTO {
        try await get(path: "api/location")
    }

    func listFilesV2(absoluteDirectory: String) async throws -> [V2FsEntryDTO] {
        let envelope: APILocatedEnvelope<[V2FsEntryDTO]> = try await get(
            path: "api/fs/list",
            locationDirectory: absoluteDirectory,
            query: ["path": "."]
        )
        return envelope.data
    }

    private func get<T: Decodable>(
        path: String,
        directory: String? = nil,
        locationDirectory: String? = nil,
        query: [String: String] = [:]
    ) async throws -> T {
        guard let connection = connectionProvider() else {
            throw ProjectsAPIError.noActiveConnection
        }
        var request = connection.authorizedRequest(
            path: path,
            directory: directory,
            locationDirectory: locationDirectory
        )
        if !query.isEmpty, let url = request.url,
           var components = URLComponents(url: url, resolvingAgainstBaseURL: false) {
            var items = components.queryItems ?? []
            query.forEach { name, value in
                items.append(URLQueryItem(name: name, value: value))
            }
            components.queryItems = items
            request.url = components.url
        }
        let data = try await data(for: request)
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
