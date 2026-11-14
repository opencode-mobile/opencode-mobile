import Foundation

/// A run failure's message text as the server sends it.
public nonisolated enum SessionFailureMessage {
    /// Upstream `unwrapErrorMessage`: a JSON envelope `{type: "message", message}` is the text inside it.
    public static func unwrap(_ raw: String?) -> String {
        let trimmed = raw?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        guard trimmed.hasPrefix("{"),
              let data = trimmed.data(using: .utf8),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              (root["type"] as? String) == "message",
              let nested = (root["message"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines),
              !nested.isEmpty
        else { return trimmed }
        return unwrap(nested)
    }
}
