import Foundation
import Testing
@testable import OpenCode_Mobile

struct UploadedAttachmentTimelineTests {
    private let adapter = MessageDomainAdapter()

    @Test func timelineShowsMetadataAttachmentsAsFilePartsAndHidesTheLine() throws {
        let json = """
        [{"id":"msg_1","type":"user","text":"look\\nAttached file: `/tmp/uploads/1/a.zip`",
          "metadata":{"attachments":[{"name":"a.zip","mime":"application/zip","path":"/tmp/uploads/1/a.zip"},
                                     {"name":"p.png","mime":"image/png","path":"/tmp/uploads/2/p.png"}]}}]
        """
        let dtos = try JSONDecoder().decode([V2SessionMessageDTO].self, from: Data(json.utf8))
        let message = try #require(adapter.messages(fromV2: dtos).first)
        #expect(message.text == "look")
        let files = message.parts.compactMap { part -> FilePart? in
            if case let .file(file) = part { return file }
            return nil
        }
        #expect(files.map(\.filename) == ["a.zip", "p.png"])
        #expect(files.map(\.kind) == [.file, .image])
        #expect(files.map(\.serverPath) == ["/tmp/uploads/1/a.zip", "/tmp/uploads/2/p.png"])
    }
}

struct AttachmentMimeKindTests {
    @Test func mediaExtensionResolvesAMissingDeclaredMime() {
        #expect(AttachmentMime.declared(nil, filename: "shot.PNG") == "image/png")
        #expect(AttachmentMime.declared("application/octet-stream", filename: "a.jpeg") == "image/jpeg")
        #expect(AttachmentMime.declared(nil, filename: "archive.zip") == nil)
    }

    @Test func kindsFollowTheMime() {
        #expect(AttachmentMime.kind(for: "image/webp") == .image)
        #expect(AttachmentMime.kind(for: "application/pdf") == .pdf)
        #expect(AttachmentMime.kind(for: "text/plain") == .text)
        #expect(AttachmentMime.kind(for: "application/zip") == .file)
    }
}

struct ServerFileReadLocationTests {
    @Test func absolutePathReadsFromItsParentFolderByName() {
        let result = FilesystemAPIClient.readLocation(forServerPath: "/tmp/uploads/u1/shot.png", sessionDirectory: "/repo")
        #expect(result.location == "/tmp/uploads/u1")
        #expect(result.relative == "shot.png")
    }

    @Test func windowsPathSplitsOnBackslash() {
        let result = FilesystemAPIClient.readLocation(forServerPath: "C:\\Temp\\uploads\\u1\\a.png", sessionDirectory: "C:\\repo")
        #expect(result.location == "C:\\Temp\\uploads\\u1")
        #expect(result.relative == "a.png")
    }

    @Test func relativePathReadsFromTheSessionDirectory() {
        let result = FilesystemAPIClient.readLocation(forServerPath: "out/chart.png", sessionDirectory: "/repo")
        #expect(result.location == "/repo")
        #expect(result.relative == "out/chart.png")
    }
}
