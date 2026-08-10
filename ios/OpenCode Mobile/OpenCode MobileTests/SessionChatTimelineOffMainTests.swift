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

    init(messages: [Message]) {
        self.messages = messages
    }

    func observeMessages(sessionID: String, directory: String?) -> AsyncStream<MessageListUpdate> {
        AsyncStream { continuation in
            continuation.yield(.messages(messages, isFromCache: false))
            continuation.finish()
        }
    }

    func observeDisplayMessages(sessionID: String, directory: String?) -> AsyncStream<DisplayMessageListUpdate> {
        AsyncStream { continuation in
            continuation.yield(.messages(messages.map { DisplayMessage(message: $0) }, isFromCache: false))
            continuation.finish()
        }
    }

    func peekWarmDisplayMessages(sessionID: String, directory: String?) -> [DisplayMessage]? {
        nil
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
