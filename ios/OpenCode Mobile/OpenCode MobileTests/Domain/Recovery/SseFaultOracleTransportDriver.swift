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
            case "target.activity":
                let target = try unwrap(step.target, "target.activity needs target")
                heartbeatTasks.append(Task { await host.heartbeat(target) })
            case "target.silence":
                host.silence(try unwrap(step.target, "target.silence needs target"))
            case "directories.set":
                host.setDirectories(step.directories ?? [])
            case "snapshot":
                let name = try unwrap(step.name, "snapshot step needs name")
                let expected = try unwrap(trace.expected.snapshots[name], "Missing expected snapshot \(name)")
                try host.assertSnapshot(expected)
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
    private let protocolValue: ServerProtocol
    private let directories: Locked<[String]>
    private let worktrees: Locked<[String]>
    private let streamID: String
    private let session: URLSession
    private let revisions: LiveConnectionRevisionSignal

    init(trace: SseFaultTrace) {
        protocolValue = SseFaultOracleDriver.protocolOf(trace.serverProtocol)
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
            serverProtocol: protocolValue
        )
        let trees = worktrees
        let client = EventsAPIClient(connectionProvider: { connection }, urlSession: session)
        stream = DefaultForegroundEventStream(
            api: client,
            adapter: EventDomainAdapter(),
            idleTimeout: .milliseconds(150),
            instanceDirectories: { trees.value },
            configurationRevisions: { revisions.stream() }
        )
        self.revisions = revisions
    }

    func awaitOpen(_ target: String) async throws {
        let opened = await waitUntil(timeoutMs: 2_000) {
            OracleSseURLProtocol.openedTargets(streamID: streamID).contains(target)
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

    func setDirectories(_ next: [String]) {
        directories.value = next
        worktrees.value = next
        revisions.yield()
    }

    func assertSnapshot(_ expected: SseFaultSnapshot) throws {
        if let wanted = expected.requiredTargets {
            let actual = SseFaultOracleDriver.requiredTargets(
                protocol: protocolValue,
                directories: directories.value
            )
            if wanted != actual {
                throw SseFaultOracleError.mismatch("requiredTargets: expected \(wanted), actual \(actual)")
            }
        }
        if let wanted = expected.liveTargets {
            let actual = OracleSseURLProtocol.liveTargets(streamID: streamID)
            if Set(wanted) != Set(actual) {
                throw SseFaultOracleError.mismatch("liveTargets: expected \(wanted), actual \(actual)")
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
    }

    func close() {
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
        lock.unlock()
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

    override func startLoading() {
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
