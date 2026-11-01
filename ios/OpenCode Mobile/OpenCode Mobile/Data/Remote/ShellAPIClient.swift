import Foundation

nonisolated enum ShellAPIError: Error, Equatable {
    case invalidResponse
    case httpStatus(Int, String?)
    case noActiveConnection

    /// The shell is gone (removed, or stopped and cleaned up).
    var isNotFound: Bool {
        if case .httpStatus(404, _) = self { return true }
        return false
    }
}

/// v2 agent shell routes (`/api/shell`), scoped by `location[directory]`. v1 has none.
nonisolated struct ShellAPIClient: Sendable {
    private let connectionProvider: @Sendable () -> ServerConnection?
    private let resolveURLSession: @Sendable () -> URLSession
    private let decoder = JSONDecoder()

    init(
        connectionProvider: @escaping @Sendable () -> ServerConnection?,
        urlSession: URLSession? = nil
    ) {
        self.connectionProvider = connectionProvider
        self.resolveURLSession = OpenCodeURLSessions.restResolver(injected: urlSession)
    }

    /// Running shells of `directory` (exited ones are not listed).
    func list(directory: String) async throws -> [ShellInfoDTO] {
        let data = try await data(for: try request(path: "api/shell", directory: directory))
        return try decoder.decode(APIListEnvelope<ShellInfoDTO>.self, from: data).data
    }

    /// One shell, with its exit code once exited. 404 once removed.
    func get(id: String, directory: String) async throws -> ShellInfoDTO {
        let data = try await data(for: try request(path: "api/shell/\(id)", directory: directory))
        return try decoder.decode(APIDataEnvelope<ShellInfoDTO>.self, from: data).data
    }

    /// The page of output from byte `cursor`; a cursor past the end answers an empty page with the size.
    func output(id: String, directory: String, cursor: Int64) async throws -> ShellOutputPageDTO {
        var request = try request(path: "api/shell/\(id)/output", directory: directory)
        if let url = request.url, var components = URLComponents(url: url, resolvingAgainstBaseURL: false) {
            components.queryItems = (components.queryItems ?? []) + [URLQueryItem(name: "cursor", value: String(cursor))]
            request.url = components.url ?? url
        }
        let data = try await data(for: request)
        return try decoder.decode(APIDataEnvelope<ShellOutputPageDTO>.self, from: data).data
    }

    /// Kills and removes the shell and its output (204).
    func delete(id: String, directory: String) async throws {
        _ = try await data(for: try request(path: "api/shell/\(id)", method: "DELETE", directory: directory))
    }

    private func request(path: String, method: String = "GET", directory: String) throws -> URLRequest {
        guard let connection = connectionProvider() else { throw ShellAPIError.noActiveConnection }
        return connection.authorizedRequest(path: path, method: method, locationDirectory: directory)
    }

    private func data(for request: URLRequest) async throws -> Data {
        let startedAt = OpenCodeAPITrace.start(request)
        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await resolveURLSession().data(for: request)
        } catch {
            OpenCodeAPITrace.failure(request, error: error, startedAt: startedAt)
            throw error
        }
        guard let http = response as? HTTPURLResponse else {
            OpenCodeAPITrace.invalidResponse(request, startedAt: startedAt)
            throw ShellAPIError.invalidResponse
        }
        OpenCodeAPITrace.finish(request, statusCode: http.statusCode, startedAt: startedAt)
        guard (200 ..< 300).contains(http.statusCode) else {
            throw ShellAPIError.httpStatus(http.statusCode, String(data: data, encoding: .utf8))
        }
        return data
    }
}
