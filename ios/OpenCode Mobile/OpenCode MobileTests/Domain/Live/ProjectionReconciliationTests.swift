import Foundation
import Testing
@testable import OpenCode_Mobile

@Suite(.serialized)
@MainActor
struct ProjectionReconciliationTests {
    @Test func lateSubscriber_seesOpenGapAndFetches() async {
        let repo = ProjectionMessageRepository()
        let eventStream = FakeForegroundEventStream()
        eventStream.setConnectionState(.connected)
        eventStream.stop()
        eventStream.setConnectionState(.connecting)
        repo.messages = [
            Message(id: "msg-1", role: .user, text: "After gap", parts: [], model: nil, mode: nil, sentAtEpochMs: nil),
        ]
        let sync = makeProjectionSync(repo: repo, eventStream: eventStream)
        let collector = ProjectionMessageCollector()
        let task = Task { await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo")) }
        let loaded = await waitUntil { collector.latest()?.first?.text == "After gap" }
        task.cancel()
        #expect(loaded)
        #expect(repo.listMessagesCallCount == 1)
    }

    @Test func worldAndReconnectPair_yieldsOneGet() async {
        let repo = ProjectionMessageRepository()
        let eventStream = FakeForegroundEventStream()
        let world = FakeWorldSignals()
        let sync = makeProjectionSync(repo: repo, eventStream: eventStream, worldSignals: world)
        let collector = ProjectionMessageCollector()
        let task = Task { await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo")) }
        let loaded = await waitUntil { collector.latest()?.first?.text == "Hello" }
        #expect(loaded)
        eventStream.setConnectionState(.connected)
        try? await Task.sleep(for: .milliseconds(40))
        let afterInitial = repo.listMessagesCallCount

        world.emit(.backgroundStop)
        world.emit(.foreground)
        let caughtUp = await waitUntil { repo.listMessagesCallCount == afterInitial + 1 }
        #expect(caughtUp)
        eventStream.emit(OpencodeEvent(type: "stream.reconnected", sessionID: nil, rawProperties: nil))
        eventStream.emit(OpencodeEvent(type: "server.connected", sessionID: nil, rawProperties: nil))
        try? await Task.sleep(for: .milliseconds(350))
        task.cancel()
        #expect(repo.listMessagesCallCount == afterInitial + 1)
    }

    @Test func briefGrace_doesNotFetchOrShowSyncing() async {
        let repo = ProjectionMessageRepository()
        let eventStream = FakeForegroundEventStream()
        let world = FakeWorldSignals()
        let sync = makeProjectionSync(repo: repo, eventStream: eventStream, worldSignals: world)
        let collector = ProjectionMessageCollector()
        var catchingUp = false
        let task = Task { await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo")) }
        let chromeTask = Task {
            for await value in sync.observeCatchUpChrome(sessionID: "ses-1", directory: "/repo") {
                catchingUp = value
            }
        }
        let loaded = await waitUntil { collector.latest()?.first?.text == "Hello" }
        #expect(loaded)
        eventStream.setConnectionState(.connected)
        try? await Task.sleep(for: .milliseconds(40))
        let afterInitial = repo.listMessagesCallCount

        world.emit(.background)
        try? await Task.sleep(for: .milliseconds(20))
        world.emit(.foreground)
        try? await Task.sleep(for: .milliseconds(80))
        task.cancel()
        chromeTask.cancel()
        #expect(repo.listMessagesCallCount == afterInitial)
        #expect(catchingUp == false)
    }

    @Test func profileGeneration_doesNotPublishIntoOldObservation() async {
        let repo = ProjectionMessageRepository()
        let eventStream = FakeForegroundEventStream()
        let sync = makeProjectionSync(repo: repo, eventStream: eventStream)
        let collector = ProjectionMessageCollector()
        let task = Task { await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo")) }
        let loaded = await waitUntil { collector.latest()?.first?.text == "Hello" }
        #expect(loaded)

        sync.clearCache()
        repo.messages = [
            Message(id: "msg-old", role: .user, text: "Old profile", parts: [], model: nil, mode: nil, sentAtEpochMs: nil),
        ]
        eventStream.openGap()
        eventStream.setConnectionState(.connected)
        eventStream.emit(OpencodeEvent(type: "stream.reconnected", sessionID: nil, rawProperties: nil))
        try? await Task.sleep(for: .milliseconds(80))
        task.cancel()
        #expect(collector.latest()?.first?.text == "Hello")
    }

    @Test func failedReload_remainsBehindAndRetriesSameGap() async {
        let repo = ProjectionMessageRepository()
        let eventStream = FakeForegroundEventStream()
        let sync = makeProjectionSync(repo: repo, eventStream: eventStream)
        let collector = ProjectionMessageCollector()
        let task = Task { await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo")) }
        let loaded = await waitUntil { collector.latest()?.first?.text == "Hello" }
        #expect(loaded)
        eventStream.setConnectionState(.connected)
        try? await Task.sleep(for: .milliseconds(40))
        let afterInitial = repo.listMessagesCallCount

        repo.shouldFail = true
        eventStream.openGap()
        eventStream.emit(OpencodeEvent(type: "stream.reconnected", sessionID: nil, rawProperties: nil))
        let failed = await waitUntil { repo.listMessagesCallCount > afterInitial }
        #expect(failed)
        let afterFail = repo.listMessagesCallCount

        repo.shouldFail = false
        repo.messages = [
            Message(id: "msg-1", role: .user, text: "Recovered", parts: [], model: nil, mode: nil, sentAtEpochMs: nil),
        ]
        let recovered = await waitUntil { collector.latest()?.first?.text == "Recovered" }
        task.cancel()
        #expect(recovered)
        #expect(repo.listMessagesCallCount > afterFail)
    }

    @Test func sseDuringRest_winsOverStaleSnapshot() async {
        let repo = ProjectionMessageRepository()
        repo.holdNextListMessages()
        let eventStream = FakeForegroundEventStream()
        let sync = makeProjectionSync(repo: repo, eventStream: eventStream)
        let collector = ProjectionMessageCollector()
        let task = Task { await collector.collect(sync.observeMessages(sessionID: "ses-1", directory: "/repo")) }
        let blocked = await waitUntil { repo.isListBlocked }
        #expect(blocked)

        eventStream.emit(eventWithInfo(id: "msg-2", role: "assistant", sessionID: "ses-1"))
        eventStream.emit(eventWithTextPart(partID: "prt-1", messageID: "msg-2", text: "", sessionID: "ses-1"))
        eventStream.emit(eventWithPartDelta(partID: "prt-1", messageID: "msg-2", delta: "Live SSE", sessionID: "ses-1"))
        repo.resumeListMessages()
        let keptLive = await waitUntil {
            collector.latest()?.first(where: { $0.id == "msg-2" })?.text == "Live SSE"
        }
        task.cancel()
        #expect(keptLive)
    }
}

@MainActor
private func makeProjectionSync(
    repo: ProjectionMessageRepository,
    eventStream: FakeForegroundEventStream,
    worldSignals: any WorldSignals = SilentWorldSignals.shared,
    pollIntervalMs: Int = 60_000
) -> MessageSync {
    MessageSync(
        listMessages: { sessionID, directory in
            try await repo.listMessages(sessionID: sessionID, directory: directory)
        },
        store: SessionMessageEngine(store: InMemoryMessageStore()),
        eventStream: eventStream,
        worldSignals: worldSignals,
        pollIntervalMs: pollIntervalMs,
        connectionProfileIDProvider: { "local" },
        recoveryDelay: { _ in try? await Task.sleep(for: .milliseconds(1)) }
    )
}

private final class ProjectionMessageCollector: @unchecked Sendable {
    private(set) var emissions: [[Message]] = []

    func collect(_ stream: AsyncStream<MessageListUpdate>) async {
        for await update in stream {
            if case .messages(let messages, _) = update {
                emissions.append(messages)
            }
        }
    }

    func latest() -> [Message]? {
        emissions.last
    }
}

private final class ProjectionMessageRepository: @unchecked Sendable {
    var messages: [Message] = [
        Message(id: "msg-1", role: .user, text: "Hello", parts: [
            .text(TextPart(text: "Hello", isStreaming: false)),
        ], model: nil, mode: nil, sentAtEpochMs: nil),
    ]
    var listMessagesCallCount = 0
    var shouldFail = false
    private var holdNextList = false
    private var blockedListContinuation: CheckedContinuation<Void, Never>?
    private let lock = NSLock()

    var isListBlocked: Bool {
        lock.lock()
        defer { lock.unlock() }
        return blockedListContinuation != nil
    }

    func holdNextListMessages() {
        holdNextList = true
    }

    func resumeListMessages() {
        lock.lock()
        let continuation = blockedListContinuation
        blockedListContinuation = nil
        lock.unlock()
        continuation?.resume()
    }

    func listMessages(sessionID: String, directory: String?) async throws -> [Message] {
        listMessagesCallCount += 1
        if holdNextList {
            holdNextList = false
            await withCheckedContinuation { continuation in
                lock.lock()
                blockedListContinuation = continuation
                lock.unlock()
            }
        }
        if shouldFail {
            throw URLError(.cannotConnectToHost)
        }
        return messages
    }
}

@MainActor
private func waitUntil(
    timeoutMs: Int = 5_000,
    predicate: @MainActor () -> Bool
) async -> Bool {
    let deadline = Date().addingTimeInterval(TimeInterval(timeoutMs) / 1_000)
    while Date() < deadline {
        if predicate() { return true }
        try? await Task.sleep(for: .milliseconds(10))
    }
    return predicate()
}

private func eventWithInfo(id: String, role: String, sessionID: String) -> OpencodeEvent {
    let json = """
    {"payload":{"type":"message.updated","properties":{"info":{"id":"\(id)","role":"\(role)","sessionID":"\(sessionID)"}}}}
    """
    return EventDomainAdapter().parseEvent(dataLine: json)!
}

private func eventWithTextPart(partID: String, messageID: String, text: String, sessionID: String) -> OpencodeEvent {
    let json = """
    {"payload":{"type":"message.part.updated","properties":{"part":{"id":"\(partID)","messageID":"\(messageID)","sessionID":"\(sessionID)","type":"text","text":"\(text)"}}}}
    """
    return EventDomainAdapter().parseEvent(dataLine: json)!
}

private func eventWithPartDelta(partID: String, messageID: String, delta: String, sessionID: String) -> OpencodeEvent {
    let json = """
    {"payload":{"type":"message.part.delta","properties":{"sessionID":"\(sessionID)","messageID":"\(messageID)","partID":"\(partID)","field":"text","delta":"\(delta)"}}}
    """
    return EventDomainAdapter().parseEvent(dataLine: json)!
}
