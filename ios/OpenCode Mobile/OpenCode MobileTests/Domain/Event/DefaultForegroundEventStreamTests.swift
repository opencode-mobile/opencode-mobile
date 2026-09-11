import Foundation
import Testing
@testable import OpenCode_Mobile

@Suite(.serialized)
@MainActor
struct DefaultForegroundEventStreamTests {
    @Test func successfulHTTPResponseMarksStreamConnectedWithoutServerConnectedEvent() async {
        let stream = makeStream(
            responses: [
                #"data: {"payload":{"type":"server.heartbeat","properties":{}}}"# + "\n\n",
            ]
        )
        var states: [EventStreamConnectionState] = []
        let stateTask = Task { @MainActor in
            for await state in stream.connectionStateUpdates() {
                states.append(state)
            }
        }

        await Task.yield()
        stream.start()
        try? await Task.sleep(for: .milliseconds(50))
        let becameConnected = await waitUntil(timeoutMs: 2_000) { states.contains(.connected) }
        stream.stop()
        stateTask.cancel()

        #expect(becameConnected)
    }

    @Test func messageEventsPublishWhileStreamIsConnected() async {
        let stream = makeStream(
            responses: [
                #"data: {"payload":{"type":"message.updated","properties":{"sessionID":"ses-1"}}}"# + "\n\n",
            ]
        )
        var states: [EventStreamConnectionState] = []
        var events: [OpencodeEvent] = []
        let stateTask = Task { @MainActor in
            for await state in stream.connectionStateUpdates() {
                states.append(state)
            }
        }
        let eventTask = Task { @MainActor in
            for await event in stream.events() {
                events.append(event)
            }
        }

        await Task.yield()
        stream.start()
        try? await Task.sleep(for: .milliseconds(50))
        let receivedMessage = await waitUntil(timeoutMs: 2_000) {
            states.contains(.connected) && events.contains { $0.type == "message.updated" && $0.sessionID == "ses-1" }
        }
        stream.stop()
        stateTask.cancel()
        eventTask.cancel()

        #expect(receivedMessage)
    }

    @Test func reconnectPublishesSyntheticEventOnlyOnceForCurrentStream() async {
        let connected = #"data: {"payload":{"type":"server.connected","properties":{}}}"# + "\n\n"
        let stream = makeStream(responses: [connected, connected])
        var events: [OpencodeEvent] = []
        let eventTask = Task { @MainActor in
            for await event in stream.events() {
                events.append(event)
            }
        }

        await Task.yield()
        stream.start()
        try? await Task.sleep(for: .milliseconds(1_200))
        let reconnected = await waitUntil(timeoutMs: 3_500) {
            events.filter { $0.type == "stream.reconnected" }.count == 1
        }
        try? await Task.sleep(for: .milliseconds(100))
        stream.stop()
        eventTask.cancel()

        #expect(reconnected)
        #expect(events.filter { $0.type == "stream.reconnected" }.count == 1)
    }

    @Test func stopBetweenReconnectDecisionAndPublish_suppressesStaleReconnect() async {
        for _ in 0..<8 {
            let connected = #"data: {"payload":{"type":"server.connected","properties":{}}}"# + "\n\n"
            let stream = makeStream(responses: [connected, connected, connected])
            let events = Locked([OpencodeEvent]())
            let states = Locked([EventStreamConnectionState]())
            let eventTask = Task {
                for await event in stream.events() {
                    events.mutate { $0.append(event) }
                }
            }
            let stateTask = Task {
                for await state in stream.connectionStateUpdates() {
                    states.mutate { $0.append(state) }
                }
            }
            await Task.yield()
            stream.start()
            try? await Task.sleep(for: .milliseconds(50))
            let live = await waitUntil(timeoutMs: 3_000) {
                states.value.contains(.connected)
            }
            #expect(live)
            stateTask.cancel()
            let reconnectsAtStop = events.value.filter { $0.type == "stream.reconnected" }.count
            stream.stop()
            try? await Task.sleep(for: .milliseconds(80))
            eventTask.cancel()
            #expect(stream.connectionState == .idle)
            #expect(events.value.filter { $0.type == "stream.reconnected" }.count == reconnectsAtStop)
            #expect(stream.snapshot.connectionState == .idle)
        }
    }

    @Test func startIsIdempotentWhileStreamIsRunning() async {
        let stream = makeStream(
            responses: [
                #"data: {"payload":{"type":"server.heartbeat","properties":{}}}"# + "\n\n",
            ]
        )
        var states: [EventStreamConnectionState] = []
        let stateTask = Task { @MainActor in
            for await state in stream.connectionStateUpdates() {
                states.append(state)
            }
        }

        await Task.yield()
        stream.start()
        let connected = await waitUntil(timeoutMs: 2_000) { states.contains(.connected) }
        #expect(connected)
        let countAfterFirst = states.count
        stream.start()
        try? await Task.sleep(for: .milliseconds(80))
        let countAfterSecondStart = states.count
        stream.stop()
        stateTask.cancel()

        // Second start must not tear down (no new Idle/Connecting while live).
        #expect(countAfterSecondStart == countAfterFirst)
    }

    @Test func successfulResumeDoesNotPublishDisconnectedWhileRetrying() async {
        // Stream body ends after the first event; the loop retries while still
        // occupied. Chrome must stay connecting/reconnecting — not offline.
        let stream = makeStream(
            responses: [
                #"data: {"payload":{"type":"server.connected","properties":{}}}"# + "\n\n",
                #"data: {"payload":{"type":"server.connected","properties":{}}}"# + "\n\n",
            ]
        )
        var states: [EventStreamConnectionState] = []
        let stateTask = Task { @MainActor in
            for await state in stream.connectionStateUpdates() {
                states.append(state)
            }
        }

        await Task.yield()
        stream.start()
        let reconnected = await waitUntil(timeoutMs: 3_500) {
            states.filter { $0 == .connected }.count >= 2
        }
        stream.stop()
        stateTask.cancel()

        #expect(reconnected)
        #expect(!states.contains(.disconnected))
    }

    @Test func stopThenStartPublishesStreamReconnectedForColdResume() async {
        let connected = #"data: {"payload":{"type":"server.connected","properties":{}}}"# + "\n\n"
        let stream = makeStream(responses: [connected, connected])
        var events: [OpencodeEvent] = []
        let eventTask = Task { @MainActor in
            for await event in stream.events() {
                events.append(event)
            }
        }

        await Task.yield()
        stream.start()
        let firstLive = await waitUntil(timeoutMs: 2_000) {
            events.contains { $0.type == "server.connected" }
        }
        #expect(firstLive)
        stream.stop()
        try? await Task.sleep(for: .milliseconds(50))
        let beforeResume = events.filter { $0.type == "stream.reconnected" }.count
        stream.start()
        let coldResume = await waitUntil(timeoutMs: 3_500) {
            events.filter { $0.type == "stream.reconnected" }.count == beforeResume + 1
        }
        stream.stop()
        eventTask.cancel()

        #expect(coldResume)
    }

    @Test func stopThenStartDoesNotLeaveCancelledTaskIdleOverConnecting() async {
        let stream = makeStream(
            responses: [
                #"data: {"payload":{"type":"server.heartbeat","properties":{}}}"# + "\n\n",
                #"data: {"payload":{"type":"server.heartbeat","properties":{}}}"# + "\n\n",
            ]
        )
        var states: [EventStreamConnectionState] = []
        let stateTask = Task { @MainActor in
            for await state in stream.connectionStateUpdates() {
                states.append(state)
            }
        }

        await Task.yield()
        stream.start()
        let firstLive = await waitUntil(timeoutMs: 2_000) { states.contains(.connected) }
        #expect(firstLive)
        stream.stop()
        let stopped = await waitUntil(timeoutMs: 1_000) { states.last == .idle }
        #expect(stopped)
        let indexAfterStop = states.count
        stream.start()
        let secondLive = await waitUntil(timeoutMs: 2_000) {
            states[indexAfterStop...].contains(.connected)
        }
        #expect(secondLive)
        // After the second start, Idle from the cancelled first task must not
        // appear after Connecting for the new generation (before Connected).
        let slice = Array(states[indexAfterStop...])
        if let connectingIdx = slice.firstIndex(of: .connecting),
           let connectedIdx = slice.firstIndex(of: .connected),
           connectingIdx < connectedIdx
        {
            #expect(!slice[connectingIdx..<connectedIdx].contains(.idle))
        }
        stream.stop()
        stateTask.cancel()
    }

    @Test func start_opensLeasesWithoutWaitingForDirectories() async {
        let hold = HoldingDirectories(initial: ["/repo"], next: ["/repo"])
        let stream = makeStream(
            responses: [
                #"data: {"payload":{"type":"server.heartbeat","properties":{}}}"# + "\n\n",
            ],
            instanceDirectories: { await hold.next() }
        )
        var states: [EventStreamConnectionState] = []
        let stateTask = Task { @MainActor in
            for await state in stream.connectionStateUpdates() {
                states.append(state)
            }
        }
        await Task.yield()
        stream.start()
        let becameConnected = await waitUntil(timeoutMs: 2_000) { states.contains(.connected) }
        stream.stop()
        stateTask.cancel()
        await hold.release()
        #expect(becameConnected)
    }

    @Test func startDuringHeldConfigResolve_usesPendingConfigNotStale() async {
        let hold = HoldingDirectories(initial: ["/old"], next: ["/new"])
        let stream = makeStream(
            responses: [
                #"data: {"payload":{"type":"server.heartbeat","properties":{}}}"# + "\n\n",
            ],
            instanceDirectories: { await hold.next() },
            protocolHint: { .v1 }
        )
        stream.start()
        let firstHeld = await waitUntil(timeoutMs: 2_000) { hold.isHolding }
        #expect(firstHeld)
        stream.start()
        let pendingCaptured = await waitUntil(timeoutMs: 2_000) { hold.resolveCount >= 2 }
        #expect(pendingCaptured)
        await hold.release()
        let usedNew = await waitUntil(timeoutMs: 2_000) {
            stream.snapshot.requiredTargets.contains("event:/new") &&
                !stream.snapshot.requiredTargets.contains("event:/old")
        }
        stream.stop()
        #expect(usedNew)
        #expect(!stream.snapshot.requiredTargets.contains("event:/old"))
    }

    @Test func stopThenStart_doesNotWaitForStaleOverlappingStartApply() async {
        let stall = StallNextDirectories()
        let heartbeat = #"data: {"payload":{"type":"server.heartbeat","properties":{}}}"# + "\n\n"
        let stream = makeStream(
            responses: [heartbeat, heartbeat, heartbeat],
            instanceDirectories: { await stall.next() }
        )
        var states: [EventStreamConnectionState] = []
        let stateTask = Task { @MainActor in
            for await state in stream.connectionStateUpdates() {
                states.append(state)
            }
        }
        await Task.yield()
        stream.start()
        let firstLive = await waitUntil(timeoutMs: 2_000) { states.contains(.connected) }
        #expect(firstLive)
        stall.arm()
        stream.start()
        let overlappingHeld = await waitUntil(timeoutMs: 2_000) { stall.isHolding }
        #expect(overlappingHeld)
        stream.stop()
        let stopped = await waitUntil(timeoutMs: 1_000) { stream.connectionState == .idle }
        #expect(stopped)
        let indexAfterStop = states.count
        stream.start()
        let resumedLive = await waitUntil(timeoutMs: 2_000) {
            states[indexAfterStop...].contains(.connected)
        }
        stream.stop()
        stall.release()
        stateTask.cancel()
        #expect(resumedLive)
    }

    @Test func stopThenStart_reusesCachedDirectoriesForSameIdentity() async {
        let stall = StallNextDirectories(result: ["/repo-a"])
        let heartbeat = #"data: {"payload":{"type":"server.heartbeat","properties":{}}}"# + "\n\n"
        let stream = makeStream(
            responses: [heartbeat, heartbeat, heartbeat, heartbeat],
            instanceDirectories: { await stall.next() },
            protocolHint: { .v1 },
            profileID: { "profile-a" },
            connectionIdentity: { "http://127.0.0.1:4096/|opencode" }
        )
        stream.start()
        let firstLive = await waitUntil(timeoutMs: 2_000) {
            stream.snapshot.requiredTargets.contains("event:/repo-a")
                && stream.snapshot.config?.profileID == "profile-a"
        }
        #expect(firstLive)
        stream.stop()
        stall.arm()
        stream.start()
        let reusedCache = await waitUntil(timeoutMs: 2_000) {
            stream.snapshot.config?.profileID == "profile-a"
                && stream.snapshot.requiredTargets.contains("event:/repo-a")
                && stream.snapshot.requiredTargets.contains("global/event")
        }
        #expect(reusedCache)
        #expect(stall.isHolding)
        stream.stop()
        stall.release()
    }

    @Test func stopThenStart_doesNotReuseCachedDirectoriesAcrossProfiles() async {
        let identity = LockedIdentity(
            profileID: "profile-a",
            connectionIdentity: "http://127.0.0.1:4096/|opencode"
        )
        let stall = StallNextDirectories(result: ["/repo-a"])
        let heartbeat = #"data: {"payload":{"type":"server.heartbeat","properties":{}}}"# + "\n\n"
        let stream = makeStream(
            responses: [heartbeat, heartbeat, heartbeat, heartbeat],
            instanceDirectories: { await stall.next() },
            protocolHint: { .v1 },
            profileID: { identity.profileID },
            connectionIdentity: { identity.connectionIdentity }
        )
        stream.start()
        let firstLive = await waitUntil(timeoutMs: 2_000) {
            stream.snapshot.requiredTargets.contains("event:/repo-a")
        }
        #expect(firstLive)
        stream.stop()
        identity.profileID = "profile-b"
        identity.connectionIdentity = "http://10.0.0.2:5096/|opencode"
        stall.arm()
        stream.start()
        let switched = await waitUntil(timeoutMs: 2_000) {
            stream.snapshot.config?.profileID == "profile-b"
                && stream.snapshot.requiredTargets.contains("global/event")
        }
        try? await Task.sleep(for: .milliseconds(80))
        #expect(switched)
        #expect(stream.snapshot.config?.profileID == "profile-b")
        #expect(!stream.snapshot.requiredTargets.contains("event:/repo-a"))
        #expect(!stream.snapshot.requiredTargets.contains("event:/repo-b"))
        stream.stop()
        stall.release()
    }

    @Test func unavailablePath_doesNotOpenSseUntilReady() async {
        let path = FakeDeviceNetworkPath(status: .unavailable)
        var states: [EventStreamConnectionState] = []
        let stream = makeStream(
            responses: [
                #"data: {"payload":{"type":"server.heartbeat","properties":{}}}"# + "\n\n",
            ],
            deviceNetworkPath: path
        )
        let stateTask = Task { @MainActor in
            for await state in stream.connectionStateUpdates() {
                states.append(state)
            }
        }
        await Task.yield()
        stream.start()
        try? await Task.sleep(for: .milliseconds(80))
        let stayedIdle = !states.contains(.connecting) && !states.contains(.connected)
        path.set(.ready)
        let becameConnected = await waitUntil(timeoutMs: 2_000) { states.contains(.connected) }
        stream.stop()
        stateTask.cancel()
        #expect(stayedIdle)
        #expect(becameConnected)
    }

    @Test func unavailablePath_overlappingStartDuringWait_keepsNewestTargets() async {
        let path = FakeDeviceNetworkPath(status: .ready)
        let directories = MutableDirectories(["/old"])
        let heartbeat = #"data: {"payload":{"type":"server.heartbeat","properties":{}}}"# + "\n\n"
        let stream = makeStream(
            responses: [heartbeat, heartbeat, heartbeat, heartbeat, heartbeat],
            deviceNetworkPath: path,
            instanceDirectories: { directories.snapshot() },
            protocolHint: { .v1 },
            profileID: { "profile-a" },
            connectionIdentity: { "http://127.0.0.1:4096/|opencode" }
        )
        stream.start()
        let firstLive = await waitUntil(timeoutMs: 2_000) {
            stream.snapshot.requiredTargets.contains("event:/old")
        }
        #expect(firstLive)
        stream.stop()
        let stopped = await waitUntil(timeoutMs: 1_000) { stream.connectionState == .idle }
        #expect(stopped)

        path.set(.unavailable)
        directories.replace(["/new"])
        stream.start()
        let cachedWhileWaiting = await waitUntil(timeoutMs: 2_000) {
            stream.snapshot.requiredTargets.contains("event:/old")
        }
        #expect(cachedWhileWaiting)
        stream.start()
        let newestWhileWaiting = await waitUntil(timeoutMs: 2_000) {
            stream.snapshot.requiredTargets.contains("event:/new")
        }
        #expect(newestWhileWaiting)
        path.set(.ready)
        let keptNewest = await waitUntil(timeoutMs: 2_000) {
            stream.snapshot.requiredTargets.contains("event:/new")
                && !stream.snapshot.requiredTargets.contains("event:/old")
        }
        try? await Task.sleep(for: .milliseconds(80))
        let required = stream.snapshot.requiredTargets
        stream.stop()
        #expect(keptNewest)
        #expect(required.contains("event:/new"))
        #expect(!required.contains("event:/old"))
    }

    @Test func configurationRevision_emittedDuringInitialResolve_isNotLost() async {
        let revisions = ReplayZeroRevisionDirectories(first: ["/old"], next: ["/new"])
        let heartbeat = #"data: {"payload":{"type":"server.heartbeat","properties":{}}}"# + "\n\n"
        let stream = makeStream(
            responses: [heartbeat, heartbeat, heartbeat],
            instanceDirectories: { await revisions.next() },
            configurationRevisions: { revisions.revisions() },
            protocolHint: { .v1 }
        )
        stream.start()
        let firstHeld = await waitUntil(timeoutMs: 2_000) { revisions.firstResolveEntered }
        #expect(firstHeld)
        revisions.replace(["/new"])
        revisions.emitRevision()
        revisions.releaseFirstResolve()
        let usedNew = await waitUntil(timeoutMs: 2_000) {
            stream.snapshot.requiredTargets.contains("event:/new")
                && !stream.snapshot.requiredTargets.contains("event:/old")
        }
        stream.stop()
        revisions.releaseFirstResolve()
        #expect(usedNew)
    }

    @Test func newerIdempotentStart_preventsOlderStaleResolve() async {
        let revisions = HeldRevisionDirectories(initial: ["/old"])
        let heartbeat = #"data: {"payload":{"type":"server.heartbeat","properties":{}}}"# + "\n\n"
        let stream = makeStream(
            responses: [heartbeat, heartbeat, heartbeat, heartbeat],
            instanceDirectories: { await revisions.next() },
            configurationRevisions: { revisions.revisions() },
            protocolHint: { .v1 }
        )
        stream.start()
        let firstLive = await waitUntil(timeoutMs: 2_000) {
            stream.snapshot.requiredTargets.contains("event:/old")
        }
        #expect(firstLive)
        revisions.holdNextAddedWorktrees = true
        revisions.emitRevision()
        let revisionHeld = await waitUntil(timeoutMs: 2_000) { revisions.revisionResolveEntered }
        #expect(revisionHeld)
        let resolvesBeforeNewerStart = revisions.resolveCount
        stream.start()
        let newerResolved = await waitUntil(timeoutMs: 2_000) {
            revisions.resolveCount > resolvesBeforeNewerStart
        }
        #expect(newerResolved)
        revisions.replace(["/stale"])
        revisions.releaseRevisionResolve()
        try? await Task.sleep(for: .milliseconds(80))
        let required = stream.snapshot.requiredTargets
        stream.stop()
        revisions.releaseRevisionResolve()
        #expect(required.contains("event:/old"))
        #expect(!required.contains("event:/stale"))
    }

    @Test func configurationRevision_afterPathWait_appliesCurrentDirectories() async {
        let path = FakeDeviceNetworkPath(status: .ready)
        let revisions = HeldRevisionDirectories(initial: ["/old"])
        let heartbeat = #"data: {"payload":{"type":"server.heartbeat","properties":{}}}"# + "\n\n"
        let stream = makeStream(
            responses: [heartbeat, heartbeat, heartbeat, heartbeat, heartbeat],
            deviceNetworkPath: path,
            instanceDirectories: { await revisions.next() },
            configurationRevisions: { revisions.revisions() },
            protocolHint: { .v1 }
        )
        stream.start()
        let firstLive = await waitUntil(timeoutMs: 2_000) {
            stream.snapshot.requiredTargets.contains("event:/old")
        }
        #expect(firstLive)
        stream.stop()
        let stopped = await waitUntil(timeoutMs: 1_000) { stream.connectionState == .idle }
        #expect(stopped)
        path.set(.unavailable)
        revisions.replace(["/new"])
        stream.start()
        let cachedWhileWaiting = await waitUntil(timeoutMs: 2_000) {
            stream.snapshot.requiredTargets.contains("event:/old")
        }
        #expect(cachedWhileWaiting)
        path.set(.ready)
        revisions.emitRevision()
        let usedNew = await waitUntil(timeoutMs: 2_000) {
            stream.snapshot.requiredTargets.contains("event:/new")
                && !stream.snapshot.requiredTargets.contains("event:/old")
        }
        stream.stop()
        #expect(usedNew)
    }

    @Test func overlappingStartAfterGenerationChange_doesNotRetargetToStalePending() async {
        let identity = StallableIdentity(
            profileID: "profile-a",
            connectionIdentity: "http://127.0.0.1:4096/|opencode"
        )
        let directories = MutableDirectories(["/repo-a"])
        let serverProtocol = LockedProtocol(.v1)
        let revisions = LiveConnectionRevisionSignal()
        let heartbeat = #"data: {"payload":{"type":"server.heartbeat","properties":{}}}"# + "\n\n"
        let stream = makeStream(
            responses: [heartbeat, heartbeat, heartbeat, heartbeat, heartbeat],
            instanceDirectories: { directories.snapshot() },
            configurationRevisions: { revisions.stream() },
            protocolHint: { serverProtocol.value },
            profileID: { await identity.nextProfileID() },
            connectionIdentity: { identity.connectionIdentity }
        )
        stream.start()
        let firstLive = await waitUntil(timeoutMs: 2_000) {
            stream.snapshot.config?.profileID == "profile-a"
                && stream.snapshot.requiredTargets.contains("event:/repo-a")
        }
        #expect(firstLive)
        identity.arm()
        stream.start()
        let staleHeld = await waitUntil(timeoutMs: 2_000) { identity.isHolding }
        #expect(staleHeld)
        identity.profileID = "profile-b"
        identity.connectionIdentity = "http://10.0.0.2:5096/|opencode"
        serverProtocol.value = .v2
        directories.replace(["/repo-b"])
        stream.start()
        let switched = await waitUntil(timeoutMs: 2_000) {
            stream.snapshot.config?.profileID == "profile-b"
                && stream.snapshot.config?.serverProtocol == .v2
        }
        #expect(switched)
        identity.release()
        revisions.yield()
        try? await Task.sleep(for: .milliseconds(120))
        stream.stop()
        #expect(stream.snapshot.config?.profileID != "profile-a")
        #expect(!stream.snapshot.requiredTargets.contains("event:/repo-a"))
    }

    private func makeStream(
        responses: [String],
        deviceNetworkPath: any DeviceNetworkPath = ReadyDeviceNetworkPath(),
        instanceDirectories: @escaping @Sendable () async -> [String] = { [] },
        configurationRevisions: (@Sendable () -> AsyncStream<Void>)? = nil,
        protocolHint: (@Sendable () -> ServerProtocol)? = nil,
        profileID: (@Sendable () async -> String)? = nil,
        connectionIdentity: (@Sendable () async -> String)? = nil
    ) -> DefaultForegroundEventStream {
        let streamID = UUID().uuidString
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [MockForegroundSSEURLProtocol.self]
        MockForegroundSSEURLProtocol.register(
            streamID: streamID,
            responses: responses.map { Data($0.utf8) }
        )
        let session = URLSession(configuration: configuration)
        let connection = ServerConnection(
            baseURL: URL(string: "http://127.0.0.1:4096/\(streamID)/")!,
            password: "secret"
        )
        let client = EventsAPIClient(connectionProvider: { connection }, urlSession: session)
        return DefaultForegroundEventStream(
            api: client,
            adapter: EventDomainAdapter(),
            deviceNetworkPath: deviceNetworkPath,
            instanceDirectories: instanceDirectories,
            configurationRevisions: configurationRevisions ?? { AsyncStream { $0.finish() } },
            profileID: profileID ?? { "" },
            connectionIdentity: connectionIdentity,
            protocolHint: protocolHint
        )
    }

    private func waitUntil(
        timeoutMs: Int = 1_000,
        predicate: @MainActor () -> Bool
    ) async -> Bool {
        let deadline = Date().addingTimeInterval(TimeInterval(timeoutMs) / 1_000)
        while Date() < deadline {
            if predicate() { return true }
            try? await Task.sleep(for: .milliseconds(10))
        }
        return predicate()
    }
}

private final class MutableDirectories: @unchecked Sendable {
    private let lock = NSLock()
    private var value: [String]

    init(_ value: [String]) {
        self.value = value
    }

    func replace(_ next: [String]) {
        lock.lock()
        value = next
        lock.unlock()
    }

    func snapshot() -> [String] {
        lock.lock()
        defer { lock.unlock() }
        return value
    }
}

private final class LockedProtocol: @unchecked Sendable {
    private let lock = NSLock()
    private var _value: ServerProtocol

    var value: ServerProtocol {
        get {
            lock.lock()
            defer { lock.unlock() }
            return _value
        }
        set {
            lock.lock()
            _value = newValue
            lock.unlock()
        }
    }

    init(_ value: ServerProtocol) {
        _value = value
    }
}

private final class StallableIdentity: @unchecked Sendable {
    private let lock = NSLock()
    private var _profileID: String
    private var _connectionIdentity: String
    private var armed = false
    private var holdContinuation: CheckedContinuation<Void, Never>?

    var profileID: String {
        get {
            lock.lock()
            defer { lock.unlock() }
            return _profileID
        }
        set {
            lock.lock()
            _profileID = newValue
            lock.unlock()
        }
    }

    var connectionIdentity: String {
        get {
            lock.lock()
            defer { lock.unlock() }
            return _connectionIdentity
        }
        set {
            lock.lock()
            _connectionIdentity = newValue
            lock.unlock()
        }
    }

    var isHolding: Bool {
        lock.lock()
        defer { lock.unlock() }
        return holdContinuation != nil
    }

    init(profileID: String, connectionIdentity: String) {
        _profileID = profileID
        _connectionIdentity = connectionIdentity
    }

    func arm() {
        lock.lock()
        armed = true
        lock.unlock()
    }

    func nextProfileID() async -> String {
        lock.lock()
        let captured = _profileID
        let shouldStall = armed
        if shouldStall { armed = false }
        lock.unlock()
        if shouldStall {
            await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
                lock.lock()
                holdContinuation = continuation
                lock.unlock()
            }
        }
        return captured
    }

    func release() {
        lock.lock()
        let continuation = holdContinuation
        holdContinuation = nil
        lock.unlock()
        continuation?.resume()
    }
}

private final class ReplayZeroRevisionDirectories: @unchecked Sendable {
    private let lock = NSLock()
    private let first: [String]
    private var worktrees: [String]
    private var resolves = 0
    private var firstHold: CheckedContinuation<Void, Never>?
    private var revisionContinuations: [UUID: AsyncStream<Void>.Continuation] = [:]
    private(set) var firstResolveEntered = false

    init(first: [String], next: [String]) {
        self.first = first
        worktrees = next
    }

    func next() async -> [String] {
        lock.lock()
        resolves += 1
        let count = resolves
        lock.unlock()
        if count == 1 {
            await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
                lock.lock()
                firstResolveEntered = true
                firstHold = continuation
                lock.unlock()
            }
            return first
        }
        lock.lock()
        let directories = worktrees
        lock.unlock()
        return directories
    }

    func revisions() -> AsyncStream<Void> {
        let id = UUID()
        return AsyncStream { continuation in
            lock.lock()
            revisionContinuations[id] = continuation
            lock.unlock()
            continuation.onTermination = { [weak self] _ in
                guard let self else { return }
                self.lock.lock()
                self.revisionContinuations.removeValue(forKey: id)
                self.lock.unlock()
            }
        }
    }

    func emitRevision() {
        lock.lock()
        let continuations = Array(revisionContinuations.values)
        lock.unlock()
        for continuation in continuations {
            continuation.yield(())
        }
    }

    func replace(_ next: [String]) {
        lock.lock()
        worktrees = next
        lock.unlock()
    }

    func releaseFirstResolve() {
        lock.lock()
        let continuation = firstHold
        firstHold = nil
        lock.unlock()
        continuation?.resume()
    }
}

private final class HeldRevisionDirectories: @unchecked Sendable {
    private let lock = NSLock()
    private var worktrees: [String]
    private var holdContinuation: CheckedContinuation<Void, Never>?
    private var holdingRevision = false
    private var revisionContinuations: [UUID: AsyncStream<Void>.Continuation] = [:]
    private var resolves = 0
    var holdNextAddedWorktrees = false
    private(set) var revisionResolveEntered = false

    var resolveCount: Int {
        lock.lock()
        defer { lock.unlock() }
        return resolves
    }

    init(initial: [String]) {
        worktrees = initial
    }

    func next() async -> [String] {
        lock.lock()
        resolves += 1
        let shouldHold = holdNextAddedWorktrees && !holdingRevision
        if shouldHold {
            holdNextAddedWorktrees = false
            holdingRevision = true
            revisionResolveEntered = true
        }
        lock.unlock()
        if shouldHold {
            await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
                lock.lock()
                holdContinuation = continuation
                lock.unlock()
            }
        }
        lock.lock()
        let directories = worktrees
        lock.unlock()
        return directories
    }

    func revisions() -> AsyncStream<Void> {
        let id = UUID()
        return AsyncStream { continuation in
            lock.lock()
            revisionContinuations[id] = continuation
            lock.unlock()
            continuation.onTermination = { [weak self] _ in
                guard let self else { return }
                self.lock.lock()
                self.revisionContinuations.removeValue(forKey: id)
                self.lock.unlock()
            }
        }
    }

    func emitRevision() {
        lock.lock()
        let continuations = Array(revisionContinuations.values)
        lock.unlock()
        for continuation in continuations {
            continuation.yield(())
        }
    }

    func replace(_ next: [String]) {
        lock.lock()
        worktrees = next
        lock.unlock()
    }

    func releaseRevisionResolve() {
        lock.lock()
        let continuation = holdContinuation
        holdContinuation = nil
        lock.unlock()
        continuation?.resume()
    }
}

private final class HoldingDirectories: @unchecked Sendable {
    private let lock = NSLock()
    private let initial: [String]
    private let next: [String]
    private var resolves = 0
    private var holdContinuation: CheckedContinuation<Void, Never>?

    var resolveCount: Int {
        lock.lock()
        defer { lock.unlock() }
        return resolves
    }

    var isHolding: Bool {
        lock.lock()
        defer { lock.unlock() }
        return holdContinuation != nil
    }

    init(initial: [String], next: [String]) {
        self.initial = initial
        self.next = next
    }

    func next() async -> [String] {
        lock.lock()
        resolves += 1
        let count = resolves
        lock.unlock()
        if count == 1 {
            await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
                lock.lock()
                holdContinuation = continuation
                lock.unlock()
            }
            return initial
        }
        return next
    }

    func release() async {
        lock.lock()
        let continuation = holdContinuation
        holdContinuation = nil
        lock.unlock()
        continuation?.resume()
    }
}

private final class LockedIdentity: @unchecked Sendable {
    private let lock = NSLock()
    private var _profileID: String
    private var _connectionIdentity: String

    var profileID: String {
        get {
            lock.lock()
            defer { lock.unlock() }
            return _profileID
        }
        set {
            lock.lock()
            _profileID = newValue
            lock.unlock()
        }
    }

    var connectionIdentity: String {
        get {
            lock.lock()
            defer { lock.unlock() }
            return _connectionIdentity
        }
        set {
            lock.lock()
            _connectionIdentity = newValue
            lock.unlock()
        }
    }

    init(profileID: String, connectionIdentity: String) {
        _profileID = profileID
        _connectionIdentity = connectionIdentity
    }
}

private final class StallNextDirectories: @unchecked Sendable {
    private let lock = NSLock()
    private var armed = false
    private var holdContinuation: CheckedContinuation<Void, Never>?
    private let result: [String]

    var isHolding: Bool {
        lock.lock()
        defer { lock.unlock() }
        return holdContinuation != nil
    }

    init(result: [String] = []) {
        self.result = result
    }

    func arm() {
        lock.lock()
        armed = true
        lock.unlock()
    }

    func next() async -> [String] {
        lock.lock()
        let shouldStall = armed
        if shouldStall { armed = false }
        lock.unlock()
        if shouldStall {
            await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
                lock.lock()
                holdContinuation = continuation
                lock.unlock()
            }
        }
        return result
    }

    func release() {
        lock.lock()
        let continuation = holdContinuation
        holdContinuation = nil
        lock.unlock()
        continuation?.resume()
    }
}

private final class MockForegroundSSEURLProtocol: URLProtocol {
    private static let lock = NSLock()
    nonisolated(unsafe) private static var responseQueues: [String: [Data]] = [:]

    static func register(streamID: String, responses: [Data]) {
        lock.lock()
        responseQueues[streamID] = responses
        lock.unlock()
    }

    private static func streamID(from request: URLRequest) -> String? {
        guard let url = request.url, url.path.contains("event") else { return nil }
        return url.pathComponents.filter { $0 != "/" }.first
    }

    override class func canInit(with request: URLRequest) -> Bool {
        streamID(from: request) != nil
    }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest {
        request
    }

    override func startLoading() {
        guard let streamID = Self.streamID(from: request) else { return }
        Self.lock.lock()
        var queue = Self.responseQueues[streamID] ?? []
        let body = queue.isEmpty ? Data() : queue.removeFirst()
        Self.responseQueues[streamID] = queue
        Self.lock.unlock()

        let response = HTTPURLResponse(
            url: request.url!,
            statusCode: 200,
            httpVersion: "HTTP/1.1",
            headerFields: ["Content-Type": "text/event-stream"]
        )!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        if !body.isEmpty {
            client?.urlProtocol(self, didLoad: body)
        }
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}
