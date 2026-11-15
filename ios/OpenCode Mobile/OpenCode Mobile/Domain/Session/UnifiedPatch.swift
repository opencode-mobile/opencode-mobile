import Foundation

/// The patch text Changes keeps for one file. A server patch longer than `previewBytes` is cut
/// at its last line end before the limit; a patch built from whole `before`/`after` contents is
/// written once, straight into a string of its final size, and stops at the same limit.
nonisolated enum UnifiedPatch {
    /// UTF-8 bytes of one file's patch that Changes keeps and shows (1 MB, as the File Viewer).
    static let previewBytes = 1024 * 1024

    struct Preview: Equatable, Sendable {
        let text: String
        let isTruncated: Bool
    }

    /// `patch` as sent, or its first whole lines up to `limit` UTF-8 bytes.
    static func preview(_ patch: String, limit: Int = previewBytes) -> Preview {
        let utf8 = patch.utf8
        guard utf8.count > limit else { return Preview(text: patch, isTruncated: false) }
        let end = utf8.index(utf8.startIndex, offsetBy: limit)
        guard let newline = utf8[..<end].lastIndex(of: 0x0A) else { return Preview(text: "", isTruncated: true) }
        var cut = newline
        // `\r\n` is one line end, as the diff parser splits it.
        if cut > utf8.startIndex, utf8[utf8.index(before: cut)] == 0x0D { cut = utf8.index(before: cut) }
        return Preview(text: String(decoding: utf8[..<cut], as: UTF8.self), isTruncated: true)
    }

    /// One hunk that deletes every line of `before` and adds every line of `after`, or nil when
    /// either is missing or both are equal.
    static func fromContents(before: String?, after: String?, limit: Int = previewBytes) -> Preview? {
        guard let before, let after, before != after else { return nil }
        return build(before: before.utf8, after: after.utf8, limit: limit)
    }

    /// Lines end at `\n`, but `\r\n` stays inside its line, as `String.split(separator: "\n")`
    /// treats it. Each input is read once to count its lines and once to copy them; nothing is
    /// split or joined.
    static func build<Bytes: BidirectionalCollection>(before: Bytes, after: Bytes, limit: Int) -> Preview
        where Bytes.Element == UInt8
    {
        let oldLines = lineCount(before)
        let newLines = lineCount(after)
        let header = Array("@@ -\(oldLines == 0 ? 0 : 1),\(oldLines) +\(newLines == 0 ? 0 : 1),\(newLines) @@".utf8)
        let fullLength = header.count
            + (oldLines == 0 ? 0 : before.count + 1 + oldLines)
            + (newLines == 0 ? 0 : after.count + 1 + newLines)
        let capacity = max(header.count, min(fullLength, limit))
        var complete = true
        let text = String(unsafeUninitializedCapacity: capacity) { buffer in
            var count = 0
            for byte in header {
                buffer[count] = byte
                count += 1
            }
            complete = appendLines(before, lines: oldLines, marker: 0x2D, into: buffer, count: &count)
                && appendLines(after, lines: newLines, marker: 0x2B, into: buffer, count: &count)
            return count
        }
        return Preview(text: text, isTruncated: !complete)
    }

    private static func lineCount<Bytes: BidirectionalCollection>(_ bytes: Bytes) -> Int where Bytes.Element == UInt8 {
        guard !bytes.isEmpty else { return 0 }
        var count = 1
        var previous: UInt8 = 0
        for byte in bytes {
            if byte == 0x0A, previous != 0x0D { count += 1 }
            previous = byte
        }
        return count
    }

    /// Writes `\n<marker><line>` per line; false once the next line would not fit in `buffer`.
    private static func appendLines<Bytes: BidirectionalCollection>(
        _ bytes: Bytes,
        lines: Int,
        marker: UInt8,
        into buffer: UnsafeMutableBufferPointer<UInt8>,
        count: inout Int
    ) -> Bool where Bytes.Element == UInt8 {
        guard lines > 0 else { return true }
        var line: [UInt8] = []
        var previous: UInt8 = 0
        func flush() -> Bool {
            guard count + 2 + line.count <= buffer.count else { return false }
            buffer[count] = 0x0A
            buffer[count + 1] = marker
            count += 2
            for byte in line {
                buffer[count] = byte
                count += 1
            }
            line.removeAll(keepingCapacity: true)
            return true
        }
        for byte in bytes {
            if byte == 0x0A, previous != 0x0D {
                guard flush() else { return false }
            } else {
                line.append(byte)
            }
            previous = byte
        }
        return flush()
    }
}
