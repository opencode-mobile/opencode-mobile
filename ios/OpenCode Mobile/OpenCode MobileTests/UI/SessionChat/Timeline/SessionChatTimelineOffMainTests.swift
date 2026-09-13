import Foundation
import Testing
@testable import OpenCode_Mobile

@MainActor
struct SessionChatTimelineOffMainTests {
    @Test func applyLoadedDisplayMessages_buildsFrameOffMainThread() async {
        DisplayFrameBuilder.lastBuildWasOnMainThread = nil

        let repository = OffMainTimelineMessageRepository(
            messages: [
                Message(
                    id: "m1",
                    role: .assistant,
                    text: String(repeating: "hello ", count: 200),
                    model: "model",
                    mode: "build",
                    sentAtEpochMs: 1_700_000_000_000
                ),
            ]
        )
        let timeline = SessionChatTimeline(
            sessionID: "ses-1",
            worktree: "/repo",
            messageRepository: repository
        )
        timeline.startObserving()
        let ready = await waitUntil {
            timeline.state.timeline == .ready && !timeline.state.messages.isEmpty
        }
        #expect(ready)
        #expect(DisplayFrameBuilder.lastBuildWasOnMainThread == false)
        timeline.reset()
    }

    @Test func startObserving_warmReopen_doesNotShowSyncing() async {
        let message = Message(
            id: "m1",
            role: .user,
            text: "Hello",
            model: nil,
            mode: nil,
            sentAtEpochMs: 1
        )
        let repository = OffMainTimelineMessageRepository(
            messages: [message],
            warmPeek: true,
            holdObservation: true
        )
        let timeline = SessionChatTimeline(
            sessionID: "ses-1",
            worktree: "/repo",
            messageRepository: repository
        )
        timeline.startObserving()
        let ready = await waitUntil {
            timeline.state.timeline == .ready && !timeline.state.messages.isEmpty
        }
        #expect(ready)
        #expect(!timeline.state.isSyncingMessages)
        #expect(!timeline.state.messagesFromCache)
        timeline.reset()
    }

    @Test func reloadAfterSend_invokesOnCompleteWhenApplyIsStale() async {
        let repository = OffMainTimelineMessageRepository(
            messages: [
                Message(id: "m1", role: .user, text: "one", model: nil, mode: nil, sentAtEpochMs: 1),
            ]
        )
        let timeline = SessionChatTimeline(
            sessionID: "ses-stale-send",
            worktree: "/repo",
            messageRepository: repository
        )
        await timeline.loadMessages()

        var holdFirst = true
        var firstHeld: CheckedContinuation<Void, Never>?
        SessionChatTimeline.applyTestGate = {
            guard holdFirst else { return }
            holdFirst = false
            await withCheckedContinuation { continuation in
                firstHeld = continuation
            }
        }
        defer { SessionChatTimeline.applyTestGate = nil }

        var completed = false
        let reload = Task {
            await timeline.reloadAfterSend(
                onComplete: { completed = true },
                onReloadFailed: { Issue.record("reload failed") }
            )
        }
        let held = await waitUntil { firstHeld != nil }
        #expect(held)
        await timeline.refreshMessages()
        firstHeld?.resume()
        await reload.value
        #expect(completed)
        timeline.reset()
    }

    @Test func applyLoadedDisplayMessages_dropsStaleDetachedResult() async {
        let repository = OffMainTimelineMessageRepository(
            messages: [
                Message(id: "m1", role: .user, text: "one", model: nil, mode: nil, sentAtEpochMs: 1),
            ]
        )
        let timeline = SessionChatTimeline(
            sessionID: "ses-stale",
            worktree: "/repo",
            messageRepository: repository
        )
        // Start two overlapping applies; the second bumps displayFrameRevision.
        async let first: Void = timeline.loadMessages()
        async let second: Void = timeline.refreshMessages()
        _ = await (first, second)
        #expect(timeline.state.messages.map(\.id) == ["m1"])
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

private final class OffMainTimelineMessageRepository: MessageRepository, @unchecked Sendable {
    private let messages: [Message]
    private let warmPeek: Bool
    private let holdObservation: Bool

    init(messages: [Message], warmPeek: Bool = false, holdObservation: Bool = false) {
        self.messages = messages
        self.warmPeek = warmPeek
        self.holdObservation = holdObservation
    }

    func observeMessages(sessionID: String, directory: String?) -> AsyncStream<MessageListUpdate> {
        AsyncStream { continuation in
            if holdObservation { return }
            continuation.yield(.messages(messages, isFromCache: false))
            continuation.finish()
        }
    }

    func observeDisplayMessages(sessionID: String, directory: String?) -> AsyncStream<DisplayMessageListUpdate> {
        AsyncStream { continuation in
            if holdObservation { return }
            continuation.yield(.messages(messages.map { DisplayMessage(message: $0) }, isFromCache: false))
            continuation.finish()
        }
    }

    func peekWarmDisplayMessages(sessionID: String, directory: String?) -> [DisplayMessage]? {
        guard warmPeek else { return nil }
        return messages.map { DisplayMessage(message: $0) }
    }

    func refreshMessages(sessionID: String, directory: String?) async throws -> [Message] {
        messages
    }

    func refreshDisplayMessages(sessionID: String, directory: String?) async throws -> [DisplayMessage] {
        messages.map { DisplayMessage(message: $0) }
    }

    func prepareOptimisticUserMessage(id: String, text: String) async -> DisplayMessage {
        DisplayMessage(message: Message(id: id, role: .user, text: text, model: nil, mode: nil, sentAtEpochMs: 0))
    }

    func clearMessageCache() {}

    func listMessages(sessionID: String, directory: String?) async throws -> [Message] {
        messages
    }

    func getMessage(sessionID: String, messageID: String, directory: String?) async throws -> Message {
        messages.first { $0.id == messageID }!
    }

    func sendMessage(sessionID: String, text: String, directory: String?) async throws -> Message {
        Message(id: "sent", role: .user, text: text, model: nil, mode: nil, sentAtEpochMs: 0)
    }

    func sendPromptAsync(sessionID: String, options: PromptSendOptions, directory: String?) async throws {}
}
