import Foundation

nonisolated enum SessionsAPIError: Error {
    case invalidResponse
    case httpStatus(Int, String?)
    case noActiveConnection
}

nonisolated struct SessionsAPIClient: Sendable {
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

    func listPendingPermissionsV2(directory: String? = nil) async throws -> [V2PermissionRequestDTO] {
        let envelope: APIListEnvelope<V2PermissionRequestDTO> = try await get(
            path: "api/permission/request",
            locationDirectory: directory
        )
        return envelope.data
    }

    func listPendingQuestions(directory: String? = nil) async throws -> [QuestionRequestDTO] {
        try await get(path: "question", directory: directory)
    }

    func listPendingFormsV2(directory: String? = nil) async throws -> [V2FormInfoDTO] {
        let envelope: APIListEnvelope<V2FormInfoDTO> = try await get(
            path: "api/form",
            locationDirectory: directory
        )
        return envelope.data
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
            var raw: [String: String] = [:]
            for (key, value) in dict {
                if let nested = value as? [String: Any] {
                    if key == "action" {
                        if let label = nested["label"] as? String, !label.isEmpty { raw["actionLabel"] = label }
                        if let link = nested["link"] as? String, !link.isEmpty { raw["actionLink"] = link }
                    } else if key == "error" {
                        if let type = nested["type"] as? String, !type.isEmpty { raw["errorType"] = type }
                        if raw["message"] == nil, let message = nested["message"] as? String {
                            let unwrapped = SessionFailure.unwrap(message)
                            if !unwrapped.isEmpty { raw["message"] = unwrapped }
                        }
                    }
                } else {
                    raw[key] = String(describing: value)
                }
            }
            return raw
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

    func interruptSessionV2(id: String) async throws -> Bool {
        try await postWithoutBody(path: "api/session/\(id)/interrupt")
    }

    /// `POST /api/session/{id}/view` → 204. Moves the server-wide viewed watermark to `idle`.
    func markSessionViewedV2(id: String, idle: Int64) async throws -> Bool {
        try await postEmpty(path: "api/session/\(id)/view", body: V2ViewSessionRequestDTO(idle: idle))
    }

    /// `POST /api/session/{id}/move` → 204. Moves the session to `directory` at the server's
    /// default delivery boundary (steer); the body carries nothing else.
    func moveSessionV2(id: String, directory: String) async throws -> Bool {
        try await postEmpty(path: "api/session/\(id)/move", body: V2MoveSessionRequestDTO(directory: directory))
    }

    func cancelInboxV2(sessionID: String, inboxID: String) async throws -> Bool {
        try await delete(path: "api/session/\(sessionID)/inbox/\(inboxID)")
    }

    func steerInboxV2(sessionID: String, inboxID: String) async throws -> Bool {
        try await patchEmpty(
            path: "api/session/\(sessionID)/inbox/\(inboxID)",
            body: V2InboxDeliveryRequestDTO(delivery: "steer")
        )
    }

    func queueInboxV2(sessionID: String, inboxID: String) async throws -> Bool {
        try await patchEmpty(
            path: "api/session/\(sessionID)/inbox/\(inboxID)",
            body: V2InboxDeliveryRequestDTO(delivery: "queue")
        )
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

    func getVcsStatus(directory: String? = nil) async throws -> [VcsFileStatusDTO] {
        try await get(path: "vcs/status", directory: directory)
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
        // Compaction can run longer than OpenCodeURLSessions.rest's 15s request timeout.
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
        sessionID _: String,
        permissionID: String,
        body: PermissionResponseRequestDTO,
        directory: String? = nil
    ) async throws -> Bool {
        try await postEmpty(path: "permission/\(permissionID)/reply", body: body, directory: directory)
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

    func respondToPermissionV2(
        sessionID: String,
        permissionID: String,
        body: V2PermissionReplyRequestDTO
    ) async throws -> Bool {
        try await postEmpty(
            path: "api/session/\(sessionID)/permission/\(permissionID)/reply",
            body: body
        )
    }

    func replyToFormV2(sessionID: String, formID: String, body: V2FormReplyRequestDTO) async throws -> Bool {
        try await postEmpty(path: "api/session/\(sessionID)/form/\(formID)/reply", body: body)
    }

    func cancelFormV2(sessionID: String, formID: String) async throws -> Bool {
        try await delete(path: "api/session/\(sessionID)/form/\(formID)")
    }

    // MARK: - V2 home / session CRUD

    func locationForSessionList(directory: String) async throws -> V2LocationInfoDTO {
        try await get(path: "api/location", locationDirectory: directory)
    }

    /// `projectID` lists every worktree of that project. Passing `directory` as well would hide those sessions.
    func listSessionsV2(
        directory: String? = nil,
        parentID: String? = nil,
        projectID: String? = nil,
        archived: Bool? = nil
    ) async throws -> [V2SessionDTO] {
        var sessions: [V2SessionDTO] = []
        var cursor: String?
        var pages = 0
        repeat {
            var query: [String: String] = [:]
            if let parentID {
                query["parentID"] = parentID
            }
            if let projectID {
                query["project"] = projectID
            }
            if let archived {
                query["archived"] = archived ? "true" : "false"
            }
            if let cursor {
                query["cursor"] = cursor
            }
            let envelope: APIListEnvelope<V2SessionDTO> = try await get(
                path: "api/session",
                directory: projectID == nil ? directory : nil,
                query: query
            )
            sessions.append(contentsOf: envelope.data)
            let next = envelope.cursor?.next.flatMap { $0.isEmpty ? nil : $0 }
            if next == cursor { break }
            cursor = next
            pages += 1
        } while cursor != nil && pages < Self.maxV2SessionListPages
        return sessions
    }

    /// A `parentID` creates a linked child at the parent's location, so no location is sent with it.
    func createSessionV2(directory: String? = nil, title: String? = nil, parentID: String? = nil) async throws -> V2SessionDTO {
        let envelope: APIDataEnvelope<V2SessionDTO> = try await post(
            path: "api/session",
            body: V2CreateSessionRequestDTO(
                parentID: parentID,
                title: title.flatMap { $0.isEmpty ? nil : $0 },
                location: parentID == nil ? directory.map { V2LocationDTO(directory: $0) } : nil
            )
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
        _ = try await patchEmpty(path: "api/session/\(id)", body: V2RenameSessionRequestDTO(title: title))
    }

    /// Archives or restores a session (opencode 2.1.0+). Repeating the current state is a no-op.
    func setSessionArchivedV2(id: String, archived: Bool) async throws {
        _ = try await patchEmpty(path: "api/session/\(id)", body: V2ArchiveSessionRequestDTO(archived: archived))
    }

    func deleteSessionV2(id: String) async throws -> Bool {
        try await delete(path: "api/session/\(id)")
    }

    func forkSessionV2(id: String, messageID: String?) async throws -> V2SessionDTO {
        let before = messageID.flatMap { value in
            value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : value
        }
        let envelope: APIDataEnvelope<V2SessionDTO> = try await post(
            path: "api/session/\(id)/fork",
            body: V2ForkSessionRequestDTO(before: before)
        )
        return envelope.data
    }

    func compactSessionV2(id: String) async throws -> Bool {
        var request = try requireConnection().authorizedRequest(path: "api/session/\(id)/compact", method: "POST")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try encoder.encode(V2CompactSessionRequestDTO())
        request.timeoutInterval = .infinity
        let data = try await data(for: request)
        if let value = try? decoder.decode(Bool.self, from: data) {
            return value
        }
        return true
    }

    func stageRevertV2(id: String, messageID: String, partID: String?) async throws -> Bool {
        try await postEmpty(
            path: "api/session/\(id)/revert/stage",
            body: V2RevertStageRequestDTO(messageID: messageID)
        )
    }

    func clearRevertV2(id: String) async throws -> Bool {
        try await delete(path: "api/session/\(id)/revert")
    }

    func commitRevertV2(id: String) async throws -> Bool {
        try await postWithoutBody(path: "api/session/\(id)/revert/commit")
    }

    func getVcsInfoV2(directory: String? = nil) async throws -> V2VcsInfoDTO {
        let envelope: APILocatedEnvelope<V2VcsInfoDTO> = try await get(
            path: "api/vcs",
            locationDirectory: directory
        )
        return envelope.data
    }

    func getVcsStatusV2(directory: String? = nil) async throws -> [VcsFileStatusDTO] {
        let envelope: APILocatedEnvelope<[VcsFileStatusDTO]> = try await get(
            path: "api/vcs/status",
            locationDirectory: directory
        )
        return envelope.data
    }

    func getVcsDiffV2(mode: String, directory: String? = nil, context: Int = 12) async throws -> [FileDiffDTO] {
        let envelope: APILocatedEnvelope<[FileDiffDTO]> = try await get(
            path: "api/vcs/diff",
            locationDirectory: directory,
            query: ["mode": mode, "context": String(context)]
        )
        return envelope.data
    }

    /// `GET /api/session/{id}/diff`: the files the turn opened by user message `from` changed.
    /// Servers before opencode 2.1.0 have no route and answer 404.
    func getSessionDiffV2(id: String, from: String?, context: Int = 12) async throws -> [FileDiffDTO] {
        var query = ["context": String(context)]
        if let from { query["from"] = from }
        let envelope: APIListEnvelope<FileDiffDTO> = try await get(path: "api/session/\(id)/diff", query: query)
        return envelope.data
    }

    private struct EmptyBody: Codable {}
    private static let maxV2SessionListPages = 500

    private func get<T: Decodable>(
        path: String,
        directory: String? = nil,
        locationDirectory: String? = nil,
        query: [String: String] = [:]
    ) async throws -> T {
        var request = try requireConnection().authorizedRequest(
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

    private func patchEmpty<B: Encodable>(path: String, body: B, directory: String? = nil) async throws -> Bool {
        var request = try requireConnection().authorizedRequest(path: path, method: "PATCH", directory: directory)
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try encoder.encode(body)
        let data = try await data(for: request)
        if let value = try? decoder.decode(Bool.self, from: data) {
            return value
        }
        return true
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
            (data, response) = try await resolveURLSession().data(for: request)
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
