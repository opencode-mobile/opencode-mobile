import Foundation

public nonisolated enum SessionsAPIError: Error {
    case invalidResponse
    case httpStatus(Int, String?)
    case noActiveConnection
}

public nonisolated struct SessionsAPIClient: Sendable {
    private let connectionProvider: @Sendable () -> ServerConnection?
    private let resolveURLSession: @Sendable () -> URLSession
    private let decoder = JSONDecoder()
    private let encoder = JSONEncoder()

    public init(
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

    public func listSessions(directory: String? = nil) async throws -> [SessionDTO] {
        try await get(path: "session", directory: directory)
    }

    /// `search` matches titles; `rootsOnly` drops child sessions. Older servers ignore both.
    public func listSessions(directory: String?, search: String, rootsOnly: Bool) async throws -> [SessionDTO] {
        var query = ["search": search]
        if rootsOnly { query["roots"] = "true" }
        return try await get(path: "session", directory: directory, query: query)
    }

    /// `GET /session?roots=true&limit=`: the newest `limit` root sessions. Older servers ignore
    /// `roots` or `limit` and list more, children included.
    public func listRootSessions(directory: String?, limit: Int) async throws -> [SessionDTO] {
        try await get(path: "session", directory: directory, query: ["roots": "true", "limit": String(limit)])
    }

    public func listPendingPermissions(directory: String? = nil) async throws -> [PermissionRequestDTO] {
        try await get(path: "permission", directory: directory)
    }

    public func listPendingPermissionsV2(directory: String? = nil) async throws -> [V2PermissionRequestDTO] {
        let envelope: APIListEnvelope<V2PermissionRequestDTO> = try await get(
            path: "api/permission/request",
            locationDirectory: directory
        )
        return envelope.data
    }

    public func listPendingQuestions(directory: String? = nil) async throws -> [QuestionRequestDTO] {
        try await get(path: "question", directory: directory)
    }

    public func listPendingFormsV2(directory: String? = nil) async throws -> [V2FormInfoDTO] {
        let envelope: APIListEnvelope<V2FormInfoDTO> = try await get(
            path: "api/form",
            locationDirectory: directory
        )
        return envelope.data
    }

    public func createSession(parentID: String?, title: String?, directory: String? = nil) async throws -> SessionDTO {
        try await post(path: "session", body: CreateSessionRequestDTO(parentID: parentID, title: title), directory: directory)
    }

    public func getAllSessionStatuses(directory: String? = nil) async throws -> [String: [String: String]] {
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
                            let unwrapped = SessionFailureMessage.unwrap(message)
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

    public func getSession(id: String, directory: String? = nil) async throws -> SessionDTO {
        try await get(path: "session/\(id)", directory: directory)
    }

    public func updateSession(id: String, title: String?, directory: String? = nil) async throws -> SessionDTO {
        try await patch(path: "session/\(id)", body: UpdateSessionRequestDTO(title: title), directory: directory)
    }

    public func archiveSession(id: String, directory: String? = nil) async throws -> SessionDTO {
        let archivedAt = Int64(Date().timeIntervalSince1970 * 1000)
        return try await patch(
            path: "session/\(id)",
            body: UpdateSessionRequestDTO(time: UpdateSessionTimeDTO(archived: archivedAt)),
            directory: directory
        )
    }

    public func deleteSession(id: String, directory: String? = nil) async throws -> Bool {
        try await delete(path: "session/\(id)", directory: directory)
    }

    public func listChildSessions(id: String, directory: String? = nil) async throws -> [SessionDTO] {
        try await get(path: "session/\(id)/children", directory: directory)
    }

    public func listSessionTodos(id: String, directory: String? = nil) async throws -> [TodoDTO] {
        try await get(path: "session/\(id)/todo", directory: directory)
    }

    public func initializeSessionAgents(id: String, body: InitSessionRequestDTO, directory: String? = nil) async throws -> Bool {
        try await postEmpty(path: "session/\(id)/init", body: body, directory: directory)
    }

    public func forkSession(id: String, messageID: String?, directory: String? = nil) async throws -> SessionDTO {
        try await post(path: "session/\(id)/fork", body: ForkSessionRequestDTO(messageID: messageID), directory: directory)
    }

    public func abortSession(id: String, directory: String? = nil) async throws -> Bool {
        try await postEmpty(path: "session/\(id)/abort", body: EmptyBody(), directory: directory)
    }

    public func interruptSessionV2(id: String) async throws -> Bool {
        Self.boolOrTrue(try await interruptV2(id: id), decoder: decoder)
    }

    /// The interrupt's answer: true when a run was stopped, false when the session was already
    /// idle. It answers at once; `session.execution.interrupted` follows.
    public func interruptSessionResultV2(id: String) async throws -> Bool {
        let data = try await interruptV2(id: id)
        return try decoder.decode(APIDataEnvelope<V2InterruptResultDTO>.self, from: data).data.interrupted ?? false
    }

    /// `POST /api/session/{id}/interrupt`: stops the session's run.
    private func interruptV2(id: String) async throws -> Data {
        try await data(for: try requireConnection().authorizedRequest(path: "api/session/\(id)/interrupt", method: "POST"))
    }

    /// `POST /api/session/{id}/view` → 204. Moves the server-wide viewed watermark to `idle`.
    public func markSessionViewedV2(id: String, idle: Int64) async throws -> Bool {
        try await postEmpty(path: "api/session/\(id)/view", body: V2ViewSessionRequestDTO(idle: idle))
    }

    /// `POST /api/session/{id}/move` → 204. Moves the session to `directory` at the server's
    /// default delivery boundary (steer); the body carries nothing else.
    public func moveSessionV2(id: String, directory: String) async throws -> Bool {
        try await postEmpty(path: "api/session/\(id)/move", body: V2MoveSessionRequestDTO(directory: directory))
    }

    public func cancelInboxV2(sessionID: String, inboxID: String) async throws -> Bool {
        try await delete(path: "api/session/\(sessionID)/inbox/\(inboxID)")
    }

    public func steerInboxV2(sessionID: String, inboxID: String) async throws -> Bool {
        try await patchEmpty(
            path: "api/session/\(sessionID)/inbox/\(inboxID)",
            body: V2InboxDeliveryRequestDTO(delivery: "steer")
        )
    }

    public func queueInboxV2(sessionID: String, inboxID: String) async throws -> Bool {
        try await patchEmpty(
            path: "api/session/\(sessionID)/inbox/\(inboxID)",
            body: V2InboxDeliveryRequestDTO(delivery: "queue")
        )
    }

    public func shareSession(id: String, directory: String? = nil) async throws -> SessionDTO {
        try await post(path: "session/\(id)/share", body: EmptyBody(), directory: directory)
    }

    public func unshareSession(id: String, directory: String? = nil) async throws -> SessionDTO {
        let request = try requireConnection().authorizedRequest(path: "session/\(id)/share", method: "DELETE", directory: directory)
        return try await send(request)
    }

    public func getSessionDiff(id: String, messageID: String?, directory: String? = nil) async throws -> [FileDiffDTO] {
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

    public func getVcsInfo(directory: String? = nil) async throws -> VcsInfoDTO {
        try await get(path: "vcs", directory: directory)
    }

    public func getVcsStatus(directory: String? = nil) async throws -> [VcsFileStatusDTO] {
        try await get(path: "vcs/status", directory: directory)
    }

    public func getVcsDiff(mode: VcsDiffMode, directory: String? = nil, context: Int = 12) async throws -> [FileDiffDTO] {
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

    public func summarizeSession(id: String, body: SummarizeSessionRequestDTO, directory: String? = nil) async throws -> Bool {
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

    public func revertSession(id: String, body: RevertSessionRequestDTO, directory: String? = nil) async throws -> Bool {
        try await postEmpty(path: "session/\(id)/revert", body: body, directory: directory)
    }

    public func unrevertSession(id: String, directory: String? = nil) async throws -> Bool {
        try await postEmpty(path: "session/\(id)/unrevert", body: EmptyBody(), directory: directory)
    }

    public func respondToPermission(
        sessionID _: String,
        permissionID: String,
        body: PermissionResponseRequestDTO,
        directory: String? = nil
    ) async throws -> Bool {
        try await postEmpty(path: "permission/\(permissionID)/reply", body: body, directory: directory)
    }

    public func replyToQuestion(
        questionID: String,
        body: QuestionReplyRequestDTO,
        directory: String? = nil
    ) async throws -> Bool {
        try await postEmpty(path: "question/\(questionID)/reply", body: body, directory: directory)
    }

    public func rejectQuestion(questionID: String, directory: String? = nil) async throws -> Bool {
        try await postEmpty(path: "question/\(questionID)/reject", body: EmptyBody(), directory: directory)
    }

    public func respondToPermissionV2(
        sessionID: String,
        permissionID: String,
        body: V2PermissionReplyRequestDTO
    ) async throws -> Bool {
        try await postEmpty(
            path: "api/session/\(sessionID)/permission/\(permissionID)/reply",
            body: body
        )
    }

    public func replyToFormV2(sessionID: String, formID: String, body: V2FormReplyRequestDTO) async throws -> Bool {
        try await postEmpty(path: "api/session/\(sessionID)/form/\(formID)/reply", body: body)
    }

    /// `DELETE /api/session/{id}/form/{form}`, with `?message=` when there is one. With a message
    /// the agent reads it and carries on; without one the run ends.
    public func cancelFormV2(sessionID: String, formID: String, message: String? = nil) async throws -> Bool {
        let request = try requireConnection()
            .authorizedRequest(path: "api/session/\(sessionID)/form/\(formID)", method: "DELETE")
            .appendingQueryItems(message.map { [URLQueryItem(name: "message", value: $0)] } ?? [])
        return Self.boolOrTrue(try await data(for: request), decoder: decoder)
    }

    /// `GET /api/session/{id}/permission`: the session's own pending approvals (not its children's).
    public func listSessionPermissionsV2(id: String) async throws -> [V2PermissionRequestDTO] {
        try await listSessionPermissionsV2(id: id, as: V2PermissionRequestDTO.self)
    }

    /// The session's own pending approvals, read as `Request`.
    public func listSessionPermissionsV2<Request: Decodable & Sendable>(id: String, as _: Request.Type) async throws -> [Request] {
        let envelope: APIListEnvelope<Request> = try await get(path: "api/session/\(id)/permission")
        return envelope.data
    }

    /// `GET /api/session/{id}/form`: the session's own pending questions.
    public func listSessionFormsV2(id: String) async throws -> [V2FormInfoDTO] {
        try await listSessionFormsV2(id: id, as: V2FormInfoDTO.self)
    }

    /// The session's own pending questions, read as `Form`.
    public func listSessionFormsV2<Form: Decodable & Sendable>(id: String, as _: Form.Type) async throws -> [Form] {
        let envelope: APIListEnvelope<Form> = try await get(path: "api/session/\(id)/form")
        return envelope.data
    }

    /// `PUT /api/experimental/session/{id}/instructions/entries/{key}` with `{"value": text}`.
    /// A repeat with the same value changes nothing on the server.
    public func putInstructionEntryV2(sessionID: String, key: String, value: String) async throws {
        var request = try requireConnection().authorizedRequest(
            path: "api/experimental/session/\(sessionID)/instructions/entries/\(key)",
            method: "PUT"
        )
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try encoder.encode(V2InstructionEntryRequestDTO(value: value))
        _ = try await data(for: request)
    }

    // MARK: - V2 home / session CRUD

    public func locationForSessionList(directory: String) async throws -> V2LocationInfoDTO {
        try await get(path: "api/location", locationDirectory: directory)
    }

    /// `parentID` value that lists root sessions only.
    public static let v2RootSessionsParentID = "null"

    /// `projectID` lists every worktree of that project. Passing `directory` as well would hide those sessions.
    public func listSessionsV2(
        directory: String? = nil,
        parentID: String? = nil,
        projectID: String? = nil,
        archived: Bool? = nil,
        search: String? = nil
    ) async throws -> [V2SessionDTO] {
        var sessions: [V2SessionDTO] = []
        var cursor: String?
        var pages = 0
        repeat {
            let envelope = try await sessionPageV2(
                V2SessionDTO.self,
                directory: projectID == nil ? directory : nil,
                search: search,
                parentID: parentID,
                projectID: projectID,
                archived: archived,
                cursor: cursor
            )
            sessions.append(contentsOf: envelope.data)
            let next = envelope.cursor?.next.flatMap { $0.isEmpty ? nil : $0 }
            if next == cursor { break }
            cursor = next
            pages += 1
        } while cursor != nil && pages < Self.maxV2SessionListPages
        return sessions
    }

    /// One page of `GET /api/session`, newest first. Never follows the cursor: the server sends a
    /// `next` cursor even on the last page.
    public func listSessionsPageV2(
        directory: String? = nil,
        parentID: String? = nil,
        projectID: String? = nil,
        archived: Bool? = nil,
        limit: Int
    ) async throws -> [V2SessionDTO] {
        try await sessionPageV2(
            V2SessionDTO.self,
            directory: projectID == nil ? directory : nil,
            parentID: parentID,
            projectID: projectID,
            archived: archived,
            order: "desc",
            limit: limit
        ).data
    }

    /// One page of `GET /api/session`, read as `Session`, with exactly the filters given (nil ones
    /// are left out). `parentID` is a session id or `v2RootSessionsParentID`; `project` is sent
    /// for `projectID`. The server sends a `next` cursor even on the last page.
    public func sessionPageV2<Session: Decodable & Sendable>(
        _: Session.Type,
        directory: String? = nil,
        search: String? = nil,
        parentID: String? = nil,
        projectID: String? = nil,
        archived: Bool? = nil,
        order: String? = nil,
        limit: Int? = nil,
        cursor: String? = nil
    ) async throws -> APIListEnvelope<Session> {
        var query: [URLQueryItem] = []
        if let search { query.append(URLQueryItem(name: "search", value: search)) }
        if let parentID { query.append(URLQueryItem(name: "parentID", value: parentID)) }
        if let projectID { query.append(URLQueryItem(name: "project", value: projectID)) }
        if let archived { query.append(URLQueryItem(name: "archived", value: archived ? "true" : "false")) }
        if let order { query.append(URLQueryItem(name: "order", value: order)) }
        if let limit { query.append(URLQueryItem(name: "limit", value: String(limit))) }
        if let cursor { query.append(URLQueryItem(name: "cursor", value: cursor)) }
        let request = try requireConnection()
            .authorizedRequest(path: "api/session", directory: directory)
            .appendingQueryItems(query)
        return try await send(request)
    }

    /// A `parentID` creates a linked child at the parent's location, so no location is sent with it.
    public func createSessionV2(
        directory: String? = nil,
        title: String? = nil,
        parentID: String? = nil,
        model: V2PromptModelRefDTO? = nil
    ) async throws -> V2SessionDTO {
        try await createSessionV2(V2SessionDTO.self, directory: directory, title: title, parentID: parentID, model: model)
    }

    /// `POST /api/session`, with the new session read as `Session`. An empty title is left out.
    public func createSessionV2<Session: Decodable & Sendable>(
        _: Session.Type,
        directory: String? = nil,
        title: String? = nil,
        parentID: String? = nil,
        model: V2PromptModelRefDTO? = nil
    ) async throws -> Session {
        let envelope: APIDataEnvelope<Session> = try await post(
            path: "api/session",
            body: V2CreateSessionRequestDTO(
                parentID: parentID,
                title: title.flatMap { $0.isEmpty ? nil : $0 },
                location: parentID == nil ? directory.map { V2LocationDTO(directory: $0) } : nil,
                model: model
            )
        )
        return envelope.data
    }

    public func getActiveSessionsV2() async throws -> [String: APISessionActiveDTO] {
        let envelope: APIActiveSessionsEnvelope = try await get(path: "api/session/active")
        return envelope.data
    }

    public func getSessionV2(id: String) async throws -> V2SessionDTO {
        try await getSessionV2(id: id, as: V2SessionDTO.self)
    }

    /// `GET /api/session/{id}`, read as `Session`.
    public func getSessionV2<Session: Decodable & Sendable>(id: String, as _: Session.Type) async throws -> Session {
        let envelope: APIDataEnvelope<Session> = try await get(path: "api/session/\(id)")
        return envelope.data
    }

    public func renameSessionV2(id: String, title: String) async throws {
        _ = try await patchEmpty(path: "api/session/\(id)", body: V2RenameSessionRequestDTO(title: title))
    }

    /// Archives or restores a session (opencode 2.1.0+). Repeating the current state is a no-op.
    public func setSessionArchivedV2(id: String, archived: Bool) async throws {
        _ = try await patchEmpty(path: "api/session/\(id)", body: V2ArchiveSessionRequestDTO(archived: archived))
    }

    public func deleteSessionV2(id: String) async throws -> Bool {
        try await delete(path: "api/session/\(id)")
    }

    public func forkSessionV2(id: String, messageID: String?) async throws -> V2SessionDTO {
        let before = messageID.flatMap { value in
            value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : value
        }
        let envelope: APIDataEnvelope<V2SessionDTO> = try await post(
            path: "api/session/\(id)/fork",
            body: V2ForkSessionRequestDTO(before: before)
        )
        return envelope.data
    }

    /// `POST /api/session/{id}/generate` → `{ data: { text } }`. Answers `prompt` from the session's
    /// context and model without adding a message; nothing streams. One model call can outlast the
    /// default REST timeout, so this waits up to `timeout` (`sideQuestionTimeout` unless given).
    public func generateSessionTextV2(id: String, prompt: String, timeout: TimeInterval = Self.sideQuestionTimeout) async throws -> String {
        var request = try requireConnection().authorizedRequest(path: "api/session/\(id)/generate", method: "POST")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try encoder.encode(V2SessionGenerateRequestDTO(prompt: prompt))
        request.timeoutInterval = timeout
        let data = try await data(for: request)
        return try decoder.decode(APIDataEnvelope<V2SessionGenerateResponseDTO>.self, from: data).data.text
    }

    public static let sideQuestionTimeout: TimeInterval = 300

    public func compactSessionV2(id: String) async throws -> Bool {
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

    public func stageRevertV2(id: String, messageID: String, partID: String?) async throws -> Bool {
        try await postEmpty(
            path: "api/session/\(id)/revert/stage",
            body: V2RevertStageRequestDTO(messageID: messageID)
        )
    }

    public func clearRevertV2(id: String) async throws -> Bool {
        try await delete(path: "api/session/\(id)/revert")
    }

    public func commitRevertV2(id: String) async throws -> Bool {
        try await postWithoutBody(path: "api/session/\(id)/revert/commit")
    }

    public func getVcsInfoV2(directory: String? = nil) async throws -> V2VcsInfoDTO {
        let envelope: APILocatedEnvelope<V2VcsInfoDTO> = try await get(
            path: "api/vcs",
            locationDirectory: directory
        )
        return envelope.data
    }

    public func getVcsStatusV2(directory: String? = nil) async throws -> [VcsFileStatusDTO] {
        let envelope: APILocatedEnvelope<[VcsFileStatusDTO]> = try await get(
            path: "api/vcs/status",
            locationDirectory: directory
        )
        return envelope.data
    }

    public func getVcsDiffV2(mode: String, directory: String? = nil, context: Int = 12) async throws -> [FileDiffDTO] {
        let envelope: APILocatedEnvelope<[FileDiffDTO]> = try await get(
            path: "api/vcs/diff",
            locationDirectory: directory,
            query: ["mode": mode, "context": String(context)]
        )
        return envelope.data
    }

    /// `GET /api/session/{id}/diff`: the files the turn opened by user message `from` changed.
    /// Servers before opencode 2.1.0 have no route and answer 404.
    public func getSessionDiffV2(id: String, from: String?, context: Int = 12) async throws -> [FileDiffDTO] {
        var query = ["context": String(context)]
        if let from { query["from"] = from }
        let envelope: APIListEnvelope<FileDiffDTO> = try await get(path: "api/session/\(id)/diff", query: query)
        return envelope.data
    }

    private struct EmptyBody: Codable {}

    /// A body that is a JSON boolean, else true (the call succeeded with no such body).
    private static func boolOrTrue(_ data: Data, decoder: JSONDecoder) -> Bool {
        (try? decoder.decode(Bool.self, from: data)) ?? true
    }
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
