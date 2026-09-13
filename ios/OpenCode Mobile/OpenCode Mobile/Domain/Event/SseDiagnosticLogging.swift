import Foundation

enum SseDiagnosticLogging {
    static func parseFailedMessage(_ dataLine: String) -> String {
        let trimmed = dataLine.trimmingCharacters(in: .whitespacesAndNewlines)
        let type = extractType(trimmed) ?? "-"
        return "SSE event parse failed type=\(type) chars=\(trimmed.count)"
    }

    private static func extractType(_ line: String) -> String? {
        guard let typeKey = line.range(of: "\"type\""),
              let colon = line[typeKey.upperBound...].firstIndex(of: ":"),
              let startQuote = line[line.index(after: colon)...].firstIndex(of: "\""),
              let endQuote = line[line.index(after: startQuote)...].firstIndex(of: "\"")
        else { return nil }
        return String(line[line.index(after: startQuote)..<endQuote])
    }

    static func bufferMessage(for event: OpencodeEvent) -> String? {
        switch event.type {
        case "server.heartbeat",
             "message.part.delta",
             "message.part.updated",
             "message.updated",
             "file.watcher.updated",
             "file.edited",
             "session.diff",
             "session.updated":
            return nil
        case "session.status":
            let status =
                event.rawProperties?["status"]?.dictionaryValue?["type"]?.stringValue ?? "-"
            return "SSE event type=session.status session=\(event.sessionID ?? "-") status=\(status)"
        case "session.error":
            let error = event.sessionErrorMessage ?? "-"
            return "SSE event type=session.error session=\(event.sessionID ?? "-") error=\(error)"
        default:
            return "SSE event type=\(event.type) session=\(event.sessionID ?? "-")"
        }
    }
}
