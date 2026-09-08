import Foundation

nonisolated struct JSONStreamWriter {
    private let handle: FileHandle
    private var frames: [Frame] = [Frame()]

    private struct Frame {
        var needsComma = false
        var afterKey = false
    }

    init(fileURL: URL) throws {
        FileManager.default.createFile(atPath: fileURL.path, contents: nil)
        handle = try FileHandle(forWritingTo: fileURL)
    }

    func close() throws {
        try handle.close()
    }

    mutating func beginObject() throws {
        try writeSeparator()
        try writeRaw("{")
        frames.append(Frame())
    }

    mutating func endObject() throws {
        _ = frames.popLast()
        try writeRaw("}")
        markValueWritten()
    }

    mutating func beginArray() throws {
        try writeSeparator()
        try writeRaw("[")
        frames.append(Frame())
    }

    mutating func endArray() throws {
        _ = frames.popLast()
        try writeRaw("]")
        markValueWritten()
    }

    mutating func key(_ name: String) throws {
        try writeSeparator()
        try writeRaw(Self.escape(name))
        try writeRaw(":")
        frames[frames.count - 1].afterKey = true
        frames[frames.count - 1].needsComma = false
    }

    mutating func writeString(_ value: String) throws {
        try writeSeparator()
        try writeRaw(Self.escape(value))
        markValueWritten()
    }

    mutating func writeInt(_ value: Int) throws {
        try writeSeparator()
        try writeRaw(String(value))
        markValueWritten()
    }

    mutating func writeDataURL(mime: String, fileURL: URL) throws {
        try writeSeparator()
        try writeRaw("\"data:")
        try writeRaw(mime.lowercased())
        try writeRaw(";base64,")
        try writeBase64(from: fileURL)
        try writeRaw("\"")
        markValueWritten()
    }

    mutating func writeDataURLString(_ value: String) throws {
        try writeString(value)
    }

    private mutating func writeSeparator() throws {
        var frame = frames[frames.count - 1]
        if frame.afterKey {
            frame.afterKey = false
            frames[frames.count - 1] = frame
            return
        }
        if frame.needsComma {
            try writeRaw(",")
        }
    }

    private mutating func markValueWritten() {
        var frame = frames[frames.count - 1]
        frame.needsComma = true
        frame.afterKey = false
        frames[frames.count - 1] = frame
    }

    private func writeRaw(_ string: String) throws {
        try handle.write(contentsOf: Data(string.utf8))
    }

    private func writeBase64(from fileURL: URL) throws {
        let input = try FileHandle(forReadingFrom: fileURL)
        defer { try? input.close() }
        var leftover = Data()
        while true {
            let chunk = try input.read(upToCount: 24 * 1024) ?? Data()
            if chunk.isEmpty {
                if !leftover.isEmpty {
                    try handle.write(contentsOf: Data(leftover.base64EncodedString().utf8))
                }
                return
            }
            var combined = leftover + chunk
            leftover = Data()
            let remainder = combined.count % 3
            if remainder != 0 {
                leftover = Data(combined.suffix(remainder))
                combined = Data(combined.dropLast(remainder))
            }
            if !combined.isEmpty {
                try handle.write(contentsOf: Data(combined.base64EncodedString().utf8))
            }
        }
    }

    static func escape(_ value: String) -> String {
        var output = "\""
        for scalar in value.unicodeScalars {
            switch scalar {
            case "\"": output += "\\\""
            case "\\": output += "\\\\"
            case "\n": output += "\\n"
            case "\r": output += "\\r"
            case "\t": output += "\\t"
            default:
                if scalar.value < 0x20 {
                    output += String(format: "\\u%04x", scalar.value)
                } else {
                    output.unicodeScalars.append(scalar)
                }
            }
        }
        output += "\""
        return output
    }
}
