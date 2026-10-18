import Foundation
import Testing
@testable import OpenCode_Mobile

@Suite(.serialized)
struct FirebaseFeedbackRESTSubmitterTests {
    @Test func freshInstall_signsUpUploadsEachFileThenCommits() async throws {
        FeedbackStubURLProtocol.reset { request in
            switch Self.stage(of: request) {
            case "signUp": (200, #"{"idToken":"id-1","refreshToken":"refresh-1","localId":"uid-1"}"#)
            case "upload": (200, #"{"name":"ok"}"#)
            case "commit": (200, #"{"writeResults":[{},{}]}"#)
            default: (500, "{}")
            }
        }
        let store = MemoryRefreshTokenStore()

        let result = await makeSubmitter(store: store).submit(Self.submission)

        #expect(result == .sent(reference: "1F0DE9"))
        let requests = FeedbackStubURLProtocol.requests
        #expect(requests.map(Self.stage) == ["signUp", "upload", "upload", "commit"])
        for request in requests {
            #expect(request.httpMethod == "POST")
            #expect(request.value(forHTTPHeaderField: "X-Firebase-AppCheck") == "app-check-token")
            #expect(request.value(forHTTPHeaderField: "X-Ios-Bundle-Identifier") == "dev.opencode.mobile")
        }
        #expect(requests[0].url?.absoluteString == "https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=api-key")
        #expect(Self.json(requests[0])?["returnSecureToken"] as? Bool == true)
        #expect(
            requests[1].url?.absoluteString
                == "https://firebasestorage.googleapis.com/v0/b/demo.firebasestorage.app/o?name=feedback%2Fuid-1%2F1f0de9c2-7a41-4b8e-9d3f-0c5e6a7b8c9d%2Fscreenshot.jpg"
        )
        #expect(requests[1].value(forHTTPHeaderField: "Authorization") == "Firebase id-1")
        #expect(requests[1].value(forHTTPHeaderField: "Content-Type") == "image/jpeg")
        #expect(FeedbackStubURLProtocol.bodies[1] == Data(repeating: 7, count: 12))
        #expect(requests[2].url?.query?.hasSuffix("logs.jsonl.gz") == true)
        #expect(requests[2].value(forHTTPHeaderField: "Content-Type") == "application/gzip")
        #expect(
            requests[3].url?.absoluteString
                == "https://firestore.googleapis.com/v1/projects/demo-project/databases/(default)/documents:commit"
        )
        #expect(requests[3].value(forHTTPHeaderField: "Authorization") == "Bearer id-1")
        #expect(store.token == "refresh-1")
    }

    @Test func commitBody_createsReportAndStampsRateLimit() throws {
        let body = FirebaseFeedbackRESTSubmitter.commitBody(Self.submission, uid: "uid-1", projectID: "demo-project")
        let writes = try #require(body["writes"] as? [[String: Any]])
        #expect(writes.count == 2)

        let report = writes[0]
        let update = try #require(report["update"] as? [String: Any])
        #expect(
            update["name"] as? String
                == "projects/demo-project/databases/(default)/documents/feedback/1f0de9c2-7a41-4b8e-9d3f-0c5e6a7b8c9d"
        )
        let fields = try #require(update["fields"] as? [String: Any])
        #expect(Set(fields.keys) == ["uid", "kind", "message", "app", "context", "attachments"])
        #expect((fields["uid"] as? [String: String]) == ["stringValue": "uid-1"])
        #expect((report["updateTransforms"] as? [[String: String]]) == [["fieldPath": "createdAt", "setToServerValue": "REQUEST_TIME"]])
        #expect((report["currentDocument"] as? [String: Bool]) == ["exists": false])
        #expect(report["updateMask"] == nil)

        let rateLimit = writes[1]
        let rateUpdate = try #require(rateLimit["update"] as? [String: Any])
        #expect(rateUpdate["name"] as? String == "projects/demo-project/databases/(default)/documents/rateLimits/uid-1")
        #expect(
            (rateUpdate["fields"] as? [String: [String: String]])
                == ["lastReportId": ["stringValue": "1f0de9c2-7a41-4b8e-9d3f-0c5e6a7b8c9d"]]
        )
        #expect((rateLimit["updateMask"] as? [String: [String]]) == ["fieldPaths": ["lastReportId"]])
        #expect((rateLimit["updateTransforms"] as? [[String: String]]) == [["fieldPath": "lastReportAt", "setToServerValue": "REQUEST_TIME"]])
        #expect(rateLimit["currentDocument"] == nil)
    }

    @Test func storedRefreshToken_refreshesAndKeepsTheRotatedToken() async throws {
        FeedbackStubURLProtocol.reset { request in
            switch Self.stage(of: request) {
            case "refresh": (200, #"{"id_token":"id-2","refresh_token":"refresh-rotated","user_id":"uid-2"}"#)
            case "upload", "commit": (200, "{}")
            default: (500, "{}")
            }
        }
        let store = MemoryRefreshTokenStore(token: "refresh-old")

        let result = await makeSubmitter(store: store).submit(Self.submission)

        #expect(result == .sent(reference: "1F0DE9"))
        let requests = FeedbackStubURLProtocol.requests
        #expect(requests.map(Self.stage) == ["refresh", "upload", "upload", "commit"])
        #expect(requests[0].url?.absoluteString == "https://securetoken.googleapis.com/v1/token?key=api-key")
        #expect(requests[0].value(forHTTPHeaderField: "Content-Type") == "application/x-www-form-urlencoded")
        #expect(
            String(decoding: FeedbackStubURLProtocol.bodies[0], as: UTF8.self)
                == "grant_type=refresh_token&refresh_token=refresh-old"
        )
        #expect(requests[1].url?.query?.contains("feedback%2Fuid-2%2F") == true)
        #expect(requests[3].value(forHTTPHeaderField: "Authorization") == "Bearer id-2")
        #expect(store.token == "refresh-rotated")
    }

    @Test func expiredRefreshToken_signsUpAgain() async throws {
        FeedbackStubURLProtocol.reset { request in
            switch Self.stage(of: request) {
            case "refresh": (400, #"{"error":{"code":400,"message":"TOKEN_EXPIRED","status":"INVALID_ARGUMENT"}}"#)
            case "signUp": (200, #"{"idToken":"id-3","refreshToken":"refresh-3","localId":"uid-3"}"#)
            case "upload", "commit": (200, "{}")
            default: (500, "{}")
            }
        }
        let store = MemoryRefreshTokenStore(token: "refresh-expired")

        let result = await makeSubmitter(store: store).submit(Self.submission)

        #expect(result == .sent(reference: "1F0DE9"))
        #expect(FeedbackStubURLProtocol.requests.map(Self.stage) == ["refresh", "signUp", "upload", "upload", "commit"])
        #expect(store.token == "refresh-3")
    }

    @Test(arguments: ["USER_NOT_FOUND", "USER_DISABLED", "INVALID_REFRESH_TOKEN"])
    func refreshErrorsThatMeanTheUserIsGone_signUpAgain(message: String) {
        let body = Data(#"{"error":{"code":400,"message":"\#(message)"}}"#.utf8)
        #expect(FirebaseFeedbackRESTSubmitter.requiresSignUp(status: 400, body: body))
        #expect(!FirebaseFeedbackRESTSubmitter.requiresSignUp(status: 500, body: body))
        #expect(!FirebaseFeedbackRESTSubmitter.requiresSignUp(
            status: 400,
            body: Data(#"{"error":{"message":"QUOTA_EXCEEDED"}}"#.utf8)
        ))
    }

    @Test func appCheckFailure_isUnverifiedWithoutAnyRequest() async {
        FeedbackStubURLProtocol.reset { _ in (200, "{}") }
        let submitter = makeSubmitter(appCheck: { throw URLError(.userAuthenticationRequired) })

        let result = await submitter.submit(Self.submission)

        #expect(result == .unverified)
        #expect(FeedbackStubURLProtocol.requests.isEmpty)
    }

    @Test func transportError_isOffline() async {
        FeedbackStubURLProtocol.reset(failWith: URLError(.notConnectedToInternet))

        let result = await makeSubmitter().submit(Self.submission)

        #expect(result == .offline)
    }

    @Test(arguments: [401, 403])
    func storageRejection_isUnverified(status: Int) async {
        FeedbackStubURLProtocol.reset { request in
            switch Self.stage(of: request) {
            case "signUp": (200, #"{"idToken":"id","refreshToken":"r","localId":"uid"}"#)
            case "upload": (status, #"{"error":{"code":\#(status)}}"#)
            default: (200, "{}")
            }
        }

        let result = await makeSubmitter().submit(Self.submission)

        #expect(result == .unverified)
        #expect(FeedbackStubURLProtocol.requests.map(Self.stage) == ["signUp", "upload"])
    }

    @Test func commit403_afterARecentSuccess_isTheServerRateLimit() async {
        FeedbackStubURLProtocol.reset(commitStatus: 403)
        let now = Date()
        let cooldown = StubCooldownStore(lastSent: now.addingTimeInterval(-20))

        let result = await makeSubmitter(cooldown: cooldown, now: now).submit(Self.submission)

        #expect(result == .rateLimited(retryAfterSeconds: 40))
    }

    @Test func commit403_withoutARecentSuccess_isAnAppCheckRejection() async {
        FeedbackStubURLProtocol.reset(commitStatus: 403)
        let now = Date()

        let never = await makeSubmitter(cooldown: StubCooldownStore(lastSent: nil), now: now)
            .submit(Self.submission)
        FeedbackStubURLProtocol.reset(commitStatus: 403)
        let longAgo = await makeSubmitter(cooldown: StubCooldownStore(lastSent: now.addingTimeInterval(-61)), now: now)
            .submit(Self.submission)

        #expect(never == .unverified)
        #expect(longAgo == .unverified)
    }

    @Test func commit401_isUnverified() async {
        FeedbackStubURLProtocol.reset(commitStatus: 401)

        let result = await makeSubmitter().submit(Self.submission)

        #expect(result == .unverified)
    }

    @Test func otherHTTPFailures_areFailed() async {
        FeedbackStubURLProtocol.reset { request in
            switch Self.stage(of: request) {
            case "signUp": (200, #"{"idToken":"id","refreshToken":"r","localId":"uid"}"#)
            case "commit": (500, "{}")
            default: (200, "{}")
            }
        }

        let result = await makeSubmitter().submit(Self.submission)

        #expect(result == .failed(message: "Couldn't send the report."))
    }

    @Test func signUpRejected_isFailed() async {
        FeedbackStubURLProtocol.reset { _ in (403, #"{"error":{"message":"API_KEY_IOS_APP_BLOCKED"}}"#) }

        let result = await makeSubmitter().submit(Self.submission)

        #expect(result == .failed(message: "Couldn't send the report."))
        #expect(FeedbackStubURLProtocol.requests.map(Self.stage) == ["signUp"])
    }

    // MARK: - Helpers

    static let submission = FeedbackSubmission(
        reportID: "1f0de9c2-7a41-4b8e-9d3f-0c5e6a7b8c9d",
        kind: .bug,
        message: "Approval card missing after unlock.",
        metadata: FeedbackPayloadBuilderTests.metadata,
        attachments: [.screenshot(Data(repeating: 7, count: 12)), .logs(Data(repeating: 9, count: 5))]
    )

    private func makeSubmitter(
        store: MemoryRefreshTokenStore = MemoryRefreshTokenStore(),
        cooldown: StubCooldownStore = StubCooldownStore(lastSent: nil),
        now: Date = Date(),
        appCheck: @escaping @Sendable () async throws -> String = { "app-check-token" }
    ) -> FirebaseFeedbackRESTSubmitter {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [FeedbackStubURLProtocol.self]
        return FirebaseFeedbackRESTSubmitter(
            configuration: FirebaseFeedbackConfiguration(
                apiKey: "api-key",
                projectID: "demo-project",
                storageBucket: "demo.firebasestorage.app"
            ),
            appCheckToken: appCheck,
            tokenStore: store,
            cooldownStore: cooldown,
            now: { now },
            session: URLSession(configuration: configuration)
        )
    }

    static func stage(of request: URLRequest) -> String {
        let url = request.url?.absoluteString ?? ""
        if url.contains("securetoken.googleapis.com") { return "refresh" }
        if url.contains("accounts:signUp") { return "signUp" }
        if url.contains("firebasestorage.googleapis.com") { return "upload" }
        if url.contains("documents:commit") { return "commit" }
        return "unknown"
    }

    static func json(_ request: URLRequest) -> [String: Any]? {
        guard let index = FeedbackStubURLProtocol.requests.firstIndex(of: request) else { return nil }
        return try? JSONSerialization.jsonObject(with: FeedbackStubURLProtocol.bodies[index]) as? [String: Any]
    }
}

private struct StubCooldownStore: FeedbackCooldownStore {
    let lastSent: Date?

    func lastSentAt() -> Date? { lastSent }

    func recordSent(at date: Date) {}
}

private final class MemoryRefreshTokenStore: FeedbackRefreshTokenStore, @unchecked Sendable {
    private let lock = NSLock()
    private var _token: String?

    init(token: String? = nil) {
        _token = token
    }

    var token: String? {
        lock.lock()
        defer { lock.unlock() }
        return _token
    }

    func read() -> String? { token }

    func write(_ token: String) {
        lock.lock()
        _token = token
        lock.unlock()
    }

    func delete() {
        lock.lock()
        _token = nil
        lock.unlock()
    }
}

private final class FeedbackStubURLProtocol: URLProtocol {
    private static let lock = NSLock()
    nonisolated(unsafe) private static var handler: (URLRequest) -> (Int, String) = { _ in (500, "{}") }
    nonisolated(unsafe) private static var failure: URLError?
    nonisolated(unsafe) private static var recorded: [(URLRequest, Data)] = []

    static func reset(handler: @escaping (URLRequest) -> (Int, String)) {
        lock.lock()
        self.handler = handler
        failure = nil
        recorded = []
        lock.unlock()
    }

    /// Sign-up and uploads succeed; the commit answers with `commitStatus`.
    static func reset(commitStatus: Int) {
        reset { request in
            if FirebaseFeedbackRESTSubmitterTests.stage(of: request) == "signUp" {
                return (200, #"{"idToken":"id","refreshToken":"r","localId":"uid"}"#)
            }
            if FirebaseFeedbackRESTSubmitterTests.stage(of: request) == "commit" {
                return (commitStatus, #"{"error":{"code":\#(commitStatus),"status":"PERMISSION_DENIED"}}"#)
            }
            return (200, "{}")
        }
    }

    static func reset(failWith error: URLError) {
        lock.lock()
        handler = { _ in (500, "{}") }
        failure = error
        recorded = []
        lock.unlock()
    }

    static var requests: [URLRequest] {
        lock.lock()
        defer { lock.unlock() }
        return recorded.map(\.0)
    }

    static var bodies: [Data] {
        lock.lock()
        defer { lock.unlock() }
        return recorded.map(\.1)
    }

    override class func canInit(with request: URLRequest) -> Bool { true }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        let body = request.httpBody ?? Self.read(request.httpBodyStream)
        Self.lock.lock()
        Self.recorded.append((request, body))
        let failure = Self.failure
        let handler = Self.handler
        Self.lock.unlock()
        if let failure {
            client?.urlProtocol(self, didFailWithError: failure)
            return
        }
        let (status, text) = handler(request)
        let response = HTTPURLResponse(
            url: request.url!,
            statusCode: status,
            httpVersion: nil,
            headerFields: ["Content-Type": "application/json"]
        )!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(text.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}

    private static func read(_ stream: InputStream?) -> Data {
        guard let stream else { return Data() }
        stream.open()
        defer { stream.close() }
        var data = Data()
        var buffer = [UInt8](repeating: 0, count: 4_096)
        while stream.hasBytesAvailable {
            let count = stream.read(&buffer, maxLength: buffer.count)
            guard count > 0 else { break }
            data.append(buffer, count: count)
        }
        return data
    }
}
