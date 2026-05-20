import Foundation

protocol MessageRepository: Sendable {
    func listMessages(sessionID: String, directory: String?) async throws -> [Message]
    func getMessage(sessionID: String, messageID: String, directory: String?) async throws -> Message
    func sendMessage(sessionID: String, text: String, directory: String?) async throws -> Message
    func sendPromptAsync(sessionID: String, text: String, directory: String?) async throws
}

struct DefaultMessageRepository: MessageRepository, Sendable {
    private let api: MessagesAPIClient
    private let adapter: MessageDomainAdapter

    init(api: MessagesAPIClient, adapter: MessageDomainAdapter) {
        self.api = api
        self.adapter = adapter
    }

    func listMessages(sessionID: String, directory: String?) async throws -> [Message] {
        try await run { try await api.listMessages(sessionID: sessionID, directory: directory).map(adapter.message) }
    }

    func getMessage(sessionID: String, messageID: String, directory: String?) async throws -> Message {
        try await run {
            adapter.message(from: try await api.getMessage(sessionID: sessionID, messageID: messageID, directory: directory))
        }
    }

    func sendMessage(sessionID: String, text: String, directory: String?) async throws -> Message {
        try await run {
            adapter.message(
                from: try await api.sendMessage(
                    sessionID: sessionID,
                    body: adapter.sendRequest(text: text),
                    directory: directory
                )
            )
        }
    }

    func sendPromptAsync(sessionID: String, text: String, directory: String?) async throws {
        try await run {
            try await api.sendPromptAsync(
                sessionID: sessionID,
                body: adapter.sendRequest(text: text),
                directory: directory
            )
        }
    }

    private func run<T>(_ work: () async throws -> T) async throws -> T {
        do {
            return try await work()
        } catch {
            throw adapter.mapError(error)
        }
    }
}
