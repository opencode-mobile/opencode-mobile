import Foundation
@testable import OpenCode_Mobile

enum SseFaultOracleDriver {
    static func run(_ trace: SseFaultTrace) async throws {
        if trace.surface == "transport" {
            try await SseFaultOracleTransportDriver.run(trace)
            return
        }
        try await runRecovery(trace)
    }

    private static func runRecovery(_ trace: SseFaultTrace) async throws {
        let stream = RecordingForegroundEventStream()
        let profileID = Locked(trace.seed.profileId)
        let sessionID = Locked(trace.seed.sessionId)
        let directory = Locked(trace.seed.directory)
        let directories = Locked(trace.seed.directories.isEmpty ? [trace.seed.directory] : trace.seed.directories)
        let messages = Locked(seedMessages(trace.seed.messages))
        let transcript = Locked([SseFaultTranscriptMessage]())
        let catchUp = Locked(false)
        let restHold = RestHoldGate()
        let restFailures = Locked(0)
        let restInFlight = Locked(0)
        let clock = OracleClock()
        var observeTasks: [Task<Void, Never>] = []

        let world: any WorldSignals
        if trace.surface == "composed" {
            world = SceneWorldSignals(
                stream: stream,
                deviceNetworkPath: ReadyDeviceNetworkPath(),
                hasActiveConnection: { true },
                stopGrace: .milliseconds(5_000)
            )
        } else {
            world = FakeWorldSignals()
        }

        let serverProtocol = protocolOf(trace.serverProtocol)
        if trace.steps.contains(where: { $0.op == "target.open" || $0.op == "target.close" }) {
            stream.fake.modelLeasesExplicitly()
        }
        stream.fake.applyConfig(
            profileID: profileID.value,
            protocol: serverProtocol,
            directories: directories.value
        )
        let sync = MessageSync(
            listMessages: { _, _ in
                restInFlight.mutate { $0 += 1 }
                defer { restInFlight.mutate { $0 -= 1 } }
                await restHold.awaitIfHeld()
                if restFailures.value > 0 {
                    restFailures.mutate { $0 -= 1 }
                    throw NSError(domain: "oracle", code: 1)
                }
                return messages.value
            },
            store: SessionMessageEngine(store: InMemoryMessageStore()),
            eventStream: stream,
            worldSignals: world,
            pollIntervalMs: 60_000,
            connectionProfileIDProvider: { profileID.value },
            recoveryDelay: { _ in await clock.wait(ms: 25) },
            nowMs: { clock.current }
        )

        applyConnectionState(stream, trace.seed.connectionState)
        seedOccupancy(world, trace.seed.occupancy)
        await drainOracle(sync: sync, restInFlight: restInFlight, restHold: restHold, expectWork: false)

        var lastT = 0
        for step in trace.steps {
            if step.t != lastT {
                let resumed = clock.advance(to: Int64(step.t))
                await drainOracle(
                    sync: sync,
                    restInFlight: restInFlight,
                    restHold: restHold,
                    expectWork: resumed > 0
                )
            }
            lastT = step.t
            switch step.op {
            case "observe":
                let observedSession = sessionID.value
                let observedDirectory = directory.value
                observeTasks.append(Task {
                    for await update in sync.observeMessages(sessionID: observedSession, directory: observedDirectory) {
                        if case .messages(let next, _) = update {
                            transcript.value = next.map { SseFaultTranscriptMessage(id: $0.id, text: $0.text) }
                        }
                    }
                })
                observeTasks.append(Task {
                    for await flag in sync.observeCatchUpChrome(sessionID: observedSession, directory: observedDirectory) {
                        catchUp.value = flag
                    }
                })
                _ = await waitUntil(timeoutMs: 1_000) { !transcript.value.isEmpty || !sync.recordedRestAttempts().isEmpty }
            case "unobserve":
                observeTasks.forEach { $0.cancel() }
                observeTasks.removeAll()
            case "world.foreground":
                emitWorld(world, .foreground)
            case "world.background":
                emitWorld(world, .background)
            case "world.backgroundStop":
                emitWorld(world, .backgroundStop)
            case "world.pathReady":
                emitWorld(world, .pathReady)
            case "world.pathLost":
                emitWorld(world, .pathLost)
            case "world.profileSwitched":
                emitWorld(world, .profileSwitched)
            case "stream.start":
                stream.start()
            case "stream.stop":
                stream.stop()
            case "connectionState":
                let state = try unwrap(step.state, "connectionState step needs state")
                applyConnectionState(stream, state)
            case "epoch.bump", "openGap":
                stream.fake.openGap(gapReason(step.state))
            case "cache.clear":
                sync.clearCache()
            case "protocol.set":
                let next = protocolOf(try unwrap(step.state, "protocol.set needs state"))
                stream.fake.applyConfig(
                    profileID: profileID.value,
                    protocol: next,
                    directories: directories.value
                )
                stream.start()
            case "emit":
                let event = try unwrap(step.event, "emit step needs event")
                stream.fake.emit(toOpencodeEvent(event, defaultSessionID: sessionID.value))
            case "rest.setMessages":
                messages.value = seedMessages(step.messages ?? [])
            case "rest.hold":
                restHold.hold()
            case "rest.release":
                restHold.release()
                await drainOracle(sync: sync, restInFlight: restInFlight, restHold: restHold, expectWork: true)
            case "rest.fail":
                restFailures.mutate { $0 += 1 }
            case "profile.switch":
                profileID.value = try unwrap(step.profileId, "profile.switch needs profileId")
                if let next = step.sessionId { sessionID.value = next }
                if let next = step.directory { directory.value = next }
                if let next = step.directories { directories.value = next }
                sync.clearCache()
                stream.fake.applyConfig(
                    profileID: profileID.value,
                    protocol: serverProtocol,
                    directories: directories.value
                )
                if let scene = world as? SceneWorldSignals {
                    scene.onProfileSwitched()
                } else {
                    emitWorld(world, .profileSwitched)
                }
            case "directories.set":
                directories.value = step.directories ?? []
                stream.fake.applyConfig(
                    profileID: profileID.value,
                    protocol: serverProtocol,
                    directories: directories.value
                )
            case "target.open":
                stream.fake.openTarget(try unwrap(step.target, "target.open needs target"))
                await drainOracle(sync: sync, restInFlight: restInFlight, restHold: restHold, expectWork: false)
            case "target.close":
                stream.fake.closeTarget(try unwrap(step.target, "target.close needs target"))
            case "snapshot":
                await drainOracle(sync: sync, restInFlight: restInFlight, restHold: restHold, expectWork: false)
                let name = try unwrap(step.name, "snapshot step needs name")
                let expected = try unwrap(trace.expected.snapshots[name], "Missing expected snapshot \(name)")
                if let wanted = expected.catchUpChrome {
                    _ = await waitUntil(timeoutMs: 2_000) { catchUp.value == wanted }
                }
                if let wanted = expected.transcript {
                    _ = await waitUntil(timeoutMs: 2_000) { transcript.value == wanted }
                }
                try assertSnapshot(
                    expected,
                    sync: sync,
                    sessionID: sessionID.value,
                    directory: directory.value,
                    stream: stream,
                    world: world,
                    transcript: transcript.value,
                    catchUpChrome: catchUp.value
                )
            default:
                throw SseFaultOracleError.unsupportedOp(step.op)
            }
            try? await Task.sleep(for: .milliseconds(5))
        }
        observeTasks.forEach { $0.cancel() }
    }

    private static func emitWorld(_ world: any WorldSignals, _ signal: WorldSignal) {
        if let scene = world as? SceneWorldSignals {
            switch signal {
            case .foreground:
                scene.onForeground()
            case .background:
                scene.onBackground()
            case .backgroundStop:
                scene.onBackgroundStop()
            case .pathReady:
                scene.onPathReady()
            case .pathLost:
                scene.onPathLost()
            case .profileSwitched:
                scene.onProfileSwitched()
            }
            return
        }
        if let fake = world as? FakeWorldSignals {
            fake.emit(signal)
        }
    }

    private static func seedOccupancy(_ world: any WorldSignals, _ occupancy: String?) {
        guard let fake = world as? FakeWorldSignals else { return }
        switch occupancy {
        case "grace":
            fake.setOccupancy(.grace)
        case "stopped":
            fake.setOccupancy(.stopped)
        default:
            break
        }
    }

    static func seedMessages(_ messages: [SseFaultTranscriptMessage]) -> [Message] {
        let source = messages.isEmpty
            ? [SseFaultTranscriptMessage(id: "msg-1", text: "Hello")]
            : messages
        return source.map { item in
            Message(
                id: item.id,
                role: .user,
                text: item.text,
                parts: [.text(TextPart(id: "part-1", text: item.text, isStreaming: false))],
                model: nil,
                mode: nil,
                sentAtEpochMs: nil
            )
        }
    }

    static func protocolOf(_ value: String) -> ServerProtocol {
        switch value {
        case "v1": .v1
        case "v2": .v2
        default: fatalError("Unknown protocol \(value)")
        }
    }

    static func applyConnectionState(_ stream: RecordingForegroundEventStream, _ state: String?) {
        guard let state else { return }
        stream.fake.setConnectionState(connectionStateOf(state))
    }

    static func connectionStateOf(_ value: String) -> EventStreamConnectionState {
        switch value {
        case "idle": .idle
        case "connecting": .connecting
        case "connected": .connected
        case "disconnected": .disconnected
        case "reconnecting": .reconnecting
        case "authFailed": .authFailed
        default: fatalError("Unknown connection state \(value)")
        }
    }

    static func requiredTargets(protocol serverProtocol: ServerProtocol, directories: [String]) -> [String] {
        SseSubscribeTargets.targets(protocol: serverProtocol, directories: directories).map(\.key)
    }

    static func assertSnapshot(
        _ expected: SseFaultSnapshot,
        sync: MessageSync,
        sessionID: String,
        directory: String,
        stream: RecordingForegroundEventStream,
        world: any WorldSignals,
        transcript: [SseFaultTranscriptMessage],
        catchUpChrome: Bool,
        liveTargets: [String]? = nil,
        publishedEventTypes: [String]? = nil
    ) throws {
        let snapshot = stream.snapshot
        if let wanted = expected.catchUpChrome {
            try expectEqual(wanted, catchUpChrome, "catchUpChrome")
        }
        if let wanted = expected.chrome {
            let chrome = composeLiveUpdates(
                connectionState: snapshot.connectionState,
                path: .ready,
                occupancy: world.occupancy,
                hasConnection: true
            )
            try expectEqual(wanted, chrome.label.rawValue, "chrome")
        }
        if let wanted = expected.publishedProfileId {
            let actual = sync.publishedProfileID() ?? snapshot.config?.profileID
            try expectEqual(wanted, actual, "publishedProfileId")
        }
        if let wanted = expected.transcript {
            try expectEqual(wanted, transcript, "transcript")
        }
        if let wanted = expected.requiredTargets {
            try expectEqual(wanted, snapshot.requiredTargets, "requiredTargets")
        }
        if let wanted = expected.liveTargets {
            let actual = liveTargets ?? snapshot.liveTargets
            try expectEqual(Set(wanted), Set(actual), "liveTargets")
        }
        if let forbidden = expected.unpublishedEventTypes {
            let actual = publishedEventTypes ?? []
            for type in forbidden where actual.contains(type) {
                throw SseFaultOracleError.mismatch("\(type) was published in \(actual)")
            }
        }
    }

    static func toOpencodeEvent(_ event: SseFaultEvent, defaultSessionID: String) -> OpencodeEvent {
        let sessionID = event.sessionId ?? defaultSessionID
        let properties: [String: AnySendable]?
        if event.type == "message.part.delta" || event.text != nil {
            properties = [
                "delta": .string(event.text ?? ""),
                "field": .string(event.field ?? "text"),
                "messageID": .string(event.messageId ?? "msg-1"),
                "partID": .string(event.partId ?? "part-1"),
            ]
        } else {
            properties = nil
        }
        return OpencodeEvent(type: event.type, sessionID: sessionID, rawProperties: properties)
    }

    static func gapReason(_ state: String?) -> LiveGapReason {
        switch state {
        case "parseFailed": .parseFailed
        case "leaseLost": .leaseLost
        case "pathLost": .pathLost
        case "configChanged": .configChanged
        default: .reconnect
        }
    }

    private static func unwrap<T>(_ value: T?, _ message: String) throws -> T {
        guard let value else { throw SseFaultOracleError.mismatch(message) }
        return value
    }

    private static func expectEqual<T: Equatable>(_ expected: T, _ actual: T, _ label: String) throws {
        if expected != actual {
            throw SseFaultOracleError.mismatch("\(label): expected \(expected), actual \(actual)")
        }
    }
}

enum SseFaultOracleError: Error, CustomStringConvertible {
    case unsupportedOp(String)
    case mismatch(String)
    case invalidSchema(String)

    var description: String {
        switch self {
        case .unsupportedOp(let op): "Unsupported op \(op)"
        case .mismatch(let message): message
        case .invalidSchema(let name): "Invalid schema \(name)"
        }
    }
}

final class Locked<Value: Sendable>: @unchecked Sendable {
    private let lock = NSLock()
    private var _value: Value

    init(_ value: Value) {
        _value = value
    }

    var value: Value {
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

    func mutate(_ body: (inout Value) -> Void) {
        lock.lock()
        body(&_value)
        lock.unlock()
    }
}

final class RestHoldGate: @unchecked Sendable {
    private let lock = NSLock()
    private var held = false
    private var waiters: [CheckedContinuation<Void, Never>] = []

    var isHeld: Bool {
        lock.lock()
        defer { lock.unlock() }
        return held
    }

    func hold() {
        lock.lock()
        held = true
        lock.unlock()
    }

    func release() {
        lock.lock()
        held = false
        let pending = waiters
        waiters.removeAll()
        lock.unlock()
        pending.forEach { $0.resume() }
    }

    func awaitIfHeld() async {
        lock.lock()
        if !held {
            lock.unlock()
            return
        }
        await withCheckedContinuation { continuation in
            waiters.append(continuation)
            lock.unlock()
        }
    }
}

private func drainOracle(
    sync: MessageSync,
    restInFlight: Locked<Int>,
    restHold: RestHoldGate,
    expectWork: Bool
) async {
    if restHold.isHeld && restInFlight.value > 0 { return }
    let baseline = sync.recordedRestAttempts()
    if expectWork {
        _ = await waitUntil(timeoutMs: 1_000) {
            restInFlight.value > 0 || sync.recordedRestAttempts() != baseline
        }
    }
    var lastAttempts = sync.recordedRestAttempts()
    var quiet = 0
    for _ in 0..<50 {
        await Task.yield()
        try? await Task.sleep(for: .milliseconds(2))
        let attempts = sync.recordedRestAttempts()
        if restInFlight.value == 0 && attempts == lastAttempts {
            quiet += 1
            if quiet >= 4 { return }
        } else {
            quiet = 0
            lastAttempts = attempts
        }
    }
}

final class OracleClock: @unchecked Sendable {
    private let lock = NSLock()
    private var now: Int64 = 0
    private var waiters: [(deadline: Int64, continuation: CheckedContinuation<Void, Never>)] = []

    var current: Int64 {
        lock.lock()
        defer { lock.unlock() }
        return now
    }

    @discardableResult
    func advance(to t: Int64) -> Int {
        lock.lock()
        now = t
        let due = waiters.filter { $0.deadline <= t }
        waiters.removeAll { $0.deadline <= t }
        lock.unlock()
        due.forEach { $0.continuation.resume() }
        return due.count
    }

    func wait(ms: Int) async {
        lock.lock()
        let deadline = now + Int64(ms)
        if now >= deadline {
            lock.unlock()
            return
        }
        await withCheckedContinuation { continuation in
            waiters.append((deadline, continuation))
            lock.unlock()
        }
    }
}

func waitUntil(timeoutMs: Int = 1_000, predicate: () -> Bool) async -> Bool {
    let deadline = Date().addingTimeInterval(TimeInterval(timeoutMs) / 1_000)
    while Date() < deadline {
        if predicate() { return true }
        try? await Task.sleep(for: .milliseconds(10))
    }
    return predicate()
}
