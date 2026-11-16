import Foundation
import Testing
@testable import OpenCode_Mobile

@Suite(.serialized)
struct OpenCodeURLSessionsTests {
    @Test func restSessionUsesShortTimeoutsWithoutWaitingForConnectivity() {
        assertRestTimeouts(OpenCodeURLSessions.rest)
    }

    @Test func replaceRestSwapsTheLivePoolAndLeavesInjectedSessionsFixed() {
        let original = OpenCodeURLSessions.rest
        let explicit = URLSession(configuration: .ephemeral)
        let live = AgentsAPIClient(connectionProvider: { nil })
        let fixed = AgentsAPIClient(connectionProvider: { nil }, urlSession: explicit)
        #expect(live.resolvedURLSession() === original)
        #expect(fixed.resolvedURLSession() === explicit)

        OpenCodeURLSessions.replaceRest()
        let replaced = OpenCodeURLSessions.rest
        defer {
            // `original` is cancelled. Leave a live pool so later tests are not stuck on it.
            if OpenCodeURLSessions.rest !== original {
                OpenCodeURLSessions.replaceRest()
            }
        }

        #expect(replaced !== original)
        #expect(live.resolvedURLSession() === replaced)
        #expect(live.resolvedURLSession() !== original)
        #expect(fixed.resolvedURLSession() === explicit)
        assertRestTimeouts(replaced)
        assertRestTimeouts(OpenCodeURLSessions.rest)
    }

    @Test func replaceRestMovesTheLiveWorktreesClientToTheNewPool() {
        let original = OpenCodeURLSessions.rest
        let explicit = URLSession(configuration: .ephemeral)
        let live = WorktreesAPIClient(connectionProvider: { nil })
        let fixed = WorktreesAPIClient(connectionProvider: { nil }, urlSession: explicit)
        #expect(live.resolvedURLSession() === original)
        #expect(fixed.resolvedURLSession() === explicit)

        OpenCodeURLSessions.replaceRest()
        let replaced = OpenCodeURLSessions.rest
        defer {
            if OpenCodeURLSessions.rest !== original {
                OpenCodeURLSessions.replaceRest()
            }
        }

        #expect(replaced !== original)
        #expect(live.resolvedURLSession() === replaced)
        #expect(fixed.resolvedURLSession() === explicit)
    }

    @Test func replaceRestLeavesThePreviousPoolUsableForAStragglerTask() throws {
        let original = OpenCodeURLSessions.rest
        OpenCodeURLSessions.replaceRest()
        defer { OpenCodeURLSessions.replaceRest() }

        // A caller that read `original` just before the swap still creates its task. An immediate
        // invalidation would raise NSGenericException here and crash the test process (#777).
        let url = try #require(URL(string: "http://127.0.0.1:9/"))
        let task = original.dataTask(with: url)
        task.cancel()
        #expect(OpenCodeURLSessions.rest !== original)
    }

    @Test func longOperationSessionLiftsOnlyTheResourceCap() {
        let long = OpenCodeURLSessions.longOperation.configuration
        let rest = OpenCodeURLSessions.rest.configuration
        #expect(OpenCodeURLSessions.longOperation !== OpenCodeURLSessions.rest)
        #expect(long.timeoutIntervalForResource == OpenCodeURLSessions.longOperationResourceTimeout)
        #expect(long.timeoutIntervalForRequest == rest.timeoutIntervalForRequest)
        #expect(long.waitsForConnectivity == false)
        #expect(long.urlCache == nil)
        #expect(long.requestCachePolicy == .reloadIgnoringLocalCacheData)
        #expect(rest.urlCache == nil)
        #expect(rest.requestCachePolicy == .reloadIgnoringLocalCacheData)
        #expect(OpenCodeURLSessions.longOperation.delegate === RedirectPolicyDelegate.shared)
        #expect(OpenCodeURLSessions.rest.delegate === RedirectPolicyDelegate.shared)
    }

    @Test func sessionsClientRoutesLongOperationsToTheLongOperationPool() {
        let client = SessionsAPIClient(connectionProvider: { nil })
        #expect(client.resolvedURLSession() === OpenCodeURLSessions.rest)
        #expect(client.resolvedLongOperationURLSession() === OpenCodeURLSessions.longOperation)

        let explicit = URLSession(configuration: .ephemeral)
        let injected = SessionsAPIClient(connectionProvider: { nil }, urlSession: explicit)
        #expect(injected.resolvedURLSession() === explicit)
        #expect(injected.resolvedLongOperationURLSession() === explicit)
    }

    @Test func replaceRestAlsoReplacesTheLongOperationPool() {
        let original = OpenCodeURLSessions.rest
        let originalLong = OpenCodeURLSessions.longOperation
        let client = SessionsAPIClient(connectionProvider: { nil })
        OpenCodeURLSessions.replaceRest()
        defer {
            if OpenCodeURLSessions.rest !== original {
                OpenCodeURLSessions.replaceRest()
            }
        }

        #expect(OpenCodeURLSessions.longOperation !== originalLong)
        #expect(client.resolvedLongOperationURLSession() === OpenCodeURLSessions.longOperation)
        #expect(OpenCodeURLSessions.longOperation.configuration.timeoutIntervalForResource == OpenCodeURLSessions.longOperationResourceTimeout)
    }

    @Test func compactionAndGenerateUseTheLongOperationSession() async throws {
        RoutingURLProtocol.reset()
        let rest = Self.routedSession(RestRoutingURLProtocol.self)
        let long = Self.routedSession(LongRoutingURLProtocol.self)
        let connection = ServerConnection(baseURL: URL(string: "https://example.test")!, username: "opencode", password: "test-password")
        let client = SessionsAPIClient(connectionProvider: { connection }, urlSession: rest, longOperationURLSession: long)

        _ = try await client.summarizeSession(
            id: "ses_1",
            body: SummarizeSessionRequestDTO(providerID: "provider", modelID: "model")
        )
        _ = try await client.compactSessionV2(id: "ses_1")
        _ = try await client.generateSessionTextV2(id: "ses_1", prompt: "Hi")
        _ = try await client.listSessions()

        #expect(RoutingURLProtocol.routes == [
            "long /session/ses_1/summarize",
            "long /api/session/ses_1/compact",
            "long /api/session/ses_1/generate",
            "rest /session",
        ])
    }

    private static func routedSession(_ protocolClass: AnyClass) -> URLSession {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [protocolClass]
        return URLSession(configuration: configuration)
    }

    private func assertRestTimeouts(_ session: URLSession) {
        let configuration = session.configuration
        #expect(configuration.timeoutIntervalForRequest == 15)
        #expect(configuration.timeoutIntervalForResource == 60)
        #expect(configuration.waitsForConnectivity == false)
    }
}

/// Records which injected session carried each request, as "<route> <path>".
private class RoutingURLProtocol: URLProtocol {
    static var routes: [String] = []
    class var route: String { "" }

    static func reset() { routes = [] }

    override class func canInit(with _: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let url = request.url!
        Self.routes.append("\(Self.route) \(url.path)")
        let body = url.path.hasSuffix("/generate") ? #"{"data":{"text":"ok"}}"# : url.path == "/session" ? "[]" : "true"
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: url, statusCode: 200, httpVersion: nil, headerFields: nil)!, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(body.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

private final class RestRoutingURLProtocol: RoutingURLProtocol {
    override class var route: String { "rest" }
}

private final class LongRoutingURLProtocol: RoutingURLProtocol {
    override class var route: String { "long" }
}
