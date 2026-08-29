import Foundation

enum SseDiagnosticLogging {
    static func bufferMessage(for event: OpencodeEvent) -> String? {
        switch event.type {
        case "server.heartbeat",
             "message.part.delta",
             "message.part.updated",
             "message.updated",
             "file.watcher.updated",
             "file.edited",
             "session.diff",
             "session.updated",
             "session.active.snapshot":
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
