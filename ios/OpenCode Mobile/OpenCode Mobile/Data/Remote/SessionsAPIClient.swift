import Foundation

nonisolated enum SessionsAPIError: Error {
    case invalidResponse
    case httpStatus(Int, String?)
    case noActiveConnection
}

nonisolated struct SessionsAPIClient: Sendable {
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

    private func requireConnection() throws -> ServerConnection {
        guard let connection = connectionProvider() else {
            throw SessionsAPIError.noActiveConnection
        }
        return connection
    }

    func listSessions(directory: String? = nil) async throws -> [SessionDTO] {
        try await get(path: "session", directory: directory)
    }

    func listPendingPermissions(directory: String? = nil) async throws -> [PermissionRequestDTO] {
        try await get(path: "permission", directory: directory)
    }

    func listPendingQuestions(directory: String? = nil) async throws -> [QuestionRequestDTO] {
        try await get(path: "question", directory: directory)
    }

    func createSession(parentID: String?, title: String?, directory: String? = nil) async throws -> SessionDTO {
        try await post(path: "session", body: CreateSessionRequestDTO(parentID: parentID, title: title), directory: directory)
    }

    func getAllSessionStatuses(directory: String? = nil) async throws -> [String: [String: String]] {
        let data = try await data(for: try requireConnection().authorizedRequest(path: "session/status", directory: directory))
        guard let json = try JSONSerialization.jsonObject(with: data) as? [String: [String: Any]] else {
            return [:]
        }
        return json.mapValues { dict in
            dict.mapValues { String(describing: $0) }
        }
    }

    func getSession(id: String, directory: String? = nil) async throws -> SessionDTO {
        try await get(path: "session/\(id)", directory: directory)
    }

    func updateSession(id: String, title: String?, directory: String? = nil) async throws -> SessionDTO {
        try await patch(path: "session/\(id)", body: UpdateSessionRequestDTO(title: title), directory: directory)
    }

    func archiveSession(id: String, directory: String? = nil) async throws -> SessionDTO {
        let archivedAt = Int64(Date().timeIntervalSince1970 * 1000)
        return try await patch(
            path: "session/\(id)",
            body: UpdateSessionRequestDTO(time: UpdateSessionTimeDTO(archived: archivedAt)),
            directory: directory
        )
    }

    func deleteSession(id: String, directory: String? = nil) async throws -> Bool {
        try await delete(path: "session/\(id)", directory: directory)
    }

    func listChildSessions(id: String, directory: String? = nil) async throws -> [SessionDTO] {
        try await get(path: "session/\(id)/children", directory: directory)
    }

    func listSessionTodos(id: String, directory: String? = nil) async throws -> [TodoDTO] {
        try await get(path: "session/\(id)/todo", directory: directory)
    }

    func initializeSessionAgents(id: String, body: InitSessionRequestDTO, directory: String? = nil) async throws -> Bool {
        try await postEmpty(path: "session/\(id)/init", body: body, directory: directory)
    }

    func forkSession(id: String, messageID: String?, directory: String? = nil) async throws -> SessionDTO {
        try await post(path: "session/\(id)/fork", body: ForkSessionRequestDTO(messageID: messageID), directory: directory)
    }

    func abortSession(id: String, directory: String? = nil) async throws -> Bool {
        try await postEmpty(path: "session/\(id)/abort", body: EmptyBody(), directory: directory)
    }

    func shareSession(id: String, directory: String? = nil) async throws -> SessionDTO {
        try await post(path: "session/\(id)/share", body: EmptyBody(), directory: directory)
    }

    func unshareSession(id: String, directory: String? = nil) async throws -> SessionDTO {
        let request = try requireConnection().authorizedRequest(path: "session/\(id)/share", method: "DELETE", directory: directory)
        return try await send(request)
    }

    func getSessionDiff(id: String, messageID: String?, directory: String? = nil) async throws -> [FileDiffDTO] {
        var request = try requireConnection().authorizedRequest(path: "session/\(id)/diff", directory: directory)
        if let messageID, let url = request.url,
           var components = URLComponents(url: url, resolvingAgainstBaseURL: false) {
            var items = components.queryItems ?? []
            items.append(URLQueryItem(name: "messageID", value: messageID))
            components.queryItems = items
            request.url = components.url
        }
        return try decoder.decode([FileDiffDTO].self, from: try await data(for: request))
    }

    func getVcsInfo(directory: String? = nil) async throws -> VcsInfoDTO {
        try await get(path: "vcs", directory: directory)
    }

    func getVcsDiff(mode: VcsDiffMode, directory: String? = nil, context: Int = 12) async throws -> [FileDiffDTO] {
        var request = try requireConnection().authorizedRequest(path: "vcs/diff", directory: directory)
        if let url = request.url, var components = URLComponents(url: url, resolvingAgainstBaseURL: false) {
            var items = components.queryItems ?? []
            items.append(URLQueryItem(name: "mode", value: mode.rawValue))
            items.append(URLQueryItem(name: "context", value: String(context)))
            components.queryItems = items
            request.url = components.url
        }
        return try decoder.decode([FileDiffDTO].self, from: try await data(for: request))
    }

    func summarizeSession(id: String, body: SummarizeSessionRequestDTO, directory: String? = nil) async throws -> Bool {
        var request = try requireConnection().authorizedRequest(path: "session/\(id)/summarize", method: "POST", directory: directory)
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try encoder.encode(body)
        // Compaction can run longer than URLSession.shared's 60s default.
        request.timeoutInterval = .infinity
        let data = try await data(for: request)
        if let value = try? decoder.decode(Bool.self, from: data) {
            return value
        }
        return true
    }

    func revertSession(id: String, body: RevertSessionRequestDTO, directory: String? = nil) async throws -> Bool {
        try await postEmpty(path: "session/\(id)/revert", body: body, directory: directory)
    }

    func unrevertSession(id: String, directory: String? = nil) async throws -> Bool {
        try await postEmpty(path: "session/\(id)/unrevert", body: EmptyBody(), directory: directory)
    }

    func respondToPermission(
        sessionID: String,
        permissionID: String,
        body: PermissionResponseRequestDTO,
        directory: String? = nil
    ) async throws -> Bool {
        try await postEmpty(path: "session/\(sessionID)/permissions/\(permissionID)", body: body, directory: directory)
    }

    func replyToQuestion(
        questionID: String,
        body: QuestionReplyRequestDTO,
        directory: String? = nil
    ) async throws -> Bool {
        try await postEmpty(path: "question/\(questionID)/reply", body: body, directory: directory)
    }

    func rejectQuestion(questionID: String, directory: String? = nil) async throws -> Bool {
        try await postEmpty(path: "question/\(questionID)/reject", body: EmptyBody(), directory: directory)
    }

    // MARK: - V2 home / session CRUD

    func listSessionsV2(directory: String? = nil) async throws -> [V2SessionDTO] {
        var sessions: [V2SessionDTO] = []
        var cursor: String?
        var pages = 0
        repeat {
            var query: [String: String] = [:]
            if let cursor {
                query["cursor"] = cursor
            }
            let envelope: APIListEnvelope<V2SessionDTO> = try await get(
                path: "api/session",
                directory: directory,
                query: query
            )
            sessions.append(contentsOf: envelope.data)
            cursor = envelope.cursor?.next.flatMap { $0.isEmpty ? nil : $0 }
            pages += 1
        } while cursor != nil && pages < Self.maxV2SessionListPages
        return sessions
    }

    func createSessionV2(directory: String? = nil) async throws -> V2SessionDTO {
        let envelope: APIDataEnvelope<V2SessionDTO> = try await post(
            path: "api/session",
            body: V2CreateSessionRequestDTO(location: directory.map { V2LocationDTO(directory: $0) })
        )
        return envelope.data
    }

    func getActiveSessionsV2() async throws -> [String: APISessionActiveDTO] {
        let envelope: APIActiveSessionsEnvelope = try await get(path: "api/session/active")
        return envelope.data
    }

    func getSessionV2(id: String) async throws -> V2SessionDTO {
        let envelope: APIDataEnvelope<V2SessionDTO> = try await get(path: "api/session/\(id)")
        return envelope.data
    }

    func renameSessionV2(id: String, title: String) async throws {
        _ = try await postEmpty(path: "api/session/\(id)/rename", body: V2RenameSessionRequestDTO(title: title))
    }

    func archiveSessionV2(id: String) async throws {
        _ = try await postWithoutBody(path: "api/session/\(id)/archive")
    }

    func deleteSessionV2(id: String) async throws -> Bool {
        try await delete(path: "api/session/\(id)")
    }

    func forkSessionV2(id: String, messageID: String?) async throws -> V2SessionDTO {
        let envelope: APIDataEnvelope<V2SessionDTO> = try await post(
            path: "api/session/\(id)/fork",
            body: V2ForkSessionRequestDTO(messageID: messageID)
        )
        return envelope.data
    }

    private struct EmptyBody: Codable {}
    private static let maxV2SessionListPages = 20

    private func get<T: Decodable>(
        path: String,
        directory: String? = nil,
        query: [String: String] = [:]
    ) async throws -> T {
        var request = try requireConnection().authorizedRequest(path: path, directory: directory)
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

    private func post<T: Decodable, B: Encodable>(path: String, body: B, directory: String? = nil) async throws -> T {
        var request = try requireConnection().authorizedRequest(path: path, method: "POST", directory: directory)
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try encoder.encode(body)
        return try await send(request)
    }

    private func postEmpty<B: Encodable>(path: String, body: B, directory: String? = nil) async throws -> Bool {
        var request = try requireConnection().authorizedRequest(path: path, method: "POST", directory: directory)
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try encoder.encode(body)
        let data = try await data(for: request)
        if let value = try? decoder.decode(Bool.self, from: data) {
            return value
        }
        return true
    }

    private func postWithoutBody(path: String, directory: String? = nil) async throws -> Bool {
        let request = try requireConnection().authorizedRequest(path: path, method: "POST", directory: directory)
        let data = try await data(for: request)
        if let value = try? decoder.decode(Bool.self, from: data) {
            return value
        }
        return true
    }

    private func patch<T: Decodable, B: Encodable>(path: String, body: B, directory: String? = nil) async throws -> T {
        var request = try requireConnection().authorizedRequest(path: path, method: "PATCH", directory: directory)
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try encoder.encode(body)
        return try await send(request)
    }

    private func delete(path: String, directory: String? = nil) async throws -> Bool {
        let request = try requireConnection().authorizedRequest(path: path, method: "DELETE", directory: directory)
        let data = try await data(for: request)
        if let value = try? decoder.decode(Bool.self, from: data) {
            return value
        }
        return true
    }

    private func send<T: Decodable>(_ request: URLRequest) async throws -> T {
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
            throw SessionsAPIError.invalidResponse
        }
        OpenCodeAPITrace.finish(request, statusCode: http.statusCode, startedAt: startedAt)
        guard (200 ..< 300).contains(http.statusCode) else {
            let message = String(data: data, encoding: .utf8)
            throw SessionsAPIError.httpStatus(http.statusCode, message)
        }
        return data
    }
}
