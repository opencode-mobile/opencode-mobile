import Foundation
@testable import OpenCode_Mobile

enum SseFaultOracleTransportDriver {
    static func run(_ trace: SseFaultTrace) async throws {
        let host = TransportHost(trace: trace)
        defer { host.close() }
        var lastT = 0
        var heartbeatTasks: [Task<Void, Never>] = []
        for step in trace.steps {
            let delta = step.t - lastT
            if delta > 0 {
                try? await Task.sleep(for: .milliseconds(delta))
            }
            lastT = step.t
            switch step.op {
            case "stream.start":
                host.stream.start()
            case "stream.stop":
                host.stream.stop()
            case "target.open":
                try await host.awaitOpen(try unwrap(step.target, "target.open needs target"))
                host.captureGeneration()
            case "protocol.set":
                try await host.setProtocol(SseFaultOracleDriver.protocolOf(try unwrap(step.state, "protocol.set needs state")))
            case "target.activity":
                let target = try unwrap(step.target, "target.activity needs target")
                heartbeatTasks.append(Task { await host.heartbeat(target) })
            case "target.silence":
                host.silence(try unwrap(step.target, "target.silence needs target"))
            case "target.close":
                host.closeTarget(try unwrap(step.target, "target.close needs target"))
            case "target.write":
                host.write(
                    try unwrap(step.target, "target.write needs target"),
                    event: try unwrap(step.event, "target.write needs event")
                )
            case "directories.set":
                host.setDirectories(step.directories ?? [])
            case "snapshot":
                let name = try unwrap(step.name, "snapshot step needs name")
                let expected = try unwrap(trace.expected.snapshots[name], "Missing expected snapshot \(name)")
                try await host.assertSnapshot(expected)
            default:
                throw SseFaultOracleError.unsupportedOp(step.op)
            }
        }
        heartbeatTasks.forEach { $0.cancel() }
    }

    private static func unwrap<T>(_ value: T?, _ message: String) throws -> T {
        guard let value else { throw SseFaultOracleError.mismatch(message) }
        return value
    }
}

private final class TransportHost: @unchecked Sendable {
    let stream: DefaultForegroundEventStream
    private let protocolValue: Locked<ServerProtocol>
    private let directories: Locked<[String]>
    private let worktrees: Locked<[String]>
    private let streamID: String
    private let session: URLSession
    private let revisions: LiveConnectionRevisionSignal
    private let connectionBox: Locked<ServerConnection>

    init(trace: SseFaultTrace) {
        let initialProtocol = SseFaultOracleDriver.protocolOf(trace.serverProtocol)
        protocolValue = Locked(initialProtocol)
        let initial = trace.seed.directories.isEmpty ? [trace.seed.directory] : trace.seed.directories
        directories = Locked(initial)
        worktrees = Locked(initial)
        let revisions = LiveConnectionRevisionSignal()
        streamID = UUID().uuidString
        OracleSseURLProtocol.register(streamID: streamID)
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [OracleSseURLProtocol.self]
        configuration.timeoutIntervalForRequest = TimeInterval.infinity
        configuration.timeoutIntervalForResource = TimeInterval.infinity
        session = URLSession(configuration: configuration)
        let connection = ServerConnection(
            baseURL: URL(string: "http://127.0.0.1:4096/\(streamID)/")!,
            password: "secret",
            serverProtocol: initialProtocol
        )
        let connectionBox = Locked(connection)
        self.connectionBox = connectionBox
        let trees = worktrees
        let seedProfile = trace.seed.profileId
        let client = EventsAPIClient(connectionProvider: { connectionBox.value }, urlSession: session)
        stream = DefaultForegroundEventStream(
            api: client,
            adapter: EventDomainAdapter(),
            idleTimeout: .milliseconds(trace.steps.contains(where: { $0.op == "target.silence" }) ? 150 : 45_000),
            instanceDirectories: { trees.value },
            configurationRevisions: { revisions.stream() },
            profileID: { seedProfile },
            protocolHint: { connectionBox.value.serverProtocol ?? .v1 },
            protocolProbe: { connectionBox.value.serverProtocol ?? .v1 }
        )
        self.revisions = revisions
        collectTask = Task { [stream, publishedTypes] in
            for await event in stream.events() {
                publishedTypes.mutate { $0.append(event.type) }
            }
        }
    }

    private let publishedTypes = Locked([String]())
    private var collectTask: Task<Void, Never>?

    func awaitOpen(_ target: String) async throws {
        OracleSseURLProtocol.allow(streamID: streamID, target: target)
        let opened = await waitUntil(timeoutMs: 2_500) {
            OracleSseURLProtocol.openedTargets(streamID: streamID).contains(target) &&
                stream.snapshot.liveTargets.contains(target)
        }
        if !opened {
            throw SseFaultOracleError.mismatch("target \(target) did not open")
        }
    }

    func heartbeat(_ target: String) async {
        while !Task.isCancelled, !OracleSseURLProtocol.isSilenced(streamID: streamID, target: target) {
            OracleSseURLProtocol.write(streamID: streamID, target: target, frame: Self.heartbeatFrame)
            try? await Task.sleep(for: .milliseconds(40))
        }
    }

    func silence(_ target: String) {
        OracleSseURLProtocol.silence(streamID: streamID, target: target)
    }

    func closeTarget(_ target: String) {
        OracleSseURLProtocol.finish(streamID: streamID, target: target)
    }

    func write(_ target: String, event: SseFaultEvent) {
        let sessionID = event.sessionId ?? "ses-1"
        let payload: String
        if event.type == "message.part.delta" {
            payload =
                "{\"payload\":{\"type\":\"message.part.delta\",\"properties\":{" +
                "\"sessionID\":\"\(sessionID)\"," +
                "\"delta\":\"\(event.text ?? "")\"," +
                "\"field\":\"\(event.field ?? "text")\"," +
                "\"messageID\":\"\(event.messageId ?? "msg-1")\"," +
                "\"partID\":\"\(event.partId ?? "part-1")\"}}}"
        } else {
            payload = "{\"payload\":{\"type\":\"\(event.type)\",\"properties\":{\"sessionID\":\"\(sessionID)\"}}}"
        }
        OracleSseURLProtocol.write(streamID: streamID, target: target, frame: "data: \(payload)\n\n")
    }

    func setDirectories(_ next: [String]) {
        directories.value = next
        worktrees.value = next
        revisions.yield()
    }

    private var generationAtStart: UInt64?

    func captureGeneration() {
        if generationAtStart == nil {
            generationAtStart = stream.snapshot.generation
        }
    }

    func setProtocol(_ next: ServerProtocol) async {
        protocolValue.value = next
        let current = connectionBox.value
        connectionBox.value = ServerConnection(
            baseURL: current.baseURL,
            password: current.password,
            serverProtocol: next
        )
        stream.start()
        _ = await waitUntil(timeoutMs: 2_000) {
            stream.snapshot.requiredTargets == SseFaultOracleDriver.requiredTargets(
                protocol: next,
                directories: directories.value
            )
        }
    }

    func assertSnapshot(_ expected: SseFaultSnapshot) async throws {
        if let wanted = expected.publishedEventTypes {
            let reached = await waitUntil(timeoutMs: 1_000) {
                Set(publishedTypes.value) == Set(wanted)
            }
            if !reached {
                throw SseFaultOracleError.mismatch(
                    "publishedEventTypes: expected \(wanted.sorted()), actual \(Array(Set(publishedTypes.value)).sorted())"
                )
            }
        }
        let snapshot = stream.snapshot
        if let wanted = expected.requiredTargets {
            if wanted != snapshot.requiredTargets {
                throw SseFaultOracleError.mismatch("requiredTargets: expected \(wanted), actual \(snapshot.requiredTargets)")
            }
        }
        if let wanted = expected.liveTargets {
            if Set(wanted) != Set(snapshot.liveTargets) {
                throw SseFaultOracleError.mismatch("liveTargets: expected \(wanted), actual \(snapshot.liveTargets)")
            }
        }
        if let unchanged = expected.generationUnchanged {
            guard let baseline = generationAtStart else {
                throw SseFaultOracleError.mismatch("generationUnchanged requires a target.open")
            }
            if unchanged != (snapshot.generation == baseline) {
                throw SseFaultOracleError.mismatch("generationUnchanged: expected \(unchanged)")
            }
        }
        if let wanted = expected.gapReason {
            let actual = SseFaultOracleDriver.gapReasonName(snapshot.gap?.reason)
            if wanted != actual {
                throw SseFaultOracleError.mismatch("gapReason: expected \(wanted), actual \(String(describing: actual))")
            }
        }
        if let wanted = expected.openedPaths {
            let actual = OracleSseURLProtocol.openedPaths(streamID: streamID)
            for path in wanted {
                let found = actual.contains { $0 == path || $0.hasPrefix("\(path)?") || path.hasPrefix("\($0)?") }
                if !found {
                    throw SseFaultOracleError.mismatch("openedPaths missing \(path) in \(actual)")
                }
            }
        }
        if let wanted = expected.openedPathCounts {
            let actual = OracleSseURLProtocol.openedPaths(streamID: streamID)
            for (path, count) in wanted {
                let matches = actual.filter { $0 == path || $0.hasPrefix("\(path)?") || path.hasPrefix("\($0)?") }.count
                if matches != count {
                    throw SseFaultOracleError.mismatch("openedPathCounts for \(path): expected \(count), actual \(matches) in \(actual)")
                }
            }
        }
        if let wanted = expected.connectionState {
            let actual = SseFaultOracleDriver.connectionStateName(stream.connectionState)
            if wanted != actual {
                throw SseFaultOracleError.mismatch("connectionState: expected \(wanted), actual \(actual)")
            }
        }
        if let wanted = expected.chrome {
            let chrome = composeLiveUpdates(
                connectionState: stream.connectionState,
                path: .ready,
                occupancy: .active,
                hasConnection: true
            )
            if wanted != chrome.label.rawValue {
                throw SseFaultOracleError.mismatch("chrome: expected \(wanted), actual \(chrome.label.rawValue)")
            }
        }
        if let wanted = expected.publishedProfileId {
            let actual = stream.snapshot.config?.profileID
            if wanted != actual {
                throw SseFaultOracleError.mismatch("publishedProfileId: expected \(wanted), actual \(String(describing: actual))")
            }
        }
        if let wanted = expected.publishedEventTypes {
            let actual = Array(Set(publishedTypes.value)).sorted()
            if wanted.sorted() != actual {
                throw SseFaultOracleError.mismatch("publishedEventTypes: expected \(wanted.sorted()), actual \(actual)")
            }
        }
    }

    func close() {
        collectTask?.cancel()
        stream.stop()
        session.invalidateAndCancel()
        OracleSseURLProtocol.unregister(streamID: streamID)
    }

    private static let heartbeatFrame =
        "data: {\"payload\":{\"type\":\"server.heartbeat\",\"properties\":{}}}\n\n"
}

private final class OracleSseURLProtocol: URLProtocol, @unchecked Sendable {
    private struct StreamState {
        var openedPaths: [String] = []
        var liveTargets: Set<String> = []
        var silenced: Set<String> = []
        var allowed: Set<String> = []
        var pending: [String: OracleSseURLProtocol] = [:]
        var clients: [String: OracleSseURLProtocol] = [:]
    }

    private static let lock = NSLock()
    nonisolated(unsafe) private static var streams: [String: StreamState] = [:]

    static func register(streamID: String) {
        lock.lock()
        streams[streamID] = StreamState()
        lock.unlock()
    }

    static func unregister(streamID: String) {
        lock.lock()
        streams.removeValue(forKey: streamID)
        lock.unlock()
    }

    static func openedTargets(streamID: String) -> Set<String> {
        lock.lock()
        defer { lock.unlock() }
        return Set((streams[streamID]?.clients ?? [:]).keys)
    }

    static func liveTargets(streamID: String) -> [String] {
        lock.lock()
        defer { lock.unlock() }
        return Array(streams[streamID]?.liveTargets ?? [])
    }

    static func openedPaths(streamID: String) -> [String] {
        lock.lock()
        defer { lock.unlock() }
        return streams[streamID]?.openedPaths ?? []
    }

    static func isSilenced(streamID: String, target: String) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return streams[streamID]?.silenced.contains(target) == true
    }

    static func silence(streamID: String, target: String) {
        lock.lock()
        streams[streamID]?.silenced.insert(target)
        streams[streamID]?.liveTargets.remove(target)
        streams[streamID]?.clients.removeValue(forKey: target)
        lock.unlock()
    }

    static func finish(streamID: String, target: String) {
        lock.lock()
        let client = streams[streamID]?.clients[target]
        streams[streamID]?.liveTargets.remove(target)
        streams[streamID]?.clients.removeValue(forKey: target)
        streams[streamID]?.allowed.remove(target)
        lock.unlock()
        guard let client else { return }
        client.client?.urlProtocolDidFinishLoading(client)
    }

    static func write(streamID: String, target: String, frame: String) {
        lock.lock()
        let client = streams[streamID]?.clients[target]
        lock.unlock()
        guard let client, let data = frame.data(using: .utf8) else { return }
        client.client?.urlProtocol(client, didLoad: data)
    }

    private static func streamID(from request: URLRequest) -> String? {
        request.url?.pathComponents.filter { $0 != "/" }.first
    }

    private static func targetKey(from request: URLRequest) -> String {
        let path = request.url?.path ?? ""
        let directory = URLComponents(url: request.url!, resolvingAgainstBaseURL: false)?
            .queryItems?
            .first { $0.name == "directory" }?
            .value
        if path.contains("global/event") { return "global/event" }
        if path.contains("api/event") { return "api/event" }
        if let directory { return "event:\(directory)" }
        return "event"
    }

    private static func openedPath(from request: URLRequest) -> String {
        let path = request.url?.path ?? ""
        let opened: String
        if path.contains("global/event") {
            opened = "/global/event"
        } else if path.contains("api/event") {
            opened = "/api/event"
        } else {
            opened = "/event"
        }
        let directory = URLComponents(url: request.url!, resolvingAgainstBaseURL: false)?
            .queryItems?
            .first { $0.name == "directory" }?
            .value
        if let directory {
            return "\(opened)?directory=\(directory)"
        }
        return opened
    }

    override class func canInit(with request: URLRequest) -> Bool {
        guard let streamID = streamID(from: request) else { return false }
        lock.lock()
        let known = streams[streamID] != nil
        lock.unlock()
        return known && (request.url?.path.contains("event") == true)
    }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    static func allow(streamID: String, target: String) {
        lock.lock()
        streams[streamID]?.allowed.insert(target)
        let pending = streams[streamID]?.pending.removeValue(forKey: target)
        lock.unlock()
        pending?.completeOpen()
    }

    private func completeOpen() {
        guard let streamID = Self.streamID(from: request) else { return }
        let target = Self.targetKey(from: request)
        let path = Self.openedPath(from: request)
        let response = HTTPURLResponse(
            url: request.url!,
            statusCode: 200,
            httpVersion: "HTTP/1.1",
            headerFields: ["Content-Type": "text/event-stream"]
        )!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        let connected = Data("data: {\"payload\":{\"type\":\"server.connected\",\"properties\":{}}}\n\n".utf8)
        client?.urlProtocol(self, didLoad: connected)
        Self.lock.lock()
        Self.streams[streamID]?.openedPaths.append(path)
        Self.streams[streamID]?.liveTargets.insert(target)
        Self.streams[streamID]?.clients[target] = self
        Self.lock.unlock()
    }

    override func startLoading() {
        guard let streamID = Self.streamID(from: request) else { return }
        let target = Self.targetKey(from: request)
        Self.lock.lock()
        let allowed = Self.streams[streamID]?.allowed.contains(target) == true
        if !allowed {
            Self.streams[streamID]?.pending[target] = self
            Self.lock.unlock()
            return
        }
        Self.lock.unlock()
        completeOpen()
    }

    override func stopLoading() {
        guard let streamID = Self.streamID(from: request) else { return }
        let target = Self.targetKey(from: request)
        Self.lock.lock()
        Self.streams[streamID]?.liveTargets.remove(target)
        if Self.streams[streamID]?.clients[target] === self {
            Self.streams[streamID]?.clients.removeValue(forKey: target)
        }
        Self.lock.unlock()
    }
}
