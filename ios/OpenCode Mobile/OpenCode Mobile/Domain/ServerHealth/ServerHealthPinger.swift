import Foundation

/// The server a health check goes to.
nonisolated struct ServerHealthPingTarget: Sendable {
    let profileID: String
    let connection: ServerConnection
    let serverProtocol: ServerProtocol

    /// `global/health` on v1, `api/info` on v2 (v2 has no `api/health`; `api/info` carries pid and version).
    var path: String {
        serverProtocol == .v2 ? "api/info" : "global/health"
    }
}

nonisolated enum ServerHealthPingOutcome: Equatable, Sendable {
    case success(latencyMs: Int, pid: Int?, version: String?)
    case failure(ServerHealthFailureCategory)
}

/// Sends one health check. Returns nil when the check was cancelled.
nonisolated protocol ServerHealthPinging: Sendable {
    func ping(_ target: ServerHealthPingTarget) async -> ServerHealthPingOutcome?
}

/// Health check over the short-timeout probe session, with Basic Auth.
nonisolated struct URLSessionServerHealthPinger: ServerHealthPinging {
    private let session: URLSession
    private let requestTimeout: TimeInterval

    init(session: URLSession = ConnectionProbeService.probeSession, requestTimeout: TimeInterval = 5) {
        self.session = session
        self.requestTimeout = requestTimeout
    }

    func ping(_ target: ServerHealthPingTarget) async -> ServerHealthPingOutcome? {
        var request = target.connection.authorizedRequest(path: target.path)
        request.timeoutInterval = requestTimeout
        request.cachePolicy = .reloadIgnoringLocalCacheData
        let clock = ContinuousClock()
        let start = clock.now
        do {
            let (data, response) = try await session.data(for: request)
            let elapsed = start.duration(to: clock.now)
            let latencyMs = Int(elapsed.components.seconds * 1000 + elapsed.components.attoseconds / 1_000_000_000_000_000)
            let status = (response as? HTTPURLResponse)?.statusCode ?? 0
            return ServerHealthPingClassifier.classify(statusCode: status, body: data, latencyMs: latencyMs)
        } catch {
            if Task.isCancelled { return nil }
            return ServerHealthPingClassifier.classify(error: error)
        }
    }
}

nonisolated enum ServerHealthPingClassifier {
    static func classify(statusCode: Int, body: Data, latencyMs: Int) -> ServerHealthPingOutcome {
        guard (200 ..< 300).contains(statusCode) else {
            return .failure(ServerHealthFailureCategory.httpStatus(statusCode))
        }
        guard let health = try? JSONDecoder().decode(HealthDTO.self, from: body) else {
            return .failure(.other)
        }
        if health.healthy == false {
            return .failure(.server)
        }
        return .success(latencyMs: max(0, latencyMs), pid: health.pid, version: health.version)
    }

    /// Nil when the error is a cancellation.
    static func classify(error: Error) -> ServerHealthPingOutcome? {
        ServerHealthFailureCategory.transportError(error).map { .failure($0) }
    }
}
