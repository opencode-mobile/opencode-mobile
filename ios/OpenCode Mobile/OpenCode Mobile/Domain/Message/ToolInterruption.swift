import Foundation

/// Tells a user-initiated stop (Stop, Deny, Dismiss) apart from a real tool failure.
/// v2 types it (`error.type: "aborted"`); v1 only has the error text.
nonisolated enum ToolInterruption {
    static func isAbortedType(_ type: String?) -> Bool {
        type?.lowercased() == "aborted"
    }

    /// v2 `error.type` of a tool call whose permission request the user denied.
    static func isPermissionRejectedType(_ type: String?) -> Bool {
        type?.lowercased() == "permission.rejected"
    }

    /// A v2 tool `error.type` the user caused (Stop or Deny): shown neutral, not as a failure.
    static func isUserInterruptionType(_ type: String?) -> Bool {
        isAbortedType(type) || isPermissionRejectedType(type)
    }

    /// v2: the decline of a tool failed with `permission.rejected`; its `message` is the note
    /// the user sent with Deny. Nil for any other error type.
    static func v2Decline(type: String?, message: String?) -> ToolDecline? {
        guard isPermissionRejectedType(type) else { return nil }
        return ToolDecline(note: nonBlank(message))
    }

    private static let v1RejectedPrefix = "the user rejected permission"
    private static let v1FeedbackMarker = "with the following feedback:"

    /// v1: the decline of a tool whose error is the server's "The user rejected permission…"
    /// text; a note follows "with the following feedback:". Nil for any other error.
    static func v1Decline(error: String?) -> ToolDecline? {
        guard let text = error?.trimmingCharacters(in: .whitespacesAndNewlines),
              text.lowercased().hasPrefix(v1RejectedPrefix)
        else { return nil }
        guard let marker = text.range(of: v1FeedbackMarker, options: .caseInsensitive) else {
            return ToolDecline(note: nil)
        }
        return ToolDecline(note: nonBlank(String(text[marker.upperBound...])))
    }

    private static func nonBlank(_ text: String?) -> String? {
        guard let trimmed = text?.trimmingCharacters(in: .whitespacesAndNewlines), !trimmed.isEmpty else {
            return nil
        }
        return trimmed
    }

    /// A `session.error` the user caused by stopping the run: v2 `error.type: "aborted"`,
    /// v1 `error.name: "MessageAbortedError"`. Not worth an error toast.
    static func isUserAbortError(type: String?, name: String?) -> Bool {
        isAbortedType(type) || name == "MessageAbortedError"
    }

    private static let v1Prefixes = [
        "tool execution aborted",
        "tool execution interrupted",
        "the user rejected permission",
        "the user declined",
        "the user dismissed",
    ]

    static func isUserInterruption(_ error: String?) -> Bool {
        guard let text = error?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() else { return false }
        return v1Prefixes.contains { text.hasPrefix($0) }
    }
}
