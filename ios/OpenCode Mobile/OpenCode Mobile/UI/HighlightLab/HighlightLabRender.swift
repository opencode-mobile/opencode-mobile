#if HIGHLIGHT_LAB
import Foundation

/// Highlighted text prepared for display. Offsets stay those of the measured source: lines
/// split on `\n` only (no tab expansion), and very long lines are cut for layout.
/// A reference type so Screen Model states compare by identity, not by megabytes of lines.
nonisolated final class HighlightLabRenderedText: Equatable, @unchecked Sendable {
    let lines: [String]
    let lineSpans: [Int: [NativeCodeSpan]]
    let maxLineLength: Int
    /// UTF-16 offset where each line starts in the source.
    let lineStarts: [Int]

    init(source: String, spans: [HighlightLabSpan]?) {
        // Scalars, not Characters: "\r\n" is one Character and would not split.
        let raw = source.unicodeScalars
            .split(separator: "\n", omittingEmptySubsequences: false)
            .map { String(String.UnicodeScalarView($0)) }
        var starts: [Int] = []
        starts.reserveCapacity(raw.count)
        var offset = 0
        for line in raw {
            starts.append(offset)
            offset += line.utf16.count + 1
        }
        lineStarts = starts
        let limit = FileTextDocument.maxDisplayLineLength
        let display = raw.map { line -> String in
            var value = line
            if value.hasSuffix("\r") { value.removeLast() }
            if value.utf16.count > limit { value = String(value.utf16.prefix(limit)) ?? String(value.prefix(limit)) }
            return value
        }
        lines = display
        maxLineLength = display.reduce(0) { max($0, $1.count) }
        let native = (spans ?? []).compactMap { span -> NativeCodeSpan? in
            guard let role = NativeCodeTokenRole(rawValue: span.role) else { return nil }
            return NativeCodeSpan(start: span.start, end: span.end, role: role)
        }
        let mapped = FileCodeHighlightMapper.lineSpans(native, lines: raw[...], firstLine: 0)
        var clipped: [Int: [NativeCodeSpan]] = [:]
        for (index, lineSpans) in mapped {
            let length = display[index].utf16.count
            clipped[index] = lineSpans.compactMap { span in
                guard span.start < length else { return nil }
                return NativeCodeSpan(start: span.start, end: min(span.end, length), role: span.role)
            }
        }
        lineSpans = clipped
    }

    /// `ranges` (UTF-16 offsets into the source) split into ranges within each display line.
    /// A range over a line break marks the rest of the line; ranges past a cut line's end are dropped.
    func lineMarks(_ ranges: [Range<Int>]) -> [Int: [Range<Int>]] {
        var result: [Int: [Range<Int>]] = [:]
        for range in ranges where !range.isEmpty {
            var line = lineIndex(containing: range.lowerBound)
            while line < lineStarts.count, lineStarts[line] < range.upperBound {
                let start = max(range.lowerBound - lineStarts[line], 0)
                let end = min(range.upperBound - lineStarts[line], lines[line].utf16.count)
                if start < end {
                    result[line, default: []].append(start..<end)
                } else if start == 0, lines[line].isEmpty {
                    // An empty line inside a differing range still counts as marked.
                    result[line, default: []].append(0..<1)
                }
                line += 1
            }
        }
        return result
    }

    private func lineIndex(containing offset: Int) -> Int {
        var low = 0
        var high = lineStarts.count - 1
        var answer = 0
        while low <= high {
            let mid = (low + high) / 2
            if lineStarts[mid] <= offset {
                answer = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return answer
    }

    static func == (lhs: HighlightLabRenderedText, rhs: HighlightLabRenderedText) -> Bool {
        lhs === rhs
    }

    /// Builds the display off the main actor; a 1 MB file has tens of thousands of lines.
    @concurrent
    static func make(source: String, spans: [HighlightLabSpan]?) async -> HighlightLabRenderedText {
        HighlightLabRenderedText(source: source, spans: spans)
    }
}
#endif
