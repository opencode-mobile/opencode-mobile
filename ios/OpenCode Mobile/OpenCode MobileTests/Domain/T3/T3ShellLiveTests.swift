import Foundation
import Testing
@testable import OpenCode_Mobile

/// Live home updates for the T3 engine: the shell subscription keeps the
/// client cache current and publishes the same synthetic `session.*` /
/// `permission.*` events Android does, re-subscribes from the last sequence
/// after a reconnect, coalesces forced REST refreshes, and tears down on stop.
struct T3ShellLiveTests {
    @Test func shellStream_updatesCacheAndPublishesHomeEvents() async throws {
        let harness = T3ClientHarness()
        let events = EventCollector(signals: harness.signals)
        await harness.signals.emit(.start)

        let socket = try await harness.connector.connection(at: 0)
        socket.open()
        let subscribe = try await socket.firstRequest(tag: "orchestration.subscribeShell")
        #expect(subscribe.payload["afterSequence"] == nil)
        #expect(subscribe.payload["requestCompletionMarker"] as? Bool == true)

        socket.receive(Frames.chunk(requestID: subscribe.id, values: [
            ShellItems.snapshot(sequence: 3, threads: [ShellItems.thread("A")]),
            ["kind": "synchronized"],
        ]))
        #expect(await waitUntil { harness.client.cachedThread(id: "A")?.title == "Thread A" })
        try await Task.sleep(for: .milliseconds(30))
        #expect(events.types.isEmpty, "the first snapshot does not announce existing threads as created")

        events.clear()
        socket.receive(Frames.chunk(requestID: subscribe.id, values: [
            ShellItems.threadUpdated(sequence: 4, ShellItems.thread("B", activeRunID: "run-1")),
        ]))
        #expect(await waitUntil { events.types == ["session.created:B", "session.status:B"] })
        #expect(events.statusTypes == ["running"])

        events.clear()
        socket.receive(Frames.chunk(requestID: subscribe.id, values: [
            ShellItems.threadUpdated(sequence: 5, ShellItems.thread("A", pendingRequestID: "req-9")),
        ]))
        #expect(await waitUntil { events.types == ["session.updated:A", "permission.asked:A"] })

        events.clear()
        socket.receive(Frames.chunk(requestID: subscribe.id, values: [
            ShellItems.threadUpdated(sequence: 2, ShellItems.thread("A", title: "stale")),
        ]))
        try await Task.sleep(for: .milliseconds(50))
        #expect(events.types.isEmpty)
        #expect(harness.client.cachedThread(id: "A")?.title == "Thread A")

        socket.receive(Frames.chunk(requestID: subscribe.id, values: [
            ShellItems.threadRemoved(sequence: 6, "B"),
        ]))
        #expect(await waitUntil { events.types == ["session.deleted:B", "session.status:B"] })
        #expect(events.statusTypes == ["idle"])
        #expect(harness.client.cachedThread(id: "B") == nil)
        let shell = try await harness.client.fetchShell(force: false)
        #expect(shell.sequence == 6)
        #expect(shell.threads.map(\.id) == ["A"])

        events.clear()
        socket.receive(Frames.chunk(requestID: subscribe.id, values: [
            ShellItems.threadUpdated(sequence: 7, ShellItems.thread("A")),
        ]))
        #expect(await waitUntil { events.types == ["session.updated:A", "permission.replied:A"] })
    }

    @Test func archivedShell_staysSeparateFromTheActiveSnapshot() async throws {
        let harness = T3ClientHarness()
        await harness.signals.emit(.start)
        let socket = try await harness.connector.connection(at: 0)
        socket.open()
        let active = try await socket.firstRequest(tag: "orchestration.subscribeShell")
        let archive = try await socket.firstRequest(tag: "orchestration.subscribeArchivedShell")
        #expect(archive.payload.isEmpty)

        socket.receive(Frames.chunk(requestID: active.id, values: [
            ShellItems.snapshot(sequence: 3, threads: [ShellItems.thread("A")]),
        ]))
        #expect(await waitUntil { harness.client.cachedThread(id: "A") != nil })

        socket.receive(Frames.chunk(requestID: archive.id, values: [
            ShellItems.archivedSnapshot(sequence: 3, threads: [
                ShellItems.thread("Z", archivedAt: "2026-10-02T00:00:00Z"),
            ]),
        ]))
        #expect(await waitUntil { harness.client.cachedThread(id: "Z")?.isArchived == true })
        var shell = try await harness.client.fetchShell(force: false)
        #expect(shell.threads.map(\.id) == ["A"])
        #expect(shell.archivedThreads.map(\.id) == ["Z"])
        #expect(shell.sequence == 3)

        socket.receive(Frames.chunk(requestID: active.id, values: [
            ShellItems.snapshot(sequence: 4, threads: [ShellItems.thread("A"), ShellItems.thread("B")]),
        ]))
        #expect(await waitUntil { harness.client.cachedThread(id: "B") != nil })
        shell = try await harness.client.fetchShell(force: false)
        #expect(shell.threads.map(\.id) == ["A", "B"])
        #expect(shell.archivedThreads.map(\.id) == ["Z"])
        #expect(shell.sequence == 4)

        socket.receive(Frames.chunk(requestID: active.id, values: [
            ShellItems.threadRemoved(sequence: 5, "A"),
        ]))
        #expect(await waitUntil { harness.client.cachedThread(id: "A") == nil })
        shell = try await harness.client.fetchShell(force: false)
        #expect(shell.thread(id: "Z")?.id == "Z")
        #expect(shell.sequence == 5)

        socket.receive(Frames.chunk(requestID: archive.id, values: [
            ShellItems.archivedThreadUpdated(sequence: 6, ShellItems.thread("B", archivedAt: "2026-10-02T00:00:00Z")),
        ]))
        #expect(await waitUntil { harness.client.cachedShellSnapshot?.threads.isEmpty == true })
        shell = try await harness.client.fetchShell(force: false)
        #expect(shell.threads.isEmpty)
        #expect(shell.archivedThreads.map(\.id) == ["Z", "B"])
        #expect(shell.sequence == 5)
        #expect(shell.archiveSequence == 6)

        harness.http.shell = T3ShellSnapshot(sequence: 7, threads: [Fixtures.thread(id: "C")], projects: [])
        let refreshed = try await harness.client.fetchShell(force: true)
        #expect(refreshed.threads.map(\.id) == ["C"])
        #expect(refreshed.archivedThreads.map(\.id) == ["Z", "B"])
        #expect(refreshed.sequence == 7)
        #expect(refreshed.archiveSequence == 6)
    }

    @Test func shellStream_firstSnapshotPublishesStatusAndRequestsButNoLifecycle() async throws {
        let harness = T3ClientHarness()
        let events = EventCollector(signals: harness.signals)
        await harness.signals.emit(.start)
        let socket = try await harness.connector.connection(at: 0)
        socket.open()
        let subscribe = try await socket.firstRequest(tag: "orchestration.subscribeShell")
        socket.receive(Frames.chunk(requestID: subscribe.id, values: [
            ShellItems.snapshot(sequence: 3, threads: [
                ShellItems.thread("A"),
                ShellItems.thread("B", activeRunID: "run-1"),
                ShellItems.thread("C", pendingRequestID: "q-1", pendingRequestKind: "user_input"),
                ShellItems.thread("D", pendingRequestID: "req-1"),
            ]),
        ]))
        #expect(await waitUntil { events.types.count == 3 })
        #expect(events.types == ["session.status:B", "question.asked:C", "permission.asked:D"])
        #expect(events.statusTypes == ["running"])

        // With a cached snapshot, lifecycle is diffed as usual.
        events.clear()
        socket.receive(Frames.chunk(requestID: subscribe.id, values: [
            ShellItems.threadUpdated(sequence: 4, ShellItems.thread("E")),
        ]))
        #expect(await waitUntil { events.types == ["session.created:E"] })
    }

    @Test func shellStream_publishesQuestionAskedAndReplied() async throws {
        let harness = T3ClientHarness()
        harness.http.shell = T3ShellSnapshot(sequence: 1, threads: [Fixtures.thread(id: "A")], projects: [])
        _ = try await harness.client.fetchShell(force: true)
        let events = EventCollector(signals: harness.signals)
        await harness.signals.emit(.start)
        let socket = try await harness.connector.connection(at: 0)
        socket.open()
        let subscribe = try await socket.firstRequest(tag: "orchestration.subscribeShell")

        socket.receive(Frames.chunk(requestID: subscribe.id, values: [
            ShellItems.threadUpdated(sequence: 2, ShellItems.thread("A", pendingRequestID: "q-1", pendingRequestKind: "user_input")),
        ]))
        #expect(await waitUntil { events.types == ["session.updated:A", "question.asked:A"] })

        events.clear()
        socket.receive(Frames.chunk(requestID: subscribe.id, values: [
            ShellItems.threadUpdated(sequence: 3, ShellItems.thread("A", pendingRequestID: "q-2", pendingRequestKind: "user_input")),
        ]))
        #expect(await waitUntil { events.types == ["session.updated:A", "question.asked:A"] })

        events.clear()
        socket.receive(Frames.chunk(requestID: subscribe.id, values: [
            ShellItems.threadUpdated(sequence: 4, ShellItems.thread("A")),
        ]))
        #expect(await waitUntil { events.types == ["session.updated:A", "question.replied:A"] })

        // Swapping a question for an approval replies the question and asks the permission.
        events.clear()
        socket.receive(Frames.chunk(requestID: subscribe.id, values: [
            ShellItems.threadUpdated(sequence: 5, ShellItems.thread("A", pendingRequestID: "q-3", pendingRequestKind: "user_input")),
            ShellItems.threadUpdated(sequence: 6, ShellItems.thread("A", pendingRequestID: "req-1")),
        ]))
        #expect(await waitUntil { events.types.count == 5 })
        #expect(events.types == [
            "session.updated:A", "question.asked:A",
            "session.updated:A", "permission.asked:A", "question.replied:A",
        ])
    }

    @MainActor
    @Test func refreshActivity_publishesQuestionEventsFromThreadActivity() async throws {
        let harness = T3ClientHarness(shellRefreshDebounce: .zero)
        var waiting = Fixtures.thread(id: "t1")
        waiting.pendingRequestID = "q-1"
        waiting.pendingRequestKind = "user_input"
        harness.http.shell = T3ShellSnapshot(sequence: 1, threads: [waiting], projects: [])
        harness.http.threadDetail = T3ThreadDetail(sequence: 1, thread: waiting, messages: [])
        let eventStream = SyntheticEventRecorder()
        let repository = T3MessageRepository(client: harness.client, eventStream: eventStream, resubscribeDelay: .seconds(30))
        let observe = Task {
            for await _ in repository.observeMessages(sessionID: "t1", directory: nil) {}
        }
        let socket = try await harness.connector.connection(at: 0)
        socket.open()
        let subscribe = try await socket.firstRequest(tag: "orchestration.subscribeThread")

        socket.receive(Frames.chunk(requestID: subscribe.id, values: [
            ["kind": "event", "sequence": 2, "event": ["type": "runtime-request.updated"]],
        ]))
        #expect(await waitUntil { eventStream.types == ["session.status:t1", "question.asked:t1"] })

        eventStream.clear()
        harness.http.shell = T3ShellSnapshot(sequence: 2, threads: [Fixtures.thread(id: "t1")], projects: [])
        socket.receive(Frames.chunk(requestID: subscribe.id, values: [
            ["kind": "event", "sequence": 3, "event": ["type": "thread.updated"]],
        ]))
        #expect(await waitUntil { eventStream.types == ["session.status:t1", "question.replied:t1"] })

        // An approval on the same thread is still a permission, never a question.
        eventStream.clear()
        var approval = Fixtures.thread(id: "t1")
        approval.pendingRequestID = "req-1"
        approval.pendingRequestKind = "approval"
        harness.http.shell = T3ShellSnapshot(sequence: 3, threads: [approval], projects: [])
        socket.receive(Frames.chunk(requestID: subscribe.id, values: [
            ["kind": "event", "sequence": 4, "event": ["type": "run.updated"]],
        ]))
        #expect(await waitUntil { eventStream.types == ["session.status:t1", "permission.asked:t1"] })
        observe.cancel()
    }

    @Test func shellStream_projectItemsUpdateCacheWithoutSessionEvents() async throws {
        let harness = T3ClientHarness()
        let events = EventCollector(signals: harness.signals)
        await harness.signals.emit(.start)
        let socket = try await harness.connector.connection(at: 0)
        socket.open()
        let subscribe = try await socket.firstRequest(tag: "orchestration.subscribeShell")
        socket.receive(Frames.chunk(requestID: subscribe.id, values: [
            ShellItems.snapshot(sequence: 1, threads: []),
            [
                "kind": "project.updated",
                "sequence": 2,
                "project": ["id": "p2", "title": "Two", "workspaceRoot": "/two"],
            ],
        ]))
        #expect(await waitUntil { harness.client.cachedShellProjectIDs == ["p1", "p2"] })
        socket.receive(Frames.chunk(requestID: subscribe.id, values: [
            ["kind": "project.removed", "sequence": 3, "projectId": "p1"],
        ]))
        #expect(await waitUntil { harness.client.cachedShellProjectIDs == ["p2"] })
        #expect(events.types.isEmpty)
    }

    @Test func shellStream_resubscribesFromLastSequenceAfterReconnect() async throws {
        let harness = T3ClientHarness()
        harness.http.shell = T3ShellSnapshot(sequence: 3, threads: [Fixtures.thread(id: "A")], projects: [])
        _ = try await harness.client.fetchShell(force: true)
        await harness.signals.emit(.start)

        let socket1 = try await harness.connector.connection(at: 0)
        socket1.open()
        let subscribe1 = try await socket1.firstRequest(tag: "orchestration.subscribeShell")
        #expect(subscribe1.payload["afterSequence"] as? Int == 3)

        socket1.receive(Frames.chunk(requestID: subscribe1.id, values: [
            ShellItems.threadUpdated(sequence: 5, ShellItems.thread("A", title: "Renamed")),
        ]))
        #expect(await waitUntil { harness.client.cachedThread(id: "A")?.title == "Renamed" })

        socket1.serverClose()
        let socket2 = try await harness.connector.connection(at: 1)
        socket2.open()
        let subscribe2 = try await socket2.firstRequest(tag: "orchestration.subscribeShell")
        #expect(subscribe2.payload["afterSequence"] as? Int == 5)
        #expect(harness.http.ticketCount == 2)
        // The cache survived the socket loss; only the subscription moved.
        #expect(harness.client.cachedThread(id: "A")?.title == "Renamed")
    }

    @Test func fetchShell_coalescesForcedRefreshesAndSharesInFlightRequest() async throws {
        let harness = T3ClientHarness(shellRefreshDebounce: .milliseconds(200))
        harness.http.shell = Fixtures.shell(threadID: "A")
        let start = ContinuousClock.now
        _ = try await harness.client.fetchShell(force: true)
        #expect(harness.http.shellFetchCount == 1)

        async let first = harness.client.fetchShell(force: true)
        async let second = harness.client.fetchShell(force: true)
        async let third = harness.client.fetchShell(force: true)
        _ = try await (first, second, third)
        #expect(harness.http.shellFetchCount == 2)
        #expect(ContinuousClock.now - start >= .milliseconds(200))

        _ = try await harness.client.fetchShell(force: false)
        #expect(harness.http.shellFetchCount == 2)
    }

    @Test func fetchShell_restRefreshPublishesStatusButNotLifecycle() async throws {
        let harness = T3ClientHarness(shellRefreshDebounce: .zero)
        let events = EventCollector(signals: harness.signals)
        harness.http.shell = T3ShellSnapshot(sequence: 1, threads: [Fixtures.thread(id: "A")], projects: [])
        _ = try await harness.client.fetchShell(force: true)
        try await Task.sleep(for: .milliseconds(20))
        #expect(events.types.isEmpty)

        var running = Fixtures.thread(id: "A")
        running.activeRunID = "run-1"
        running.pendingRequestID = "req-1"
        running.pendingRequestKind = "approval"
        harness.http.shell = T3ShellSnapshot(sequence: 2, threads: [running, Fixtures.thread(id: "B")], projects: [])
        _ = try await harness.client.fetchShell(force: true)
        #expect(await waitUntil { events.types == ["session.status:A", "permission.asked:A"] })
        #expect(events.statusTypes == ["running"])
    }

    @Test func fetchShell_doesNotRegressBelowLiveSequence() async throws {
        let harness = T3ClientHarness(shellRefreshDebounce: .zero)
        await harness.signals.emit(.start)
        let socket = try await harness.connector.connection(at: 0)
        socket.open()
        let subscribe = try await socket.firstRequest(tag: "orchestration.subscribeShell")
        socket.receive(Frames.chunk(requestID: subscribe.id, values: [
            ShellItems.snapshot(sequence: 5, threads: [ShellItems.thread("A", title: "Live")]),
        ]))
        #expect(await waitUntil { harness.client.cachedThread(id: "A")?.title == "Live" })

        harness.http.shell = T3ShellSnapshot(sequence: 3, threads: [ShellItemsSwift.thread(id: "A", title: "Old")], projects: [])
        let shell = try await harness.client.fetchShell(force: true)
        #expect(shell.sequence == 5)
        #expect(harness.client.cachedThread(id: "A")?.title == "Live")
    }

    @Test func lifecycleStop_tearsDownShellSubscription() async throws {
        let harness = T3ClientHarness()
        await harness.signals.emit(.start)
        let socket = try await harness.connector.connection(at: 0)
        socket.open()
        _ = try await socket.firstRequest(tag: "orchestration.subscribeShell")
        #expect(harness.client.isShellLiveRunning)

        await harness.signals.emit(.stop)
        #expect(socket.isClosed)
        #expect(harness.client.isShellLiveRunning == false)
        try await Task.sleep(for: .milliseconds(100))
        #expect(harness.http.ticketCount == 1)

        // A plain REST fetch after stop does not revive the subscription.
        harness.http.shell = Fixtures.shell(threadID: "A")
        _ = try await harness.client.fetchShell(force: true)
        let socket2 = try await harness.connector.connection(at: 1)
        socket2.open()
        try await Task.sleep(for: .milliseconds(100))
        // Only the connect-time catalogue refresh may go out; no shell subscription.
        #expect(socket2.frames(tag: "Request").allSatisfy { $0["tag"] as? String == T3JSON.serverGetConfigTag })
        #expect(harness.client.isShellLiveRunning == false)
    }

    @Test func foregroundEventStream_forwardsT3ShellEventsWhileLive() async throws {
        let signals = T3LiveSignals()
        let stream = DefaultForegroundEventStream(
            api: EventsAPIClient(
                connectionProvider: { ServerConnection(baseURL: URL(string: "http://t3-a.test/")!, password: "secret") },
                urlSession: URLSession(configuration: .ephemeral)
            ),
            adapter: EventDomainAdapter(),
            engineProvider: { .t3 },
            t3ConnectionStates: { signals.connectionStateUpdates() },
            t3Lifecycle: { _ in },
            t3Events: { signals.eventUpdates() }
        )
        let received = StringCollector()
        let observe = Task {
            for await event in stream.events() {
                received.append("\(event.type):\(event.sessionID ?? "-")")
            }
        }
        stream.start()
        #expect(await waitUntil { signals.hasEventSubscribers })
        signals.publishEvent(OpencodeEvent(type: "session.created", sessionID: "A"))
        #expect(await waitUntil { received.values == ["session.created:A"] })

        await stream.stop()
        signals.publishEvent(OpencodeEvent(type: "session.updated", sessionID: "A"))
        try await Task.sleep(for: .milliseconds(50))
        #expect(received.values == ["session.created:A"])
        observe.cancel()
    }
}

// MARK: - Helpers

private enum ShellItems {
    static func thread(
        _ id: String,
        title: String? = nil,
        activeRunID: String? = nil,
        pendingRequestID: String? = nil,
        pendingRequestKind: String = "approval",
        archivedAt: String? = nil
    ) -> [String: Any] {
        var object: [String: Any] = [
            "id": id,
            "title": title ?? "Thread \(id)",
            "projectId": "p1",
            "status": "idle",
            "modelSelection": ["instanceId": "i1", "model": "m1"],
        ]
        if let activeRunID { object["activeRunId"] = activeRunID }
        if let pendingRequestID {
            object["pendingRuntimeRequest"] = ["id": pendingRequestID, "kind": pendingRequestKind]
        }
        if let archivedAt { object["archivedAt"] = archivedAt }
        return object
    }

    static func snapshot(sequence: Int, threads: [[String: Any]]) -> [String: Any] {
        [
            "kind": "snapshot",
            "snapshot": [
                "schemaVersion": 1,
                "snapshotSequence": sequence,
                "threads": threads,
                "archivedThreads": [Any](),
                "projects": [["id": "p1", "title": "One", "workspaceRoot": "/one"]],
            ],
        ]
    }

    static func threadUpdated(sequence: Int, _ thread: [String: Any]) -> [String: Any] {
        ["kind": "thread.updated", "sequence": sequence, "location": "active", "thread": thread]
    }

    static func threadRemoved(sequence: Int, _ id: String) -> [String: Any] {
        ["kind": "thread.removed", "sequence": sequence, "location": "active", "threadId": id]
    }

    static func archivedSnapshot(sequence: Int, threads: [[String: Any]]) -> [String: Any] {
        [
            "kind": "snapshot",
            "snapshot": [
                "snapshotSequence": sequence,
                "threads": threads,
                "projects": [Any](),
            ],
        ]
    }

    static func archivedThreadUpdated(sequence: Int, _ thread: [String: Any]) -> [String: Any] {
        ["kind": "thread.updated", "sequence": sequence, "thread": thread]
    }
}

private extension T3EnvironmentClient {
    var cachedShellProjectIDs: [String]? {
        cachedShellSnapshot?.projects.map(\.id)
    }
}

private enum ShellItemsSwift {
    static func thread(id: String, title: String) -> T3Thread {
        var thread = Fixtures.thread(id: id)
        thread.title = title
        return thread
    }
}

private final class EventCollector: @unchecked Sendable {
    private let lock = NSLock()
    private var events: [OpencodeEvent] = []
    private var task: Task<Void, Never>?

    init(signals: T3LiveSignals) {
        let stream = signals.eventUpdates()
        task = Task { [weak self] in
            for await event in stream {
                guard let self else { return }
                self.lock.lock()
                self.events.append(event)
                self.lock.unlock()
            }
        }
    }

    deinit { task?.cancel() }

    var types: [String] {
        lock.lock()
        defer { lock.unlock() }
        return events.map { "\($0.type):\($0.sessionID ?? "-")" }
    }

    var statusTypes: [String] {
        lock.lock()
        defer { lock.unlock() }
        return events.compactMap { $0.rawProperties?["status"]?.dictionaryValue?["type"]?.stringValue }
    }

    func clear() {
        lock.lock()
        events = []
        lock.unlock()
    }
}

/// Records what `T3MessageRepository.refreshActivity` publishes as synthetic events.
private final class SyntheticEventRecorder: ForegroundEventStream, @unchecked Sendable {
    private let lock = NSLock()
    private var recorded: [OpencodeEvent] = []
    var connectionState: EventStreamConnectionState { .connected }
    func connectionStateUpdates() -> AsyncStream<EventStreamConnectionState> { AsyncStream { _ in } }
    func events() -> AsyncStream<OpencodeEvent> { AsyncStream { _ in } }
    func start() {}
    func stop() async {}
    func publishSynthetic(_ event: OpencodeEvent) {
        lock.lock()
        recorded.append(event)
        lock.unlock()
    }

    var types: [String] {
        lock.lock()
        defer { lock.unlock() }
        return recorded.map { "\($0.type):\($0.sessionID ?? "-")" }
    }

    func clear() {
        lock.lock()
        recorded = []
        lock.unlock()
    }
}

private final class StringCollector: @unchecked Sendable {
    private let lock = NSLock()
    private var _values: [String] = []
    var values: [String] { lock.lock(); defer { lock.unlock() }; return _values }
    func append(_ value: String) { lock.lock(); _values.append(value); lock.unlock() }
}

private func waitUntil(
    timeoutMs: Int = 5_000,
    _ predicate: @escaping @Sendable () -> Bool
) async -> Bool {
    let deadline = Date().addingTimeInterval(TimeInterval(timeoutMs) / 1_000)
    while Date() < deadline {
        if predicate() { return true }
        try? await Task.sleep(for: .milliseconds(10))
    }
    return predicate()
}
