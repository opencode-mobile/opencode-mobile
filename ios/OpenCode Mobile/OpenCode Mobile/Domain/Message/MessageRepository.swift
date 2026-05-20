import Foundation

protocol MessageRepository: Sendable {
    func listMessages(sessionID: String) async throws -> [Message]
    func getMessage(sessionID: String, messageID: String) async throws -> Message
    func sendMessage(sessionID: String, text: String) async throws -> Message
    func sendPromptAsync(sessionID: String, text: String) async throws
}

struct DefaultMessageRepository: MessageRepository, Sendable {
    private let api: MessagesAPIClient
    private let adapter: MessageDomainAdapter

    init(api: MessagesAPIClient, adapter: MessageDomainAdapter) {
        self.api = api
        self.adapter = adapter
    }

    func listMessages(sessionID: String) async throws -> [Message] {
        try await run { try await api.listMessages(sessionID: sessionID).map(adapter.message) }
    }

    func getMessage(sessionID: String, messageID: String) async throws -> Message {
        try await run { adapter.message(from: try await api.getMessage(sessionID: sessionID, messageID: messageID)) }
    }

    func sendMessage(sessionID: String, text: String) async throws -> Message {
        try await run {
            adapter.message(
                from: try await api.sendMessage(sessionID: sessionID, body: adapter.sendRequest(text: text))
            )
        }
    }

    func sendPromptAsync(sessionID: String, text: String) async throws {
        try await run {
            try await api.sendPromptAsync(sessionID: sessionID, body: adapter.sendRequest(text: text))
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
