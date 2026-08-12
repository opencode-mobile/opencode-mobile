import Foundation
import Testing
@testable import OpenCode_Mobile

struct ConnectionProbeServiceTests {
    @Test func probe_detectsV1FromGlobalHealthAndSkipsApiHealth() async throws {
        ProtocolHealthURLProtocol.reset(responsesByPath: [
            "/global/health": (200, #"{"healthy":true,"version":"1.18.4"}"#),
        ])
        let service = makeService()

        let detected = try await service.probe(connection: sampleConnection)

        #expect(detected == .v1)
        #expect(requestedPaths == ["/global/health"])
    }

    @Test func probe_detectsV2FromApiHealthPidWhenGlobalMissing() async throws {
        ProtocolHealthURLProtocol.reset(responsesByPath: [
            "/global/health": (404, #"{}"#),
            "/api/health": (200, #"{"healthy":true,"version":"2.0.0","pid":123}"#),
        ])
        let service = makeService()

        let detected = try await service.probe(connection: sampleConnection)

        #expect(detected == .v2)
        #expect(requestedPaths == ["/global/health", "/api/health"])
    }

    @Test func probe_detectsTransitionalV1FromApiHealthWithoutPid() async throws {
        ProtocolHealthURLProtocol.reset(responsesByPath: [
            "/global/health": (404, #"{}"#),
            "/api/health": (200, #"{"healthy":true}"#),
        ])
        let service = makeService()

        let detected = try await service.probe(connection: sampleConnection)

        #expect(detected == .v1)
    }

    @Test func probe_prefersV1WhenBothHealthEndpointsExist() async throws {
        ProtocolHealthURLProtocol.reset(responsesByPath: [
            "/global/health": (200, #"{"healthy":true,"version":"1.18.4"}"#),
            "/api/health": (200, #"{"healthy":true,"version":"2.0.0","pid":123}"#),
        ])
        let service = makeService()

        let detected = try await service.probe(connection: sampleConnection)

        #expect(detected == .v1)
        #expect(requestedPaths == ["/global/health"])
    }

    @Test func probe_failsWhenNeitherHealthEndpointIsUsable() async {
        ProtocolHealthURLProtocol.reset(responsesByPath: [
            "/global/health": (404, #"{}"#),
            "/api/health": (404, #"{}"#),
        ])
        let service = makeService()

        do {
            _ = try await service.probe(connection: sampleConnection)
            Issue.record("expected probe failure")
        } catch is ServerProbeError {
            // expected
        } catch {
            Issue.record("unexpected error \(error)")
        }
    }

    @Test func activeConnectionManager_cachesDetectedProtocol() async {
        let manager = ActiveConnectionManager()
        let profile = ConnectionProfile(
            baseURL: "http://127.0.0.1:4096/",
            username: "opencode",
            password: "x"
        )

        manager.apply(profile, serverProtocol: .v2)
        #expect(manager.serverProtocol == .v2)
        #expect(manager.capabilities.supportsSessionMessageDiff == false)

        manager.updateProtocol(.v1)
        #expect(manager.serverProtocol == .v1)
        #expect(manager.capabilities.supportsSessionMessageDiff == true)

        manager.disconnect()
        #expect(manager.serverProtocol == nil)
    }

    private var sampleConnection: ServerConnection {
        ServerConnection(
            baseURL: URL(string: "https://example.test")!,
            username: "opencode",
            password: "secret"
        )
    }

    private func makeService() -> ConnectionProbeService {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [ProtocolHealthURLProtocol.self]
        return ConnectionProbeService(urlSession: URLSession(configuration: configuration))
    }
}

private final class ProtocolHealthURLProtocol: URLProtocol {
    private struct Response {
        let statusCode: Int
        let body: String
    }

    private static var responsesByPath: [String: Response] = [:]
    private static var requestedPaths: [String] = []
    private static let lock = NSLock()

    static func reset(responsesByPath: [String: (Int, String)]) {
        lock.lock()
        defer { lock.unlock() }
        self.responsesByPath = responsesByPath.mapValues { Response(statusCode: $0.0, body: $0.1) }
        requestedPaths = []
    }

    static var requestedPathsSnapshot: [String] {
        lock.lock()
        defer { lock.unlock() }
        return requestedPaths
    }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        let path = request.url?.path ?? ""
        Self.lock.lock()
        Self.requestedPaths.append(path)
        let response = Self.responsesByPath[path] ?? Response(statusCode: 404, body: "{}")
        Self.lock.unlock()

        let http = HTTPURLResponse(
            url: request.url!,
            statusCode: response.statusCode,
            httpVersion: nil,
            headerFields: ["Content-Type": "application/json"]
        )!
        client?.urlProtocol(self, didReceive: http, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(response.body.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}

private extension ConnectionProbeServiceTests {
    var requestedPaths: [String] { ProtocolHealthURLProtocol.requestedPathsSnapshot }
}
