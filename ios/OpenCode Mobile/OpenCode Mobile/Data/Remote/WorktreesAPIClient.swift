import Foundation

nonisolated struct WorktreesAPIClient: WorktreeRemote, Sendable {
    private let connectionProvider: @Sendable () -> ServerConnection?
    private let resolveURLSession: @Sendable () -> URLSession
    private let decoder = JSONDecoder()
    private let encoder = JSONEncoder()

    init(
        connectionProvider: @escaping @Sendable () -> ServerConnection?,
        urlSession: URLSession? = nil
    ) {
        self.connectionProvider = connectionProvider
        self.resolveURLSession = OpenCodeURLSessions.restResolver(injected: urlSession)
    }

    func resolvedURLSession() -> URLSession {
        resolveURLSession()
    }

    func listV2(projectID: String) async throws -> [String] {
        guard let connection = connectionProvider() else { throw WorktreesAPIError.noActiveConnection }
        var request = connection.authorizedRequest(path: "api/worktree")
        if let url = request.url, var components = URLComponents(url: url, resolvingAgainstBaseURL: false) {
            var items = components.queryItems ?? []
            items.append(URLQueryItem(name: "projectID", value: projectID))
            components.queryItems = items
            request.url = components.url
        }
        let data = try await data(for: request)
        let listed = try decoder.decode([WorktreeDirectoryDTO].self, from: data)
        return listed.map(\.directory)
    }

    func refreshV2(projectID: String) async throws {
        guard let connection = connectionProvider() else { throw WorktreesAPIError.noActiveConnection }
        var request = connection.authorizedRequest(path: "api/worktree/refresh", method: "POST")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try encoder.encode(WorktreeRefreshBody(projectID: projectID))
        _ = try await data(for: request)
    }

    func listV1(directory: String) async -> V1WorktreeHTTP {
        guard let connection = connectionProvider() else { return .failed(-1) }
        let request = connection.authorizedRequest(path: "experimental/worktree", directory: directory)
        do {
            let (data, response) = try await resolveURLSession().data(for: request)
            guard let http = response as? HTTPURLResponse else { return .failed(-1) }
            if http.statusCode == 404 { return .notFound }
            guard (200 ..< 300).contains(http.statusCode) else { return .failed(http.statusCode) }
            return .ok(String(data: data, encoding: .utf8) ?? "")
        } catch {
            return .failed(-1)
        }
    }

    func createV2(projectID: String, from: String, name: String, branch: String?) async throws -> String {
        guard let connection = connectionProvider() else { throw WorktreeRemoteError.noActiveConnection }
        var request = connection.authorizedRequest(path: "api/worktree", method: "POST")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try encoder.encode(
            WorktreeCreateV2Body(projectID: projectID, from: from, name: name, branch: branch)
        )
        let data = try await bodyData(for: request)
        do {
            return try decoder.decode(WorktreeDirectoryDTO.self, from: data).directory
        } catch {
            throw WorktreeRemoteError.invalidResponse
        }
    }

    func createV1(directory: String, name: String) async throws -> CreatedWorktree {
        guard let connection = connectionProvider() else { throw WorktreeRemoteError.noActiveConnection }
        var request = connection.authorizedRequest(path: "experimental/worktree", method: "POST", directory: directory)
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try encoder.encode(WorktreeCreateV1Body(name: name))
        let data = try await bodyData(for: request)
        guard let created = try? decoder.decode(WorktreeCreatedV1DTO.self, from: data) else {
            throw WorktreeRemoteError.invalidResponse
        }
        return CreatedWorktree(directory: created.directory, name: created.name ?? "", branch: created.branch)
    }

    func branchesV2(directory: String) async throws -> [String] {
        guard let connection = connectionProvider() else { throw WorktreeRemoteError.noActiveConnection }
        let request = connection.authorizedRequest(path: "api/vcs/branch", locationDirectory: directory)
        let data = try await bodyData(for: request)
        if let located = try? decoder.decode(APILocatedEnvelope<[String]>.self, from: data) {
            return located.data
        }
        if let list = try? decoder.decode([String].self, from: data) {
            return list
        }
        throw WorktreeRemoteError.invalidResponse
    }

    /// Like `data(for:)`, keeping the error body so the server's words reach the user.
    private func bodyData(for request: URLRequest) async throws -> Data {
        let (data, response) = try await resolveURLSession().data(for: request)
        guard let http = response as? HTTPURLResponse else { throw WorktreeRemoteError.invalidResponse }
        guard (200 ..< 300).contains(http.statusCode) else {
            throw WorktreeRemoteError.http(statusCode: http.statusCode, body: String(data: data, encoding: .utf8) ?? "")
        }
        return data
    }

    private func data(for request: URLRequest) async throws -> Data {
        let (data, response) = try await resolveURLSession().data(for: request)
        guard let http = response as? HTTPURLResponse else { throw WorktreesAPIError.invalidResponse }
        guard (200 ..< 300).contains(http.statusCode) else {
            throw WorktreesAPIError.httpStatus(http.statusCode)
        }
        return data
    }
}

private nonisolated struct WorktreeDirectoryDTO: Decodable {
    let directory: String
}

private nonisolated struct WorktreeCreateV2Body: Encodable {
    let projectID: String
    let from: String
    let name: String
    /// Omitted when nil: the server starts from `from`'s HEAD.
    let branch: String?
}

private nonisolated struct WorktreeCreateV1Body: Encodable {
    let name: String
}

private nonisolated struct WorktreeCreatedV1DTO: Decodable {
    let name: String?
    let branch: String?
    let directory: String
}

private nonisolated struct WorktreeRefreshBody: Encodable {
    let projectID: String
}

private nonisolated enum WorktreesAPIError: Error {
    case invalidResponse
    case httpStatus(Int)
    case noActiveConnection
}
