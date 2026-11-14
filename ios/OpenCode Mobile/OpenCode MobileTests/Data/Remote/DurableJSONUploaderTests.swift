import Foundation
import Testing
@testable import OpenCode_Mobile

@Suite(.serialized)
struct DurableJSONUploaderTests {
    @Test func upload_postsFileBodyAndReturnsHTTPResponse() async throws {
        UploaderStub.reset()
        let uploader = DurableJSONUploader(configuration: UploaderStub.configuration(DurableStubProtocol.self))

        let (data, response) = try await uploader.upload(promptRequest(), body: promptBody, onProgress: nil)

        let http = try #require(response as? HTTPURLResponse)
        #expect(http.statusCode == 204)
        #expect(data.isEmpty)
        #expect(UploaderStub.channels() == ["durable"])
        #expect(UploaderStub.requests().first?.url?.path.hasSuffix("/prompt") == true)
    }

    @Test func directFirstPrompt_goesOverTheDirectSessionWhileActive() async throws {
        UploaderStub.reset()
        let uploader = makeUploader(active: true)

        let (_, response) = try await uploader.upload(promptRequest(), body: promptBody, directFirst: true, onProgress: nil)

        #expect((response as? HTTPURLResponse)?.statusCode == 204)
        #expect(UploaderStub.channels() == ["direct"])
        #expect(UploaderStub.bodies() == [promptBody])
    }

    @Test func directFirstPrompt_usesTheBackgroundSessionWhenTheAppIsNotActive() async throws {
        UploaderStub.reset()
        let uploader = makeUploader(active: false)

        _ = try await uploader.upload(promptRequest(), body: promptBody, directFirst: true, onProgress: nil)

        #expect(UploaderStub.channels() == ["durable"])
    }

    @Test func shellBody_neverGoesDirect() async throws {
        UploaderStub.reset()
        let uploader = makeUploader(active: true)

        _ = try await uploader.upload(promptRequest(), body: promptBody, onProgress: nil)

        #expect(UploaderStub.channels() == ["durable"])
    }

    @Test func failedDirectAttempt_handsTheSameBodyToTheBackgroundSession() async throws {
        UploaderStub.reset(direct: [.fail(.cannotConnectToHost)])
        let uploader = makeUploader(active: true)

        let (_, response) = try await uploader.upload(promptRequest(), body: promptBody, directFirst: true, onProgress: nil)

        #expect((response as? HTTPURLResponse)?.statusCode == 204)
        #expect(UploaderStub.channels() == ["direct", "durable"])
        #expect(UploaderStub.bodies() == [promptBody, promptBody])
    }

    @Test func directHTTPError_isReturnedWithoutResending() async throws {
        UploaderStub.reset(direct: [.respond(500)])
        let uploader = makeUploader(active: true)

        let (_, response) = try await uploader.upload(promptRequest(), body: promptBody, directFirst: true, onProgress: nil)

        #expect((response as? HTTPURLResponse)?.statusCode == 500)
        #expect(UploaderStub.channels() == ["direct"])
    }

    @Test func retry_replacesAHeldAttemptWithANewDirectOne() async throws {
        UploaderStub.reset(direct: [.hang, .respond(204)])
        let uploader = makeUploader(active: true)
        let control = PromptSendControl()

        let send = Task {
            try await PromptSendControl.$current.withValue(control) {
                try await uploader.upload(promptRequest(), body: promptBody, directFirst: true, onProgress: nil)
            }
        }
        try await waitUntil { UploaderStub.requests().count == 1 && control.canRetry }
        control.retry()
        let (_, response) = try await send.value

        #expect((response as? HTTPURLResponse)?.statusCode == 204)
        #expect(UploaderStub.channels() == ["direct", "direct"])
        #expect(UploaderStub.bodies() == [promptBody, promptBody])
        #expect(control.canRetry == false)
    }

    @Test func orphanAge_comesFromTheTaskDescription() {
        let description = DurableJSONUploader.taskDescription(createdAtMs: 1_700_000_000_000)
        #expect(DurableJSONUploader.createdAtMs(taskDescription: description) == 1_700_000_000_000)
        #expect(DurableJSONUploader.createdAtMs(taskDescription: nil) == nil)
        #expect(DurableJSONUploader.createdAtMs(taskDescription: "other") == nil)
    }

    @Test func orphan_isCancelledOnceOlderThanTheLimitOrOfUnknownAge() {
        #expect(DurableJSONUploader.shouldCancelOrphan(ageMs: nil))
        #expect(DurableJSONUploader.shouldCancelOrphan(ageMs: DurableJSONUploader.orphanMaxAgeMs + 1))
        #expect(DurableJSONUploader.shouldCancelOrphan(ageMs: DurableJSONUploader.orphanMaxAgeMs) == false)
        #expect(DurableJSONUploader.shouldCancelOrphan(ageMs: 5_000) == false)
    }

    @Test func percent_capsBelowOneHundredUntilComplete() {
        #expect(PromptSendProgress.percent(bytesSent: 1, bytesTotal: 4) == 25)
        #expect(PromptSendProgress.percent(bytesSent: 4, bytesTotal: 4) == 99)
        #expect(PromptSendProgress.shouldShow(bytesTotal: 10, hasAttachments: true))
        #expect(PromptSendProgress.shouldShow(bytesTotal: PromptSendProgress.visibleBodyThreshold, hasAttachments: false))
        #expect(PromptSendProgress.shouldShow(bytesTotal: 10, hasAttachments: false) == false)
    }

    private var promptBody: Data { Data(#"{"id":"msg_1","text":"Hi"}"#.utf8) }

    private func promptRequest() -> URLRequest {
        var request = URLRequest(url: URL(string: "https://example.com/api/session/ses_1/prompt")!)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        return request
    }

    private func makeUploader(active: Bool) -> DurableJSONUploader {
        DurableJSONUploader(
            configuration: UploaderStub.configuration(DurableStubProtocol.self),
            directConfiguration: UploaderStub.configuration(DirectStubProtocol.self),
            isAppActive: { active }
        )
    }

    private func waitUntil(_ condition: @escaping () -> Bool) async throws {
        for _ in 0 ..< 200 where !condition() {
            try await Task.sleep(for: .milliseconds(10))
        }
        #expect(condition())
    }
}

/// Scripted answers per session. Each request takes the next step for its channel; an empty
/// script answers 204.
private enum UploaderStep {
    case respond(Int)
    case fail(URLError.Code)
    case hang
}

private enum UploaderStub {
    private static let lock = NSLock()
    nonisolated(unsafe) private static var log: [(channel: String, request: URLRequest, body: Data)] = []
    nonisolated(unsafe) private static var scripts: [String: [UploaderStep]] = [:]

    static func reset(direct: [UploaderStep] = [], durable: [UploaderStep] = []) {
        lock.withLock {
            log = []
            scripts = ["direct": direct, "durable": durable]
        }
    }

    static func configuration(_ protocolClass: AnyClass) -> URLSessionConfiguration {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [protocolClass]
        return configuration
    }

    static func record(channel: String, request: URLRequest) -> UploaderStep {
        let body = request.httpBodyStream.map(readAll) ?? request.httpBody ?? Data()
        return lock.withLock {
            log.append((channel, request, body))
            guard var script = scripts[channel], !script.isEmpty else { return .respond(204) }
            let step = script.removeFirst()
            scripts[channel] = script
            return step
        }
    }

    static func channels() -> [String] { lock.withLock { log.map(\.channel) } }
    static func requests() -> [URLRequest] { lock.withLock { log.map(\.request) } }
    static func bodies() -> [Data] { lock.withLock { log.map(\.body) } }

    private static func readAll(_ stream: InputStream) -> Data {
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

private class UploaderStubProtocol: URLProtocol {
    class var channel: String { "" }

    override class func canInit(with request: URLRequest) -> Bool { true }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        switch UploaderStub.record(channel: Self.channel, request: request) {
        case let .respond(status):
            let response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil, headerFields: nil)!
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocolDidFinishLoading(self)
        case let .fail(code):
            client?.urlProtocol(self, didFailWithError: URLError(code))
        case .hang:
            break
        }
    }

    override func stopLoading() {}
}

private final class DirectStubProtocol: UploaderStubProtocol {
    override class var channel: String { "direct" }
}

private final class DurableStubProtocol: UploaderStubProtocol {
    override class var channel: String { "durable" }
}
