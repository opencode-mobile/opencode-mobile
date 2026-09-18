import Foundation
import Testing
@testable import OpenCode_Mobile

struct ServerProtocolProbeErrorsTests {
    @Test func preferKeepsAuthOverLaterNotFound() {
        let preferred = ServerProtocolProbeErrors.prefer(
            previous: ServerProbeError.http(statusCode: 401, message: "unauthorized"),
            next: ServerProbeError.http(statusCode: 404, message: "missing")
        )
        guard case let .http(statusCode, _) = preferred as? ServerProbeError else {
            Issue.record("expected http error")
            return
        }
        #expect(statusCode == 401)
    }

    @Test func unresolvedUsesUnhealthyBodyWhenFetchesDidNotThrow() {
        let error = ServerProtocolProbeErrors.unresolved(
            globalHealth: HealthDTO(healthy: false, version: "9.9.9", pid: nil),
            apiHealth: nil,
            fetchError: nil
        )
        guard case let .unhealthy(version) = error as? ServerProbeError else {
            Issue.record("expected unhealthy error")
            return
        }
        #expect(version == "9.9.9")
    }

    @Test func preferDoesNotPromoteNotFoundOverDecodeMiss() {
        let preferred = ServerProtocolProbeErrors.prefer(
            previous: ServerProbeError.transport("non-json"),
            next: ServerProbeError.http(statusCode: 404, message: "Not Found")
        )
        guard case .transport = preferred as? ServerProbeError else {
            Issue.record("expected transport miss, got \(String(describing: preferred))")
            return
        }
    }
}
