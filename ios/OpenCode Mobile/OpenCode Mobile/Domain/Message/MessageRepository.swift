import Foundation

protocol MessageRepository: Sendable {
    func observeMessages(sessionID: String, directory: String?) -> AsyncStream<MessageListUpdate>
    func observeDisplayMessages(sessionID: String, directory: String?) -> AsyncStream<DisplayMessageListUpdate>
    func observeCatchUpChrome() -> AsyncStream<Bool>
    func observeCatchUpChrome(sessionID: String, directory: String?) -> AsyncStream<Bool>
    func peekWarmDisplayMessages(sessionID: String, directory: String?) -> [DisplayMessage]?
    func refreshMessages(sessionID: String, directory: String?) async throws -> [Message]
    func refreshDisplayMessages(sessionID: String, directory: String?) async throws -> [DisplayMessage]
    func prepareOptimisticUserMessage(id: String, text: String) async -> DisplayMessage
    func clearMessageCache()
    func listMessages(sessionID: String, directory: String?) async throws -> [Message]
    func listMessagePage(sessionID: String, directory: String?, query: MessageHistoryQuery) async throws -> MessageHistoryPage
    func loadOlderMessages(sessionID: String, directory: String?) async throws -> [Message]
    func loadOlderDisplayMessages(sessionID: String, directory: String?) async throws -> [DisplayMessage]
    func hasOlderMessages(sessionID: String, directory: String?) -> Bool
    func getMessage(sessionID: String, messageID: String, directory: String?) async throws -> Message
    func sendMessage(sessionID: String, text: String, directory: String?) async throws -> Message
    func sendPromptAsync(sessionID: String, options: PromptSendOptions, directory: String?) async throws
    func sendPromptAsync(sessionID: String, text: String, directory: String?) async throws
    /// Runs a composer `!` command in the session; its output arrives through shell events.
    func runShellCommand(sessionID: String, options: ShellCommandOptions, directory: String?) async throws
}

extension MessageRepository {
    func observeCatchUpChrome() -> AsyncStream<Bool> {
        AsyncStream { continuation in
            continuation.yield(false)
            continuation.finish()
        }
    }

    func observeCatchUpChrome(sessionID: String, directory: String?) -> AsyncStream<Bool> {
        observeCatchUpChrome()
    }

    func peekWarmDisplayMessages(sessionID: String, directory: String?) -> [DisplayMessage]? { nil }

    func listMessagePage(
        sessionID _: String,
        directory _: String?,
        query _: MessageHistoryQuery
    ) async throws -> MessageHistoryPage {
        throw MessageHistoryPagingError.pageRequestNotImplemented
    }

    func loadOlderMessages(sessionID _: String, directory _: String?) async throws -> [Message] {
        throw MessageHistoryPagingError.pageRequestNotImplemented
    }

    func loadOlderDisplayMessages(sessionID: String, directory: String?) async throws -> [DisplayMessage] {
        try await loadOlderMessages(sessionID: sessionID, directory: directory).map { DisplayMessage(message: $0) }
    }

    func hasOlderMessages(sessionID _: String, directory _: String?) -> Bool { false }

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
    private let messageRemote: any MessageRemote

    init(
        api: any MessagesAPI,
        adapter: MessageDomainAdapter,
        messageSync: MessageSync,
        displayMessagePipeline: DisplayMessagePipeline,
        contentPreparer: MessageContentPreparer,
        messageRemote: (any MessageRemote)? = nil
    ) {
        self.api = api
        self.adapter = adapter
        self.messageSync = messageSync
        self.displayMessagePipeline = displayMessagePipeline
        self.contentPreparer = contentPreparer
        self.messageRemote = messageRemote ?? ProtocolSelectedMessageRemote(api: api, adapter: adapter)
    }

    func observeMessages(sessionID: String, directory: String?) -> AsyncStream<MessageListUpdate> {
        messageSync.observeMessages(sessionID: sessionID, directory: directory)
    }

    func observeDisplayMessages(sessionID: String, directory: String?) -> AsyncStream<DisplayMessageListUpdate> {
        displayMessagePipeline.observeDisplayMessages(sessionID: sessionID, directory: directory)
    }

    func observeCatchUpChrome() -> AsyncStream<Bool> {
        messageSync.observeCatchUpChrome()
    }

    func observeCatchUpChrome(sessionID: String, directory: String?) -> AsyncStream<Bool> {
        messageSync.observeCatchUpChrome(sessionID: sessionID, directory: directory)
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
        try await run { try await messageRemote.listMessages(sessionID: sessionID, directory: directory) }
    }

    func listMessagePage(
        sessionID: String,
        directory: String?,
        query: MessageHistoryQuery
    ) async throws -> MessageHistoryPage {
        try await run {
            try await messageRemote.listMessagePage(sessionID: sessionID, directory: directory, query: query)
        }
    }

    func loadOlderMessages(sessionID: String, directory: String?) async throws -> [Message] {
        try await run { try await messageSync.loadOlderMessages(sessionID: sessionID, directory: directory) }
    }

    func loadOlderDisplayMessages(sessionID: String, directory: String?) async throws -> [DisplayMessage] {
        let messages = try await loadOlderMessages(sessionID: sessionID, directory: directory)
        return await contentPreparer.prepareMessages(messages)
    }

    func hasOlderMessages(sessionID: String, directory: String?) -> Bool {
        messageSync.hasOlderMessages(sessionID: sessionID, directory: directory)
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
            "prompt_async session=\(sessionID) fileParts=\(options.attachments.count) textCharacters=\(options.text.count)"
        )
        DiagnosticLog.info(
            .chat,
            "prompt_async session=\(sessionID) fileParts=\(options.attachments.count) textCharacters=\(options.text.count)"
        )
        try await run {
            try await messageRemote.sendPromptAsync(
                sessionID: sessionID,
                options: options,
                directory: directory
            )
        }
        DiagnosticLog.info(.chat, "prompt_async done session=\(sessionID)")
    }

    func runShellCommand(sessionID: String, options: ShellCommandOptions, directory: String?) async throws {
        DiagnosticLog.info(.chat, "runShellCommand session=\(sessionID)")
        try await run {
            try await messageRemote.runShellCommand(sessionID: sessionID, options: options, directory: directory)
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
