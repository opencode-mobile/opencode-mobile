import Foundation
import Testing
@testable import OpenCode_Mobile

@Suite struct ServerHealthClassificationTests {
    private func body(_ json: String) -> Data { Data(json.utf8) }

    @Test func healthyCheckDecodesPidAndVersion() {
        let outcome = ServerHealthPingClassifier.classify(
            statusCode: 200,
            body: body(#"{"healthy":true,"version":"1.4.0","pid":4821}"#),
            latencyMs: 23
        )
        #expect(outcome == .success(latencyMs: 23, pid: 4821, version: "1.4.0"))
    }

    @Test func v2InfoBodyWithoutHealthyFlagIsHealthy() {
        let outcome = ServerHealthPingClassifier.classify(
            statusCode: 200,
            body: body(#"{"version":"2.1.0","pid":77,"paths":{"tmp":"/tmp/example"}}"#),
            latencyMs: 12
        )
        #expect(outcome == .success(latencyMs: 12, pid: 77, version: "2.1.0"))
    }

    @Test func checkFailuresByStatusAndBody() {
        let ok = body(#"{"healthy":true}"#)
        #expect(ServerHealthPingClassifier.classify(statusCode: 401, body: ok, latencyMs: 5) == .failure(.auth))
        #expect(ServerHealthPingClassifier.classify(statusCode: 403, body: ok, latencyMs: 5) == .failure(.auth))
        #expect(ServerHealthPingClassifier.classify(statusCode: 500, body: ok, latencyMs: 5) == .failure(.server))
        #expect(ServerHealthPingClassifier.classify(statusCode: 503, body: ok, latencyMs: 5) == .failure(.server))
        #expect(ServerHealthPingClassifier.classify(statusCode: 404, body: ok, latencyMs: 5) == .failure(.other))
        #expect(ServerHealthPingClassifier.classify(statusCode: 302, body: ok, latencyMs: 5) == .failure(.other))
        #expect(
            ServerHealthPingClassifier.classify(statusCode: 200, body: body(#"{"healthy":false}"#), latencyMs: 5)
                == .failure(.server)
        )
        #expect(ServerHealthPingClassifier.classify(statusCode: 200, body: body("<html>"), latencyMs: 5) == .failure(.other))
    }

    @Test func checkTransportErrors() {
        #expect(ServerHealthPingClassifier.classify(error: URLError(.timedOut)) == .failure(.timeout))
        #expect(ServerHealthPingClassifier.classify(error: URLError(.notConnectedToInternet)) == .failure(.network))
        #expect(ServerHealthPingClassifier.classify(error: URLError(.cannotConnectToHost)) == .failure(.network))
        #expect(ServerHealthPingClassifier.classify(error: URLError(.cancelled)) == nil)
        #expect(ServerHealthPingClassifier.classify(error: CancellationError()) == nil)
    }

    @Test func streamOutcomes() {
        #expect(ServerHealthFailureCategory.streamOutcome(.ended) == .server)
        #expect(ServerHealthFailureCategory.streamOutcome(.http(401)) == .auth)
        #expect(ServerHealthFailureCategory.streamOutcome(.http(403)) == .auth)
        #expect(ServerHealthFailureCategory.streamOutcome(.http(502)) == .server)
        #expect(ServerHealthFailureCategory.streamOutcome(.http(404)) == .other)
        #expect(ServerHealthFailureCategory.streamOutcome(.transport(SseHandshakeError(reason: .timeout))) == .timeout)
        #expect(ServerHealthFailureCategory.streamOutcome(.transport(SseConnectBudgetExceeded())) == .timeout)
        #expect(ServerHealthFailureCategory.streamOutcome(.transport(URLError(.timedOut))) == .timeout)
        #expect(ServerHealthFailureCategory.streamOutcome(.transport(URLError(.networkConnectionLost))) == .network)
        #expect(ServerHealthFailureCategory.streamOutcome(.transport(nil)) == .network)
        #expect(ServerHealthFailureCategory.streamOutcome(.transport(CancellationError())) == nil)
    }

    @Test func attemptReportClaimsOnce() {
        let report = ServerHealthAttemptReport()
        #expect(report.claim())
        #expect(!report.claim())
    }

    @Test func pingPathFollowsTheProtocol() {
        let connection = ServerConnection(baseURL: URL(string: "http://192.0.2.10:4096")!, password: "test-password")
        #expect(ServerHealthPingTarget(profileID: "p", connection: connection, serverProtocol: .v1).path == "global/health")
        #expect(ServerHealthPingTarget(profileID: "p", connection: connection, serverProtocol: .v2).path == "api/info")
    }

    @Test func connectionStatesMapFromTheEventStream() {
        #expect(ServerHealthConnectionState(.idle) == .stopped)
        #expect(ServerHealthConnectionState(.connecting) == .connecting)
        #expect(ServerHealthConnectionState(.connected) == .live)
        #expect(ServerHealthConnectionState(.disconnected) == .offline)
        #expect(ServerHealthConnectionState(.reconnecting) == .reconnecting)
        #expect(ServerHealthConnectionState(.authFailed) == .authFailed)
        #expect(ServerHealthConnectionState.authFailed.rawValue == "authFailed")
    }
}
