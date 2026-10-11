import Foundation
import OpenCodeMarkdownCore

/// Prepared text for the File Viewer: display lines, language, and highlight chunks.
/// A reference type so the Screen Model can cache up to 1 MiB per open tab and compare by identity.
nonisolated final class FileTextDocument: Equatable, @unchecked Sendable {
    /// Lines longer than this are cut for display (minified files would stall layout).
    static let maxDisplayLineLength = 5_000
    static let chunkMaxLines = 200
    static let chunkMaxCharacters = 32_000

    let id = UUID()
    let text: String
    let lines: [String]
    let maxLineLength: Int
    let language: ProjectFileLanguage?
    let isTruncated: Bool
    /// Line ranges highlighted together, so visible text is colored first.
    let chunks: [Range<Int>]

    init(text: String, fileName: String, isTruncated: Bool) {
        self.text = text
        self.isTruncated = isTruncated
        language = ProjectFileLanguage.forFileName(fileName)
        let prepared = Self.displayLines(text)
        lines = prepared
        maxLineLength = prepared.reduce(0) { max($0, $1.count) }
        chunks = Self.chunks(for: prepared)
    }

    var languageName: String { language?.displayName ?? ProjectFileLanguage.plainTextName }

    /// `"Swift · 120 lines"`.
    var metaText: String {
        "\(languageName) · \(lines.count) \(lines.count == 1 ? "line" : "lines")"
    }

    func chunkIndex(containingLine line: Int) -> Int? {
        chunks.firstIndex { $0.contains(line) }
    }

    static func == (lhs: FileTextDocument, rhs: FileTextDocument) -> Bool {
        lhs.id == rhs.id
    }

    /// Splits on `\n` and `\r\n`, drops the empty line after a final newline, expands tabs,
    /// and cuts very long lines.
    static func displayLines(_ text: String) -> [String] {
        guard !text.isEmpty else { return [] }
        var lines = text
            .split(omittingEmptySubsequences: false, whereSeparator: { $0 == "\n" || $0 == "\r\n" })
            .map { line -> String in
                var value = String(line)
                if value.hasSuffix("\r") { value.removeLast() }
                if value.contains("\t") { value = value.replacingOccurrences(of: "\t", with: "    ") }
                if value.utf16.count > maxDisplayLineLength {
                    value = String(value.prefix(maxDisplayLineLength)) + " …"
                }
                return value
            }
        if lines.count > 1, lines.last?.isEmpty == true { lines.removeLast() }
        return lines
    }

    static func chunks(for lines: [String]) -> [Range<Int>] {
        var result: [Range<Int>] = []
        var start = 0
        var characters = 0
        for (index, line) in lines.enumerated() {
            let size = line.utf16.count + 1
            if index > start, index - start >= chunkMaxLines || characters + size > chunkMaxCharacters {
                result.append(start..<index)
                start = index
                characters = 0
            }
            characters += size
        }
        if start < lines.count { result.append(start..<lines.count) }
        return result
    }
}

/// Splits TextMate spans over a `\n`-joined chunk into per-line spans.
nonisolated enum FileCodeHighlightMapper {
    static func lineSpans(
        _ spans: [NativeCodeSpan],
        lines: ArraySlice<String>,
        firstLine: Int
    ) -> [Int: [NativeCodeSpan]] {
        var starts: [Int] = []
        starts.reserveCapacity(lines.count)
        var offset = 0
        for line in lines {
            starts.append(offset)
            offset += line.utf16.count + 1
        }
        var result: [Int: [NativeCodeSpan]] = [:]
        for span in spans where span.end > span.start {
            var lineIndex = lastIndex(in: starts, notGreaterThan: span.start)
            while lineIndex < starts.count {
                let lineStart = starts[lineIndex]
                let lineEnd = lineStart + lines[lines.startIndex + lineIndex].utf16.count
                if lineStart >= span.end { break }
                let start = max(span.start, lineStart)
                let end = min(span.end, lineEnd)
                if start < end {
                    result[firstLine + lineIndex, default: []].append(
                        NativeCodeSpan(start: start - lineStart, end: end - lineStart, role: span.role)
                    )
                }
                lineIndex += 1
            }
        }
        // Every line in the chunk is settled, even when it has no colored span.
        for index in 0..<lines.count where result[firstLine + index] == nil {
            result[firstLine + index] = []
        }
        return result
    }

    private static func lastIndex(in starts: [Int], notGreaterThan value: Int) -> Int {
        var low = 0
        var high = starts.count - 1
        var answer = 0
        while low <= high {
            let mid = (low + high) / 2
            if starts[mid] <= value {
                answer = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return answer
    }
}

/// The non-TextMate highlighter Session Chat code fences use when TextMate is off
/// (`OpenCodeSemanticFallbackHighlighter` in the shared markdown core).
nonisolated enum FileFallbackCodeHighlighter {
    static func spans(language: String, source: String) -> [NativeCodeSpan] {
        OpenCodeSemanticFallbackHighlighter.shared
            .highlight(languageOrAlias: language, code: source)
            .spans
            .compactMap { span in
                guard let role = NativeCodeTokenRole(rawValue: span.role.name) else { return nil }
                return NativeCodeSpan(start: Int(span.start), end: Int(span.end), role: role)
            }
    }
}
