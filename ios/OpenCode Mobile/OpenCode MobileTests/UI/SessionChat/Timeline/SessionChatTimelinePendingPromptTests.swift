import Foundation
import Testing
@testable import OpenCode_Mobile

@MainActor
struct SessionChatTimelinePendingPromptTests {
    @Test func concurrentDeleteThenQueueComposesBothMutations() async {
        let repository = PendingPromptMessageRepository(initial: [steer("a"), steer("b")])
        let timeline = SessionChatTimeline(
            sessionID: "ses-1",
            worktree: "/repo",
            messageRepository: repository
        )
        await timeline.loadMessages()

        timeline.applyOptimisticPendingPrompt(messageID: "a", removed: true)
        timeline.applyOptimisticPendingPrompt(messageID: "b", delivery: .queue)
        let composed = await waitUntil {
            timeline.loadedMessages().map(\.id) == ["b"] &&
                timeline.loadedMessages().contains(where: { $0.id == "b" && $0.followUpDelivery == .queue })
        }

        #expect(composed)
        #expect(timeline.loadedMessages().map(\.id) == ["b"])
        #expect(queued(timeline.loadedMessages().first { $0.id == "b" }))
    }

    @Test func staleLiveSnapshotDoesNotRestoreDeletedPrompt() async {
        let repository = PendingPromptMessageRepository(initial: [steer("a"), steer("b")])
        let timeline = observingTimeline(repository: repository)
        let painted = await waitUntil { timeline.loadedMessages().map(\.id) == ["a", "b"] }
        #expect(painted)

        timeline.applyOptimisticPendingPrompt(messageID: "a", removed: true)
        let removed = await waitUntil { !timeline.loadedMessages().contains(where: { $0.id == "a" }) }
        #expect(removed)

        repository.emit([steer("a"), steer("b")])
        let held = await waitUntil(timeoutNanoseconds: 300_000_000) {
            timeline.loadedMessages().contains(where: { $0.id == "a" })
        }
        #expect(!held)
        #expect(timeline.loadedMessages().map(\.id) == ["b"])
        timeline.reset()
    }

    @Test func staleLiveSnapshotKeepsOptimisticQueueUntilAuthoritativeMatches() async {
        let repository = PendingPromptMessageRepository(initial: [steer("a")])
        let timeline = observingTimeline(repository: repository)
        let painted = await waitUntil { timeline.loadedMessages().map(\.id) == ["a"] }
        #expect(painted)

        timeline.applyOptimisticPendingPrompt(messageID: "a", delivery: .queue)
        let queuedLocally = await waitUntil {
            queued(timeline.loadedMessages().first { $0.id == "a" })
        }
        #expect(queuedLocally)

        repository.emit([steer("a")])
        let restoredSteer = await waitUntil(timeoutNanoseconds: 300_000_000) {
            timeline.loadedMessages().contains {
                $0.id == "a" && $0.followUpDelivery == .steer
            }
        }
        #expect(!restoredSteer)
        #expect(queued(timeline.loadedMessages().first { $0.id == "a" }))

        repository.emit([queuedMessage("a")])
        let matched = await waitUntil {
            queued(timeline.loadedMessages().first { $0.id == "a" })
        }
        #expect(matched)
        timeline.reset()
    }

    @Test func authoritativeOmitExpiresRemovedPatch() async {
        let repository = PendingPromptMessageRepository(initial: [steer("a"), steer("b")])
        let timeline = observingTimeline(repository: repository)
        let painted = await waitUntil { timeline.loadedMessages().map(\.id) == ["a", "b"] }
        #expect(painted)

        timeline.applyOptimisticPendingPrompt(messageID: "a", removed: true)
        _ = await waitUntil { !timeline.loadedMessages().contains(where: { $0.id == "a" }) }
        #expect(timeline.pendingPromptPatchCount > 0)

        repository.emit([steer("b")])
        let expired = await waitUntil { timeline.pendingPromptPatchCount == 0 }
        #expect(expired)

        repository.emit([steer("a"), steer("b")])
        let restored = await waitUntil { timeline.loadedMessages().map(\.id) == ["a", "b"] }
        #expect(restored)
        timeline.reset()
    }

    @Test func newerLiveSnapshotIsNotDroppedByOptimisticDelete() async {
        let repository = PendingPromptMessageRepository(initial: [steer("a"), steer("b")])
        let timeline = observingTimeline(repository: repository)
        let painted = await waitUntil { timeline.loadedMessages().map(\.id) == ["a", "b"] }
        #expect(painted)

        timeline.applyOptimisticPendingPrompt(messageID: "a", removed: true)
        repository.emit([steer("a"), steer("b"), steer("c")])
        let kept = await waitUntil { timeline.loadedMessages().map(\.id) == ["b", "c"] }
        #expect(kept)
        timeline.reset()
    }

    private func observingTimeline(repository: PendingPromptMessageRepository) -> SessionChatTimeline {
        let timeline = SessionChatTimeline(
            sessionID: "ses-1",
            worktree: "/repo",
            messageRepository: repository
        )
        timeline.startObserving()
        return timeline
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

private func queued(_ message: Message?) -> Bool {
    guard let message else { return false }
    return message.isWaitingForPickup && message.followUpDelivery == .queue
}

private func steer(_ id: String) -> DisplayMessage {
    DisplayMessage(
        message: Message(
            id: id,
            role: .user,
            text: id,
            model: nil,
            mode: nil,
            sentAtEpochMs: 1,
            followUpDelivery: .steer,
            isWaitingForPickup: true
        )
    )
}

private func queuedMessage(_ id: String) -> DisplayMessage {
    DisplayMessage(
        message: Message(
            id: id,
            role: .user,
            text: id,
            model: nil,
            mode: nil,
            sentAtEpochMs: 1,
            followUpDelivery: .queue,
            isWaitingForPickup: true
        )
    )
}

private final class PendingPromptMessageRepository: MessageRepository, @unchecked Sendable {
    private let initial: [DisplayMessage]
    private let lock = NSLock()
    private var continuations: [UUID: AsyncStream<DisplayMessageListUpdate>.Continuation] = [:]

    init(initial: [DisplayMessage]) {
        self.initial = initial
    }

    func observeMessages(sessionID: String, directory: String?) -> AsyncStream<MessageListUpdate> {
        AsyncStream { $0.finish() }
    }

    func observeDisplayMessages(sessionID: String, directory: String?) -> AsyncStream<DisplayMessageListUpdate> {
        AsyncStream { continuation in
            let id = UUID()
            lock.lock()
            continuations[id] = continuation
            lock.unlock()
            continuation.yield(.messages(initial, isFromCache: false))
            continuation.onTermination = { [weak self] _ in
                self?.lock.lock()
                self?.continuations[id] = nil
                self?.lock.unlock()
            }
        }
    }

    func emit(_ messages: [DisplayMessage]) {
        lock.lock()
        let continuations = Array(continuations.values)
        lock.unlock()
        for continuation in continuations {
            continuation.yield(.messages(messages, isFromCache: false))
        }
    }

    func refreshMessages(sessionID: String, directory: String?) async throws -> [Message] {
        initial.map(\.message)
    }

    func refreshDisplayMessages(sessionID: String, directory: String?) async throws -> [DisplayMessage] {
        initial
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
