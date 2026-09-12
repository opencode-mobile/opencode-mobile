import Foundation
import Testing
@testable import OpenCode_Mobile

@MainActor
struct SessionChatTimelineOlderHistoryTests {
    @Test func loadOlderPrependsRowsAndClearsLoadingFlag() async {
        let repository = OlderHistoryMessageRepository(
            initial: [display("m3", role: .user)],
            older: [display("m1", role: .user), display("m3", role: .user)],
            hasOlderInitially: true,
            hasOlderAfterLoad: false
        )
        let timeline = SessionChatTimeline(
            sessionID: "ses-1",
            worktree: "/repo",
            messageRepository: repository
        )
        await timeline.loadMessages()

        await timeline.loadOlderMessages()

        #expect(timeline.state.messages.map(\.id) == ["m1", "m3"])
        #expect(!timeline.state.isLoadingOlder)
        #expect(!timeline.state.hasOlder)
        #expect(repository.loadOlderCount == 1)
    }

    @Test func loadOlderFailureRetainsRowsAndAllowsRetry() async {
        let repository = OlderHistoryMessageRepository(
            initial: [display("m3", role: .user)],
            older: [display("m1", role: .user), display("m3", role: .user)],
            failOlder: true,
            hasOlderInitially: true,
            hasOlderAfterLoad: true
        )
        let timeline = SessionChatTimeline(
            sessionID: "ses-1",
            worktree: "/repo",
            messageRepository: repository
        )
        await timeline.loadMessages()

        await timeline.loadOlderMessages()
        #expect(timeline.state.messages.map(\.id) == ["m3"])
        #expect(!timeline.state.isLoadingOlder)
        #expect(timeline.state.hasOlder)

        repository.failOlder = false
        await timeline.loadOlderMessages()
        #expect(timeline.state.messages.map(\.id) == ["m1", "m3"])
        #expect(repository.loadOlderCount == 2)
    }

    @Test func endOfHistoryDoesNotLoadOlder() async {
        let repository = OlderHistoryMessageRepository(initial: [display("m1", role: .user)])
        let timeline = SessionChatTimeline(
            sessionID: "ses-1",
            worktree: "/repo",
            messageRepository: repository
        )
        await timeline.loadMessages()

        await timeline.loadOlderMessages()
        #expect(repository.loadOlderCount == 0)
    }
}

private func display(_ id: String, role: MessageRole) -> DisplayMessage {
    DisplayMessage(message: Message(id: id, role: role, text: id, model: nil, mode: nil, sentAtEpochMs: nil))
}

private final class OlderHistoryMessageRepository: MessageRepository, @unchecked Sendable {
    private let initial: [DisplayMessage]
    private let older: [DisplayMessage]
    var failOlder: Bool
    private var hasOlderInitially: Bool
    private var hasOlderAfterLoad: Bool
    var loadOlderCount = 0

    init(
        initial: [DisplayMessage],
        older: [DisplayMessage] = [],
        failOlder: Bool = false,
        hasOlderInitially: Bool = false,
        hasOlderAfterLoad: Bool = false
    ) {
        self.initial = initial
        self.older = older
        self.failOlder = failOlder
        self.hasOlderInitially = hasOlderInitially
        self.hasOlderAfterLoad = hasOlderAfterLoad
    }

    func observeMessages(sessionID: String, directory: String?) -> AsyncStream<MessageListUpdate> {
        AsyncStream { $0.finish() }
    }

    func observeDisplayMessages(sessionID: String, directory: String?) -> AsyncStream<DisplayMessageListUpdate> {
        AsyncStream { $0.finish() }
    }

    func refreshMessages(sessionID: String, directory: String?) async throws -> [Message] {
        initial.map(\.message)
    }

    func refreshDisplayMessages(sessionID: String, directory: String?) async throws -> [DisplayMessage] {
        initial
    }

    func loadOlderDisplayMessages(sessionID: String, directory: String?) async throws -> [DisplayMessage] {
        loadOlderCount += 1
        if failOlder { throw MessageError.transport("offline") }
        return older
    }

    func hasOlderMessages(sessionID: String, directory: String?) -> Bool {
        loadOlderCount == 0 ? hasOlderInitially : hasOlderAfterLoad
    }

    func prepareOptimisticUserMessage(id: String, text: String) async -> DisplayMessage {
        DisplayMessage(message: Message(id: id, role: .user, text: text, model: nil, mode: nil, sentAtEpochMs: 0))
    }

    func clearMessageCache() {}

    func listMessages(sessionID: String, directory: String?) async throws -> [Message] {
        initial.map(\.message)
    }

    func getMessage(sessionID: String, messageID: String, directory: String?) async throws -> Message {
        initial.map(\.message).first { $0.id == messageID }!
    }

    func sendMessage(sessionID: String, text: String, directory: String?) async throws -> Message {
        Message(id: "sent", role: .user, text: text, model: nil, mode: nil, sentAtEpochMs: 0)
    }

    func sendPromptAsync(sessionID: String, options: PromptSendOptions, directory: String?) async throws {}
}
