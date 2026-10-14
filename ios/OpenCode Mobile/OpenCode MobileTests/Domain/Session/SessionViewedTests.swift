import Foundation
import Testing
@testable import OpenCode_Mobile

/// Server-tracked unread state (v2 `time.idle` / `time.viewed` / `outcome`, `session.viewed`).
struct SessionViewedTests {
    // MARK: - Wire decoding

    @Test func v2SessionDecodesIdleViewedAndOutcome() throws {
        let json = #"""
        {"id":"ses_1","title":"T","outcome":"failed",
         "time":{"created":1,"updated":2,"idle":1700000000123,"viewed":1700000000000}}
        """#
        let session = SessionDomainAdapter().session(from: try JSONDecoder().decode(V2SessionDTO.self, from: Data(json.utf8)))

        #expect(session.idleAtEpochMs == 1_700_000_000_123)
        #expect(session.viewedAtEpochMs == 1_700_000_000_000)
        #expect(session.idleOutcome == .failed)
        #expect(session.isServerUnread)
    }

    @Test func v2SessionMapsEveryKnownOutcome() throws {
        for (wire, expected) in [("succeeded", SessionIdleOutcome.succeeded), ("failed", .failed), ("interrupted", .interrupted)] {
            let json = #"{"id":"ses_1","title":"T","outcome":"\#(wire)","time":{"idle":5}}"#
            let session = SessionDomainAdapter().session(from: try JSONDecoder().decode(V2SessionDTO.self, from: Data(json.utf8)))
            #expect(session.idleOutcome == expected)
        }
    }

    @Test func v2SessionUnknownOutcomeIsNil() throws {
        let json = #"{"id":"ses_1","title":"T","outcome":"exploded","time":{"idle":5}}"#
        let session = SessionDomainAdapter().session(from: try JSONDecoder().decode(V2SessionDTO.self, from: Data(json.utf8)))

        #expect(session.idleOutcome == nil)
        #expect(session.idleAtEpochMs == 5)
    }

    @Test func v2SessionMissingFieldsAreNil() throws {
        let json = #"{"id":"ses_1","title":"T","time":{"created":1,"updated":2}}"#
        let session = SessionDomainAdapter().session(from: try JSONDecoder().decode(V2SessionDTO.self, from: Data(json.utf8)))

        #expect(session.idleAtEpochMs == nil)
        #expect(session.viewedAtEpochMs == nil)
        #expect(session.idleOutcome == nil)
        #expect(!session.isServerUnread)
    }

    @Test func v1SessionNeverCarriesServerUnread() throws {
        // Even if a classic server sent these keys, v1 maps them to nil.
        let json = #"{"id":"ses_1","title":"T","outcome":"failed","time":{"created":1,"updated":2,"idle":9}}"#
        let session = SessionDomainAdapter().session(from: try JSONDecoder().decode(SessionDTO.self, from: Data(json.utf8)))

        #expect(session.idleAtEpochMs == nil)
        #expect(session.viewedAtEpochMs == nil)
        #expect(session.idleOutcome == nil)
        #expect(!session.isServerUnread)
    }

    // MARK: - isServerUnread

    @Test func isServerUnreadCases() {
        #expect(!makeSession(idle: nil, viewed: nil).isServerUnread)
        #expect(!makeSession(idle: nil, viewed: 10).isServerUnread)
        #expect(makeSession(idle: 10, viewed: nil).isServerUnread)
        #expect(!makeSession(idle: 10, viewed: 10).isServerUnread)
        #expect(makeSession(idle: 11, viewed: 10).isServerUnread)
        #expect(!makeSession(idle: 10, viewed: 11).isServerUnread)
    }

    @Test func withViewedIsMonotone() {
        let unread = makeSession(idle: 20, viewed: 10)
        #expect(unread.withViewed(idleAtEpochMs: 20).viewedAtEpochMs == 20)
        #expect(!unread.withViewed(idleAtEpochMs: 20).isServerUnread)
        let ahead = makeSession(idle: 20, viewed: 30)
        #expect(ahead.withViewed(idleAtEpochMs: 20).viewedAtEpochMs == 30)
        #expect(makeSession(idle: 5, viewed: nil).withViewed(idleAtEpochMs: 5).viewedAtEpochMs == 5)
    }

    // MARK: - Capability

    @Test func onlyV2SupportsSessionViewed() {
        #expect(ServerCapabilities.forProtocol(.v2).supportsSessionViewed)
        #expect(!ServerCapabilities.forProtocol(.v1).supportsSessionViewed)
    }

    // MARK: - Session cache codec

    @Test func cacheCodecRoundTripsServerUnreadFields() throws {
        var session = makeSession(idle: 1_700_000_000_123, viewed: 1_700_000_000_000)
        session.idleOutcome = .interrupted
        let encoded = try SessionCacheCodec.encodeSessions([session])
        let decoded = try #require(SessionCacheCodec.decodeSessions(encoded))

        #expect(decoded == [session])
    }

    @Test func cacheCodecDecodesOldPayloadWithoutServerUnreadFields() throws {
        let old = #"{"schemaVersion":1,"sessions":{"sessions":[{"id":"ses_1","title":"Old","updatedAtEpochMs":3}]}}"#
        let decoded = try #require(SessionCacheCodec.decodeSessions(old))

        #expect(decoded.first?.id == "ses_1")
        #expect(decoded.first?.idleAtEpochMs == nil)
        #expect(decoded.first?.viewedAtEpochMs == nil)
        #expect(decoded.first?.idleOutcome == nil)
    }

    // MARK: - Events

    @Test func parsesPlainSessionViewedEvent() throws {
        let adapter = EventDomainAdapter()
        let event = try #require(adapter.parseEvent(
            dataLine: #"{"id":"evt_1","type":"session.viewed","data":{"sessionID":"ses_1","idle":1700000000123}}"#
        ))

        #expect(event.type == "session.viewed")
        #expect(event.sessionID == "ses_1")
        #expect(adapter.sessionViewed(event) == SessionViewedUpdate(sessionID: "ses_1", idleAtEpochMs: 1_700_000_000_123))
        #expect(!event.triggersHomeRefresh)
    }

    @Test func parsesSyncEnvelopeSessionViewedEvent() throws {
        let adapter = EventDomainAdapter()
        let event = try #require(adapter.parseEvent(
            dataLine: #"{"payload":{"type":"sync","syncEvent":{"id":"evt_2","type":"session.viewed.1","data":{"sessionID":"ses_2","idle":42}}}}"#
        ))

        #expect(event.type == "session.viewed")
        #expect(event.sessionID == "ses_2")
        #expect(adapter.sessionViewed(event) == SessionViewedUpdate(sessionID: "ses_2", idleAtEpochMs: 42))
    }

    @Test func sessionViewedHelperRejectsOtherEvents() {
        let adapter = EventDomainAdapter()
        #expect(adapter.sessionViewed(OpencodeEvent(type: "session.idle", sessionID: "ses_1")) == nil)
        #expect(adapter.sessionViewed(OpencodeEvent(type: "session.viewed", sessionID: "ses_1", rawProperties: [:])) == nil)
    }

    @Test func runEndProjectionIsSessionRunEnd() {
        let adapter = EventDomainAdapter()
        let event = adapter.parseEvent(dataLine: #"{"type":"session.execution.succeeded","data":{"sessionID":"ses-1"}}"#)
        #expect(event?.isSessionRunEnd == true)
    }

    private func makeSession(idle: Int64?, viewed: Int64?) -> Session {
        var session = Session(id: "ses_1", title: "T", slug: nil, projectID: nil, directory: nil, parentID: nil, updatedAtEpochMs: 1)
        session.idleAtEpochMs = idle
        session.viewedAtEpochMs = viewed
        return session
    }
}

@MainActor
@Suite(.serialized)
struct SessionViewedAPIClientTests {
    @Test func markViewedPostsIdleToViewEndpoint() async throws {
        ViewURLProtocol.reset(statusCode: 204)
        let client = makeClient()

        let ok = try await client.markSessionViewedV2(id: "ses_1", idle: 1_700_000_000_123)

        #expect(ok)
        let request = try #require(ViewURLProtocol.lastRequest)
        #expect(request.httpMethod == "POST")
        #expect(request.url?.path.hasSuffix("/api/session/ses_1/view") == true)
        #expect(request.value(forHTTPHeaderField: "Content-Type") == "application/json")
        let body = try #require(ViewURLProtocol.lastBody)
        let json = try #require(try JSONSerialization.jsonObject(with: body) as? [String: Any])
        #expect((json["idle"] as? NSNumber)?.int64Value == 1_700_000_000_123)
        #expect(json.count == 1)
    }

    @Test func markViewedSurfacesNotFound() async {
        ViewURLProtocol.reset(statusCode: 404)
        let client = makeClient()

        await #expect(throws: SessionsAPIError.self) {
            _ = try await client.markSessionViewedV2(id: "missing", idle: 1)
        }
    }

    private func makeClient() -> SessionsAPIClient {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [ViewURLProtocol.self]
        let connection = ServerConnection(baseURL: URL(string: "https://example.test")!, username: "opencode", password: "secret")
        return SessionsAPIClient(connectionProvider: { connection }, urlSession: URLSession(configuration: configuration))
    }
}

private final class ViewURLProtocol: URLProtocol {
    nonisolated(unsafe) static var lastRequest: URLRequest?
    nonisolated(unsafe) static var lastBody: Data?
    nonisolated(unsafe) static var statusCode = 204

    static func reset(statusCode: Int) {
        lastRequest = nil
        lastBody = nil
        self.statusCode = statusCode
    }

    override class func canInit(with _: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        Self.lastRequest = request
        Self.lastBody = request.httpBody ?? request.httpBodyStream.map(Self.read)
        client?.urlProtocol(
            self,
            didReceive: HTTPURLResponse(url: request.url!, statusCode: Self.statusCode, httpVersion: nil, headerFields: nil)!,
            cacheStoragePolicy: .notAllowed
        )
        client?.urlProtocol(self, didLoad: Data())
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}

    private static func read(_ stream: InputStream) -> Data {
        stream.open()
        defer { stream.close() }
        var data = Data()
        var buffer = [UInt8](repeating: 0, count: 1024)
        while stream.hasBytesAvailable {
            let count = stream.read(&buffer, maxLength: buffer.count)
            guard count > 0 else { break }
            data.append(buffer, count: count)
        }
        return data
    }
}
