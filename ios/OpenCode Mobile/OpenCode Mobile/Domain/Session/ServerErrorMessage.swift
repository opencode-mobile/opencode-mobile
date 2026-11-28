import Foundation

/// Why a server request failed, read from its HTTP error body. The server's own words stay
/// verbatim (`server`); the cases the app explains in its own words are typed and keyed.
nonisolated enum ServerErrorReason: Equatable, Sendable {
    /// `UnauthorizedError`: the pairing is no longer valid.
    case pairingExpired
    /// `LocationNotFoundError` / `FileNotFoundError`, with the server's path when it sent one.
    case notFound(path: String?)
    /// `SessionBusyError` / `BusyError`: the session is running a turn.
    case sessionBusy
    /// The server's own message, with its error ref when it sent one.
    case server(message: String, ref: String?)

    /// What the app shows, in the reader's language; the server's words stay as they are.
    var text: String {
        switch self {
        case .pairingExpired:
            return String(localized: "server_error.pairing_expired", defaultValue: "Pairing expired, pair again")
        case let .notFound(path):
            guard let path, !path.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
                return String(localized: "server_error.not_found", defaultValue: "Folder or file not found")
            }
            return String(localized: "server_error.not_found_path", defaultValue: "Folder or file not found · \(path)")
        case .sessionBusy:
            return String(localized: "server_error.session_busy", defaultValue: "Session is busy")
        case let .server(message, ref):
            guard let ref, !ref.isEmpty else { return message }
            return String(localized: "server_error.with_ref", defaultValue: "\(message) (\(ref))")
        }
    }
}

/// The server's own error text from an HTTP error body, when it is worth showing a
/// user (e.g. `{"_tag":"FormInvalidAnswerError","message":"Too many selections …"}`).
/// Request-schema failures (`InvalidRequestError`, multi-line decoder paths) are
/// client bugs, not something the user can act on, so they return nil.
nonisolated enum ServerErrorMessage {
    private static let maxLength = 200
    /// The text a busy-session reply maps to; the composer matches it (in the same language)
    /// to queue instead.
    static var sessionBusy: String { ServerErrorReason.sessionBusy.text }

    static func from(_ error: Error) -> String? {
        guard case let .http(_, body)? = error as? SessionError else { return nil }
        return userFacing(body)
    }

    /// Text for a non-2xx JSON body: `reason(_:)` as the app shows it.
    static func userFacing(_ body: String?) -> String? {
        reason(body)?.text
    }

    /// The reason for a non-2xx JSON body. Known tags are typed. Schema failures stay
    /// hidden. Other tags use `fromBody`.
    static func reason(_ body: String?) -> ServerErrorReason? {
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
            return .pairingExpired
        case "LocationNotFoundError", "FileNotFoundError":
            let path = (root["path"] as? String)
                ?? (root["location"] as? String)
                ?? (nested?["path"] as? String)
                ?? (nested?["location"] as? String)
            return .notFound(path: path)
        case "SessionBusyError", "BusyError":
            return .sessionBusy
        case "UnknownError":
            let ref = ((root["ref"] as? String) ?? (nested?["ref"] as? String)).flatMap { $0.isEmpty ? nil : $0 }
            if let message = fromBody(trimmed) { return .server(message: message, ref: ref) }
            if let ref { return .server(message: ref, ref: nil) }
            return nil
        default:
            return fromBody(trimmed).map { .server(message: $0, ref: nil) }
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
