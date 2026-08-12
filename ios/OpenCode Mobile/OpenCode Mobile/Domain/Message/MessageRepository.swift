import Foundation
import OSLog

protocol MessageRepository: Sendable {
    func observeMessages(sessionID: String, directory: String?) -> AsyncStream<MessageListUpdate>
    func observeDisplayMessages(sessionID: String, directory: String?) -> AsyncStream<DisplayMessageListUpdate>
    func peekWarmDisplayMessages(sessionID: String, directory: String?) -> [DisplayMessage]?
    func refreshMessages(sessionID: String, directory: String?) async throws -> [Message]
    func refreshDisplayMessages(sessionID: String, directory: String?) async throws -> [DisplayMessage]
    func prepareOptimisticUserMessage(id: String, text: String) async -> DisplayMessage
    func clearMessageCache()
    func listMessages(sessionID: String, directory: String?) async throws -> [Message]
    func getMessage(sessionID: String, messageID: String, directory: String?) async throws -> Message
    func sendMessage(sessionID: String, text: String, directory: String?) async throws -> Message
    func sendPromptAsync(sessionID: String, options: PromptSendOptions, directory: String?) async throws
    func sendPromptAsync(sessionID: String, text: String, directory: String?) async throws
}

extension MessageRepository {
    func peekWarmDisplayMessages(sessionID: String, directory: String?) -> [DisplayMessage]? { nil }

    func sendPromptAsync(sessionID: String, text: String, directory: String?) async throws {
        try await sendPromptAsync(sessionID: sessionID, options: PromptSendOptions(text: text, model: nil, variant: nil, mode: nil), directory: directory)
    }
}

@MainActor
final class DefaultMessageRepository: MessageRepository {
    private let api: any MessagesAPI
    private let adapter: MessageDomainAdapter
    private let messageSync: MessageSync
    private let displayMessagePipeline: DisplayMessagePipeline
    private let contentPreparer: MessageContentPreparer
    private let protocolRouter: MessageProtocolRouter

    init(
        api: any MessagesAPI,
        adapter: MessageDomainAdapter,
        messageSync: MessageSync,
        displayMessagePipeline: DisplayMessagePipeline,
        contentPreparer: MessageContentPreparer,
        protocolRouter: MessageProtocolRouter? = nil
    ) {
        self.api = api
        self.adapter = adapter
        self.messageSync = messageSync
        self.displayMessagePipeline = displayMessagePipeline
        self.contentPreparer = contentPreparer
        self.protocolRouter = protocolRouter ?? MessageProtocolRouter(api: api, adapter: adapter)
    }

    func observeMessages(sessionID: String, directory: String?) -> AsyncStream<MessageListUpdate> {
        messageSync.observeMessages(sessionID: sessionID, directory: directory)
    }

    func observeDisplayMessages(sessionID: String, directory: String?) -> AsyncStream<DisplayMessageListUpdate> {
        displayMessagePipeline.observeDisplayMessages(sessionID: sessionID, directory: directory)
    }

    func peekWarmDisplayMessages(sessionID: String, directory: String?) -> [DisplayMessage]? {
        displayMessagePipeline.peekWarmDisplayMessages(sessionID: sessionID, directory: directory)
    }

    func refreshMessages(sessionID: String, directory: String?) async throws -> [Message] {
        try await messageSync.refreshMessages(sessionID: sessionID, directory: directory)
    }

    func refreshDisplayMessages(sessionID: String, directory: String?) async throws -> [DisplayMessage] {
        try await displayMessagePipeline.refreshDisplayMessages(sessionID: sessionID, directory: directory)
    }

    func prepareOptimisticUserMessage(id: String, text: String) async -> DisplayMessage {
        await contentPreparer.prepareOptimisticUserMessage(id: id, text: text)
    }

    func clearMessageCache() {
        messageSync.clearCache()
        displayMessagePipeline.clearPreparedCache()
    }

    func listMessages(sessionID: String, directory: String?) async throws -> [Message] {
        try await run { try await protocolRouter.listMessages(sessionID: sessionID, directory: directory) }
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

    func sendPromptAsync(sessionID: String, options: PromptSendOptions, directory: String?) async throws {
        OpenCodeLog.sessionChat.info(
            "prompt_async session=\(sessionID, privacy: .public) fileParts=\(options.attachments.count) textCharacters=\(options.text.count)"
        )
        DiagnosticLog.info(
            .chat,
            "prompt_async session=\(sessionID) fileParts=\(options.attachments.count) textCharacters=\(options.text.count)"
        )
        try await run {
            try await api.sendPromptAsync(
                sessionID: sessionID,
                body: adapter.sendRequest(options: options),
                directory: directory
            )
        }
        DiagnosticLog.info(.chat, "prompt_async done session=\(sessionID)")
    }

    private func run<T>(_ work: () async throws -> T) async throws -> T {
        do {
            return try await work()
        } catch {
            throw adapter.mapError(error)
        }
    }
}
