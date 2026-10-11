import Foundation
import Testing
@testable import OpenCode_Mobile

@MainActor
struct ProjectFilesListingPolicyTests {
    private let entries = [
        ProjectEntry(name: "zeta.txt", relativePath: "zeta.txt", isDirectory: false),
        ProjectEntry(name: "Alpha.swift", relativePath: "Alpha.swift", isDirectory: false),
        ProjectEntry(name: "src", relativePath: "src", isDirectory: true),
        ProjectEntry(name: ".github", relativePath: ".github", isDirectory: true),
        ProjectEntry(name: "Docs", relativePath: "Docs", isDirectory: true),
        ProjectEntry(name: ".env", relativePath: ".env", isDirectory: false),
    ]

    @Test func foldersFirstThenCaseInsensitiveNameAndHiddenFiltered() {
        let visible = ProjectFilesListingPolicy.visible(entries, showHidden: false)
        #expect(visible.map(\.name) == ["Docs", "src", "Alpha.swift", "zeta.txt"])
    }

    @Test func showHiddenKeepsDotEntriesSorted() {
        let visible = ProjectFilesListingPolicy.visible(entries, showHidden: true)
        #expect(visible.map(\.name) == [".github", "Docs", "src", ".env", "Alpha.swift", "zeta.txt"])
    }

    @Test func noCapOnLargeFolders() {
        let many = (0..<500).map { ProjectEntry(name: "f\($0)", relativePath: "f\($0)", isDirectory: false) }
        #expect(ProjectFilesListingPolicy.visible(many, showHidden: false).count == 500)
    }

    @Test func addProjectPolicyIsUnchanged() {
        let nodes = [
            DirectoryNode(name: "b", absolutePath: "/b", isDirectory: true),
            DirectoryNode(name: "file.txt", absolutePath: "/file.txt", isDirectory: false),
        ]
        #expect(DirectoryListingPolicy.prepare(nodes).map(\.name) == ["b"])
    }
}

@MainActor
struct ProjectFileContentDecoderTests {
    @Test func truncationNeverSplitsUTF8AndDropsPartialLine() {
        let bytes = [UInt8]("ab\ncdé".utf8) // é = 2 bytes at the end
        let cutMidCharacter = ProjectFileContentDecoder.truncatedText(bytes, limit: bytes.count - 1)
        #expect(String(bytes: cutMidCharacter, encoding: .utf8) == "ab")

        let noNewline = [UInt8]("héllo".utf8)
        let kept = ProjectFileContentDecoder.truncatedText(noNewline, limit: 2) // "h" + first byte of é
        #expect(String(bytes: kept, encoding: .utf8) == "h")
    }

    @Test func contentRangeTotal() {
        #expect(ProjectFileContentDecoder.totalBytes(fromContentRange: "bytes 0-1048575/2000000") == 2_000_000)
        #expect(ProjectFileContentDecoder.totalBytes(fromContentRange: "bytes 0-9/*") == nil)
        #expect(ProjectFileContentDecoder.totalBytes(fromContentRange: nil) == nil)
    }

    @Test func nulInFirst8KiBIsBinaryButLaterNulIsText() {
        var late = [UInt8](repeating: 0x61, count: ProjectFileLimits.binarySniffBytes + 10)
        late[ProjectFileLimits.binarySniffBytes + 5] = 0
        let lateContent = ProjectFileContentDecoder.content(fromBytes: Data(late), isTruncated: false, totalBytes: nil, mimeType: nil)
        if case .text = lateContent {} else { Issue.record("expected text, got \(lateContent)") }

        let early = ProjectFileContentDecoder.content(fromBytes: Data([0x61, 0]), isTruncated: false, totalBytes: nil, mimeType: "x/y")
        #expect(early == .binary(mimeType: "x/y", byteCount: 2))
    }

    @Test func v1Base64ImageOverCapIsTooLarge() {
        let big = Data(count: ProjectFileLimits.maxImageBytes + 1)
        let dto = FileContentDTO(type: "binary", content: big.base64EncodedString(), encoding: "base64", mimeType: "image/png")
        #expect(ProjectFileContentDecoder.content(fromV1: dto, fileName: "a.png") == .tooLarge(byteCount: big.count))
    }

    @Test func v1Base64NonImageOverImageCapStaysBinary() {
        let big = Data(count: ProjectFileLimits.maxImageBytes + 1)
        let dto = FileContentDTO(type: "binary", content: big.base64EncodedString(), encoding: "base64", mimeType: "application/zip")
        #expect(ProjectFileContentDecoder.content(fromV1: dto, fileName: "a.zip") == .binary(mimeType: "application/zip", byteCount: big.count))
    }

    @Test func v2ImageOverCapIsTooLargeWithReportedSize() {
        let cut = ProjectFileContentDecoder.image(
            fromBytes: Data(count: 8),
            exceededLimit: true,
            totalBytes: 40_000_000,
            mimeType: "image/png",
            fileName: "a.png"
        )
        #expect(cut == .tooLarge(byteCount: 40_000_000))
        let unknown = ProjectFileContentDecoder.image(fromBytes: Data(count: 8), exceededLimit: true, mimeType: nil, fileName: "a.png")
        #expect(unknown == .tooLarge(byteCount: nil))
    }

    @Test func normalizedMimeTypeStripsParameters() {
        #expect(ProjectFileContentDecoder.normalizedMimeType("Text/Plain; charset=utf-8") == "text/plain")
        #expect(ProjectFileContentDecoder.normalizedMimeType("") == nil)
    }
}

@MainActor
struct FileTextDocumentTests {
    @Test func splitsCRLFAndDropsFinalEmptyLine() {
        let document = FileTextDocument(text: "one\r\ntwo\n\tthree\n", fileName: "a.swift", isTruncated: false)
        #expect(document.lines == ["one", "two", "    three"])
        #expect(document.metaText == "Swift · 3 lines")
    }

    @Test func singleLineMeta() {
        #expect(FileTextDocument(text: "x", fileName: "a.json", isTruncated: false).metaText == "JSON · 1 line")
    }

    @Test func veryLongLinesAreCutForDisplay() {
        let long = String(repeating: "x", count: FileTextDocument.maxDisplayLineLength + 10)
        let document = FileTextDocument(text: "a\n\(long)", fileName: "min.js", isTruncated: false)
        #expect(document.lines[1] == String(repeating: "x", count: FileTextDocument.maxDisplayLineLength) + " …")
    }

    @Test func semanticFallbackColorsKeywordsLikeCodeFences() {
        let spans = FileFallbackCodeHighlighter.spans(language: "kotlin", source: "val answer = 42")
        #expect(spans.contains(NativeCodeSpan(start: 0, end: 3, role: .keyword)))
        #expect(FileFallbackCodeHighlighter.spans(language: "kotlin", source: "").isEmpty)
    }

    @Test func mapperSplitsSpansAcrossLines() {
        let lines = ["let a", "/* x", "y */"][...]
        // Chunk text: "let a\n/* x\ny */" — comment spans 6..<15.
        let spans = [
            NativeCodeSpan(start: 0, end: 3, role: .keyword),
            NativeCodeSpan(start: 6, end: 15, role: .comment),
        ]
        let mapped = FileCodeHighlightMapper.lineSpans(spans, lines: lines, firstLine: 10)
        #expect(mapped[10] == [NativeCodeSpan(start: 0, end: 3, role: .keyword)])
        #expect(mapped[11] == [NativeCodeSpan(start: 0, end: 4, role: .comment)])
        #expect(mapped[12] == [NativeCodeSpan(start: 0, end: 4, role: .comment)])
    }
}
