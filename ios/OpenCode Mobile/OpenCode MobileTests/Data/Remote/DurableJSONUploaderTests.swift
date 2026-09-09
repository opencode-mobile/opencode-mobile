import Foundation
import Testing
@testable import OpenCode_Mobile

@Suite(.serialized)
struct DurableJSONUploaderTests {
    @Test func upload_postsFileBodyAndReturnsHTTPResponse() async throws {
        DurableJSONUploaderURLProtocol.reset(
            response: DurableJSONUploaderStub(statusCode: 204, body: "")
        )
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [DurableJSONUploaderURLProtocol.self]
        let uploader = DurableJSONUploader(configuration: configuration)
        var request = URLRequest(url: URL(string: "https://example.test/session/ses_1/prompt_async")!)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        let body = Data(#"{"parts":[{"type":"text","text":"Hi"}]}"#.utf8)

        let (data, response) = try await uploader.upload(request, body: body, onProgress: nil)

        let http = try #require(response as? HTTPURLResponse)
        #expect(http.statusCode == 204)
        #expect(data.isEmpty)
        #expect(DurableJSONUploaderURLProtocol.requests.count == 1)
        #expect(DurableJSONUploaderURLProtocol.requests.first?.url?.path.hasSuffix("/prompt_async") == true)
    }

    @Test func percent_capsBelowOneHundredUntilComplete() {
        #expect(PromptSendProgress.percent(bytesSent: 1, bytesTotal: 4) == 25)
        #expect(PromptSendProgress.percent(bytesSent: 4, bytesTotal: 4) == 99)
        #expect(PromptSendProgress.shouldShow(bytesTotal: 10, hasAttachments: true))
        #expect(PromptSendProgress.shouldShow(bytesTotal: PromptSendProgress.visibleBodyThreshold, hasAttachments: false))
        #expect(PromptSendProgress.shouldShow(bytesTotal: 10, hasAttachments: false) == false)
    }
}

private struct DurableJSONUploaderStub {
    let statusCode: Int
    let body: String
}

private final class DurableJSONUploaderURLProtocol: URLProtocol {
    static var requests: [URLRequest] = []
    static var response = DurableJSONUploaderStub(statusCode: 204, body: "")

    static func reset(response: DurableJSONUploaderStub) {
        requests = []
        self.response = response
    }

    override class func canInit(with request: URLRequest) -> Bool { true }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        Self.requests.append(request)
        let response = HTTPURLResponse(
            url: request.url!,
            statusCode: Self.response.statusCode,
            httpVersion: nil,
            headerFields: ["Content-Type": "application/json"]
        )!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        if !Self.response.body.isEmpty {
            client?.urlProtocol(self, didLoad: Data(Self.response.body.utf8))
        }
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}
