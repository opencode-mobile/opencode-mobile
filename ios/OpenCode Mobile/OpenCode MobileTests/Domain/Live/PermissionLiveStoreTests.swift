import Foundation
import Testing
@testable import OpenCode_Mobile

@Suite(.serialized)
@MainActor
struct PermissionLiveStoreTests {
    @Test func permissionAskedBurst_isNeitherDelayedNorAmplified() async throws {
        let first = testPermission("perm-1")
        let second = testPermission("perm-2")
        let third = testPermission("perm-3")
        let repository = PermissionLiveStoreFakeSessionRepository(
            script: [
                [],
                [first],
                [first, second],
                [first, second, third],
            ]
        )
        let eventStream = FakeForegroundEventStream()
        let store = DefaultPermissionLiveStore(
            sessionRepository: repository,
            eventStream: eventStream
        )
        let collector = PermissionCollector(store: store, directory: "/repo")
        defer { collector.cancel() }

        _ = try await collector.awaitEmission { $0.isEmpty }

        let started = ContinuousClock.now
        eventStream.emit(OpencodeEvent(type: "permission.asked", sessionID: "ses-1"))
        _ = try await collector.awaitEmission { $0.map(\.id) == ["perm-1"] }
        #expect(started.duration(to: ContinuousClock.now) < .milliseconds(LiveStoreRefreshPolicy.statusDebounceMs))

        eventStream.emit(OpencodeEvent(type: "permission.asked", sessionID: "ses-1"))
        eventStream.emit(OpencodeEvent(type: "permission.asked", sessionID: "ses-1"))
        _ = try await collector.awaitEmission { $0.map(\.id) == ["perm-1", "perm-2", "perm-3"] }

        #expect(repository.permissionFetchCount == 4)
    }

    @Test func reconnectPair_coalescesIntoOneFetch() async throws {
        let repository = PermissionLiveStoreFakeSessionRepository(script: [[]])
        let eventStream = FakeForegroundEventStream()
        let store = DefaultPermissionLiveStore(
            sessionRepository: repository,
            eventStream: eventStream
        )
        let collector = PermissionCollector(store: store, directory: "/repo")
        defer { collector.cancel() }

        _ = try await collector.awaitEmission { $0.isEmpty }
        #expect(repository.permissionFetchCount == 1)

        eventStream.openGap()
        eventStream.setConnectionState(.connected)
        eventStream.emit(OpencodeEvent(type: "stream.reconnected", sessionID: nil))
        eventStream.emit(OpencodeEvent(type: "server.connected", sessionID: nil))
        await settleBeyondDebounce()

        #expect(repository.permissionFetchCount == 2)
    }

    @Test func unchangedList_doesNotReEmit() async throws {
        let request = testPermission("perm-1")
        let repository = PermissionLiveStoreFakeSessionRepository(script: [[request]])
        let eventStream = FakeForegroundEventStream()
        let store = DefaultPermissionLiveStore(
            sessionRepository: repository,
            eventStream: eventStream
        )
        let collector = PermissionCollector(store: store, directory: "/repo")
        defer { collector.cancel() }

        _ = try await collector.awaitEmission { $0.map(\.id) == ["perm-1"] }
        #expect(collector.emissions.count == 1)

        eventStream.emit(OpencodeEvent(type: "permission.replied", sessionID: "ses-1"))
        eventStream.emit(OpencodeEvent(type: "permission.replied", sessionID: "ses-1"))
        try await pollUntil { repository.permissionFetchCount == 3 }

        #expect(collector.emissions.count == 1)
        #expect(repository.permissionFetchCount == 3)
    }

    @Test func clearCache_existingCollectorKeepsReceiving() async throws {
        let later = testPermission("perm-1")
        let repository = PermissionLiveStoreFakeSessionRepository(script: [[], [later]])
        let eventStream = FakeForegroundEventStream()
        let store = DefaultPermissionLiveStore(
            sessionRepository: repository,
            eventStream: eventStream
        )
        let collector = PermissionCollector(store: store, directory: "/repo")
        defer { collector.cancel() }

        _ = try await collector.awaitEmission { $0.isEmpty }
        store.clearCache()
        _ = try await collector.awaitEmission { $0.map(\.id) == ["perm-1"] }
    }

    @Test func clearCache_isolatesSameDirectoryAfterProfileSwitch() async throws {
        let first = testPermission("perm-a")
        let second = testPermission("perm-b")
        let repository = PermissionLiveStoreFakeSessionRepository(script: [[first], [second]])
        let eventStream = FakeForegroundEventStream()
        let store = DefaultPermissionLiveStore(
            sessionRepository: repository,
            eventStream: eventStream
        )
        let firstCollector = PermissionCollector(store: store, directory: "/repo")
        _ = try await firstCollector.awaitEmission { $0.map(\.id) == ["perm-a"] }
        firstCollector.cancel()
        store.clearCache()
        let secondCollector = PermissionCollector(store: store, directory: "/repo")
        defer { secondCollector.cancel() }
        _ = try await secondCollector.awaitEmission { $0.map(\.id) == ["perm-b"] }
        #expect(secondCollector.emissions.allSatisfy { $0.allSatisfy { $0.id != "perm-a" } })
    }

    @Test func slowPermissionRefresh_doesNotStallMessageEventDelivery() async {
        let repository = PermissionLiveStoreFakeSessionRepository(
            script: [[], [testPermission("perm-slow")]]
        )
        repository.delayAfterFirstFetch = .seconds(5)
        let eventStream = FakeForegroundEventStream()
        eventStream.setConnectionState(.connected)
        let permissionStore = DefaultPermissionLiveStore(
            sessionRepository: repository,
            eventStream: eventStream
        )
        let seed = [
            Message(
                id: "msg-1",
                role: .user,
                text: "Hello",
                parts: [.text(TextPart(id: "part-1", text: "Hello", isStreaming: false))],
                model: nil,
                mode: nil,
                sentAtEpochMs: nil
            ),
        ]
        let sync = MessageSync(
            listMessages: { _, _ in seed },
            store: SessionMessageEngine(store: InMemoryMessageStore()),
            eventStream: eventStream,
            connectionProfileIDProvider: { "local" },
            recoveryDelay: { _ in try? await Task.sleep(for: .milliseconds(1)) }
        )
        let permissionCollector = PermissionCollector(store: permissionStore, directory: "/repo")
        defer { permissionCollector.cancel() }
        let texts = LockedTexts()
        let messageTask = Task {
            for await update in sync.observeMessages(sessionID: "ses-1", directory: "/repo") {
                if case .messages(let messages, _) = update {
                    texts.append(messages.first?.text ?? "")
                }
            }
        }
        _ = await waitUntil { texts.latest() == "Hello" }
        eventStream.emit(OpencodeEvent(type: "permission.asked", sessionID: "ses-1"))
        try? await Task.sleep(for: .milliseconds(20))
        let deltaJSON = """
        {"payload":{"type":"message.part.delta","properties":{"sessionID":"ses-1","messageID":"msg-1","partID":"part-1","field":"text","delta":" streamed"}}}
        """
        eventStream.emit(EventDomainAdapter().parseEvent(dataLine: deltaJSON)!)
        let streamed = await waitUntil { texts.latest() == "Hello streamed" }
        messageTask.cancel()
        #expect(streamed)
    }
}

@MainActor
private final class PermissionCollector {
    private(set) var emissions: [[PermissionRequest]] = []
    private var task: Task<Void, Never>?

    init(store: DefaultPermissionLiveStore, directory: String) {
        task = Task {
            for await requests in store.observePermissions(directory: directory) {
                emissions.append(requests)
            }
        }
    }

    func awaitEmission(
        timeout: Duration = .seconds(3),
        matching predicate: @escaping ([PermissionRequest]) -> Bool
    ) async throws -> [PermissionRequest] {
        let deadline = ContinuousClock.now + timeout
        while ContinuousClock.now < deadline {
            if let match = emissions.last(where: predicate) {
                return match
            }
            try await Task.sleep(for: .milliseconds(20))
        }
        throw PermissionLiveStoreTimeoutError()
    }

    func cancel() {
        task?.cancel()
        task = nil
    }
}

private struct PermissionLiveStoreTimeoutError: Error {}

@MainActor
private func pollUntil(
    timeout: Duration = .seconds(3),
    predicate: () -> Bool
) async throws {
    let deadline = ContinuousClock.now + timeout
    while ContinuousClock.now < deadline {
        if predicate() { return }
        try await Task.sleep(for: .milliseconds(20))
    }
    throw PermissionLiveStoreTimeoutError()
}

@MainActor
private func settleBeyondDebounce() async {
    try? await Task.sleep(for: .milliseconds(LiveStoreRefreshPolicy.statusDebounceMs + 400))
}

@MainActor
private func waitUntil(
    timeoutMs: Int = 2_000,
    predicate: @MainActor () -> Bool
) async -> Bool {
    let deadline = Date().addingTimeInterval(TimeInterval(timeoutMs) / 1_000)
    while Date() < deadline {
        if predicate() { return true }
        try? await Task.sleep(for: .milliseconds(10))
    }
    return predicate()
}

private final class LockedTexts: @unchecked Sendable {
    private let lock = NSLock()
    private var values: [String] = []

    func append(_ value: String) {
        lock.lock()
        values.append(value)
        lock.unlock()
    }

    func latest() -> String? {
        lock.lock()
        defer { lock.unlock() }
        return values.last
    }
}

private func testPermission(_ id: String) -> PermissionRequest {
    PermissionRequest(
        id: id,
        sessionID: "ses-1",
        permission: "bash",
        patterns: [],
        metadata: [:],
        always: [],
        toolMessageID: nil,
        toolCallID: nil
    )
}

private final class PermissionLiveStoreFakeSessionRepository: SessionRepository, @unchecked Sendable {
    private let script: [[PermissionRequest]]
    private let lock = NSLock()
    private var index = 0
    private var _permissionFetchCount = 0
    var delayAfterFirstFetch: Duration?

    var permissionFetchCount: Int {
        lock.lock()
        defer { lock.unlock() }
        return _permissionFetchCount
    }

    init(script: [[PermissionRequest]]) {
        self.script = script
    }

    func listPendingPermissions(directory: String?) async throws -> [PermissionRequest] {
        lock.lock()
        _permissionFetchCount += 1
        let fetchNumber = _permissionFetchCount
        let delay = delayAfterFirstFetch
        let result: [PermissionRequest]
        if script.isEmpty {
            result = []
        } else if index < script.count {
            result = script[index]
            index += 1
        } else {
            result = script[script.count - 1]
        }
        lock.unlock()
        if fetchNumber > 1, let delay {
            try? await Task.sleep(for: delay)
        }
        return result
    }

    func listSessions(directory: String?) async throws -> [Session] { [] }
    func createSession(parentID: String?, title: String?, directory: String?) async throws -> Session { fatalError() }
    func getAllSessionStatuses(directory: String?) async throws -> [SessionStatus] { fatalError() }
    func getSession(id: String, directory: String?) async throws -> Session { fatalError() }
    func updateSession(id: String, title: String?, directory: String?) async throws -> Session { fatalError() }
    func archiveSession(id: String, directory: String?) async throws -> Session { fatalError() }
    func deleteSession(id: String, directory: String?) async throws -> Bool { fatalError() }
    func listChildSessions(id: String) async throws -> [Session] { fatalError() }
    func listSessionTodos(id: String, directory: String?) async throws -> [SessionTodo] { fatalError() }
    func initializeSessionAgents(id: String, messageID: String, providerID: String, modelID: String) async throws -> Bool { fatalError() }
    func forkSession(id: String, atMessageID: String?) async throws -> Session { fatalError() }
    func abortSession(id: String, directory: String?) async throws -> Bool { fatalError() }
    func shareSession(id: String) async throws -> Session { fatalError() }
    func unshareSession(id: String) async throws -> Session { fatalError() }
    func getSessionDiff(id: String, messageID: String?, directory: String?) async throws -> [FileDiff] { fatalError() }
    func summarizeSession(id: String, providerID: String, modelID: String) async throws -> Bool { fatalError() }
    func revertSession(id: String, messageID: String, partID: String?) async throws -> Bool { fatalError() }
    func unrevertSession(id: String) async throws -> Bool { fatalError() }
    func respondToPermission(
        sessionID: String,
        permissionID: String,
        decision: PermissionDecision,
        directory: String?
    ) async throws -> Bool { fatalError() }
}
