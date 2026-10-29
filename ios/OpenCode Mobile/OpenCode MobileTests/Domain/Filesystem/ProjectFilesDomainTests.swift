import Foundation
import OpenCodeMarkdownCore
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
        #expect(DirectoryListingPolicy.directories(nodes).map(\.name) == ["b"])
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

    @Test func oneShotHighlighterColorsKeywordsLikeCodeFences() {
        let spans = HighlightCoreOneShotHighlighter.spans(language: "kotlin", source: "val answer = 42")
        #expect(spans.contains(NativeCodeSpan(start: 0, end: 3, role: .keyword)))
        #expect(HighlightCoreOneShotHighlighter.spans(language: "kotlin", source: "").isEmpty)
        #expect(HighlightCoreOneShotHighlighter.spans(language: "brainfuck", source: "++[>+<-]").isEmpty)
    }

    @Test func everyFileLanguageIsACanonicalHighlightCoreID() {
        let languages = Set(ProjectFileLanguage.extensions.values).union(ProjectFileLanguage.fileNames.values)
        for language in languages {
            #expect(HighlightCore.shared.canonicalLanguage(language: language.id) == language.id, "\(language.id)")
        }
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

    @Test func sourceSpansMoveRightByThreeColumnsPerTab() {
        let text = "a\tb\t\tc\nplain"
        let lines = FileTextDocument.displayLines(text)
        #expect(lines == ["a    b        c", "plain"])
        let spans = [
            NativeCodeSpan(start: 0, end: 1, role: .keyword), // a
            NativeCodeSpan(start: 1, end: 3, role: .string), // tab b
            NativeCodeSpan(start: 5, end: 6, role: .number), // c
            NativeCodeSpan(start: 7, end: 12, role: .comment), // plain
        ]
        let mapped = FileCodeHighlightMapper.displayLineSpans(
            spans,
            source: FileSourceLines(text: text),
            displayLines: lines,
            range: 0..<2
        )
        #expect(mapped[0] == [
            NativeCodeSpan(start: 0, end: 1, role: .keyword),
            NativeCodeSpan(start: 1, end: 6, role: .string),
            NativeCodeSpan(start: 14, end: 15, role: .number),
        ])
        #expect(mapped[1] == [NativeCodeSpan(start: 0, end: 5, role: .comment)])
    }

    @Test func crLfIsNeverColoredAndASpanAcrossLinesSplits() {
        let text = "/* a\r\nb */\r\nx"
        let lines = FileTextDocument.displayLines(text)
        #expect(lines == ["/* a", "b */", "x"])
        let source = FileSourceLines(text: text)
        #expect(source.starts == [0, 6, 12])
        let mapped = FileCodeHighlightMapper.displayLineSpans(
            [NativeCodeSpan(start: 0, end: 10, role: .comment), NativeCodeSpan(start: 12, end: 13, role: .variable)],
            source: source,
            displayLines: lines,
            range: 1..<3
        )
        #expect(mapped[1] == [NativeCodeSpan(start: 0, end: 4, role: .comment)])
        #expect(mapped[2] == [NativeCodeSpan(start: 0, end: 1, role: .variable)])
        #expect(mapped[0] == nil)
        // The whole comment, \r\n included, stays inside the first line's four columns.
        let first = FileCodeHighlightMapper.displayLineSpans(
            [NativeCodeSpan(start: 0, end: 10, role: .comment)],
            source: source,
            displayLines: lines,
            range: 0..<1
        )
        #expect(first[0] == [NativeCodeSpan(start: 0, end: 4, role: .comment)])
    }

    @Test func aCutLineKeepsOnlySpansBeforeTheCut() {
        let limit = FileTextDocument.maxDisplayLineLength
        let text = "\t" + String(repeating: "x", count: limit) + "\nnext"
        let lines = FileTextDocument.displayLines(text)
        #expect(lines[0].utf16.count == limit + FileTextDocument.cutMarker.utf16.count)
        #expect(lines[0].hasSuffix(FileTextDocument.cutMarker))
        let mapped = FileCodeHighlightMapper.displayLineSpans(
            [
                NativeCodeSpan(start: 0, end: 1, role: .punctuation), // the tab
                NativeCodeSpan(start: 1, end: limit - 1, role: .string), // crosses the cut
                NativeCodeSpan(start: limit - 1, end: limit + 1, role: .keyword), // past it
                NativeCodeSpan(start: limit + 2, end: limit + 6, role: .variable), // next line
            ],
            source: FileSourceLines(text: text),
            displayLines: lines,
            range: 0..<2
        )
        #expect(mapped[0] == [
            NativeCodeSpan(start: 0, end: 4, role: .punctuation),
            NativeCodeSpan(start: 4, end: limit, role: .string),
        ])
        #expect(mapped[1] == [NativeCodeSpan(start: 0, end: 4, role: .variable)])
    }
}
