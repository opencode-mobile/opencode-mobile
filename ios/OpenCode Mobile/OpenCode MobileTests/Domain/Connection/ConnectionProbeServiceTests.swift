import Foundation
import Testing
@testable import OpenCode_Mobile

struct ConnectionProbeServiceTests {
    @Test func confirmServer_savedV2SeesAServerThatMovedToV1() async throws {
        ProtocolHealthURLProtocol.reset(responsesByPath: [
            "/global/health": (200, #"{"healthy":true,"version":"1.18.4"}"#),
            "/api/info": (200, #"{"healthy":true,"version":"2.0.0","pid":123}"#),
        ])

        let outcome = try await makeService().confirmServer(connection: sampleConnection, expected: .v2)

        #expect(outcome.serverProtocol == .v1)
    }

    @Test func confirmServer_savedV1SeesAServerThatMovedToV2() async throws {
        ProtocolHealthURLProtocol.reset(responsesByPath: [
            "/global/health": (404, #"{}"#),
            "/api/info": (200, #"{"healthy":true,"version":"2.0.0","pid":123}"#),
        ])

        let outcome = try await makeService().confirmServer(connection: sampleConnection, expected: .v1)

        #expect(outcome.serverProtocol == .v2)
    }

    @Test func confirmServer_savedV2GivesASlowGlobalHealthTheAndroidGrace() async throws {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [SlowGlobalHealthURLProtocol.self]
        let service = ConnectionProbeService(urlSession: URLSession(configuration: configuration))

        // Past the old 300 ms and inside 750 ms: the healthy global answer means v1.
        let outcome = try await service.confirmServer(connection: sampleConnection, expected: .v2)

        #expect(outcome.serverProtocol == .v1)
    }

    @Test func confirmServer_savedV2DoesNotWaitOnAHangingEndpoint() async throws {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [HangingGlobalHealthURLProtocol.self]
        let service = ConnectionProbeService(urlSession: URLSession(configuration: configuration))

        let outcome = try await service.confirmServer(connection: sampleConnection, expected: .v2)

        #expect(outcome.serverProtocol == .v2)
    }

    @Test func probe_detectsV1FromGlobalHealthAndSkipsApiHealth() async throws {
        ProtocolHealthURLProtocol.reset(responsesByPath: [
            "/global/health": (200, #"{"healthy":true,"version":"1.18.4"}"#),
        ])
        let service = makeService()

        let detected = try await service.probe(connection: sampleConnection)

        #expect(detected == .v1)
        #expect(Set(requestedPaths) == ["/global/health", "/api/info", "/api/health"])
    }

    @Test func probe_detectsV2FromApiInfoPidWhenGlobalMissing() async throws {
        ProtocolHealthURLProtocol.reset(responsesByPath: [
            "/global/health": (404, #"{}"#),
            "/api/info": (200, #"{"healthy":true,"version":"2.0.0","pid":123}"#),
        ])
        let service = makeService()

        let detected = try await service.probe(connection: sampleConnection)

        #expect(detected == .v2)
        #expect(Set(requestedPaths) == ["/global/health", "/api/info", "/api/health"])
    }

    @Test func probe_prefersV1WhenBothHealthEndpointsExist() async throws {
        ProtocolHealthURLProtocol.reset(responsesByPath: [
            "/global/health": (200, #"{"healthy":true,"version":"1.18.4"}"#),
            "/api/info": (200, #"{"healthy":true,"version":"2.0.0","pid":123}"#),
        ])
        let service = makeService()

        let detected = try await service.probe(connection: sampleConnection)

        #expect(detected == .v1)
        #expect(Set(requestedPaths) == ["/global/health", "/api/info", "/api/health"])
    }

    @Test func probe_failsWhenNeitherHealthEndpointIsUsable() async {
        ProtocolHealthURLProtocol.reset(responsesByPath: [
            "/global/health": (404, #"{}"#),
            "/api/info": (404, #"{}"#),
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

    @Test func probe_reportsUnhealthyWhenBodiesArePresentButUnusable() async {
        ProtocolHealthURLProtocol.reset(responsesByPath: [
            "/global/health": (200, #"{"healthy":false,"version":"1.0.0"}"#),
            "/api/info": (404, #"{}"#),
        ])
        let service = makeService()

        do {
            _ = try await service.probe(connection: sampleConnection)
            Issue.record("expected unhealthy failure")
        } catch let error as ServerProbeError {
            guard case let .unhealthy(version) = error else {
                Issue.record("expected unhealthy probe error, got \(error)")
                return
            }
            #expect(version == "1.0.0")
        } catch {
            Issue.record("unexpected error \(error)")
        }
    }

    @Test func probe_detectsV2FromOpenCode207ApiInfoWhenHealthPathsMiss() async throws {
        ProtocolHealthURLProtocol.reset(responsesByPath: [
            "/global/health": (200, "<!doctype html><html><head><title>OpenCode</title></head><body></body></html>"),
            "/api/info": (200, #"{"version":"2.0.7","pid":1481731,"urls":["http://192.168.1.3:4096"]}"#),
        ])
        let service = makeService()

        let detected = try await service.probe(connection: sampleConnection)

        #expect(detected == .v2)
        #expect(Set(requestedPaths) == ["/global/health", "/api/info", "/api/health"])
    }

    @Test func probe_detectsV2FromApiHealthWhenInfoIsMissing() async throws {
        ProtocolHealthURLProtocol.reset(responsesByPath: [
            "/global/health": (200, "<!doctype html><html><head><title>OpenCode</title></head><body></body></html>"),
            "/api/info": (404, "Not Found"),
            "/api/health": (200, #"{"healthy":true,"version":"2.0.1","pid":2605231}"#),
        ])
        let service = makeService()

        let detected = try await service.probe(connection: sampleConnection)

        #expect(detected == .v2)
        #expect(Set(requestedPaths) == ["/global/health", "/api/info", "/api/health"])
    }

    @Test func probe_prefersUsableApiHealthOverUnhealthyApiInfo() async throws {
        ProtocolHealthURLProtocol.reset(responsesByPath: [
            "/global/health": (404, "{}"),
            "/api/info": (200, #"{"healthy":false,"version":"2.0.0","pid":1}"#),
            "/api/health": (200, #"{"healthy":true,"version":"2.0.1","pid":2}"#),
        ])
        let service = makeService()

        let detected = try await service.probe(connection: sampleConnection)

        #expect(detected == .v2)
    }

    @Test func probe_reportsUnhealthyWhenOnlyApiHealthPidIsUnhealthy() async {
        ProtocolHealthURLProtocol.reset(responsesByPath: [
            "/global/health": (404, "{}"),
            "/api/info": (404, "{}"),
            "/api/health": (200, #"{"healthy":false,"version":"2.0.1","pid":12}"#),
        ])
        let service = makeService()

        do {
            _ = try await service.probe(connection: sampleConnection)
            Issue.record("expected unhealthy failure")
        } catch let error as ServerProbeError {
            guard case let .unhealthy(version) = error else {
                Issue.record("expected unhealthy probe error, got \(error)")
                return
            }
            #expect(version == "2.0.1")
        } catch {
            Issue.record("unexpected error \(error)")
        }
    }

    @Test func probe_detectsV1FromGlobalHealthEvenIfApiInfoIsHtml() async throws {
        ProtocolHealthURLProtocol.reset(responsesByPath: [
            "/global/health": (200, #"{"healthy":true,"version":"1.18.31"}"#),
            "/api/info": (200, "<!doctype html><html><body>SPA</body></html>"),
        ])
        let service = makeService()

        let detected = try await service.probe(connection: sampleConnection)

        #expect(detected == .v1)
    }

    @Test func probe_htmlAndNotFoundDoNotSurfaceNotFound() async {
        ProtocolHealthURLProtocol.reset(responsesByPath: [
            "/global/health": (200, "<!doctype html><html><body>SPA</body></html>"),
            "/api/info": (404, "Not Found"),
        ])
        let service = makeService()

        do {
            _ = try await service.probe(connection: sampleConnection)
            Issue.record("expected probe failure")
        } catch let error as ServerProbeError {
            #expect(error.probeUserMessage == "Unable to reach the server")
            if case .unhealthy = error {
                Issue.record("HTML/404 miss should not classify as unhealthy")
            }
        } catch {
            Issue.record("unexpected error \(error)")
        }
    }

    @Test func probe_authFailureOnHealthPathsIsNotSwallowedByApiInfo() async {
        ProtocolHealthURLProtocol.reset(responsesByPath: [
            "/global/health": (401, #"{"error":"unauthorized"}"#),
            "/api/info": (404, #"{}"#),
        ])
        let service = makeService()

        do {
            _ = try await service.probe(connection: sampleConnection)
            Issue.record("expected auth failure")
        } catch let error as ServerProbeError {
            guard case let .http(statusCode, _) = error else {
                Issue.record("expected http probe error, got \(error)")
                return
            }
            #expect(statusCode == 401)
            #expect(error.probeUserMessage == "Authentication failed")
        } catch {
            Issue.record("unexpected error \(error)")
        }
    }

    @Test func probeUserMessage_nonAuthHttpDoesNotSurfaceNotFound() {
        #expect(
            ServerProbeError.http(statusCode: 404, message: "Not Found").probeUserMessage
                == "Unable to reach the server"
        )
        #expect(
            ServerProbeError.http(statusCode: 401, message: "Unauthorized").probeUserMessage
                == "Authentication failed"
        )
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

    @Test func probeServer_keepsTheV1GlobalHealthVersion() async throws {
        ProtocolHealthURLProtocol.reset(responsesByPath: [
            "/global/health": (200, #"{"healthy":true,"version":"1.18.4"}"#),
        ])

        let outcome = try await makeService().probeServer(connection: sampleConnection)

        #expect(outcome == ServerProbeOutcome(serverProtocol: .v1, serverVersion: "1.18.4"))
    }

    @Test func probeServer_keepsTheV2ApiInfoVersion() async throws {
        ProtocolHealthURLProtocol.reset(responsesByPath: [
            "/global/health": (404, #"{}"#),
            "/api/info": (200, #"{"healthy":true,"version":"2.0.0","pid":123}"#),
        ])

        let outcome = try await makeService().probeServer(connection: sampleConnection)

        #expect(outcome == ServerProbeOutcome(serverProtocol: .v2, serverVersion: "2.0.0"))
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

/// `/global/health` never answers; every other path answers as a v2 server.
private final class HangingGlobalHealthURLProtocol: URLProtocol {
    override class func canInit(with _: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        guard let url = request.url, url.path != "/global/health",
              let response = HTTPURLResponse(url: url, statusCode: 200, httpVersion: nil, headerFields: nil)
        else { return }
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(#"{"healthy":true,"version":"2.0.0","pid":123}"#.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}

/// Answers as a v2 server at once, and `/global/health` as healthy v1 after 450 ms.
private final class SlowGlobalHealthURLProtocol: URLProtocol {
    override class func canInit(with _: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        guard let url = request.url,
              let response = HTTPURLResponse(url: url, statusCode: 200, httpVersion: nil, headerFields: nil)
        else { return }
        let isGlobal = url.path == "/global/health"
        let body = isGlobal ? #"{"healthy":true,"version":"1.18.4"}"# : #"{"healthy":true,"version":"2.0.0","pid":123}"#
        let finish = { [self] in
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: Data(body.utf8))
            client?.urlProtocolDidFinishLoading(self)
        }
        if isGlobal {
            DispatchQueue.global().asyncAfter(deadline: .now() + .milliseconds(450), execute: finish)
        } else {
            finish()
        }
    }

    override func stopLoading() {}
}
