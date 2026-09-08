import Foundation

/// v2 tool lifecycle events after `input.started` usually omit `name`. TUI keeps
/// the name on its in-memory tools map. Mobile projects each event into a full
/// `message.part.updated`, so a dummy `"tool"` must never become identity.
enum ToolPartIdentity {
    static func isPlaceholder(_ name: String?) -> Bool {
        let trimmed = name?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return trimmed.isEmpty || trimmed.lowercased() == "tool"
    }

    static func canonical(_ explicit: String?) -> String {
        isPlaceholder(explicit) ? "" : explicit!.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    static func coalesce(incoming: String, existing: String) -> String {
        if isPlaceholder(incoming), !isPlaceholder(existing) { return existing }
        return incoming.isEmpty ? existing : incoming
    }

    static func resolvedName(explicit: String?, input: [String: Any]?) -> String {
        let fromWire = canonical(explicit)
        if !fromWire.isEmpty { return fromWire }
        if hasQuestions(input) { return "question" }
        if hasNonBlank(input, "query") { return "websearch" }
        if hasFilePath(input) { return "read" }
        return ""
    }

    static func resolvedName(explicit: String?, input: [String: AnyCodable]?) -> String {
        let fromWire = canonical(explicit)
        if !fromWire.isEmpty { return fromWire }
        if hasQuestions(input) { return "question" }
        if hasNonBlank(input, "query") { return "websearch" }
        if hasFilePath(input) { return "read" }
        return ""
    }

    static func isQuestion(name: String, input: [String: AnyCodable]?) -> Bool {
        if name.lowercased() == "question" { return true }
        guard case let .array(array)? = input?["questions"] else { return false }
        return !array.isEmpty
    }

    static func calledTitle(_ name: String) -> String {
        isPlaceholder(name) ? "Tool" : "Called \(name)"
    }

    /// TUI never paints a card whose name is still the dummy `"tool"`.
    static func isVisibleOnTimeline(_ tool: ToolPart) -> Bool {
        if tool.toolName.lowercased() == "todowrite" { return false }
        let question = isQuestion(name: tool.toolName, input: tool.input)
        if question, tool.status == .pending || tool.status == .running {
            return false
        }
        if isPlaceholder(resolvedName(explicit: tool.toolName, input: tool.input)), !question { return false }
        return true
    }

    private static func hasQuestions(_ input: [String: Any]?) -> Bool {
        guard let questions = input?["questions"] as? [Any] else { return false }
        return !questions.isEmpty
    }

    private static func hasQuestions(_ input: [String: AnyCodable]?) -> Bool {
        guard case let .array(array)? = input?["questions"] else { return false }
        return !array.isEmpty
    }

    private static func hasFilePath(_ input: [String: Any]?) -> Bool {
        hasNonBlank(input, "filePath") || hasNonBlank(input, "file")
    }

    private static func hasFilePath(_ input: [String: AnyCodable]?) -> Bool {
        hasNonBlank(input, "filePath") || hasNonBlank(input, "file")
    }

    private static func hasNonBlank(_ input: [String: Any]?, _ key: String) -> Bool {
        guard let value = input?[key] as? String else { return false }
        return !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    private static func hasNonBlank(_ input: [String: AnyCodable]?, _ key: String) -> Bool {
        guard case let .string(value)? = input?[key] else { return false }
        return !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }
}
