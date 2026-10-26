import Foundation
import Testing
@testable import OpenCode_Mobile

struct AttachmentMimeSharedVectorTests {
    private struct Vectors: Decodable {
        struct Case: Decodable {
            let name: String
            let mime: String?
            let filename: String?
            let bytesBase64: String
            let expected: String
        }

        let cases: [Case]
    }

    /// The vectors Android reads too: `shared/attachment-mime/attachment-mime-cases.json`.
    private static func vectors() throws -> [Vectors.Case] {
        var directory = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        let relative = "shared/attachment-mime/attachment-mime-cases.json"
        for _ in 0 ..< 12 {
            let candidate = directory.appendingPathComponent(relative)
            if FileManager.default.fileExists(atPath: candidate.path) {
                return try JSONDecoder().decode(Vectors.self, from: Data(contentsOf: candidate)).cases
            }
            directory.deleteLastPathComponent()
        }
        throw CocoaError(.fileNoSuchFile)
    }

    @Test func everySharedVectorNormalizesAsExpected() throws {
        let cases = try Self.vectors()
        #expect(cases.count > 10)
        for item in cases {
            let bytes = Data(base64Encoded: item.bytesBase64) ?? Data()
            #expect(
                AttachmentMime.normalized(
                    declared: item.filename.map { AttachmentMime.declared(item.mime, filename: $0) } ?? item.mime,
                    leadingBytes: bytes
                ) == item.expected,
                "\(item.name)"
            )
        }
    }

    @Test func mediaExtensionResolvesAMissingDeclaredMime() {
        #expect(AttachmentMime.declared(nil, filename: "shot.PNG") == "image/png")
        #expect(AttachmentMime.declared("application/octet-stream", filename: "a.jpeg") == "image/jpeg")
        #expect(AttachmentMime.declared("text/markdown; charset=utf-8", filename: "a.md") == "text/markdown")
        #expect(AttachmentMime.declared(nil, filename: "archive.zip") == nil)
    }

    @Test func kindsFollowTheNormalizedMime() {
        #expect(AttachmentMime.kind(for: "image/webp") == .image)
        #expect(AttachmentMime.kind(for: "application/pdf") == .pdf)
        #expect(AttachmentMime.kind(for: "text/plain") == .text)
        #expect(AttachmentMime.kind(for: "application/zip") == .file)
    }
}

struct AttachmentUploadPolicyTests {
    private func model(image: Bool = true, pdf: Bool = true) -> ModelSelectionOption {
        ModelSelectionOption(
            providerID: "p",
            providerName: "P",
            modelID: "m",
            modelName: "M",
            variants: [],
            supportsImageInput: image,
            supportsPdfInput: pdf
        )
    }

    @Test func nativeTypeUnderTheCapStaysInline() {
        #expect(
            AttachmentUploadPolicy.destination(mime: "image/png", byteCount: 1_000, model: model(), uploadAvailable: true) == .inline
        )
        #expect(
            AttachmentUploadPolicy.destination(
                mime: "application/pdf",
                byteCount: AttachmentLimits.maxDecodedBytes,
                model: model(),
                uploadAvailable: true
            ) == .inline
        )
    }

    @Test func nativeTypeOverTheCapUploads() {
        #expect(
            AttachmentUploadPolicy.destination(
                mime: "image/png",
                byteCount: AttachmentLimits.maxDecodedBytes + 1,
                model: model(),
                uploadAvailable: true
            ) == .upload
        )
    }

    @Test func typeTheModelCannotReadUploads() {
        #expect(
            AttachmentUploadPolicy.destination(mime: "image/png", byteCount: 10, model: model(image: false), uploadAvailable: true) == .upload
        )
        #expect(
            AttachmentUploadPolicy.destination(mime: "application/pdf", byteCount: 10, model: model(pdf: false), uploadAvailable: true) == .upload
        )
    }

    @Test func textAndBinaryFilesUpload() {
        #expect(AttachmentUploadPolicy.destination(mime: "text/plain", byteCount: 10, model: model(), uploadAvailable: true) == .upload)
        #expect(AttachmentUploadPolicy.destination(mime: "application/zip", byteCount: 10, model: model(), uploadAvailable: true) == .upload)
    }

    @Test func unknownModelSendsMediaInlineAndEverythingElseUploads() {
        for mime in ["image/png", "image/jpeg", "application/pdf"] {
            #expect(AttachmentUploadPolicy.destination(mime: mime, byteCount: 10, model: nil, uploadAvailable: true) == .inline, Comment(rawValue: mime))
        }
        for mime in ["text/plain", "application/zip"] {
            #expect(AttachmentUploadPolicy.destination(mime: mime, byteCount: 10, model: nil, uploadAvailable: true) == .upload, Comment(rawValue: mime))
        }
        #expect(
            AttachmentUploadPolicy.destination(
                mime: "image/png",
                byteCount: AttachmentLimits.maxDecodedBytes + 1,
                model: nil,
                uploadAvailable: true
            ) == .upload
        )
    }

    @Test func withoutTheRouteEverythingTakesTheOldPath() {
        for mime in ["image/png", "text/plain", "application/zip"] {
            #expect(
                AttachmentUploadPolicy.destination(
                    mime: mime,
                    byteCount: AttachmentLimits.maxDecodedBytes + 1,
                    model: model(),
                    uploadAvailable: false
                ) == .inline
            )
        }
    }

    @Test func duplicateNeedsSameNameAndSize() {
        let draft = [OpenCode_Mobile.Attachment(id: "1", filename: "a.txt", mime: "text/plain", kind: .text, dataURL: "", byteCount: 10)]
        #expect(AttachmentDuplicate.isDuplicate(filename: "a.txt", byteCount: 10, in: draft))
        #expect(!AttachmentDuplicate.isDuplicate(filename: "a.txt", byteCount: 11, in: draft))
        #expect(!AttachmentDuplicate.isDuplicate(filename: "b.txt", byteCount: 10, in: draft))
        #expect(AttachmentDuplicate.message == "This file has already been uploaded")
    }

    @Test func failureTableClassifiesEveryRow() {
        for status in [404, 405, 400, 415] {
            #expect(AttachmentUploadFailure.classify(statusCode: status, hasPath: false) == .contractMiss, "\(status)")
        }
        #expect(AttachmentUploadFailure.classify(statusCode: 200, hasPath: false) == .contractMiss)
        for status in [401, 403, 408, 409, 413, 422, 429, 500, 502, 503] {
            #expect(AttachmentUploadFailure.classify(statusCode: status, hasPath: false) == .transport, "\(status)")
        }
        #expect(AttachmentUploadFailure.classify(statusCode: 200, hasPath: true) == nil)
    }
}

struct AttachmentUploadAdapterTests {
    private let adapter = AttachmentUploadDomainAdapter()

    @Test func pathIsTmpUploadsLowercaseUuidAndSafeFilename() {
        let uuid = UUID(uuidString: "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE")!
        #expect(
            UploadedAttachmentMetadata.serverPath(tmp: "/var/tmp", filename: "a/b\\c.txt", uuid: uuid)
                == "/var/tmp/uploads/aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee/a_b_c.txt"
        )
        #expect(
            UploadedAttachmentMetadata.serverPath(tmp: "C:\\Temp\\", filename: "n.pdf", uuid: uuid)
                == "C:\\Temp\\uploads\\aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee\\n.pdf"
        )
    }

    @Test func successReadsTheResolvedPath() throws {
        let body = Data(#"{"location":{"directory":"/p"},"data":{"path":"/tmp/uploads/x/a.txt"}}"#.utf8)
        let uploaded = try adapter.result(from: AttachmentUploadHTTPResult(statusCode: 200, body: body))
        #expect(uploaded.serverPath == "/tmp/uploads/x/a.txt")
    }

    @Test func bodyWithoutDataPathIsAContractMiss() {
        #expect(throws: AttachmentUploadError.contractMiss) {
            try adapter.result(from: AttachmentUploadHTTPResult(statusCode: 200, body: Data(#"{"data":{}}"#.utf8)))
        }
        #expect(throws: AttachmentUploadError.contractMiss) {
            try adapter.result(from: AttachmentUploadHTTPResult(statusCode: 415, body: Data()))
        }
    }

    @Test func authAndServerFailuresAreTransportFailures() {
        for status in [401, 403, 500] {
            #expect(throws: AttachmentUploadError.failed) {
                try adapter.result(from: AttachmentUploadHTTPResult(statusCode: status, body: Data()))
            }
        }
    }

    @Test func errorsMapToCancelOrFailure() {
        #expect(adapter.error(from: CancellationError()) == .cancelled)
        #expect(adapter.error(from: URLError(.cancelled)) == .cancelled)
        #expect(adapter.error(from: URLError(.timedOut)) == .failed)
        #expect(adapter.error(from: URLError(.networkConnectionLost)) == .failed)
    }

    @Test func pathQueryKeepsReservedCharactersEncoded() throws {
        let base = try #require(URL(string: "https://example.com/api/experimental/fs/write?location%5Bdirectory%5D=%2Fp"))
        let url = try #require(AttachmentUploadAPIClient.appendingPath("/tmp/a b+c&d.txt", to: base))
        #expect(url.absoluteString.hasSuffix("&path=%2Ftmp%2Fa%20b%2Bc%26d.txt"))
    }
}

struct UploadedAttachmentPromptTests {
    private let adapter = MessageDomainAdapter()

    private func uploaded(_ name: String, _ mime: String, _ path: String) -> OpenCode_Mobile.Attachment {
        OpenCode_Mobile.Attachment(
            id: UUID().uuidString,
            filename: name,
            mime: mime,
            kind: AttachmentMime.kind(for: mime),
            dataURL: "",
            byteCount: 5,
            upload: .uploaded(serverPath: path)
        )
    }

    @Test func promptTextAppendsOneLinePerUploadedFile() {
        let files = [uploaded("a.zip", "application/zip", "/tmp/uploads/1/a.zip"), uploaded("b.txt", "text/plain", "/tmp/uploads/2/b.txt")]
        #expect(
            UploadedAttachmentMetadata.promptText("look", uploaded: files)
                == "look\nAttached file: `/tmp/uploads/1/a.zip`\nAttached file: `/tmp/uploads/2/b.txt`"
        )
        #expect(UploadedAttachmentMetadata.promptText("  ", uploaded: [files[0]]) == "Attached file: `/tmp/uploads/1/a.zip`")
        #expect(UploadedAttachmentMetadata.promptText("hi", uploaded: []) == "hi")
    }

    @Test func v2PromptKeepsUploadsOutOfFilesAndWritesMetadata() throws {
        let inline = OpenCode_Mobile.Attachment(id: "i", filename: "p.png", mime: "image/png", kind: .image, dataURL: "data:image/png;base64,AQID")
        let options = PromptSendOptions(
            text: "see",
            attachments: [uploaded("a.zip", "application/zip", "/tmp/uploads/1/a.zip"), inline]
        )
        let request = adapter.v2PromptRequest(options: options)
        #expect(request.text == "see\nAttached file: `/tmp/uploads/1/a.zip`")
        #expect(request.files?.map(\.name) == ["p.png"])
        #expect(
            request.metadata
                == V2PromptMetadataDTO(
                    attachments: [V2PromptUploadedAttachmentDTO(name: "a.zip", mime: "application/zip", path: "/tmp/uploads/1/a.zip")]
                )
        )
        let encoder = JSONEncoder()
        encoder.outputFormatting = .withoutEscapingSlashes
        let json = try #require(String(data: encoder.encode(request), encoding: .utf8))
        #expect(json.contains(#""metadata":{"attachments":[{"#))
        #expect(json.contains(#""path":"/tmp/uploads/1/a.zip""#))
    }

    @Test func userTypedAttachedFileLineStaysWithoutMatchingMetadata() throws {
        let typed = #"[{"id":"m1","type":"user","text":"see\nAttached file: `/etc/hosts`"}]"#
        let message = try #require(adapter.messages(fromV2: try JSONDecoder().decode([V2SessionMessageDTO].self, from: Data(typed.utf8))).first)
        #expect(message.text == "see\nAttached file: `/etc/hosts`")

        let other = """
        [{"id":"m2","type":"user","text":"see\\nAttached file: `/etc/hosts`",
          "metadata":{"attachments":[{"name":"a.txt","mime":"text/plain","path":"/tmp/uploads/1/a.txt"}]}}]
        """
        let kept = try #require(adapter.messages(fromV2: try JSONDecoder().decode([V2SessionMessageDTO].self, from: Data(other.utf8))).first)
        #expect(kept.text == "see\nAttached file: `/etc/hosts`")
    }

    @Test func v2PromptWithoutUploadsHasNoMetadata() {
        let request = adapter.v2PromptRequest(options: PromptSendOptions(text: "plain"))
        #expect(request.metadata == nil)
        #expect(request.text == "plain")
    }

    @Test func streamedBodyWriterWritesMetadataForUploads() throws {
        let options = PromptSendOptions(text: "x", attachments: [uploaded("a.zip", "application/zip", "/tmp/uploads/1/a.zip")])
        let file = try PromptJSONBodyWriter.writeV2Prompt(adapter.v2PromptRequest(options: options), attachments: options.inlineAttachments)
        defer { try? FileManager.default.removeItem(at: file) }
        let object = try #require(JSONSerialization.jsonObject(with: Data(contentsOf: file)) as? [String: Any])
        let metadata = try #require(object["metadata"] as? [String: Any])
        let attachments = try #require(metadata["attachments"] as? [[String: String]])
        #expect(attachments == [["name": "a.zip", "mime": "application/zip", "path": "/tmp/uploads/1/a.zip"]])
        #expect(object["files"] == nil)
    }

    @Test func v1PromptNeverCarriesUploadedBytes() {
        let options = PromptSendOptions(text: "x", attachments: [uploaded("a.zip", "application/zip", "/tmp/uploads/1/a.zip")])
        let request = adapter.sendRequest(options: options)
        #expect(request.parts.filter { $0.type == "file" }.isEmpty)
    }
}

struct StreamedUploadContractTests {
    static let route = ##"""
    {"paths":{"/api/experimental/fs/write":{"post":{
      "parameters":[{"name":"path","in":"query","required":true,"schema":{"type":"string"}}],
      "requestBody":{"content":{"application/octet-stream":{"schema":{"type":"string"}}}},
      "responses":{"200":{"content":{"application/json":{"schema":{"$ref":"#/components/schemas/Write"}}}}}}}},
     "components":{"schemas":{"Write":{"type":"object","properties":{"location":{},"data":{"type":"object","properties":{"path":{"type":"string"}}}}}}}}
    """##

    @Test func flagOnlyForTheKnownShape() throws {
        #expect(try V2ServerContract.features(data: Data(Self.route.utf8)).streamedUploadRoute)
    }

    @Test func flagNeedsTheTmpDirectoryToo() {
        var features = V2ServerFeatures(streamedUploadRoute: true)
        #expect(!features.streamedUpload)
        features.uploadDirectory = "/tmp"
        #expect(features.streamedUpload)
        #expect(ServerCapabilities.forProtocol(.v2, v2Features: features).supportsStreamedUpload)
        #expect(!ServerCapabilities.forProtocol(.v1, v2Features: features).supportsStreamedUpload)
    }

    @Test func followsAllOfWrapperAroundTheResponseObject() throws {
        let wrapped = Self.route.replacingOccurrences(
            of: ##""data":{"type":"object","properties":{"path":{"type":"string"}}}"##,
            with: ##""data":{"allOf":[{"type":"object","properties":{"path":{"type":"string"}}}]}"##
        )
        #expect(wrapped != Self.route)
        #expect(try V2ServerContract.features(data: Data(wrapped.utf8)).streamedUploadRoute)
    }

    @Test func nonJsonSuccessResponseTurnsItOff() throws {
        let textResponse = Self.route.replacingOccurrences(of: #""content":{"application/json""#, with: #""content":{"text/plain""#)
        #expect(textResponse != Self.route)
        #expect(try !V2ServerContract.features(data: Data(textResponse.utf8)).streamedUploadRoute)
    }

    @Test func offByDefault() throws {
        #expect(!V2ServerFeatures().streamedUpload)
        #expect(!ServerCapabilities.forProtocol(.v2).supportsStreamedUpload)
        #expect(try !V2ServerContract.features(data: Data("{}".utf8)).streamedUploadRoute)
    }

    @Test func renamedPathOrMissingPieceTurnsItOff() throws {
        let renamed = Self.route.replacingOccurrences(of: "/api/experimental/fs/write", with: "/api/experimental/fs/put")
        let optionalPath = Self.route.replacingOccurrences(of: #""required":true"#, with: #""required":false"#)
        let jsonBody = Self.route.replacingOccurrences(of: "application/octet-stream", with: "application/json")
        let noDataPath = Self.route.replacingOccurrences(of: #""path":{"type":"string"}}}}}}}"#, with: #""other":{}}}}}}}"#)
        for document in [renamed, optionalPath, jsonBody, noDataPath] {
            #expect(try !V2ServerContract.features(data: Data(document.utf8)).streamedUploadRoute)
        }
    }

    @Test func disablingUploadLeavesNoDirectory() {
        let manager = ActiveConnectionManager()
        manager.disableStreamedUpload()
        #expect(manager.streamedUploadDirectory == nil)
    }
}
