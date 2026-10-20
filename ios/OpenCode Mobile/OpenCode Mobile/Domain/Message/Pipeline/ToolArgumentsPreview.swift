import Foundation

/// Tool arguments still streaming on a v2 server. `tool.input.delta` appends the
/// raw text to `input.preview`; settled arguments (`tool.called`, or a parsable
/// `tool.input.ended`) replace it. The raw text is partial JSON, so values are
/// read leniently: a string still being written counts as its prefix so far.
nonisolated enum ToolArgumentsPreview {
    static let inputKey = "preview"

    /// Raw argument text while a pending or running tool has no settled arguments yet.
    static func raw(_ tool: ToolPart) -> String? {
        guard tool.status == .pending || tool.status == .running,
              let input = tool.input,
              !input.keys.contains(where: { $0 != inputKey })
        else { return nil }
        return input[inputKey]?.stringValue
    }

    static func isReceiving(_ tool: ToolPart) -> Bool {
        raw(tool) != nil
    }

    /// The first non-empty top-level string among `keys` in the streaming arguments.
    static func string(_ tool: ToolPart, keys: String...) -> String? {
        guard let raw = raw(tool) else { return nil }
        let fields = fields(raw)
        for key in keys {
            if let value = fields[key], !value.isEmpty { return value }
        }
        return nil
    }

    /// Store merge: a settled input drops the streaming preview instead of keeping it beside the arguments.
    static func mergedInput(
        _ existing: [String: AnyCodable]?,
        _ incoming: [String: AnyCodable]?
    ) -> [String: AnyCodable]? {
        guard let incoming, !incoming.isEmpty else { return existing }
        guard var merged = existing, !merged.isEmpty else { return incoming }
        if incoming[inputKey] == nil { merged.removeValue(forKey: inputKey) }
        for (key, value) in incoming {
            merged[key] = value
        }
        return merged
    }

    /// Previews grow one delta at a time and are read several times per frame.
    private static let cacheLock = NSLock()
    nonisolated(unsafe) private static var lastParsed: (raw: String, fields: [String: String])?

    private static func fields(_ raw: String) -> [String: String] {
        cacheLock.lock()
        if let lastParsed, lastParsed.raw == raw {
            cacheLock.unlock()
            return lastParsed.fields
        }
        cacheLock.unlock()
        let fields = partialStringFields(raw)
        cacheLock.lock()
        lastParsed = (raw, fields)
        cacheLock.unlock()
        return fields
    }

    /// Top-level string fields of a JSON object that may be cut off anywhere.
    /// Never fails: anything unreadable ends the scan with what was read.
    static func partialStringFields(_ raw: String) -> [String: String] {
        var fields: [String: String] = [:]
        var reader = PartialJSONReader(raw)
        reader.skipWhitespace()
        guard reader.consume(ascii("{")) else { return fields }
        while true {
            reader.skipWhitespace()
            guard let next = reader.peek(), next != ascii("}") else { return fields }
            if reader.consume(ascii(",")) { continue }
            guard next == ascii("\"") else { return fields }
            let key = reader.readString()
            guard key.closed else { return fields }
            reader.skipWhitespace()
            guard reader.consume(ascii(":")) else { return fields }
            reader.skipWhitespace()
            guard let valueStart = reader.peek() else { return fields }
            if valueStart == ascii("\"") {
                let value = reader.readString()
                fields[key.text] = value.text
                guard value.closed else { return fields }
            } else {
                reader.skipValue()
            }
        }
    }

    private struct PartialJSONReader {
        private let units: [UInt16]
        private var index = 0

        init(_ text: String) {
            units = Array(text.utf16)
        }

        func peek() -> UInt16? {
            index < units.count ? units[index] : nil
        }

        mutating func consume(_ unit: UInt16) -> Bool {
            guard peek() == unit else { return false }
            index += 1
            return true
        }

        mutating func skipWhitespace() {
            while let unit = peek(), unit == 0x20 || unit == 0x09 || unit == 0x0A || unit == 0x0D {
                index += 1
            }
        }

        /// Reads a string starting at its opening quote; an unfinished escape is dropped.
        mutating func readString() -> (text: String, closed: Bool) {
            index += 1
            var out: [UInt16] = []
            while index < units.count {
                let unit = units[index]
                if unit == ascii("\"") {
                    index += 1
                    return (String(decoding: out, as: UTF16.self), true)
                }
                guard unit == ascii("\\") else {
                    out.append(unit)
                    index += 1
                    continue
                }
                guard index + 1 < units.count else { break }
                let escaped = units[index + 1]
                if escaped == ascii("u") {
                    guard index + 6 <= units.count,
                          let code = UInt16(String(decoding: units[(index + 2) ..< (index + 6)], as: UTF16.self), radix: 16)
                    else { break }
                    out.append(code)
                    index += 6
                    continue
                }
                switch escaped {
                case ascii("n"): out.append(0x0A)
                case ascii("t"): out.append(0x09)
                case ascii("r"): out.append(0x0D)
                case ascii("b"): out.append(0x08)
                case ascii("f"): out.append(0x0C)
                default: out.append(escaped)
                }
                index += 2
            }
            index = units.count
            return (String(decoding: out, as: UTF16.self), false)
        }

        /// Skips a non-string value up to the `,` or `}` that ends it.
        mutating func skipValue() {
            var depth = 0
            while let unit = peek() {
                switch unit {
                case ascii("\""):
                    _ = readString()
                    continue
                case ascii("{"), ascii("["):
                    depth += 1
                case ascii("}"), ascii("]"):
                    if depth == 0 { return }
                    depth -= 1
                case ascii(","):
                    if depth == 0 { return }
                default:
                    break
                }
                index += 1
            }
        }
    }
}

/// One UTF-16 unit for an ASCII character, for scanning partial JSON.
private nonisolated func ascii(_ scalar: Unicode.Scalar) -> UInt16 {
    UInt16(scalar.value)
}
