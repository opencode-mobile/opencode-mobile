import Foundation
import Testing
@testable import OpenCode_Mobile

struct ServerAddressProbeTests {
    private let lan = "http://192.168.0.160:4096/"
    private let tailscale = "http://100.84.57.15:4096/"

    @Test func resolve_sendsCredentialsOnlyToTheChosenHost() async {
        let probe = RecordingCredentialProbe()
        let resolution = await ServerAddressProbe.resolve(
            urls: [lan, tailscale],
            username: "opencode",
            password: "secret",
            currentBaseURL: lan,
            probing: probe
        )

        #expect(resolution.chosen == tailscale)
        #expect(resolution.reachableProtocol == .v2)
        #expect(Set(probe.unauthenticated) == Set([lan, tailscale]))
        #expect(probe.authenticated == [tailscale])
        #expect(resolution.unreachable.isEmpty)
    }

    @Test func resolve_rejectedCredentialsAreAFailureAndNoOtherHostGetsThem() async {
        let probe = RecordingCredentialProbe(authenticatedResult: .failure(
            ServerProbeError.http(statusCode: 401, message: "Unauthorized")
        ))
        let resolution = await ServerAddressProbe.resolve(
            urls: [lan, tailscale],
            username: "opencode",
            password: "wrong",
            currentBaseURL: lan,
            probing: probe
        )

        #expect(resolution.chosen == tailscale)
        #expect(resolution.reachableProtocol == nil)
        #expect(resolution.failure?.probeStatus == .authFailed)
        #expect(probe.authenticated == [tailscale])
    }

    @Test func resolve_silentHostsAreUnreachableAndNeverReceiveCredentials() async {
        let probe = RecordingCredentialProbe(silentHosts: ["100.84.57.15"])
        let resolution = await ServerAddressProbe.resolve(
            urls: [lan, tailscale],
            username: "opencode",
            password: "secret",
            currentBaseURL: lan,
            probing: probe
        )

        #expect(resolution.chosen == lan)
        #expect(resolution.unreachable == [tailscale])
        #expect(probe.authenticated == [lan])
    }

    @Test func resolve_nothingAnswered_choosesNobodyAndSendsNoCredentials() async {
        let probe = RecordingCredentialProbe(silentHosts: ["192.168.0.160", "100.84.57.15"])
        let resolution = await ServerAddressProbe.resolve(
            urls: [lan, tailscale],
            username: "opencode",
            password: "secret",
            currentBaseURL: lan,
            probing: probe
        )

        #expect(resolution.chosen == nil)
        #expect(resolution.nothingAnswered)
        #expect(probe.authenticated.isEmpty)
        #expect(resolution.unreachable == Set([lan, tailscale]))
    }

    @Test func resolve_httpsBaseURL_ignoresCleartextCandidates() async {
        let tunnel = "https://box.opentunnel.xyz/"
        let probe = RecordingCredentialProbe(silentHosts: ["box.opentunnel.xyz"])
        let resolution = await ServerAddressProbe.resolve(
            urls: [tunnel, lan],
            username: "opencode",
            password: "secret",
            currentBaseURL: tunnel,
            probing: probe
        )

        #expect(resolution.chosen == nil)
        #expect(probe.authenticated.isEmpty)
    }

    @Test func resolve_emptyPassword_reusesTheUnauthenticatedAnswer() async {
        let probe = RecordingCredentialProbe()
        let resolution = await ServerAddressProbe.resolve(
            urls: [lan],
            username: "opencode",
            password: "",
            currentBaseURL: lan,
            probing: probe
        )

        #expect(resolution.chosen == lan)
        #expect(resolution.failure?.probeStatus == .authFailed)
        #expect(probe.unauthenticated == [lan])
        #expect(probe.authenticated.isEmpty)
    }

    @Test func resolve_openServerWithoutPassword_isReachable() async {
        let probe = RecordingCredentialProbe(unauthenticatedResult: .success(.v1))
        let resolution = await ServerAddressProbe.resolve(
            urls: [lan],
            username: "opencode",
            password: "",
            currentBaseURL: lan,
            probing: probe
        )

        #expect(resolution.reachableProtocol == .v1)
        #expect(probe.authenticated.isEmpty)
    }
}

/// Answers `401` to requests without a password, `authenticatedResult` otherwise, and stays
/// silent (transport failure) for `silentHosts`.
private final class RecordingCredentialProbe: ConnectionProbing, @unchecked Sendable {
    private let lock = NSLock()
    private var unauthenticatedURLs: [String] = []
    private var authenticatedURLs: [String] = []
    private let silentHosts: Set<String>
    private let unauthenticatedResult: Result<ServerProtocol, Error>
    private let authenticatedResult: Result<ServerProtocol, Error>

    var unauthenticated: [String] { lock.withLock { unauthenticatedURLs } }
    var authenticated: [String] { lock.withLock { authenticatedURLs } }

    init(
        silentHosts: Set<String> = [],
        unauthenticatedResult: Result<ServerProtocol, Error> = .failure(
            ServerProbeError.http(statusCode: 401, message: "Unauthorized")
        ),
        authenticatedResult: Result<ServerProtocol, Error> = .success(.v2)
    ) {
        self.silentHosts = silentHosts
        self.unauthenticatedResult = unauthenticatedResult
        self.authenticatedResult = authenticatedResult
    }

    func probe(connection: ServerConnection) async throws -> ServerProtocol {
        let url = connection.baseURL.absoluteString
        let hasPassword = !(connection.password ?? "").isEmpty
        lock.withLock {
            if hasPassword {
                authenticatedURLs.append(url)
            } else {
                unauthenticatedURLs.append(url)
            }
        }
        if silentHosts.contains(where: { url.contains($0) }) {
            throw ServerProbeError.transport("down")
        }
        return try (hasPassword ? authenticatedResult : unauthenticatedResult).get()
    }
}
