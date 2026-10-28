import Foundation

nonisolated enum MessagesAPIError: Error {
    case invalidResponse
    case httpStatus(Int, String?)
    case noActiveConnection
}

protocol MessagesAPI: Sendable {
    func listMessages(sessionID: String, directory: String?) async throws -> [MessageDTO]
    func listMessagesPage(
        sessionID: String,
        directory: String?,
        limit: Int,
        before: String?
    ) async throws -> (messages: [MessageDTO], nextCursor: String?)
    func listMessagesV2(sessionID: String, directory: String?) async throws -> [V2SessionMessageDTO]
    func listMessagesV2Page(
        sessionID: String,
        directory: String?,
        limit: Int,
        order: String?,
        cursor: String?
    ) async throws -> (messages: [V2SessionMessageDTO], nextCursor: String?)
    func getMessage(sessionID: String, messageID: String, directory: String?) async throws -> MessageDTO
    func sendMessage(sessionID: String, body: SendMessageRequestDTO, directory: String?) async throws -> MessageDTO
    func sendPromptAsync(sessionID: String, body: SendMessageRequestDTO, directory: String?) async throws
    func sendPromptAsync(sessionID: String, body: SendMessageRequestDTO, attachments: [Attachment], directory: String?) async throws
    func sendPromptV2(sessionID: String, body: V2PromptRequestDTO, directory: String?) async throws
    func sendPromptV2(sessionID: String, body: V2PromptRequestDTO, attachments: [Attachment], directory: String?) async throws
    func switchSessionAgent(sessionID: String, agent: String, directory: String?) async throws
    func switchSessionModel(sessionID: String, body: V2SwitchModelRequestDTO, directory: String?) async throws
    func listInboxV2(sessionID: String) async throws -> [V2InboxItemDTO]
    func runShell(sessionID: String, body: V1ShellRequestDTO, directory: String?) async throws
    func runShellV2(sessionID: String, body: V2ShellRequestDTO) async throws
}

extension MessagesAPI {
    func listMessagesPage(
        sessionID: String,
        directory: String?,
        limit _: Int,
        before _: String?
    ) async throws -> (messages: [MessageDTO], nextCursor: String?) {
        (try await listMessages(sessionID: sessionID, directory: directory), nil)
    }

    func listMessagesV2(sessionID: String, directory: String?) async throws -> [V2SessionMessageDTO] {
        []
    }

    func listMessagesV2Page(
        sessionID: String,
        directory: String?,
        limit _: Int,
        order _: String?,
        cursor _: String?
    ) async throws -> (messages: [V2SessionMessageDTO], nextCursor: String?) {
        (try await listMessagesV2(sessionID: sessionID, directory: directory), nil)
    }

    func sendPromptAsync(sessionID: String, body: SendMessageRequestDTO, attachments: [Attachment], directory: String?) async throws {
        try await sendPromptAsync(sessionID: sessionID, body: body, directory: directory)
    }

    func sendPromptV2(sessionID: String, body: V2PromptRequestDTO, directory: String?) async throws {}

    func sendPromptV2(sessionID: String, body: V2PromptRequestDTO, attachments: [Attachment], directory: String?) async throws {
        try await sendPromptV2(sessionID: sessionID, body: body, directory: directory)
    }

    func switchSessionAgent(sessionID: String, agent: String, directory: String?) async throws {}

    func switchSessionModel(sessionID: String, body: V2SwitchModelRequestDTO, directory: String?) async throws {}

    func listInboxV2(sessionID _: String) async throws -> [V2InboxItemDTO] { [] }

    func runShell(sessionID _: String, body _: V1ShellRequestDTO, directory _: String?) async throws {}

    func runShellV2(sessionID _: String, body _: V2ShellRequestDTO) async throws {}
}

nonisolated struct MessagesAPIClient: MessagesAPI, Sendable {
    private let connectionProvider: @Sendable () -> ServerConnection?
    private let resolveURLSession: @Sendable () -> URLSession
    private let bodyUploader: DurableJSONUploader?
    private let decoder = JSONDecoder()
    private let encoder = JSONEncoder()

    init(
        connectionProvider: @escaping @Sendable () -> ServerConnection?,
        urlSession: URLSession? = nil,
        bodyUploader: DurableJSONUploader? = nil
    ) {
        self.connectionProvider = connectionProvider
        self.resolveURLSession = OpenCodeURLSessions.restResolver(injected: urlSession)
        self.bodyUploader = bodyUploader
    }

    private func requireConnection() throws -> ServerConnection {
        guard let connection = connectionProvider() else {
            throw MessagesAPIError.noActiveConnection
        }
        return connection
    }

    func listMessages(sessionID: String, directory: String? = nil) async throws -> [MessageDTO] {
        try await get(path: "session/\(sessionID)/message", directory: directory)
    }

    func listMessagesPage(
        sessionID: String,
        directory: String? = nil,
        limit: Int,
        before: String?
    ) async throws -> (messages: [MessageDTO], nextCursor: String?) {
        var query = ["limit": String(limit)]
        if let before, !before.isEmpty {
            query["before"] = before
        }
        let (messages, headers): ([MessageDTO], [AnyHashable: Any]) = try await getWithHeaders(
            path: "session/\(sessionID)/message",
            directory: directory,
            query: query
        )
        return (messages, MessageListCursor.nextCursor(headers: headers))
    }

    func listMessagesV2(sessionID: String, directory: String? = nil) async throws -> [V2SessionMessageDTO] {
        var messages: [V2SessionMessageDTO] = []
        var cursor: String?
        var pages = 0
        repeat {
            // Cursor tokens already encode order. Sending `order` with `cursor`
            // returns InvalidCursorError 400 on live v2.
            var query: [String: String] = [:]
            if let cursor {
                query["cursor"] = cursor
            } else {
                query["order"] = "asc"
            }
            let envelope: APIListEnvelope<V2SessionMessageDTO> = try await get(
                path: "api/session/\(sessionID)/message",
                directory: nil,
                query: query
            )
            messages.append(contentsOf: envelope.data)
            let next = envelope.cursor?.next.flatMap { $0.isEmpty ? nil : $0 }
            if next == cursor { break }
            cursor = next
            pages += 1
        } while cursor != nil && pages < Self.maxV2MessageListPages
        return messages
    }

    func listMessagesV2Page(
        sessionID: String,
        directory: String? = nil,
        limit: Int,
        order: String?,
        cursor: String?
    ) async throws -> (messages: [V2SessionMessageDTO], nextCursor: String?) {
        var query: [String: String] = ["limit": String(limit)]
        if let cursor, !cursor.isEmpty {
            query["cursor"] = cursor
        } else if let order, !order.isEmpty {
            query["order"] = order
        }
        let envelope: APIListEnvelope<V2SessionMessageDTO> = try await get(
            path: "api/session/\(sessionID)/message",
            directory: nil,
            query: query
        )
        let next = envelope.cursor?.next.flatMap { $0.isEmpty ? nil : $0 }
        return (envelope.data, next)
    }

    func listInboxV2(sessionID: String) async throws -> [V2InboxItemDTO] {
        let envelope: APIListEnvelope<V2InboxItemDTO> = try await get(
            path: "api/session/\(sessionID)/inbox",
            directory: nil
        )
        return envelope.data
    }

    func getMessage(sessionID: String, messageID: String, directory: String? = nil) async throws -> MessageDTO {
        try await get(path: "session/\(sessionID)/message/\(messageID)", directory: directory)
    }

    func sendMessage(sessionID: String, body: SendMessageRequestDTO, directory: String? = nil) async throws -> MessageDTO {
        try await post(path: "session/\(sessionID)/message", body: body, directory: directory)
    }

    func sendPromptAsync(sessionID: String, body: SendMessageRequestDTO, directory: String? = nil) async throws {
        try await sendPromptAsync(sessionID: sessionID, body: body, attachments: [], directory: directory)
    }

    func sendPromptAsync(
        sessionID: String,
        body: SendMessageRequestDTO,
        attachments: [Attachment],
        directory: String? = nil
    ) async throws {
        var request = try requireConnection().authorizedRequest(path: "session/\(sessionID)/prompt_async", method: "POST", directory: directory)
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        _ = try await sendPromptBody(request, encoded: {
            if attachments.contains(where: \.usesFileBackedBody) || body.parts.contains(where: { $0.fileSource != nil }) {
                try PromptJSONBodyWriter.writeV1Prompt(body, attachments: attachments)
            } else {
                nil
            }
        }, fallback: { try encoder.encode(body) })
    }

    func sendPromptV2(sessionID: String, body: V2PromptRequestDTO, directory: String? = nil) async throws {
        try await sendPromptV2(sessionID: sessionID, body: body, attachments: [], directory: directory)
    }

    func sendPromptV2(
        sessionID: String,
        body: V2PromptRequestDTO,
        attachments: [Attachment],
        directory: String? = nil
    ) async throws {
        try await postPrompt(path: "api/session/\(sessionID)/prompt", body: body, attachments: attachments, directory: nil)
    }

    func switchSessionAgent(sessionID: String, agent: String, directory: String? = nil) async throws {
        try await postNoContent(
            path: "api/session/\(sessionID)/agent",
            body: V2SwitchAgentRequestDTO(agent: agent),
            directory: nil
        )
    }

    func switchSessionModel(sessionID: String, body: V2SwitchModelRequestDTO, directory: String? = nil) async throws {
        try await postNoContent(path: "api/session/\(sessionID)/model", body: body, directory: nil)
    }

    /// Both protocols run the command before they answer, so the request stays open for as
    /// long as the command does. It goes through the durable uploader, like a prompt, instead
    /// of the REST pool's 60-second resource ceiling.
    func runShell(sessionID: String, body: V1ShellRequestDTO, directory: String? = nil) async throws {
        try await postShell(path: "session/\(sessionID)/shell", body: body, directory: directory)
    }

    func runShellV2(sessionID: String, body: V2ShellRequestDTO) async throws {
        try await postShell(path: "api/session/\(sessionID)/shell", body: body, directory: nil)
    }

    private func postShell<B: Encodable>(path: String, body: B, directory: String?) async throws {
        var request = try requireConnection().authorizedRequest(path: path, method: "POST", directory: directory)
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        _ = try await sendBody(request, body: try encoder.encode(body))
    }

    private func postPrompt(path: String, body: V2PromptRequestDTO, attachments: [Attachment], directory: String?) async throws {
        var request = try requireConnection().authorizedRequest(path: path, method: "POST", directory: directory)
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        _ = try await sendPromptBody(request, encoded: {
            if attachments.contains(where: \.usesFileBackedBody) || body.fileMentions?.isEmpty == false {
                try PromptJSONBodyWriter.writeV2Prompt(body, attachments: attachments)
            } else {
                nil
            }
        }, fallback: { try encoder.encode(body) })
    }

    private func postNoContent<B: Encodable>(path: String, body: B, directory: String?) async throws {
        var request = try requireConnection().authorizedRequest(path: path, method: "POST", directory: directory)
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try encoder.encode(body)
        _ = try await data(for: request)
    }

    private func sendPromptBody(
        _ request: URLRequest,
        encoded: () throws -> URL?,
        fallback: () throws -> Data
    ) async throws -> Data {
        if let fileURL = try encoded() {
            defer { try? FileManager.default.removeItem(at: fileURL) }
            return try await sendBodyFile(request, fileURL: fileURL)
        }
        return try await sendBody(request, body: try fallback())
    }

    private func sendBodyFile(_ request: URLRequest, fileURL: URL) async throws -> Data {
        if let bodyUploader {
            let startedAt = OpenCodeAPITrace.start(request)
            let data: Data
            let response: URLResponse
            do {
                (data, response) = try await bodyUploader.upload(
                    request,
                    fileURL: fileURL,
                    onProgress: PromptSendProgressReporting.handler
                )
            } catch {
                OpenCodeAPITrace.failure(request, error: error, startedAt: startedAt)
                throw error
            }
            guard let http = response as? HTTPURLResponse else {
                OpenCodeAPITrace.invalidResponse(request, startedAt: startedAt)
                throw MessagesAPIError.invalidResponse
            }
            OpenCodeAPITrace.finish(request, statusCode: http.statusCode, startedAt: startedAt)
            guard (200 ..< 300).contains(http.statusCode) else {
                throw MessagesAPIError.httpStatus(http.statusCode, String(data: data, encoding: .utf8))
            }
            return data
        }
        var request = request
        request.httpBody = try Data(contentsOf: fileURL)
        return try await data(for: request)
    }

    private func sendBody(_ request: URLRequest, body: Data) async throws -> Data {
        if let bodyUploader {
            let startedAt = OpenCodeAPITrace.start(request)
            let data: Data
            let response: URLResponse
            do {
                (data, response) = try await bodyUploader.upload(
                    request,
                    body: body,
                    onProgress: PromptSendProgressReporting.handler
                )
            } catch {
                OpenCodeAPITrace.failure(request, error: error, startedAt: startedAt)
                throw error
            }
            guard let http = response as? HTTPURLResponse else {
                OpenCodeAPITrace.invalidResponse(request, startedAt: startedAt)
                throw MessagesAPIError.invalidResponse
            }
            OpenCodeAPITrace.finish(request, statusCode: http.statusCode, startedAt: startedAt)
            guard (200 ..< 300).contains(http.statusCode) else {
                throw MessagesAPIError.httpStatus(http.statusCode, String(data: data, encoding: .utf8))
            }
            return data
        }
        var request = request
        request.httpBody = body
        return try await data(for: request)
    }

    private func get<T: Decodable>(
        path: String,
        directory: String? = nil,
        query: [String: String] = [:]
    ) async throws -> T {
        try await getWithHeaders(path: path, directory: directory, query: query).0
    }

    private func getWithHeaders<T: Decodable>(
        path: String,
        directory: String? = nil,
        query: [String: String] = [:]
    ) async throws -> (T, [AnyHashable: Any]) {
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
        let (data, headers) = try await dataAndHeaders(for: request)
        return (try decoder.decode(T.self, from: data), headers)
    }

    private func post<T: Decodable, B: Encodable>(path: String, body: B, directory: String? = nil) async throws -> T {
        var request = try requireConnection().authorizedRequest(path: path, method: "POST", directory: directory)
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try encoder.encode(body)
        return try await send(request)
    }

    private func send<T: Decodable>(_ request: URLRequest) async throws -> T {
        let data = try await data(for: request)
        return try decoder.decode(T.self, from: data)
    }

    private func data(for request: URLRequest) async throws -> Data {
        try await dataAndHeaders(for: request).0
    }

    private func dataAndHeaders(for request: URLRequest) async throws -> (Data, [AnyHashable: Any]) {
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
            throw MessagesAPIError.invalidResponse
        }
        OpenCodeAPITrace.finish(request, statusCode: http.statusCode, startedAt: startedAt)
        guard (200 ..< 300).contains(http.statusCode) else {
            let message = String(data: data, encoding: .utf8)
            throw MessagesAPIError.httpStatus(http.statusCode, message)
        }
        return (data, http.allHeaderFields)
    }

    private static let maxV2MessageListPages = 500
}
