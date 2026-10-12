import Foundation
import Testing
@testable import OpenCode_Mobile

struct T3AttachmentUploaderTests {
    private static let png = Data([0x89, 0x50, 0x4E, 0x47, 1, 2, 3])

    private static func image(
        id: String = "a1",
        name: String = "shot.png",
        mime: String = "image/png",
        kind: AttachmentKind = .image,
        byteCount: Int64 = Int64(png.count)
    ) -> OpenCode_Mobile.Attachment {
        OpenCode_Mobile.Attachment(id: id, filename: name, mime: mime, kind: kind, dataURL: "", byteCount: byteCount)
    }

    private static func uploader(
        client: FakeUploadClient,
        transport: FakeByteTransport
    ) -> T3AttachmentUploader {
        T3AttachmentUploader(client: client, transport: transport, readBytes: { _ in png })
    }

    @Test
    func uploadSequenceMintsPostsAndReturnsChatAttachment() async throws {
        let client = FakeUploadClient()
        let transport = FakeByteTransport()
        let stored = try await Self.uploader(client: client, transport: transport).upload([Self.image()])

        let mint = try #require(client.requests.first)
        #expect(client.requests.count == 1)
        #expect(mint.tag == T3AttachmentUpload.createUploadURLTag)
        #expect(mint.payload["type"] as? String == "image")
        #expect(mint.payload["name"] as? String == "shot.png")
        #expect(mint.payload["mimeType"] as? String == "image/png")
        #expect(mint.payload["sizeBytes"] as? Int == Self.png.count)

        let post = try #require(transport.uploads.first)
        #expect(transport.uploads.count == 1)
        #expect(post.url.absoluteString == "https://t3.example.com/api/attachments/upload/sig-1")
        #expect(post.mimeType == "image/png")
        #expect(post.bearer == "bearer-token")
        #expect(post.data == Self.png)

        #expect(stored == [
            T3Attachment(id: "pending-1", type: "image", name: "shot.png", mimeType: "image/png", sizeBytes: Int64(Self.png.count)),
        ])
    }

    @Test
    func dispatchPayloadCarriesUploadedAttachments() throws {
        let stored = T3Attachment(id: "pending-1", type: "image", name: "shot.png", mimeType: "image/png", sizeBytes: 7)
        let command = T3RpcCodec.messageDispatchCommand(
            commandID: "c1",
            threadID: "thread-1",
            messageID: "m1",
            text: "",
            selection: T3ModelSelection(instanceID: "codex", model: "gpt-5"),
            isRunning: false,
            attachments: [stored]
        )
        let attachments = try #require(command["attachments"] as? [[String: Any]])
        let entry = try #require(attachments.first)
        #expect(command["text"] as? String == "")
        #expect(attachments.count == 1)
        #expect(entry["type"] as? String == "image")
        #expect(entry["id"] as? String == "pending-1")
        #expect(entry["name"] as? String == "shot.png")
        #expect(entry["mimeType"] as? String == "image/png")
        #expect(entry["sizeBytes"] as? Int64 == 7)
        #expect(entry["dataUrl"] == nil)
        #expect(JSONSerialization.isValidJSONObject(command))
    }

    @Test
    func oversizeImageIsRejectedBeforeAnyUpload() async {
        let client = FakeUploadClient()
        let transport = FakeByteTransport()
        let big = Self.image(name: "huge.png", byteCount: Int64(T3Assets.maxImageBytes) + 1)
        await #expect(throws: T3AttachmentError.oversize(filename: "huge.png")) {
            try await Self.uploader(client: client, transport: transport).upload([Self.image(), big])
        }
        #expect(client.requests.isEmpty)
        #expect(transport.uploads.isEmpty)
    }

    @Test
    func nonImageAttachmentIsRejectedWithUserFacingMessage() async {
        let client = FakeUploadClient()
        let transport = FakeByteTransport()
        let pdf = Self.image(name: "spec.pdf", mime: "application/pdf", kind: .pdf)
        await #expect(throws: T3AttachmentError.unsupported(filename: "spec.pdf")) {
            try await Self.uploader(client: client, transport: transport).upload([pdf])
        }
        #expect(client.requests.isEmpty)
        #expect(transport.uploads.isEmpty)
        #expect(
            T3AttachmentError.unsupported(filename: "spec.pdf").localizedDescription
                == "'spec.pdf' is not a supported image. T3 threads accept PNG, JPEG, GIF, or WebP images only."
        )
    }

    @Test
    func pdfUploadsAsFileWhenTheServerAllowsFiles() async throws {
        let client = FakeUploadClient()
        let transport = FakeByteTransport()
        let pdf = Self.image(name: "spec.pdf", mime: "application/pdf", kind: .pdf)
        let stored = try await Self.uploader(client: client, transport: transport)
            .upload([pdf], maxFileBytes: 1024)
        let mint = try #require(client.requests.first)
        #expect(mint.payload["type"] as? String == "file")
        #expect(mint.payload["name"] as? String == "spec.pdf")
        #expect(mint.payload["mimeType"] as? String == "application/pdf")
        #expect(stored.first?.type == "file")
        #expect(transport.uploads.first?.mimeType == "application/pdf")
    }

    @Test
    func oversizeFileIsRejectedBeforeAnyUpload() async {
        let client = FakeUploadClient()
        let transport = FakeByteTransport()
        let pdf = Self.image(name: "spec.pdf", mime: "application/pdf", kind: .pdf, byteCount: 2048)
        await #expect(throws: T3AttachmentError.fileOversize(filename: "spec.pdf", limit: T3Assets.fileLimitLabel(1024))) {
            try await Self.uploader(client: client, transport: transport).upload([pdf], maxFileBytes: 1024)
        }
        #expect(client.requests.isEmpty)
        #expect(transport.uploads.isEmpty)
    }

    @Test
    func unsupportedImageMimeIsRejected() async {
        await #expect(throws: T3AttachmentError.unsupported(filename: "icon.svg")) {
            try await Self.uploader(client: FakeUploadClient(), transport: FakeByteTransport())
                .upload([Self.image(name: "icon.svg", mime: "image/svg+xml")])
        }
    }

    @Test
    func failedTransferDeletesPendingUploadAndSurfacesError() async {
        let client = FakeUploadClient()
        let transport = FakeByteTransport(status: 500)
        await #expect(throws: T3AttachmentError.transfer(filename: "shot.png", detail: "server replied 500.")) {
            try await Self.uploader(client: client, transport: transport).upload([Self.image()])
        }
        #expect(client.requests.map(\.tag) == [T3AttachmentUpload.createUploadURLTag, T3AttachmentUpload.deleteTag])
        #expect(client.requests.last?.payload["attachmentId"] as? String == "pending-1")
    }

    @Test
    func missingCredentialsFailBeforeUpload() async {
        let client = FakeUploadClient(connected: false)
        let transport = FakeByteTransport()
        await #expect(throws: T3ClientError.notConnected) {
            try await Self.uploader(client: client, transport: transport).upload([Self.image()])
        }
        #expect(transport.uploads.isEmpty)
    }
}

private final class FakeUploadClient: T3AttachmentUploadClient, @unchecked Sendable {
    struct Request {
        let tag: String
        let payload: [String: Any]
    }

    private let lock = NSLock()
    private var _requests: [Request] = []
    private var minted = 0
    private let connected: Bool

    init(connected: Bool = true) {
        self.connected = connected
    }

    var requests: [Request] { lock.lock(); defer { lock.unlock() }; return _requests }

    func rpcRequest(tag: String, payload: [String: Any]) async throws -> Any? {
        lock.lock()
        defer { lock.unlock() }
        _requests.append(Request(tag: tag, payload: payload))
        switch tag {
        case T3AttachmentUpload.createUploadURLTag:
            minted += 1
            return [
                "attachmentId": "pending-\(minted)",
                "relativeUrl": "/api/attachments/upload/sig-\(minted)",
                "expiresAt": 1,
            ] as [String: Any]
        default:
            return [:] as [String: Any]
        }
    }

    func credentials() throws -> (baseURL: URL, bearer: String) {
        guard connected else { throw T3ClientError.notConnected }
        return (URL(string: "https://t3.example.com/")!, "bearer-token")
    }
}

private final class FakeByteTransport: T3AttachmentByteTransport, @unchecked Sendable {
    struct Upload {
        let url: URL
        let data: Data
        let mimeType: String
        let bearer: String
    }

    private let lock = NSLock()
    private var _uploads: [Upload] = []
    private let status: Int

    init(status: Int = 204) {
        self.status = status
    }

    var uploads: [Upload] { lock.lock(); defer { lock.unlock() }; return _uploads }

    func upload(url: URL, data: Data, mimeType: String, bearer: String) async throws -> Int {
        lock.lock()
        _uploads.append(Upload(url: url, data: data, mimeType: mimeType, bearer: bearer))
        lock.unlock()
        return status
    }
}
