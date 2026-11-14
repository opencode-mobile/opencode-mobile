import Foundation

// Typed reads of a tool part's wire `input` and `metadata`. The raw values stay on `ToolPart`
// (the session cache and both protocol adapters store them); feature UI reads these accessors
// and never indexes the values by key.
//
// Accessors keep the exact rule each timeline surface used before they moved here, so every
// tool renders as it did. Strings count only when they are non-empty JSON strings.

/// One file a patch or edit changed, from `metadata.files`.
nonisolated struct ToolPatchFile: Equatable, Sendable {
    let path: String
    let additions: Int?
    let deletions: Int?
}

/// What the tool result sheet reads from a call.
nonisolated struct ToolResultFields: Equatable, Sendable {
    let command: String?
    let path: String?
    let pattern: String?
    let searchPath: String?
    let offset: Int?
    let limit: Int?
    let content: String?
    let exitCode: Int?
    let oldString: String?
    let newString: String?
    /// Unified diff from `metadata.filediff.patch`, else the first `metadata.files[].patch`.
    let metadataPatch: String?
}

nonisolated extension ToolPart {
    // MARK: Input

    /// Search pattern (glob, grep).
    var inputPattern: String? { Self.string(input, "pattern") }

    /// Directory a search or listing runs in.
    var inputSearchPath: String? { Self.string(input, "path", "directory") }

    /// Directory a listing runs in, when only `directory` names it.
    var inputDirectory: String? { Self.string(input, "directory") }

    /// Web search or LSP query.
    var inputQuery: String? { Self.string(input, "query") }

    /// Fetched URL.
    var inputURL: String? { Self.string(input, "url") }

    /// Shell command.
    var inputCommand: String? { Self.string(input, "command") }

    /// Title the model gave the call.
    var inputTitle: String? { Self.string(input, "title") }

    /// Description the model gave the call.
    var inputDescription: String? { Self.string(input, "description") }

    /// A subagent task's brief: its description, else its prompt.
    var inputTaskBrief: String? { Self.string(input, "description", "prompt") }

    /// A single question asked without the `questions` list.
    var inputQuestionPrompt: String? { Self.string(input, "prompt", "text", "question") }

    /// Skill the model asked to load.
    var inputSkillID: String? { Self.string(input, "id", "name", "skill") }

    /// Subagent the task asked for.
    var inputAgent: String? { Self.string(input, "agent", "subagent_type") }

    /// Tool an `invalid` call asked for.
    var inputRequestedTool: String? { Self.string(input, "tool") }

    /// LSP operation.
    var inputOperation: String? { Self.string(input, "operation") }

    /// LSP line; a number or flag reads as its text.
    var inputLine: String? { Self.scalar(input?["line"]).flatMap { $0.isEmpty ? nil : $0 } }

    /// File an edit, write or LSP call targets (`filePath`, then `path`, then `file`).
    var inputTargetPath: String? { Self.string(input, "filePath", "path", "file") }

    /// File a read opens (`filePath`, then `file`, then `path`): v1 sends `filePath`, v2 `path`.
    var inputReadPath: String? { Self.string(input, "filePath", "file", "path") }

    /// Body a write puts in the file.
    var writeContent: String? { input?["content"]?.stringValue }

    /// One-line preview of the call's arguments, for tools without their own chrome.
    var inputPreview: String? { Self.preview(input) }

    /// Questions a question tool asked, in order.
    var inputQuestions: [String] {
        guard case let .array(array)? = input?["questions"] else { return [] }
        return array.compactMap { element in
            guard case let .dictionary(obj) = element else { return nil }
            return Self.string(obj, "question")
        }
    }

    // MARK: Metadata

    /// Answers a question tool reports; a multi-select answer joins its choices with commas.
    var questionAnswers: [String] {
        guard case let .array(array)? = metadata?["answers"] else { return [] }
        return array.compactMap { element in
            switch element {
            case let .array(values):
                let joined = values.compactMap(\.stringValue).filter { !$0.isEmpty }.joined(separator: ", ")
                return joined.isEmpty ? nil : joined
            case let .string(text) where !text.isEmpty:
                return text
            default:
                return nil
            }
        }
    }

    /// Agent the server says ran the task.
    var metadataAgent: String? { Self.string(metadata, "agent") }

    /// Name of the loaded skill.
    var skillName: String? { Self.string(metadata, "name") }

    /// Directory the loaded skill lives in.
    var skillDirectory: String? { Self.string(metadata, "directory") }

    /// Child session a subagent task runs in.
    var childSessionID: String? { Self.string(metadata, "sessionId", "sessionID") }

    /// The task runs in the background (`metadata.background` true or `"1"`).
    var isBackground: Bool { Self.isTruthy(metadata, "background") }

    /// A write replaced a file that already existed.
    var fileExisted: Bool { Self.isTruthy(metadata, "exists") }

    /// How many child tool calls a task reports in `metadata.summary`, or nil without a list.
    var summaryCallCount: Int? {
        guard case let .array(summary)? = metadata?["summary"] else { return nil }
        return summary.count
    }

    /// Results a grep or glob reports (`matches`, else `count`).
    var searchResultCount: Int? {
        guard let raw = metadata?["matches"] ?? metadata?["count"] else { return nil }
        return Self.int(raw)
    }

    /// Shell exit code.
    var exitCode: Int? { metadata?["exit"].flatMap(Self.int) }

    /// Size of `metadata.files`, counting every entry, or nil when there is no list.
    var patchFileCount: Int? {
        guard case let .array(array)? = metadata?["files"] else { return nil }
        return array.count
    }

    /// Files in `metadata.files` that name a path.
    var patchFiles: [ToolPatchFile] {
        guard case let .array(array)? = metadata?["files"] else { return [] }
        return array.compactMap { element in
            guard case let .dictionary(obj) = element,
                  let path = Self.string(obj, "filePath", "relativePath", "file")
            else { return nil }
            return ToolPatchFile(path: path, additions: obj["additions"].flatMap(Self.int), deletions: obj["deletions"].flatMap(Self.int))
        }
    }

    /// File named by `metadata.filediff`.
    var filediffPath: String? { Self.string(filediff, "file") }

    /// Lines `metadata.filediff` adds.
    var filediffAdditions: Int? { filediff?["additions"].flatMap(Self.int) }

    /// Lines `metadata.filediff` deletes.
    var filediffDeletions: Int? { filediff?["deletions"].flatMap(Self.int) }

    /// Calls a batch execute ran (`metadata.toolCalls`), each as `name preview`.
    var executeCallPreviews: [String] {
        guard case let .array(array)? = metadata?["toolCalls"] else { return [] }
        return array.compactMap { element in
            guard case let .dictionary(obj) = element else { return nil }
            guard let name = Self.scalar(obj["tool"]), !name.isEmpty else { return nil }
            let input: [String: AnyCodable]? =
                if case let .dictionary(dict)? = obj["input"] { dict } else { nil }
            if let preview = Self.preview(input) { return "\(name) \(preview)" }
            return name
        }
    }

    // MARK: Tool result sheet

    var resultFields: ToolResultFields {
        ToolResultFields(
            command: inputCommand,
            path: inputReadPath,
            pattern: inputPattern,
            searchPath: inputSearchPath,
            offset: input?["offset"].flatMap(Self.int),
            limit: input?["limit"].flatMap(Self.int),
            content: Self.string(input, "content"),
            exitCode: exitCode,
            oldString: Self.string(input, "oldString"),
            newString: Self.string(input, "newString"),
            metadataPatch: metadataPatch
        )
    }

    private var metadataPatch: String? {
        if let patch = Self.string(filediff, "patch") { return patch }
        if case let .array(files)? = metadata?["files"] {
            for element in files {
                guard case let .dictionary(object) = element else { continue }
                if let patch = Self.string(object, "patch") { return patch }
            }
        }
        return nil
    }

    // MARK: Child session linking

    /// Child session id the part's metadata names (`sessionID`, then `sessionId`).
    var linkedChildSessionID: String? { Self.string(metadata, "sessionID") ?? Self.string(metadata, "sessionId") }

    /// Session id the call's input names.
    var inputSessionID: String? { Self.string(input, "sessionID") }

    /// Description, for matching a task to its child session.
    var linkDescription: String? { Self.string(input, "description") }

    /// This part with `id` recorded as its child session, keeping its other metadata.
    func withChildSessionID(_ id: String) -> ToolPart {
        withMetadataValue(.string(id), forKey: "sessionID")
    }

    // MARK: Background work panel

    /// A subagent task's description.
    var subagentDescription: String? { Self.string(input, "description") }

    /// Agent a subagent task names: input `agent`, then `subagent_type`, then metadata `agent`.
    var subagentAgentName: String? {
        Self.string(input, "agent") ?? Self.string(input, "subagent_type") ?? Self.string(metadata, "agent")
    }

    /// Child session a subagent task runs in (`sessionID`, then `sessionId`).
    var subagentSessionID: String? { Self.string(metadata, "sessionID") ?? Self.string(metadata, "sessionId") }

    /// Child tool calls a task part reports in `metadata.summary` (classic v1 up to 1.1.x sends
    /// the child's tool parts there; later v1 and v2 send none), as tool parts. Nil when the
    /// part carries no summary list.
    var summaryCalls: [ToolPart]? {
        guard case let .array(summary)? = metadata?["summary"] else { return nil }
        return summary.enumerated().compactMap { index, element -> ToolPart? in
            guard case let .dictionary(part) = element, let name = Self.string(part, "tool") else { return nil }
            var state: [String: AnyCodable]?
            if case let .dictionary(value)? = part["state"] { state = value }
            var input: [String: AnyCodable]?
            if case let .dictionary(value)? = state?["input"] { input = value }
            let status: ToolStatus =
                switch Self.string(state, "status") {
                case "pending": .pending
                case "running": .running
                case "error": .error
                default: .completed
                }
            return ToolPart(
                id: Self.string(part, "id") ?? "summary-\(index)",
                toolName: name,
                status: status,
                title: Self.string(state, "title"),
                input: input
            )
        }
    }

    // MARK: Readers

    private var filediff: [String: AnyCodable]? {
        guard case let .dictionary(filediff)? = metadata?["filediff"] else { return nil }
        return filediff
    }

    /// First non-empty JSON string under `keys`.
    private static func string(_ values: [String: AnyCodable]?, _ keys: String...) -> String? {
        guard let values else { return nil }
        for key in keys {
            if let value = values[key]?.stringValue, !value.isEmpty {
                return value
            }
        }
        return nil
    }

    private static func int(_ value: AnyCodable) -> Int? {
        switch value {
        case .int(let number): number
        case .double(let number): Int(number)
        case .string(let text): Int(text)
        default: nil
        }
    }

    private static func scalar(_ value: AnyCodable?) -> String? {
        switch value {
        case let .string(text) where !text.isEmpty:
            text
        case let .int(number):
            String(number)
        case let .double(number):
            String(number)
        case let .bool(flag):
            String(flag)
        default:
            nil
        }
    }

    private static func isTruthy(_ values: [String: AnyCodable]?, _ key: String) -> Bool {
        guard let value = values?[key] else { return false }
        switch value {
        case .bool(let flag):
            return flag
        case .string(let text):
            let normalized = text.lowercased()
            return normalized == "true" || normalized == "1"
        default:
            return false
        }
    }

    private static func preview(_ input: [String: AnyCodable]?) -> String? {
        if let preferred = string(
            input,
            "command", "filePath", "path", "file", "url", "query", "pattern", "name", "title", "description", "prompt",
            "text", "id"
        ) {
            return preferred
        }
        guard let input else { return nil }
        let parts = input.compactMap { key, value -> String? in
            guard key != ToolArgumentsPreview.inputKey, let text = scalar(value), !text.isEmpty, text.count <= 80 else { return nil }
            return "\(key)=\(text)"
        }
        return parts.isEmpty ? nil : "[\(parts.joined(separator: ", "))]"
    }
}
