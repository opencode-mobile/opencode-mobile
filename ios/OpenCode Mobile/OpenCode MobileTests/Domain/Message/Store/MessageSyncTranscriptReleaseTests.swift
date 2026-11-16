import Foundation
import Testing
@testable import OpenCode_Mobile

/// Releasing closed sessions' transcripts (``TranscriptRetentionPolicy``). Mirrors Android
/// `MessageSyncTranscriptReleaseTest`. The grace period runs on a manual sleeper, so no test
/// waits for it in wall time.
@Suite(.serialized)
@MainActor
struct MessageSyncTranscriptReleaseTests {
    private static let dir = "/Users/example/project"
    private static let profile = "profile-1"

    @Test func closedSessionIsReleasedAfterGracePeriod() async {
        let harness = ReleaseHarness()
        let screen = harness.observe("ses-1")
        #expect(await waitUntil { harness.engine.messages(sessionID: harness.slot("ses-1")).count == 2 })

        screen.cancel()
        #expect(await waitUntil { harness.sleeper.pendingCount == 1 })
        #expect(harness.sync.retainsTranscript(sessionID: "ses-1", directory: Self.dir))

        harness.sleeper.fireAll()
        #expect(await waitUntil { !harness.sync.retainsTranscript(sessionID: "ses-1", directory: Self.dir) })
        harness.expectReleased("ses-1")
    }

    @Test func warmCapReleasesOldestOfFourClosedSessions() async {
        let harness = ReleaseHarness()
        let ids = ["ses-1", "ses-2", "ses-3", "ses-4"]
        for (index, id) in ids.enumerated() {
            let screen = harness.observe(id)
            #expect(await waitUntil { harness.engine.messages(sessionID: harness.slot(id)).count == 2 })
            screen.cancel()
            #expect(await waitUntil { harness.sleeper.pendingCount == index + 1 })
        }

        #expect(await waitUntil { !harness.sync.retainsTranscript(sessionID: "ses-1", directory: Self.dir) })
        harness.expectReleased("ses-1")
        for id in ids.dropFirst() {
            #expect(harness.sync.retainsTranscript(sessionID: id, directory: Self.dir), "\(id) stays warm")
        }
    }

    @Test func sessionWithAnyObserverLeftIsKept() async {
        let harness = ReleaseHarness()
        // Session Chat in the navigation stack, a tool-group screen over it, and a parent's
        // activity card watching it all read the same store stream.
        let backStackChat = harness.observe("ses-1")
        let toolGroup = harness.observe("ses-1")
        let activityCard = harness.observe("ses-1")
        #expect(await waitUntil { harness.engine.messages(sessionID: harness.slot("ses-1")).count == 2 })

        toolGroup.cancel()
        activityCard.cancel()
        try? await Task.sleep(for: .milliseconds(50))
        for index in 0...TranscriptRetentionPolicy.maxWarmClosedSessions {
            let other = harness.observe("other-\(index)")
            _ = await waitUntil { harness.engine.messages(sessionID: harness.slot("other-\(index)")).count == 2 }
            other.cancel()
        }
        _ = await waitUntil { harness.sleeper.pendingCount == TranscriptRetentionPolicy.maxWarmClosedSessions + 1 }
        harness.sleeper.fireAll()
        harness.sync.releaseClosedSessions()
        try? await Task.sleep(for: .milliseconds(50))

        #expect(harness.sync.retainsTranscript(sessionID: "ses-1", directory: Self.dir))
        #expect(harness.engine.messages(sessionID: harness.slot("ses-1")).count == 2)
        backStackChat.cancel()
    }

    @Test func parentActivityCardKeepsRunningChildAfterChildScreenCloses() async {
        let harness = ReleaseHarness()
        let parent = harness.observe("parent")
        let childScreen = harness.observe("child")
        let card = harness.observe("child")
        #expect(await waitUntil { harness.engine.messages(sessionID: harness.slot("child")).count == 2 })

        childScreen.cancel()
        try? await Task.sleep(for: .milliseconds(50))
        harness.sync.releaseClosedSessions()
        harness.sleeper.fireAll()
        #expect(harness.sync.retainsTranscript(sessionID: "child", directory: Self.dir))

        card.cancel()
        #expect(await waitUntil { harness.sleeper.pendingCount == 1 })
        harness.sync.releaseClosedSessions()
        harness.expectReleased("child")
        #expect(harness.sync.retainsTranscript(sessionID: "parent", directory: Self.dir))
        parent.cancel()
    }

    @Test func reopeningReleasedSessionPaintsCacheThenServerLikeFirstOpen() async {
        let harness = ReleaseHarness()
        harness.cache.note(sessionID: "ses-1", messages: ReleaseHarness.transcript("ses-1"))
        let first = ReleaseCollector()
        let firstTask = harness.observe("ses-1", into: first)
        #expect(await waitUntil { first.sawServerConfirmation })
        firstTask.cancel()
        #expect(await waitUntil { harness.sleeper.pendingCount == 1 })
        harness.sync.releaseClosedSessions()
        harness.expectReleased("ses-1")

        let second = ReleaseCollector()
        let secondTask = harness.observe("ses-1", into: second)
        #expect(await waitUntil { second.sawServerConfirmation })

        #expect(first.summaries == second.summaries)
        #expect(second.summaries.first == "messages:ses-1-a,ses-1-b:cache=true")
        #expect(harness.engine.messages(sessionID: harness.slot("ses-1")).map(\.id) == ["ses-1-a", "ses-1-b"])
        #expect(harness.tailCalls == ["ses-1", "ses-1"])
        secondTask.cancel()
    }

    @Test func reopeningWithinGracePaintsHeldTranscript() async {
        let harness = ReleaseHarness()
        let firstTask = harness.observe("ses-1")
        #expect(await waitUntil { harness.engine.messages(sessionID: harness.slot("ses-1")).count == 2 })
        firstTask.cancel()
        #expect(await waitUntil { harness.sleeper.pendingCount == 1 })

        let again = ReleaseCollector()
        let task = harness.observe("ses-1", into: again)
        #expect(await waitUntil { !again.summaries.isEmpty })
        #expect(again.summaries.first == "messages:ses-1-a,ses-1-b:cache=false")
        harness.sleeper.fireAll()
        try? await Task.sleep(for: .milliseconds(50))
        #expect(harness.sync.retainsTranscript(sessionID: "ses-1", directory: Self.dir))
        task.cancel()
    }

    @Test func clearCacheFreesOpenAndClosedSessions() async {
        let harness = ReleaseHarness()
        let closed = harness.observe("closed")
        let open = harness.observe("open")
        #expect(await waitUntil {
            harness.engine.messages(sessionID: harness.slot("closed")).count == 2 &&
                harness.engine.messages(sessionID: harness.slot("open")).count == 2
        })
        closed.cancel()
        #expect(await waitUntil { harness.sleeper.pendingCount == 1 })

        harness.sync.clearCache()

        harness.expectReleased("closed")
        harness.expectReleased("open")
        open.cancel()
        harness.sleeper.fireAll()
        try? await Task.sleep(for: .milliseconds(50))
        harness.expectReleased("open")
    }

    @Test func memoryPressureReleasesClosedSessionsAtOnceAndKeepsOpenOnes() async {
        let harness = ReleaseHarness()
        let closed = harness.observe("closed")
        let open = harness.observe("open")
        #expect(await waitUntil {
            harness.engine.messages(sessionID: harness.slot("closed")).count == 2 &&
                harness.engine.messages(sessionID: harness.slot("open")).count == 2
        })
        closed.cancel()
        #expect(await waitUntil { harness.sleeper.pendingCount == 1 })
        #expect(harness.sync.retainsTranscript(sessionID: "closed", directory: Self.dir))

        harness.sync.releaseClosedSessions()

        harness.expectReleased("closed")
        #expect(harness.engine.messages(sessionID: harness.slot("open")).map(\.id) == ["open-a", "open-b"])
        open.cancel()
    }

    @Test func eventsForReleasedSessionDoNotBuildPartialTranscript() async {
        let harness = ReleaseHarness()
        let screen = harness.observe("ses-1")
        #expect(await waitUntil { harness.engine.messages(sessionID: harness.slot("ses-1")).count == 2 })
        // Control: while observed, the same event lands in the store.
        let revisionBefore = harness.engine.revision(sessionID: harness.slot("ses-1"))
        harness.eventStream.emit(messageUpdated(sessionID: "ses-1", messageID: "live-1"))
        #expect(await waitUntil { harness.engine.revision(sessionID: harness.slot("ses-1")) > revisionBefore })

        screen.cancel()
        #expect(await waitUntil { harness.sleeper.pendingCount == 1 })
        harness.sync.releaseClosedSessions()
        harness.expectReleased("ses-1")

        harness.eventStream.emit(messageUpdated(sessionID: "ses-1", messageID: "live-2"))
        harness.eventStream.emit(OpencodeEvent(type: "session.idle", sessionID: "ses-1"))
        try? await Task.sleep(for: .milliseconds(100))
        harness.expectReleased("ses-1")
    }

    @Test func observerReturningRightAfterReleaseLoadsCleanTranscript() async {
        let harness = ReleaseHarness()
        let screen = harness.observe("ses-1")
        #expect(await waitUntil { harness.engine.messages(sessionID: harness.slot("ses-1")).count == 2 })
        screen.cancel()
        #expect(await waitUntil { harness.sleeper.pendingCount == 1 })
        // Release inside the shared observation's stop timeout, then open again at once.
        harness.sync.releaseClosedSessions()
        let again = ReleaseCollector()
        let task = harness.observe("ses-1", into: again)
        #expect(await waitUntil { again.sawServerConfirmation })

        #expect(again.summaries.first == "messages:ses-1-a,ses-1-b:cache=true")
        #expect(harness.engine.messages(sessionID: harness.slot("ses-1")).map(\.id) == ["ses-1-a", "ses-1-b"])
        task.cancel()
    }

    private func messageUpdated(sessionID: String, messageID: String) -> OpencodeEvent {
        let json = """
        {"payload":{"type":"message.updated","properties":{"info":{"id":"\(messageID)","role":"user","sessionID":"\(sessionID)","time":{"created":1}}}}}
        """
        return EventDomainAdapter().parseEvent(dataLine: json)!
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

@MainActor
private final class ReleaseHarness {
    let store = InMemoryMessageStore()
    lazy var engine = SessionMessageEngine(store: store)
    let eventStream = FakeForegroundEventStream()
    let cache = ReleaseRecordingSessionCache()
    let sleeper = ManualRetentionSleeper()
    private let calls = ReleaseCallLog()
    lazy var sync: MessageSync = {
        let calls = self.calls
        return MessageSync(
            listMessages: { sessionID, _ in
                calls.append(sessionID)
                return ReleaseHarness.transcript(sessionID)
            },
            store: engine,
            eventStream: eventStream,
            connectionProfileIDProvider: { "profile-1" },
            recoveryDelay: { _ in },
            sessionCache: cache,
            retentionSleep: { [sleeper] ms in await sleeper.sleep(ms) }
        )
    }()

    var tailCalls: [String] { calls.values }

    nonisolated static func transcript(_ sessionID: String) -> [Message] {
        [
            Message(id: "\(sessionID)-a", role: .user, text: "question", model: nil, mode: nil, sentAtEpochMs: nil),
            Message(id: "\(sessionID)-b", role: .assistant, text: "answer", model: nil, mode: nil, sentAtEpochMs: nil),
        ]
    }

    func slot(_ sessionID: String) -> String {
        MessageSessionKey(connectionProfileID: "profile-1", directory: "/Users/example/project", sessionID: sessionID)
            .storeSlot
    }

    func observe(_ sessionID: String, into collector: ReleaseCollector = ReleaseCollector()) -> Task<Void, Never> {
        let stream = sync.observeMessages(sessionID: sessionID, directory: "/Users/example/project")
        return Task { await collector.collect(stream) }
    }

    func expectReleased(_ sessionID: String, sourceLocation: SourceLocation = #_sourceLocation) {
        #expect(
            !sync.retainsTranscript(sessionID: sessionID, directory: "/Users/example/project"),
            "\(sessionID) still held",
            sourceLocation: sourceLocation
        )
        #expect(engine.messages(sessionID: slot(sessionID)).isEmpty, sourceLocation: sourceLocation)
        #expect(!store.retains(sessionID: slot(sessionID)), sourceLocation: sourceLocation)
    }
}

/// Stands in for the grace-period sleep: suspends until the test fires it.
private final class ManualRetentionSleeper: @unchecked Sendable {
    private let lock = NSLock()
    private var waiters: [CheckedContinuation<Void, Never>] = []

    var pendingCount: Int {
        lock.lock()
        defer { lock.unlock() }
        return waiters.count
    }

    func sleep(_: Int) async {
        await withCheckedContinuation { continuation in
            lock.lock()
            waiters.append(continuation)
            lock.unlock()
        }
    }

    func fireAll() {
        lock.lock()
        let pending = waiters
        waiters.removeAll()
        lock.unlock()
        pending.forEach { $0.resume() }
    }
}

private final class ReleaseCallLog: @unchecked Sendable {
    private let lock = NSLock()
    private var calls: [String] = []

    var values: [String] {
        lock.lock()
        defer { lock.unlock() }
        return calls
    }

    func append(_ value: String) {
        lock.lock()
        calls.append(value)
        lock.unlock()
    }
}

private final class ReleaseCollector: @unchecked Sendable {
    private let lock = NSLock()
    private var updates: [String] = []
    private var confirmed = false

    var summaries: [String] {
        lock.lock()
        defer { lock.unlock() }
        return updates
    }

    /// A non-cache frame or a cache confirmation: the server answered.
    var sawServerConfirmation: Bool {
        lock.lock()
        defer { lock.unlock() }
        return confirmed
    }

    func collect(_ stream: AsyncStream<MessageListUpdate>) async {
        for await update in stream {
            record(update)
        }
    }

    private func record(_ update: MessageListUpdate) {
        lock.lock()
        defer { lock.unlock() }
        switch update {
        case .messages(let messages, let isFromCache):
            updates.append("messages:\(messages.map(\.id).joined(separator: ",")):cache=\(isFromCache)")
            if !isFromCache { confirmed = true }
        case .cacheSyncConfirmed:
            updates.append("cacheSyncConfirmed")
            confirmed = true
        case .loadFailed:
            updates.append("loadFailed")
        }
    }
}

/// Disk cache stand-in: remembers the last noted frame per session and seeds from it.
private final class ReleaseRecordingSessionCache: SessionCache, @unchecked Sendable {
    private let lock = NSLock()
    private var noted: [String: [Message]] = [:]

    func note(sessionID: String, messages: [Message]) {
        lock.lock()
        noted[sessionID] = messages
        lock.unlock()
    }

    func seeded(sessionID: String) -> [Message]? {
        lock.lock()
        defer { lock.unlock() }
        return noted[sessionID]
    }

    func seedSessions(worktree: String) async -> [Session]? { nil }
    func rememberSessions(worktree: String, sessions: [Session]) async {}
    func openMessages(sessionID: String, worktree: String) -> any SessionMessageCacheHandle {
        ReleaseRecordingCacheHandle(sessionID: sessionID, cache: self)
    }
    func purgeWorktree(serverBaseURL: String, worktree: String) async {}
    func clearAll() async {}
    func estimatedSizeBytes() async -> Int64 { 0 }
}

private final class ReleaseRecordingCacheHandle: SessionMessageCacheHandle, @unchecked Sendable {
    private let sessionID: String
    private let cache: ReleaseRecordingSessionCache

    init(sessionID: String, cache: ReleaseRecordingSessionCache) {
        self.sessionID = sessionID
        self.cache = cache
    }

    func seed() async -> [Message]? { cache.seeded(sessionID: sessionID) }
    func note(messages: [Message]) { cache.note(sessionID: sessionID, messages: messages) }
    func flush() async {}
    func close() {}
}
