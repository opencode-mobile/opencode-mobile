import Foundation

@MainActor
final class EngineMessageRepository: MessageRepository {
    private let opencode: any MessageRepository
    private let t3: any MessageRepository
    private let activeConnectionManager: ActiveConnectionManager

    init(
        opencode: any MessageRepository,
        t3: any MessageRepository,
        activeConnectionManager: ActiveConnectionManager
    ) {
        self.opencode = opencode
        self.t3 = t3
        self.activeConnectionManager = activeConnectionManager
    }

    private func selected() -> any MessageRepository {
        activeConnectionManager.currentProfile()?.engine == .t3 ? t3 : opencode
    }

    func observeMessages(sessionID: String, directory: String?) -> AsyncStream<MessageListUpdate> {
        selected().observeMessages(sessionID: sessionID, directory: directory)
    }

    func observeDisplayMessages(sessionID: String, directory: String?) -> AsyncStream<DisplayMessageListUpdate> {
        selected().observeDisplayMessages(sessionID: sessionID, directory: directory)
    }

    func observeCatchUpChrome() -> AsyncStream<Bool> {
        selected().observeCatchUpChrome()
    }

    func observeCatchUpChrome(sessionID: String, directory: String?) -> AsyncStream<Bool> {
        selected().observeCatchUpChrome(sessionID: sessionID, directory: directory)
    }

    func peekWarmDisplayMessages(sessionID: String, directory: String?) -> [DisplayMessage]? {
        selected().peekWarmDisplayMessages(sessionID: sessionID, directory: directory)
    }

    func refreshMessages(sessionID: String, directory: String?) async throws -> [Message] {
        try await selected().refreshMessages(sessionID: sessionID, directory: directory)
    }

    func refreshDisplayMessages(sessionID: String, directory: String?) async throws -> [DisplayMessage] {
        try await selected().refreshDisplayMessages(sessionID: sessionID, directory: directory)
    }

    func prepareOptimisticUserMessage(id: String, text: String) async -> DisplayMessage {
        await selected().prepareOptimisticUserMessage(id: id, text: text)
    }

    func clearMessageCache() {
        // Clearing is engine-agnostic: a profile switch must not leave the
        // other engine's transcripts and image previews behind.
        opencode.clearMessageCache()
        t3.clearMessageCache()
    }

    func listMessages(sessionID: String, directory: String?) async throws -> [Message] {
        try await selected().listMessages(sessionID: sessionID, directory: directory)
    }

    func listMessagePage(
        sessionID: String,
        directory: String?,
        query: MessageHistoryQuery
    ) async throws -> MessageHistoryPage {
        try await selected().listMessagePage(sessionID: sessionID, directory: directory, query: query)
    }

    func loadOlderMessages(sessionID: String, directory: String?) async throws -> [Message] {
        try await selected().loadOlderMessages(sessionID: sessionID, directory: directory)
    }

    func loadOlderDisplayMessages(sessionID: String, directory: String?) async throws -> [DisplayMessage] {
        try await selected().loadOlderDisplayMessages(sessionID: sessionID, directory: directory)
    }

    func hasOlderMessages(sessionID: String, directory: String?) -> Bool {
        selected().hasOlderMessages(sessionID: sessionID, directory: directory)
    }

    func getMessage(sessionID: String, messageID: String, directory: String?) async throws -> Message {
        try await selected().getMessage(sessionID: sessionID, messageID: messageID, directory: directory)
    }

    func sendMessage(sessionID: String, text: String, directory: String?) async throws -> Message {
        try await selected().sendMessage(sessionID: sessionID, text: text, directory: directory)
    }

    func sendPromptAsync(sessionID: String, options: PromptSendOptions, directory: String?) async throws {
        try await selected().sendPromptAsync(sessionID: sessionID, options: options, directory: directory)
    }

    func sendPromptAsync(sessionID: String, text: String, directory: String?) async throws {
        try await selected().sendPromptAsync(sessionID: sessionID, text: text, directory: directory)
    }
}
