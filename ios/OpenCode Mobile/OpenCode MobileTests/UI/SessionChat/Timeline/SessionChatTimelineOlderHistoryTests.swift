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

    @Test func toggleStepRailFold_flipsUnfoldedSet() {
        let timeline = SessionChatTimeline(
            sessionID: "ses-1",
            worktree: "/repo",
            messageRepository: OlderHistoryMessageRepository(
                initial: [],
                older: [],
                hasOlderInitially: false,
                hasOlderAfterLoad: false
            )
        )
        timeline.toggleStepRailFold("sr:r1")
        #expect(timeline.state.unfoldedStepRails == ["sr:r1"])
        timeline.toggleStepRailFold("sr:r1")
        #expect(timeline.state.unfoldedStepRails.isEmpty)
    }

    @Test func openAndDismissActivityCard_setPresentedCard() {
        let timeline = SessionChatTimeline(
            sessionID: "ses-1",
            worktree: "/repo",
            messageRepository: OlderHistoryMessageRepository(
                initial: [],
                older: [],
                hasOlderInitially: false,
                hasOlderAfterLoad: false
            )
        )
        #expect(timeline.state.presentedActivityCard == nil)
        timeline.openActivityCard("ac:r1")
        #expect(timeline.state.presentedActivityCard == "ac:r1")
        #expect(timeline.state.unfoldedStepRails.isEmpty)
        timeline.openActivityCard("ac:r2")
        #expect(timeline.state.presentedActivityCard == "ac:r2")
        timeline.dismissActivityCard()
        #expect(timeline.state.presentedActivityCard == nil)
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

    @Test func cacheSyncConfirmedQueriesHasOlderAfterCacheApply() async {
        let repository = ConfirmableOlderHistoryRepository(
            initial: [display("m3", role: .user)]
        )
        let timeline = SessionChatTimeline(
            sessionID: "ses-1",
            worktree: "/repo",
            messageRepository: repository
        )
        timeline.startObserving()
        let painted = await waitUntil {
            !timeline.state.messages.isEmpty && timeline.state.messagesFromCache
        }
        #expect(painted)
        #expect(!timeline.state.hasOlder)

        repository.windowHasOlder = true
        repository.emitCacheSyncConfirmed()
        let confirmed = await waitUntil { timeline.state.hasOlder }
        #expect(confirmed)
        #expect(!timeline.state.messagesFromCache)
        timeline.reset()
    }

    private func waitUntil(
        timeoutNanoseconds: UInt64 = 2_000_000_000,
        _ condition: @escaping @MainActor () -> Bool
    ) async -> Bool {
        let deadline = DispatchTime.now().uptimeNanoseconds + timeoutNanoseconds
        while DispatchTime.now().uptimeNanoseconds < deadline {
            if condition() { return true }
            try? await Task.sleep(nanoseconds: 10_000_000)
        }
        return condition()
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

    func runShellCommand(sessionID: String, options: ShellCommandOptions, directory: String?) async throws {}
}

private final class ConfirmableOlderHistoryRepository: MessageRepository, @unchecked Sendable {
    private let initial: [DisplayMessage]
    var windowHasOlder = false
    private let lock = NSLock()
    private var confirm: (() -> Void)?

    init(initial: [DisplayMessage]) {
        self.initial = initial
    }

    func observeMessages(sessionID: String, directory: String?) -> AsyncStream<MessageListUpdate> {
        AsyncStream { $0.finish() }
    }

    func observeDisplayMessages(sessionID: String, directory: String?) -> AsyncStream<DisplayMessageListUpdate> {
        AsyncStream { continuation in
            continuation.yield(.messages(initial, isFromCache: true))
            lock.lock()
            confirm = { continuation.yield(.cacheSyncConfirmed) }
            lock.unlock()
        }
    }

    func emitCacheSyncConfirmed() {
        lock.lock()
        let confirm = confirm
        lock.unlock()
        confirm?()
    }

    func refreshMessages(sessionID: String, directory: String?) async throws -> [Message] {
        initial.map(\.message)
    }

    func refreshDisplayMessages(sessionID: String, directory: String?) async throws -> [DisplayMessage] {
        initial
    }

    func hasOlderMessages(sessionID: String, directory: String?) -> Bool {
        windowHasOlder
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

    func runShellCommand(sessionID: String, options: ShellCommandOptions, directory: String?) async throws {}
}
