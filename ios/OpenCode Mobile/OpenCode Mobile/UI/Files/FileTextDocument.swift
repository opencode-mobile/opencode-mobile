import Foundation

/// Prepared text for the File Viewer: display lines and language.
/// A reference type so the Screen Model can cache up to 1 MiB per open tab and compare by identity.
nonisolated final class FileTextDocument: Equatable, @unchecked Sendable {
    /// Lines longer than this are cut for display (minified files would stall layout).
    static let maxDisplayLineLength = 5_000
    /// Ends a cut line.
    static let cutMarker = " …"
    /// Spaces a tab expands to.
    static let tabWidth = 4

    let id = UUID()
    let text: String
    let lines: [String]
    let maxLineLength: Int
    let language: ProjectFileLanguage?
    let isTruncated: Bool

    init(text: String, fileName: String, isTruncated: Bool) {
        self.text = text
        self.isTruncated = isTruncated
        language = ProjectFileLanguage.forFileName(fileName)
        let prepared = Self.displayLines(text)
        lines = prepared
        maxLineLength = prepared.reduce(0) { max($0, $1.count) }
    }

    var languageName: String { language?.displayName ?? ProjectFileLanguage.plainTextName }

    /// `"Swift · 120 lines"`.
    var metaText: String {
        "\(languageName) · \(lines.count) \(lines.count == 1 ? "line" : "lines")"
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
                if value.contains("\t") {
                    value = value.replacingOccurrences(of: "\t", with: String(repeating: " ", count: tabWidth))
                }
                if value.utf16.count > maxDisplayLineLength {
                    value = String(value.prefix(maxDisplayLineLength)) + cutMarker
                }
                return value
            }
        if lines.count > 1, lines.last?.isEmpty == true { lines.removeLast() }
        return lines
    }
}

/// Where each `\n`-separated line of a file's text starts, in UTF-16 offsets (highlight-core's
/// lines), and the columns of its tabs, so spans over the text map to display columns.
nonisolated struct FileSourceLines: Sendable {
    let starts: [Int]
    let length: Int
    /// Tab columns (UTF-16, from the line start) of the lines that have tabs.
    let tabs: [Int: [Int]]

    init(text: String) {
        var starts = [0]
        var tabs: [Int: [Int]] = [:]
        var offset = 0
        for unit in text.utf16 {
            if unit == 0x0A {
                starts.append(offset + 1)
            } else if unit == 0x09 {
                tabs[starts.count - 1, default: []].append(offset - starts[starts.count - 1])
            }
            offset += 1
        }
        self.starts = starts
        length = offset
        self.tabs = tabs
    }

    /// The offset after the line's `\n`, or the text length for the last line.
    func end(of line: Int) -> Int {
        line + 1 < starts.count ? starts[line + 1] : length
    }
}

/// Splits spans over the `\n`-joined display lines into per-line spans.
nonisolated enum FileCodeHighlightMapper {
    /// Spans over a file's text (UTF-16 offsets) split into the display lines in `range`: each
    /// tab before a span widens it by `tabWidth - 1` columns, and a line cut for display keeps
    /// only what is before the cut, clipped to it. `\r` and `\n` are never colored. Every
    /// line in `range` is settled, even with no span.
    static func displayLineSpans(
        _ spans: [NativeCodeSpan],
        source: FileSourceLines,
        displayLines: [String],
        range: Range<Int>
    ) -> [Int: [NativeCodeSpan]] {
        var result: [Int: [NativeCodeSpan]] = [:]
        result.reserveCapacity(range.count)
        var next = 0
        for line in range {
            guard line < source.starts.count, line < displayLines.count else {
                result[line] = []
                continue
            }
            let lineStart = source.starts[line]
            let lineEnd = source.end(of: line)
            let display = displayLines[line].utf16.count
            // A cut line ends with the cut marker; its text before the marker is the shown part.
            let shown = display > FileTextDocument.maxDisplayLineLength
                ? display - FileTextDocument.cutMarker.utf16.count
                : display
            let tabs = source.tabs[line] ?? []
            func column(_ raw: Int) -> Int {
                guard !tabs.isEmpty else { return raw }
                var low = 0
                var high = tabs.count
                while low < high {
                    let mid = (low + high) / 2
                    if tabs[mid] < raw { low = mid + 1 } else { high = mid }
                }
                return raw + low * (FileTextDocument.tabWidth - 1)
            }
            while next < spans.count, spans[next].end <= lineStart { next += 1 }
            var lineSpans: [NativeCodeSpan] = []
            var index = next
            while index < spans.count, spans[index].start < lineEnd {
                let span = spans[index]
                let start = column(max(span.start, lineStart) - lineStart)
                let end = min(column(min(span.end, lineEnd) - lineStart), shown)
                if start < end { lineSpans.append(NativeCodeSpan(start: start, end: end, role: span.role)) }
                index += 1
            }
            result[line] = lineSpans
        }
        return result
    }

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
        // Every line is settled, even when it has no colored span.
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
