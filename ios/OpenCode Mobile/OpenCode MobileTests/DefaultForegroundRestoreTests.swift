import Foundation
import Testing
@testable import OpenCode_Mobile

@Suite
struct DefaultForegroundRestoreTests {
    @Test func restore_startsStreamAndRefreshesOpenSession() async {
        let stream = FakeForegroundEventStream()
        let messages = RestoreFakeMessageRepository()
        let restore = DefaultForegroundRestore(stream: stream, messageRepository: messages)
        restore.setOpenSession(sessionID: "ses-1", directory: "/tmp")

        restore.restore()
        let live = await waitUntil { restore.phase == .live }
        #expect(live)
        #expect(stream.connectionState == .connected)
        #expect(messages.refreshedSessions == ["ses-1"])
    }

    @Test func noteBackgroundStop_stopsStream() {
        let stream = FakeForegroundEventStream()
        let restore = DefaultForegroundRestore(
            stream: stream,
            messageRepository: RestoreFakeMessageRepository()
        )
        stream.start()
        restore.noteBackgroundStop()
        #expect(stream.connectionState == .idle)
        #expect(restore.phase == .idle)
    }

    @Test func restore_skipsRestWhenStreamStillLiveAndNoBackgroundStop() async {
        let stream = FakeForegroundEventStream()
        let messages = RestoreFakeMessageRepository()
        let restore = DefaultForegroundRestore(stream: stream, messageRepository: messages)
        restore.setOpenSession(sessionID: "ses-1", directory: "/tmp")
        restore.restore()
        _ = await waitUntil { restore.phase == .live }
        messages.refreshedSessions.removeAll()

        restore.restore()
        #expect(restore.phase == .live)
        #expect(messages.refreshedSessions.isEmpty)
    }

    @Test func noteBackground_thenRestore_refreshesEvenIfStreamStillLive() async {
        let stream = FakeForegroundEventStream()
        let messages = RestoreFakeMessageRepository()
        let restore = DefaultForegroundRestore(stream: stream, messageRepository: messages)
        restore.setOpenSession(sessionID: "ses-1", directory: "/tmp")
        restore.restore()
        _ = await waitUntil { restore.phase == .live }
        messages.refreshedSessions.removeAll()

        restore.noteBackground()
        #expect(stream.connectionState == .connected)
        restore.restore()
        let refreshed = await waitUntil { !messages.refreshedSessions.isEmpty }
        #expect(refreshed)
        #expect(messages.refreshedSessions == ["ses-1"])
        #expect(stream.connectionState == .connected)
    }

    @Test func restore_startOnlyDoesNotCancelInFlightCatchUp() async {
        let stream = FakeForegroundEventStream()
        let messages = RestoreFakeMessageRepository(refreshDelayNs: 150_000_000)
        let restore = DefaultForegroundRestore(stream: stream, messageRepository: messages)
        restore.setOpenSession(sessionID: "ses-1", directory: "/tmp")
        restore.restore()
        try? await Task.sleep(for: .milliseconds(20))
        restore.restore()
        let completed = await waitUntil(timeoutMs: 2_000) {
            !messages.completedSessions.isEmpty && restore.phase == .live
        }
        #expect(completed)
        #expect(messages.completedSessions == ["ses-1"])
    }

    @Test func setOpenSession_afterRestoreWithoutSession_refreshesThatSession() async {
        let stream = FakeForegroundEventStream()
        let messages = RestoreFakeMessageRepository()
        let restore = DefaultForegroundRestore(stream: stream, messageRepository: messages)
        restore.restore()
        _ = await waitUntil { restore.phase == .live }
        #expect(messages.refreshedSessions.isEmpty)

        restore.setOpenSession(sessionID: "ses-1", directory: "/tmp")
        let refreshed = await waitUntil { !messages.refreshedSessions.isEmpty }
        #expect(refreshed)
        #expect(messages.refreshedSessions == ["ses-1"])
    }

    @Test func setOpenSession_duringHomeCatchUp_stillCompletesHome() async {
        let stream = FakeForegroundEventStream()
        let messages = RestoreFakeMessageRepository()
        let home = ForegroundHomeCatchUpRegistry()
        let lock = NSLock()
        var completedCount = 0
        home.setHandler {
            try? await Task.sleep(nanoseconds: 150_000_000)
            if Task.isCancelled { return }
            lock.lock()
            completedCount += 1
            lock.unlock()
        }
        let restore = DefaultForegroundRestore(
            stream: stream,
            messageRepository: messages,
            homeCatchUpRegistry: home
        )
        restore.setHomeVisible(true)
        restore.restore()
        try? await Task.sleep(for: .milliseconds(20))
        restore.setHomeVisible(false)
        restore.setOpenSession(sessionID: "ses-1", directory: "/tmp")
        let done = await waitUntil(timeoutMs: 2_000) {
            lock.lock()
            let homeDone = completedCount >= 1
            lock.unlock()
            return homeDone && !messages.completedSessions.isEmpty && restore.phase == .live
        }
        #expect(done)
        #expect(messages.completedSessions == ["ses-1"])
    }

    private func waitUntil(
        timeoutMs: Int = 2_000,
        predicate: () -> Bool
    ) async -> Bool {
        let deadline = Date().addingTimeInterval(TimeInterval(timeoutMs) / 1_000)
        while Date() < deadline {
            if predicate() { return true }
            try? await Task.sleep(for: .milliseconds(10))
        }
        return predicate()
    }
}

private final class RestoreFakeMessageRepository: MessageRepository, @unchecked Sendable {
    private let lock = NSLock()
    private let refreshDelayNs: UInt64
    private var _refreshedSessions: [String] = []
    private var _completedSessions: [String] = []

    var refreshedSessions: [String] {
        get {
            lock.lock()
            defer { lock.unlock() }
            return _refreshedSessions
        }
        set {
            lock.lock()
            _refreshedSessions = newValue
            lock.unlock()
        }
    }

    var completedSessions: [String] {
        lock.lock()
        defer { lock.unlock() }
        return _completedSessions
    }

    init(refreshDelayNs: UInt64 = 0) {
        self.refreshDelayNs = refreshDelayNs
    }

    func observeMessages(sessionID: String, directory: String?) -> AsyncStream<MessageListUpdate> {
        AsyncStream { $0.finish() }
    }

    func observeDisplayMessages(sessionID: String, directory: String?) -> AsyncStream<DisplayMessageListUpdate> {
        AsyncStream { $0.finish() }
    }

    func refreshMessages(sessionID: String, directory: String?) async throws -> [Message] {
        []
    }

    func refreshDisplayMessages(sessionID: String, directory: String?) async throws -> [DisplayMessage] {
        lock.lock()
        _refreshedSessions.append(sessionID)
        lock.unlock()
        if refreshDelayNs > 0 {
            try await Task.sleep(nanoseconds: refreshDelayNs)
        }
        lock.lock()
        _completedSessions.append(sessionID)
        lock.unlock()
        return []
    }

    func prepareOptimisticUserMessage(id: String, text: String) async -> DisplayMessage {
        DisplayMessage(
            message: Message(id: id, role: .user, text: text, model: nil, mode: nil, sentAtEpochMs: nil)
        )
    }

    func clearMessageCache() {}

    func listMessages(sessionID: String, directory: String?) async throws -> [Message] {
        []
    }

    func getMessage(sessionID: String, messageID: String, directory: String?) async throws -> Message {
        throw MessageError.transport("unused")
    }

    func sendMessage(sessionID: String, text: String, directory: String?) async throws -> Message {
        throw MessageError.transport("unused")
    }

    func sendPromptAsync(sessionID: String, options: PromptSendOptions, directory: String?) async throws {}
}
