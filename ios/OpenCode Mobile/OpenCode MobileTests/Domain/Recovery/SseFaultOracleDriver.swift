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
        let epoch = ConnectionEpoch()
        let profileID = Locked(trace.seed.profileId)
        let sessionID = Locked(trace.seed.sessionId)
        let directory = Locked(trace.seed.directory)
        let directories = Locked(trace.seed.directories.isEmpty ? [trace.seed.directory] : trace.seed.directories)
        let messages = Locked(seedMessages(trace.seed.messages))
        let fetchReasons = Locked([String]())
        let pendingReason = Locked("initial-subscribe")
        let transcript = Locked([SseFaultTranscriptMessage]())
        let catchUp = Locked(false)
        let epochAtObserve = Locked<UInt64?>(nil)
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

        let sync = MessageSync(
            listMessages: { _, _ in
                fetchReasons.mutate { $0.append(pendingReason.value) }
                return messages.value
            },
            store: SessionMessageEngine(store: InMemoryMessageStore()),
            eventStream: stream,
            worldSignals: world,
            pollIntervalMs: 60_000,
            connectionProfileIDProvider: { profileID.value },
            recoveryDelay: { _ in try? await Task.sleep(for: .milliseconds(1)) },
            nowMs: { Int64(Date().timeIntervalSince1970 * 1000) },
            connectionEpoch: epoch
        )

        applyConnectionState(stream, trace.seed.connectionState)
        seedOccupancy(world, trace.seed.occupancy)
        try? await Task.sleep(for: .milliseconds(5))

        var lastT = 0
        for step in trace.steps {
            let delta = step.t - lastT
            if delta > 0 {
                try? await Task.sleep(for: .milliseconds(delta))
            }
            lastT = step.t
            switch step.op {
            case "observe":
                pendingReason.value = "initial-subscribe"
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
                _ = await waitUntil(timeoutMs: 1_000) { !transcript.value.isEmpty || !fetchReasons.value.isEmpty }
                if epochAtObserve.value == nil {
                    epochAtObserve.value = epoch.current()
                }
            case "unobserve":
                observeTasks.forEach { $0.cancel() }
                observeTasks.removeAll()
            case "world.foreground":
                pendingReason.value = "background-return"
                emitWorld(world, .foreground)
            case "world.background":
                emitWorld(world, .background)
            case "world.backgroundStop":
                emitWorld(world, .backgroundStop)
            case "world.pathReady":
                pendingReason.value = "path-ready"
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
                if state == "connecting" {
                    pendingReason.value = "stream.reconnected"
                }
                applyConnectionState(stream, state)
            case "epoch.bump":
                _ = epoch.bump()
            case "emit":
                let event = try unwrap(step.event, "emit step needs event")
                if event.type == "stream.reconnected" {
                    pendingReason.value = "stream.reconnected"
                } else if event.type == "server.connected" {
                    pendingReason.value = "server.connected"
                }
                stream.fake.emit(toOpencodeEvent(event, defaultSessionID: sessionID.value))
            case "rest.setMessages":
                messages.value = seedMessages(step.messages ?? [])
            case "rest.hold", "rest.release":
                break
            case "profile.switch":
                profileID.value = try unwrap(step.profileId, "profile.switch needs profileId")
                if let next = step.sessionId { sessionID.value = next }
                if let next = step.directory { directory.value = next }
                if let next = step.directories { directories.value = next }
                sync.clearCache()
                emitWorld(world, .profileSwitched)
            case "directories.set":
                directories.value = step.directories ?? []
            case "snapshot":
                try? await Task.sleep(for: .milliseconds(20))
                let name = try unwrap(step.name, "snapshot step needs name")
                let expected = try unwrap(trace.expected.snapshots[name], "Missing expected snapshot \(name)")
                try assertSnapshot(
                    expected,
                    sync: sync,
                    sessionID: sessionID.value,
                    directory: directory.value,
                    stream: stream,
                    world: world,
                    epoch: epoch,
                    epochAtObserve: epochAtObserve.value,
                    fetchReasons: fetchReasons.value,
                    transcript: transcript.value,
                    catchUpChrome: catchUp.value,
                    profileID: profileID.value,
                    protocol: protocolOf(trace.serverProtocol),
                    directories: directories.value
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
            default:
                break
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
            fake.emit(.background)
        case "stopped":
            fake.emit(.backgroundStop)
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
                parts: [.text(TextPart(text: item.text, isStreaming: false))],
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
        default: fatalError("Unknown connection state \(value)")
        }
    }

    static func connectionStateName(_ state: EventStreamConnectionState) -> String {
        switch state {
        case .idle: "idle"
        case .connecting: "connecting"
        case .connected: "connected"
        case .disconnected: "disconnected"
        case .reconnecting: "reconnecting"
        }
    }

    static func occupancyName(_ occupancy: StreamOccupancy) -> String {
        occupancy.rawValue
    }

    static func healthName(_ health: MessageSyncHealth) -> String {
        switch health {
        case .healthy: "healthy"
        case .stale: "stale"
        case .recovering: "recovering"
        }
    }

    static func healthReason(_ health: MessageSyncHealth) -> String? {
        switch health {
        case .healthy: nil
        case .stale(let reason), .recovering(let reason): reason.rawValue
        }
    }

    static func toOpencodeEvent(_ event: SseFaultEvent, defaultSessionID: String) -> OpencodeEvent {
        OpencodeEvent(type: event.type, sessionID: event.sessionId ?? defaultSessionID)
    }

    static func requiredTargets(protocol serverProtocol: ServerProtocol, directories: [String]) -> [String] {
        SseSubscribeTargets.targets(protocol: serverProtocol, directories: directories).map { target in
            if let directory = target.directory {
                return "\(target.path):\(directory)"
            }
            return target.path
        }
    }

    static func groupedFetches(_ reasons: [String]) -> [SseFaultRestFetch] {
        Dictionary(grouping: reasons, by: { $0 })
            .map { SseFaultRestFetch(reason: $0.key, count: $0.value.count) }
            .sorted { $0.reason < $1.reason }
    }

    static func assertSnapshot(
        _ expected: SseFaultSnapshot,
        sync: MessageSync,
        sessionID: String,
        directory: String,
        stream: RecordingForegroundEventStream,
        world: any WorldSignals,
        epoch: ConnectionEpoch,
        epochAtObserve: UInt64?,
        fetchReasons: [String],
        transcript: [SseFaultTranscriptMessage],
        catchUpChrome: Bool,
        profileID: String,
        protocol serverProtocol: ServerProtocol,
        directories: [String],
        liveTargets: [String]? = nil,
        openedPaths: [String]? = nil
    ) throws {
        let health = sync.syncHealth(sessionID: sessionID, directory: directory)
        if let wanted = expected.syncHealth {
            try expectEqual(wanted, healthName(health), "syncHealth")
        }
        if let wanted = expected.syncHealthReason {
            try expectEqual(wanted, healthReason(health), "syncHealthReason")
        }
        if let wanted = expected.catchUpChrome {
            try expectEqual(wanted, catchUpChrome, "catchUpChrome")
        }
        if let wanted = expected.connectionState {
            try expectEqual(wanted, connectionStateName(stream.connectionState), "connectionState")
        }
        if let wanted = expected.occupancy {
            try expectEqual(wanted, occupancyName(world.occupancy), "occupancy")
        }
        if let wanted = expected.chrome {
            let chrome = composeLiveUpdates(
                connectionState: stream.connectionState,
                path: .ready,
                occupancy: world.occupancy,
                hasConnection: true
            )
            try expectEqual(wanted, chrome.label.rawValue, "chrome")
        }
        if let unchanged = expected.epochUnchanged {
            let baseline = try unwrap(epochAtObserve, "epochUnchanged requires an observe step")
            try expectEqual(unchanged, epoch.current() == baseline, "epochUnchanged")
        }
        if let wanted = expected.restFetches {
            try expectEqual(wanted.sorted { $0.reason < $1.reason }, groupedFetches(fetchReasons), "restFetches")
        }
        if let wanted = expected.publishedProfileId {
            try expectEqual(wanted, profileID, "publishedProfileId")
        }
        if let wanted = expected.transcript {
            try expectEqual(wanted, transcript, "transcript")
        }
        if let wanted = expected.streamOps {
            try expectEqual(wanted, stream.ops, "streamOps")
        }
        if let wanted = expected.requiredTargets {
            try expectEqual(wanted, requiredTargets(protocol: serverProtocol, directories: directories), "requiredTargets")
        }
        if let wanted = expected.liveTargets {
            try expectEqual(Set(wanted), Set(try unwrap(liveTargets, "liveTargets")), "liveTargets")
        }
        if let wanted = expected.openedPaths {
            let actual = try unwrap(openedPaths, "openedPaths")
            for path in wanted {
                let found = actual.contains { $0 == path || $0.hasPrefix("\(path)?") || path.hasPrefix("\($0)?") }
                if !found {
                    throw SseFaultOracleError.mismatch("openedPaths missing \(path) in \(actual)")
                }
            }
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

func waitUntil(timeoutMs: Int = 1_000, predicate: () -> Bool) async -> Bool {
    let deadline = Date().addingTimeInterval(TimeInterval(timeoutMs) / 1_000)
    while Date() < deadline {
        if predicate() { return true }
        try? await Task.sleep(for: .milliseconds(10))
    }
    return predicate()
}
