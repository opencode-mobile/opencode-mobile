import Foundation

/// Compact thought-row diagnostics. Labels match Session Chat chrome
/// (`thinking` / `thought`) plus `hidden` when TUI would drop the part
/// (blank or `[REDACTED]`-only reasoning).
nonisolated enum ThoughtPartChrome {
    static func displayText(_ part: ReasoningPart) -> String {
        part.text
            .replacingOccurrences(of: "[REDACTED]", with: "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    static func isVisible(_ part: ReasoningPart) -> Bool {
        !displayText(part).isEmpty
    }

    static func paintLabel(_ part: ReasoningPart) -> String {
        if !isVisible(part) { return "hidden" }
        return part.isStreaming ? "thinking" : "thought"
    }

    static func summarize(messages: [Message], paintedCount: Int? = nil) -> String {
        let afterLastUser = assistantsAfterLastUser(messages)
        var thinking = 0
        var thought = 0
        var hidden = 0
        var omitted = 0
        var lastPaint = "none"
        var lastChars = 0
        var lastStreaming = false
        var lastHasEnd = false
        for message in afterLastUser {
            var visible = false
            for part in message.parts {
                switch part {
                case let .reasoning(reasoning):
                    let paint = paintLabel(reasoning)
                    lastPaint = paint
                    lastChars = reasoning.text.count
                    lastStreaming = reasoning.isStreaming
                    lastHasEnd = reasoning.timeEndEpochMs != nil
                    switch paint {
                    case "thinking": thinking += 1
                    case "thought": thought += 1
                    default: hidden += 1
                    }
                    if paint != "hidden" { visible = true }
                case let .text(text):
                    if text.isDisplayable, text.isStreaming || !text.text.isEmpty {
                        visible = true
                    }
                case let .tool(tool):
                    if ToolPartIdentity.isVisibleOnTimeline(tool) {
                        visible = true
                    }
                case .file:
                    visible = true
                case .compaction, .notice:
                    break
                }
            }
            if !visible, !isCompaction(message) {
                omitted += 1
            }
        }
        let painted = paintedCount.map(String.init) ?? "-"
        return "thoughtChrome store=\(messages.count) painted=\(painted) " +
            "omitted=\(omitted) thinking=\(thinking) thought=\(thought) hidden=\(hidden) " +
            "last=\(lastPaint) chars=\(lastChars) streaming=\(lastStreaming ? 1 : 0) " +
            "end=\(lastHasEnd ? 1 : 0)"
    }

    private static func assistantsAfterLastUser(_ messages: [Message]) -> [Message] {
        let lastUserIndex = messages.lastIndex(where: { $0.role == .user })
        let after = lastUserIndex.map { Array(messages.dropFirst($0 + 1)) } ?? messages
        return after.filter { $0.role == .assistant }
    }

    private static func isCompaction(_ message: Message) -> Bool {
        message.mode?.caseInsensitiveCompare("compaction") == .orderedSame ||
            message.parts.contains { if case .compaction = $0 { return true }; return false }
    }
}
