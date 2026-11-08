import Foundation

public nonisolated enum SessionStateAPIError: Error, Equatable {
    case invalidResponse
    case httpStatus(Int, String?)
    case noActiveConnection
    case timedOut
}

/// OpenCode v2 calls for following one session from a phone: find, read and create sessions (with
/// their location, model and busy time), switch the model, list the session's own pending approvals,
/// questions and inbox, answer them, stop a run, list the agent's shells, generate text from the
/// session, run a command such as `/goal`, read the goal, and put an instruction entry. See
/// `m2-api-notes.md`, `m4-proposal.md` and `m5-proposal.md` for the routes. New calls, separate
/// from OpenCode Mobile's session client.
public nonisolated struct SessionStateAPIClient: Sendable {
    /// `parentID` filter of the session list.
    public nonisolated enum Parent: Sendable, Equatable {
        /// Root sessions only (`parentID=null`).
        case none
        /// Children of this session.
        case session(String)
    }

    private let connectionProvider: @Sendable () -> ServerConnection?
    private let resolveURLSession: @Sendable () -> URLSession

    public init(connectionProvider: @escaping @Sendable () -> ServerConnection?, urlSession: URLSession? = nil) {
        self.connectionProvider = connectionProvider
        self.resolveURLSession = OpenCodeURLSessions.restResolver(injected: urlSession)
    }

    public init(fixedConnection: ServerConnection, urlSession: URLSession? = nil) {
        self.init(connectionProvider: { fixedConnection }, urlSession: urlSession)
    }

    // MARK: Sessions

    /// `GET /api/session`. With a `cursor`, the other filters come from it.
    public func listSessions(
        directory: String? = nil,
        search: String? = nil,
        parent: Parent? = nil,
        archived: Bool? = nil,
        limit: Int? = nil,
        cursor: String? = nil
    ) async throws -> SessionPageV2 {
        var query: [URLQueryItem] = []
        if let cursor {
            query.append(URLQueryItem(name: "cursor", value: cursor))
        } else {
            if let directory { query.append(URLQueryItem(name: "directory", value: directory)) }
            if let search { query.append(URLQueryItem(name: "search", value: search)) }
            switch parent {
            case .none?: query.append(URLQueryItem(name: "parentID", value: "null"))
            case let .session(id)?: query.append(URLQueryItem(name: "parentID", value: id))
            case nil: break
            }
            if let archived { query.append(URLQueryItem(name: "archived", value: archived ? "true" : "false")) }
            if let limit { query.append(URLQueryItem(name: "limit", value: String(limit))) }
        }
        let envelope: APIListEnvelope<SessionInfoV2> = try await send(path: "api/session", query: query)
        return SessionPageV2(sessions: envelope.data, nextCursor: envelope.cursor?.next)
    }

    /// `GET /api/session/{id}`.
    public func getSession(id: String) async throws -> SessionInfoV2 {
        let envelope: APIDataEnvelope<SessionInfoV2> = try await send(path: "api/session/\(id)")
        return envelope.data
    }

    /// `POST /api/session` in the project, with the model and level.
    public func createSession(title: String, directory: String, model: SessionModelRefV2?) async throws -> SessionInfoV2 {
        nonisolated struct Location: Encodable { let directory: String }
        nonisolated struct Body: Encodable {
            let title: String
            let location: Location
            let model: SessionModelRefV2?
        }
        let envelope: APIDataEnvelope<SessionInfoV2> = try await send(
            path: "api/session",
            method: "POST",
            body: Body(title: title, location: Location(directory: directory), model: model)
        )
        return envelope.data
    }

    /// `DELETE /api/session/{id}`.
    public func deleteSession(id: String) async throws {
        try await sendEmpty(path: "api/session/\(id)", method: "DELETE")
    }

    /// `GET /api/session/active`: the ids of every running session on the server.
    public func activeSessionIDs() async throws -> Set<String> {
        nonisolated struct Status: Decodable, Sendable {}
        let envelope: APIDataEnvelope<[String: Status]> = try await send(path: "api/session/active")
        return Set(envelope.data.keys)
    }

    /// `POST /api/session/{id}/model`. A call with the same model changes nothing.
    public func switchModel(sessionID: String, model: SessionModelRefV2) async throws {
        nonisolated struct Body: Encodable { let model: SessionModelRefV2 }
        try await sendEmpty(path: "api/session/\(sessionID)/model", method: "POST", body: Body(model: model))
    }

    // MARK: Pending items and inbox

    /// `GET /api/session/{id}/message?type=assistant`, newest first. A `cursor` (from the previous
    /// page) carries the order; the server refuses `order` with it, but not `type`.
    public func assistantMessages(sessionID: String, limit: Int? = nil, cursor: String? = nil) async throws -> MessagePageV2 {
        var query = [URLQueryItem(name: "type", value: "assistant")]
        if let cursor {
            query.append(URLQueryItem(name: "cursor", value: cursor))
        } else {
            query.append(URLQueryItem(name: "order", value: "desc"))
        }
        if let limit { query.append(URLQueryItem(name: "limit", value: String(limit))) }
        let envelope: APIListEnvelope<SessionMessageV2> = try await send(path: "api/session/\(sessionID)/message", query: query)
        return MessagePageV2(messages: envelope.data, nextCursor: envelope.cursor?.next)
    }

    /// `GET /api/session/{id}/permission`: the session's own pending approvals (not its children's).
    public func pendingPermissions(sessionID: String) async throws -> [PendingPermissionV2] {
        let envelope: APIListEnvelope<PendingPermissionV2> = try await send(path: "api/session/\(sessionID)/permission")
        return envelope.data
    }

    /// `GET /api/session/{id}/form`: the session's own pending questions.
    public func pendingForms(sessionID: String) async throws -> [PendingFormV2] {
        let envelope: APIListEnvelope<PendingFormV2> = try await send(path: "api/session/\(sessionID)/form")
        return envelope.data
    }

    /// `GET /api/session/{id}/inbox`: messages not delivered yet, in order.
    public func inbox(sessionID: String) async throws -> [InboxItemV2] {
        let envelope: APIListEnvelope<InboxItemV2> = try await send(path: "api/session/\(sessionID)/inbox")
        return envelope.data
    }

    // MARK: Answering

    /// How an approval is answered (`decision` of the permission reply).
    public nonisolated enum PermissionDecision: String, Sendable, Equatable {
        /// This request only.
        case once
        /// Saves a rule for each `save` pattern, for the session's project, until it is removed.
        case always
        /// Refuses it. With a message the agent reads the message and carries on; without one the
        /// run ends. Either way the session's other pending approvals are refused too.
        case reject
    }

    /// `POST /api/session/{id}/permission/{request}/reply` (204). `sessionID` is the session that
    /// owns the request (a subagent's own id). `message` only counts with `reject`. An unknown or
    /// already answered request gives `404`.
    public func replyToPermission(sessionID: String, requestID: String, decision: PermissionDecision, message: String? = nil) async throws {
        nonisolated struct Body: Encodable {
            let decision: String
            let message: String?
        }
        try await sendEmpty(
            path: "api/session/\(sessionID)/permission/\(requestID)/reply",
            method: "POST",
            body: Body(decision: decision.rawValue, message: decision == .reject ? message : nil)
        )
    }

    /// `POST /api/session/{id}/form/{form}/reply` with `{"answer": {key: value}}` (204). A form already
    /// settled gives `409 FormAlreadySettledError`; a value of the wrong type `400`.
    public func replyToForm(sessionID: String, formID: String, answer: [String: FormAnswerV2]) async throws {
        nonisolated struct Body: Encodable { let answer: [String: FormAnswerV2] }
        try await sendEmpty(path: "api/session/\(sessionID)/form/\(formID)/reply", method: "POST", body: Body(answer: answer))
    }

    /// `DELETE /api/session/{id}/form/{form}`, with `?message=` when there is one (204). With a message
    /// the agent reads it and carries on; without one the run ends.
    public func cancelForm(sessionID: String, formID: String, message: String? = nil) async throws {
        let query = message.map { [URLQueryItem(name: "message", value: $0)] } ?? []
        _ = try await perform(request(path: "api/session/\(sessionID)/form/\(formID)", method: "DELETE", query: query, body: nil, timeout: nil))
    }

    // MARK: Stopping and shells

    /// `POST /api/session/{id}/interrupt`: stops the session's run. True when a run was stopped,
    /// false when it was already idle. It answers at once; `session.execution.interrupted` follows.
    public func interrupt(sessionID: String) async throws -> Bool {
        nonisolated struct Reply: Decodable, Sendable { let interrupted: Bool? }
        let envelope: APIDataEnvelope<Reply> = try await send(path: "api/session/\(sessionID)/interrupt", method: "POST")
        return envelope.data.interrupted ?? false
    }

    /// `GET /api/shell?location[directory]=…`: the shells running in that folder (the server
    /// compares the folder as an exact string). Each names the session that started it.
    public func shells(directory: String) async throws -> [ShellInfoV2] {
        let envelope: APIListEnvelope<ShellInfoV2> = try await send(
            path: "api/shell",
            query: [URLQueryItem(name: "location[directory]", value: directory)]
        )
        return envelope.data
    }

    // MARK: Sending

    /// How a prompt reaches a running session (`Session.Inbox.Delivery`).
    public nonisolated enum Delivery: String, Sendable, Equatable {
        /// After the current turn.
        case queue
        /// At the next step (and at once for an idle session).
        case steer
    }

    /// A file sent with a prompt (`files[]` of `POST /api/session/{id}/prompt`).
    public nonisolated struct PromptFile: Encodable, Sendable, Equatable {
        /// A `data:` URL (canonical base64, no line breaks), a `file://` URL on the server, or a
        /// `media:` ref. The server works out the type from the bytes.
        public var uri: String
        /// Shown back in the message and the inbox, and named in errors.
        public var name: String?

        public init(uri: String, name: String? = nil) {
            self.uri = uri
            self.name = name
        }

        /// A `data:` URL for these bytes.
        public static func dataURL(_ data: Data, mime: String) -> String {
            "data:\(mime);base64,\(data.base64EncodedString())"
        }
    }

    /// A prompt's `metadata`, kept with the message for clients to read back. Uploaded files are
    /// named in `attachments` (their paths are also in the text, for the agent); OpenCode's desktop
    /// app shows them as chips when `displayText` and `comments` are both present.
    public nonisolated struct PromptMetadata: Encodable, Sendable, Equatable {
        public var displayText: String?
        public var comments: [JSONValueDTO]?
        public var attachments: [PromptAttachment]?

        public init(displayText: String? = nil, comments: [JSONValueDTO]? = nil, attachments: [PromptAttachment]? = nil) {
            self.displayText = displayText
            self.comments = comments
            self.attachments = attachments
        }
    }

    /// `POST /api/session/{id}/prompt` with `text`, `delivery`, and `files` and `metadata` when
    /// there are any. Every prompt goes through the inbox; the answer is that inbox item, whose id
    /// `cancelInbox` takes. A file the server refuses fails the whole prompt (`400`, `field: "files"`).
    public func prompt(
        sessionID: String,
        text: String,
        delivery: Delivery,
        files: [PromptFile] = [],
        metadata: PromptMetadata? = nil
    ) async throws -> InboxItemV2 {
        nonisolated struct Body: Encodable {
            let text: String
            let files: [PromptFile]?
            let delivery: String
            let metadata: PromptMetadata?
        }
        let envelope: APIDataEnvelope<InboxItemV2> = try await send(
            path: "api/session/\(sessionID)/prompt",
            method: "POST",
            body: Body(text: text, files: files.isEmpty ? nil : files, delivery: delivery.rawValue, metadata: metadata)
        )
        return envelope.data
    }

    /// `DELETE /api/session/{id}/inbox/{item}`: takes back a message not delivered yet. Repeating
    /// it, or cancelling one already delivered, also answers 204.
    public func cancelInbox(sessionID: String, itemID: String) async throws {
        try await sendEmpty(path: "api/session/\(sessionID)/inbox/\(itemID)", method: "DELETE")
    }

    // MARK: Commands and the goal

    /// `POST /api/session/{id}/command` with `{"name", "text"}` (the field is `text`, not
    /// `arguments`) and `files` when there are any; no `delivery`, so the command's own default
    /// applies. `204` on success. `/goal` answers `500 CommandExecutionError` when the goal isn't in
    /// the state the command needs ("No active goal to pause.") or the text is refused.
    public func runCommand(sessionID: String, name: String, text: String, files: [PromptFile] = []) async throws {
        nonisolated struct Body: Encodable {
            let name: String
            let text: String
            let files: [PromptFile]?
        }
        try await sendEmpty(
            path: "api/session/\(sessionID)/command",
            method: "POST",
            body: Body(name: name, text: text, files: files.isEmpty ? nil : files)
        )
    }

    /// `POST /api/rpc/opencode.goal/get` with `{"input": {"sessionID"}}`: the session's goal, or nil
    /// when it has none. The goal lives in the server's store, not in the session.
    public func goal(sessionID: String, directory: String? = nil) async throws -> GoalStateV2? {
        nonisolated struct Input: Encodable { let sessionID: String }
        nonisolated struct Body: Encodable { let input: Input }
        nonisolated struct Reply: Decodable, Sendable { let output: GoalStateV2? }
        let query = directory.map { [URLQueryItem(name: "location[directory]", value: $0)] } ?? []
        let reply: Reply = try await send(
            path: "api/rpc/opencode.goal/get",
            method: "POST",
            query: query,
            body: Body(input: Input(sessionID: sessionID))
        )
        return reply.output
    }

    // MARK: Generate and instructions

    /// `POST /api/session/{id}/generate`: text from the session's model and history, without
    /// changing the session. The body is only the prompt.
    public func generate(sessionID: String, prompt: String, timeout: TimeInterval) async throws -> String {
        nonisolated struct Body: Encodable { let prompt: String }
        nonisolated struct Reply: Decodable, Sendable { let text: String? }
        let envelope: APIDataEnvelope<Reply> = try await send(
            path: "api/session/\(sessionID)/generate",
            method: "POST",
            body: Body(prompt: prompt),
            timeout: timeout
        )
        return envelope.data.text ?? ""
    }

    /// `PUT /api/experimental/session/{id}/instructions/entries/{key}` with `{"value": text}`.
    /// A repeat with the same value changes nothing on the server.
    public func putInstructionEntry(sessionID: String, key: String, value: String) async throws {
        nonisolated struct Body: Encodable { let value: String }
        try await sendEmpty(
            path: "api/experimental/session/\(sessionID)/instructions/entries/\(key)",
            method: "PUT",
            body: Body(value: value)
        )
    }

    // MARK: Requests

    private func request(path: String, method: String, query: [URLQueryItem], body: (any Encodable)?, timeout: TimeInterval?) throws -> URLRequest {
        guard let connection = connectionProvider() else { throw SessionStateAPIError.noActiveConnection }
        var request = connection.authorizedRequest(path: path, method: method)
        if !query.isEmpty, let url = request.url, var components = URLComponents(url: url, resolvingAgainstBaseURL: false) {
            components.queryItems = (components.queryItems ?? []) + query
            // `+` would read as a space on the server.
            components.percentEncodedQuery = components.percentEncodedQuery?.replacingOccurrences(of: "+", with: "%2B")
            request.url = components.url
        }
        if let body {
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = try JSONEncoder().encode(body)
        }
        if let timeout { request.timeoutInterval = timeout }
        return request
    }

    private func perform(_ request: URLRequest) async throws -> Data {
        let startedAt = OpenCodeAPITrace.start(request)
        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await resolveURLSession().data(for: request)
        } catch let error as URLError where error.code == .timedOut {
            OpenCodeAPITrace.failure(request, error: error, startedAt: startedAt)
            throw SessionStateAPIError.timedOut
        } catch {
            OpenCodeAPITrace.failure(request, error: error, startedAt: startedAt)
            throw error
        }
        guard let http = response as? HTTPURLResponse else {
            OpenCodeAPITrace.invalidResponse(request, startedAt: startedAt)
            throw SessionStateAPIError.invalidResponse
        }
        OpenCodeAPITrace.finish(request, statusCode: http.statusCode, startedAt: startedAt)
        guard (200 ..< 300).contains(http.statusCode) else {
            throw SessionStateAPIError.httpStatus(http.statusCode, String(data: data, encoding: .utf8))
        }
        return data
    }

    private func send<T: Decodable>(
        path: String,
        method: String = "GET",
        query: [URLQueryItem] = [],
        body: (any Encodable)? = nil,
        timeout: TimeInterval? = nil
    ) async throws -> T {
        let data = try await perform(request(path: path, method: method, query: query, body: body, timeout: timeout))
        do {
            return try JSONDecoder().decode(T.self, from: data)
        } catch {
            throw SessionStateAPIError.invalidResponse
        }
    }

    private func sendEmpty(path: String, method: String, body: (any Encodable)? = nil) async throws {
        _ = try await perform(request(path: path, method: method, query: [], body: body, timeout: nil))
    }
}
