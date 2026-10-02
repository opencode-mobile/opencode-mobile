import Foundation

/// Tells a user-initiated stop (Stop, Deny, Dismiss) apart from a real tool failure.
/// v2 types it (`error.type: "aborted"`); v1 only has the error text.
nonisolated enum ToolInterruption {
    static func isAbortedType(_ type: String?) -> Bool {
        type?.lowercased() == "aborted"
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
