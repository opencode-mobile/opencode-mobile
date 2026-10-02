import Foundation

/// The server's own error text from an HTTP error body, when it is worth showing a
/// user (e.g. `{"_tag":"FormInvalidAnswerError","message":"Too many selections …"}`).
/// Request-schema failures (`InvalidRequestError`, multi-line decoder paths) are
/// client bugs, not something the user can act on, so they return nil.
nonisolated enum ServerErrorMessage {
    private static let maxLength = 200

    static func from(_ error: Error) -> String? {
        guard case let .http(_, body)? = error as? SessionError else { return nil }
        return fromBody(body)
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
