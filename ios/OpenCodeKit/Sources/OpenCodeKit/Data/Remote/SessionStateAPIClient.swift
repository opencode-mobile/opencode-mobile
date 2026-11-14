import Foundation

public nonisolated enum SessionStateAPIError: Error, Equatable {
    case invalidResponse
    case httpStatus(Int, String?)
    case noActiveConnection
    case timedOut
}

/// Hex Code's view of one session it follows from a phone: find, read and create sessions (with
/// their location, model and busy time), switch the model, list the session's own pending approvals,
/// questions and inbox, answer them, stop a run, list the agent's shells, generate text from the
/// session, run a command such as `/goal`, read the goal, and put an instruction entry.
///
/// It defines no route of its own: each call goes through the kit's API clients
/// (`SessionsAPIClient`, `MessagesAPIClient`, `CommandsAPIClient`, `ShellAPIClient`,
/// `GoalAPIClient`), which OpenCode Mobile uses too. What it adds is Hex Code's lenient read
/// models (`SessionInfoV2`, `PendingPermissionV2`, …) and one error type, `SessionStateAPIError`.
public nonisolated struct SessionStateAPIClient: Sendable {
    /// `parentID` filter of the session list.
    public nonisolated enum Parent: Sendable, Equatable {
        /// Root sessions only (`parentID=null`).
        case none
        /// Children of this session.
        case session(String)
    }

    private let sessions: SessionsAPIClient
    private let messages: MessagesAPIClient
    private let commands: CommandsAPIClient
    private let shell: ShellAPIClient
    private let goals: GoalAPIClient

    public init(connectionProvider: @escaping @Sendable () -> ServerConnection?, urlSession: URLSession? = nil) {
        sessions = SessionsAPIClient(connectionProvider: connectionProvider, urlSession: urlSession)
        messages = MessagesAPIClient(connectionProvider: connectionProvider, urlSession: urlSession)
        commands = CommandsAPIClient(connectionProvider: connectionProvider, urlSession: urlSession)
        shell = ShellAPIClient(connectionProvider: connectionProvider, urlSession: urlSession)
        goals = GoalAPIClient(connectionProvider: connectionProvider, urlSession: urlSession)
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
        let envelope = try await mapped {
            if let cursor {
                return try await sessions.sessionPageV2(SessionInfoV2.self, cursor: cursor)
            }
            let parentID: String? = switch parent {
            case .none?: SessionsAPIClient.v2RootSessionsParentID
            case let .session(id)?: id
            case nil: nil
            }
            return try await sessions.sessionPageV2(
                SessionInfoV2.self,
                directory: directory,
                search: search,
                parentID: parentID,
                archived: archived,
                limit: limit
            )
        }
        return SessionPageV2(sessions: envelope.data, nextCursor: envelope.cursor?.next)
    }

    /// `GET /api/session/{id}`.
    public func getSession(id: String) async throws -> SessionInfoV2 {
        try await mapped { try await sessions.getSessionV2(id: id, as: SessionInfoV2.self) }
    }

    /// `POST /api/session` in the project, with the model and level.
    public func createSession(title: String, directory: String, model: SessionModelRefV2?) async throws -> SessionInfoV2 {
        try await mapped {
            try await sessions.createSessionV2(SessionInfoV2.self, directory: directory, title: title, model: model)
        }
    }

    /// `DELETE /api/session/{id}`.
    public func deleteSession(id: String) async throws {
        _ = try await mapped { try await sessions.deleteSessionV2(id: id) }
    }

    /// `GET /api/session/active`: the ids of every running session on the server.
    public func activeSessionIDs() async throws -> Set<String> {
        Set(try await mapped { try await sessions.getActiveSessionsV2() }.keys)
    }

    /// `POST /api/session/{id}/model`. A call with the same model changes nothing.
    public func switchModel(sessionID: String, model: SessionModelRefV2) async throws {
        try await mapped {
            try await messages.switchSessionModel(sessionID: sessionID, body: V2SwitchModelRequestDTO(model: model))
        }
    }

    // MARK: Pending items and inbox

    /// `GET /api/session/{id}/message?type=assistant`, newest first. A `cursor` (from the previous
    /// page) carries the order; the server refuses `order` with it, but not `type`.
    public func assistantMessages(sessionID: String, limit: Int? = nil, cursor: String? = nil) async throws -> MessagePageV2 {
        let envelope = try await mapped {
            try await messages.messagePageV2(
                SessionMessageV2.self,
                sessionID: sessionID,
                type: "assistant",
                limit: limit,
                order: cursor == nil ? "desc" : nil,
                cursor: cursor
            )
        }
        return MessagePageV2(messages: envelope.data, nextCursor: envelope.cursor?.next)
    }

    /// `GET /api/session/{id}/permission`: the session's own pending approvals (not its children's).
    public func pendingPermissions(sessionID: String) async throws -> [PendingPermissionV2] {
        try await mapped { try await sessions.listSessionPermissionsV2(id: sessionID, as: PendingPermissionV2.self) }
    }

    /// `GET /api/session/{id}/form`: the session's own pending questions.
    public func pendingForms(sessionID: String) async throws -> [PendingFormV2] {
        try await mapped { try await sessions.listSessionFormsV2(id: sessionID, as: PendingFormV2.self) }
    }

    /// `GET /api/session/{id}/inbox`: messages not delivered yet, in order.
    public func inbox(sessionID: String) async throws -> [InboxItemV2] {
        try await mapped { try await messages.listInboxV2(sessionID: sessionID, as: InboxItemV2.self) }
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
        let body = V2PermissionReplyRequestDTO(decision: decision.rawValue, message: decision == .reject ? message : nil)
        _ = try await mapped {
            try await sessions.respondToPermissionV2(sessionID: sessionID, permissionID: requestID, body: body)
        }
    }

    /// `POST /api/session/{id}/form/{form}/reply` with `{"answer": {key: value}}` (204). A form already
    /// settled gives `409 FormAlreadySettledError`; a value of the wrong type `400`.
    public func replyToForm(sessionID: String, formID: String, answer: [String: FormAnswerV2]) async throws {
        let body = V2FormReplyRequestDTO(answer: answer.mapValues(\.jsonValue))
        _ = try await mapped { try await sessions.replyToFormV2(sessionID: sessionID, formID: formID, body: body) }
    }

    /// `DELETE /api/session/{id}/form/{form}`, with `?message=` when there is one (204). With a message
    /// the agent reads it and carries on; without one the run ends.
    public func cancelForm(sessionID: String, formID: String, message: String? = nil) async throws {
        _ = try await mapped { try await sessions.cancelFormV2(sessionID: sessionID, formID: formID, message: message) }
    }

    // MARK: Stopping and shells

    /// `POST /api/session/{id}/interrupt`: stops the session's run. True when a run was stopped,
    /// false when it was already idle. It answers at once; `session.execution.interrupted` follows.
    public func interrupt(sessionID: String) async throws -> Bool {
        try await mapped { try await sessions.interruptSessionResultV2(id: sessionID) }
    }

    /// `GET /api/shell?location[directory]=…`: the shells running in that folder (the server
    /// compares the folder as an exact string). Each names the session that started it.
    public func shells(directory: String) async throws -> [ShellInfoV2] {
        try await mapped { try await shell.list(directory: directory) }.map { ShellInfoV2($0) }
    }

    // MARK: Sending

    /// How a prompt reaches a running session (`Session.Inbox.Delivery`).
    public nonisolated enum Delivery: String, Sendable, Equatable {
        /// After the current turn.
        case queue
        /// At the next step (and at once for an idle session).
        case steer
    }

    /// A file sent with a prompt (`files[]` of `POST /api/session/{id}/prompt`): a `data:` URL
    /// (canonical base64, no line breaks), a `file://` URL on the server, or a `media:` ref, and the
    /// name shown back in the message and the inbox. The server works out the type from the bytes.
    public typealias PromptFile = V2PromptFileDTO

    /// A prompt's `metadata`, kept with the message for clients to read back.
    public typealias PromptMetadata = V2PromptMetadataDTO

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
        let body = V2PromptRequestDTO(
            text: text,
            files: files.isEmpty ? nil : files,
            delivery: delivery.rawValue,
            metadata: metadata
        )
        return try await mapped { try await messages.sendPromptV2(sessionID: sessionID, body: body, as: InboxItemV2.self) }
    }

    /// `DELETE /api/session/{id}/inbox/{item}`: takes back a message not delivered yet. Repeating
    /// it, or cancelling one already delivered, also answers 204.
    public func cancelInbox(sessionID: String, itemID: String) async throws {
        _ = try await mapped { try await sessions.cancelInboxV2(sessionID: sessionID, inboxID: itemID) }
    }

    // MARK: Commands and the goal

    /// `POST /api/session/{id}/command` with `{"name", "text"}` (the field is `text`, not
    /// `arguments`) and `files` when there are any; no `delivery`, so the command's own default
    /// applies. `204` on success. `/goal` answers `500 CommandExecutionError` when the goal isn't in
    /// the state the command needs ("No active goal to pause.") or the text is refused.
    public func runCommand(sessionID: String, name: String, text: String, files: [PromptFile] = []) async throws {
        let body = V2RunCommandRequestDTO(name: name, text: text, files: files.isEmpty ? nil : files, delivery: nil)
        try await mapped { try await commands.runCommandV2(sessionID: sessionID, body: body) }
    }

    /// `POST /api/rpc/opencode.goal/get` with `{"input": {"sessionID"}}`: the session's goal, or nil
    /// when it has none. The goal lives in the server's store, not in the session.
    public func goal(sessionID: String, directory: String? = nil) async throws -> GoalStateV2? {
        try await mapped { try await goals.goal(sessionID: sessionID, directory: directory) }
    }

    // MARK: Generate and instructions

    /// `POST /api/session/{id}/generate`: text from the session's model and history, without
    /// changing the session. The body is only the prompt.
    public func generate(sessionID: String, prompt: String, timeout: TimeInterval) async throws -> String {
        try await mapped { try await sessions.generateSessionTextV2(id: sessionID, prompt: prompt, timeout: timeout) }
    }

    /// `PUT /api/experimental/session/{id}/instructions/entries/{key}` with `{"value": text}`.
    /// A repeat with the same value changes nothing on the server.
    public func putInstructionEntry(sessionID: String, key: String, value: String) async throws {
        try await mapped { try await sessions.putInstructionEntryV2(sessionID: sessionID, key: key, value: value) }
    }

    // MARK: Errors

    /// Runs one call and reports its failure as a `SessionStateAPIError`: the clients' HTTP and
    /// connection errors as theirs, a timeout as `timedOut`, a body that can't be read as
    /// `invalidResponse`. Other errors pass through.
    private func mapped<T>(_ call: () async throws -> T) async throws -> T {
        do {
            return try await call()
        } catch let error as URLError where error.code == .timedOut {
            throw SessionStateAPIError.timedOut
        } catch is DecodingError {
            throw SessionStateAPIError.invalidResponse
        } catch let error as SessionsAPIError {
            switch error {
            case .invalidResponse: throw SessionStateAPIError.invalidResponse
            case let .httpStatus(status, body): throw SessionStateAPIError.httpStatus(status, body)
            case .noActiveConnection: throw SessionStateAPIError.noActiveConnection
            }
        } catch let error as MessagesAPIError {
            switch error {
            case .invalidResponse: throw SessionStateAPIError.invalidResponse
            case let .httpStatus(status, body): throw SessionStateAPIError.httpStatus(status, body)
            case .noActiveConnection: throw SessionStateAPIError.noActiveConnection
            }
        } catch let error as CommandsAPIError {
            switch error {
            case .invalidResponse: throw SessionStateAPIError.invalidResponse
            case let .httpStatus(status, body): throw SessionStateAPIError.httpStatus(status, body)
            case .noActiveConnection: throw SessionStateAPIError.noActiveConnection
            }
        } catch let error as ShellAPIError {
            switch error {
            case .invalidResponse: throw SessionStateAPIError.invalidResponse
            case let .httpStatus(status, body): throw SessionStateAPIError.httpStatus(status, body)
            case .noActiveConnection: throw SessionStateAPIError.noActiveConnection
            }
        } catch let error as GoalAPIError {
            switch error {
            case .invalidResponse: throw SessionStateAPIError.invalidResponse
            case let .httpStatus(status, body): throw SessionStateAPIError.httpStatus(status, body)
            case .noActiveConnection: throw SessionStateAPIError.noActiveConnection
            }
        }
    }
}
