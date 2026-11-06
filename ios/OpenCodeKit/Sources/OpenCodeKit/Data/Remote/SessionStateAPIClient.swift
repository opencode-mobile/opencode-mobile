import Foundation

public nonisolated enum SessionStateAPIError: Error, Equatable {
    case invalidResponse
    case httpStatus(Int, String?)
    case noActiveConnection
    case timedOut
}

/// OpenCode v2 calls for following one session from a phone: find, read and create sessions (with
/// their location, model and busy time), switch the model, list the session's own pending approvals,
/// questions and inbox, generate text from the session, and put an instruction entry. See
/// `m2-api-notes.md` for the routes. New calls, separate from OpenCode Mobile's session client.
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

    // MARK: Sending

    /// How a prompt reaches a running session (`Session.Inbox.Delivery`).
    public nonisolated enum Delivery: String, Sendable, Equatable {
        /// After the current turn.
        case queue
        /// At the next step (and at once for an idle session).
        case steer
    }

    /// `POST /api/session/{id}/prompt` with `text` and `delivery`. Every prompt goes through the
    /// inbox; the answer is that inbox item, whose id `cancelInbox` takes.
    public func prompt(sessionID: String, text: String, delivery: Delivery) async throws -> InboxItemV2 {
        nonisolated struct Body: Encodable {
            let text: String
            let delivery: String
        }
        let envelope: APIDataEnvelope<InboxItemV2> = try await send(
            path: "api/session/\(sessionID)/prompt",
            method: "POST",
            body: Body(text: text, delivery: delivery.rawValue)
        )
        return envelope.data
    }

    /// `DELETE /api/session/{id}/inbox/{item}`: takes back a message not delivered yet. Repeating
    /// it, or cancelling one already delivered, also answers 204.
    public func cancelInbox(sessionID: String, itemID: String) async throws {
        try await sendEmpty(path: "api/session/\(sessionID)/inbox/\(itemID)", method: "DELETE")
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
