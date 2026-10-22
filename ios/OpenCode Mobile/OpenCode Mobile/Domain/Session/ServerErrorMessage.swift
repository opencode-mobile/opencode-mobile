import Foundation

/// The server's own error text from an HTTP error body, when it is worth showing a
/// user (e.g. `{"_tag":"FormInvalidAnswerError","message":"Too many selections …"}`).
/// Request-schema failures (`InvalidRequestError`, multi-line decoder paths) are
/// client bugs, not something the user can act on, so they return nil.
nonisolated enum ServerErrorMessage {
    private static let maxLength = 200

    static func from(_ error: Error) -> String? {
        guard case let .http(_, body)? = error as? SessionError else { return nil }
        return userFacing(body)
    }

    /// Copy for a non-2xx JSON body. Known tags get a fixed sentence. Schema
    /// failures stay hidden. Other tags use `fromBody`.
    static func userFacing(_ body: String?) -> String? {
        let trimmed = body?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        guard trimmed.hasPrefix("{"),
              let data = trimmed.data(using: .utf8),
              let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
        else { return nil }
        let tag = (root["_tag"] as? String) ?? (root["name"] as? String)
        let nested = root["data"] as? [String: Any]
        switch tag {
        case "InvalidRequestError":
            return nil
        case "UnauthorizedError":
            return "Pairing expired, pair again"
        case "LocationNotFoundError", "FileNotFoundError":
            let path = (root["path"] as? String)
                ?? (root["location"] as? String)
                ?? (nested?["path"] as? String)
                ?? (nested?["location"] as? String)
            if let path, !path.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                return "Folder or file not found · \(path)"
            }
            return "Folder or file not found"
        case "SessionBusyError", "BusyError":
            return "Session is busy"
        case "UnknownError":
            let ref = (root["ref"] as? String) ?? (nested?["ref"] as? String)
            let message = fromBody(trimmed)
            if let message, let ref, !ref.isEmpty { return "\(message) (\(ref))" }
            if let message { return message }
            if let ref, !ref.isEmpty { return ref }
            return nil
        default:
            return fromBody(trimmed)
        }
    }

    /// `message` (or `data.message`) of a JSON error body, trimmed; nil when there is none.
    /// Unlike `fromBody` it keeps schema errors and long text: the caller asked for the
    /// server's words as they are.
    static func bodyMessage(_ body: String?) -> String? {
        let trimmed = body?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        guard trimmed.hasPrefix("{"),
              let data = trimmed.data(using: .utf8),
              let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
        else { return nil }
        let nested = root["data"] as? [String: Any]
        let message = ((root["message"] as? String) ?? (nested?["message"] as? String))?
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard let message, !message.isEmpty else { return nil }
        return message
    }

    static func fromBody(_ body: String?) -> String? {
        let trimmed = body?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        guard trimmed.hasPrefix("{"),
              let data = trimmed.data(using: .utf8),
              let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
        else { return nil }
        let tag = (root["_tag"] as? String) ?? (root["name"] as? String)
        if tag == "InvalidRequestError" || (root["kind"] as? String) == "Payload" { return nil }
        let message = (root["message"] as? String)
            ?? ((root["data"] as? [String: Any])?["message"] as? String)
            ?? ((root["error"] as? [String: Any])?["message"] as? String)
        var text = message?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        while text.hasSuffix(".") { text.removeLast() }
        guard !text.isEmpty, !text.contains("\n"), text.count <= maxLength else { return nil }
        return text
    }
}
