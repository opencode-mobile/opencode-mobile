import Foundation

nonisolated enum TerminalWsFrame: Equatable, Sendable {
    case output(String)
    case cursor(String)
    case ignored
}

enum TerminalWsFrameParser {
    static func parseText(_ text: String) -> TerminalWsFrame {
        .output(text)
    }

    static func parseBinary(_ data: Data) -> TerminalWsFrame {
        guard let first = data.first, first == 0 else { return .ignored }
        let jsonData = data.dropFirst()
        guard
            let object = try? JSONSerialization.jsonObject(with: jsonData) as? [String: Any]
        else {
            return .ignored
        }
        let cursor: Int?
        if let value = object["cursor"] as? Int {
            cursor = value
        } else if let number = object["cursor"] as? NSNumber {
            cursor = number.intValue
        } else {
            cursor = nil
        }
        guard let cursor, cursor >= 0 else { return .ignored }
        return .cursor(String(cursor))
    }
}
