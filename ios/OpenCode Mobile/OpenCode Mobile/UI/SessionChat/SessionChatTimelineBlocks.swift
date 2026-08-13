import Foundation

/// Session Chat presentation units inside one message timeline item.
/// Built from structured parts with hide rules and lightweight tool chrome.
/// Every block carries a stable `blockID` derived from server part IDs.
nonisolated enum SessionChatTimelineBlock: Equatable, Sendable {
    case text(blockID: String, text: String, renderedMarkdown: NativeMarkdownDocument?)
    case attachment(blockID: String, file: FilePart)
    case toolCard(blockID: String, card: SessionChatToolCardBlock)
    case toolGroup(blockID: String, group: SessionChatToolGroupBlock)
    case childSessionRow(blockID: String, row: SessionChatChildSessionRowBlock)

    var blockID: String {
        switch self {
        case .text(let blockID, _, _):
            blockID
        case .attachment(let blockID, _):
            blockID
        case .toolCard(let blockID, _):
            blockID
        case .toolGroup(let blockID, _):
            blockID
        case .childSessionRow(let blockID, _):
            blockID
        }
    }

    static func == (lhs: SessionChatTimelineBlock, rhs: SessionChatTimelineBlock) -> Bool {
        switch (lhs, rhs) {
        case let (.text(lID, lText, lDoc), .text(rID, rText, rDoc)):
            lID == rID
                && lText == rText
                && lDoc?.sourceHash == rDoc?.sourceHash
                && lDoc?.renderTreeVersion == rDoc?.renderTreeVersion
        case let (.attachment(lID, lFile), .attachment(rID, rFile)):
            lID == rID && lFile == rFile
        case let (.toolCard(lID, lCard), .toolCard(rID, rCard)):
            lID == rID && lCard == rCard
        case let (.toolGroup(lID, lGroup), .toolGroup(rID, rGroup)):
            lID == rID && lGroup == rGroup
        case let (.childSessionRow(lID, lRow), .childSessionRow(rID, rRow)):
            lID == rID && lRow == rRow
        default:
            false
        }
    }
}

nonisolated struct SessionChatToolCardBlock: Equatable, Sendable {
    let toolName: String
    let status: ToolStatus
    let title: String
    let subtitle: String?
    let errorText: String?
    let additions: Int?
    let deletions: Int?
    /// Non-nil when the card should open a Bottom Sheet with this body.
    let detailText: String?
    /// Non-nil when tap should open this child session's Session Chat.
    let childSessionId: String?
    /// When true, tap opens owning-turn Changes (optional File Viewer path).
    let opensChangesReview: Bool
    let changesInitialFilePath: String?

    init(
        toolName: String,
        status: ToolStatus,
        title: String,
        subtitle: String? = nil,
        errorText: String? = nil,
        additions: Int? = nil,
        deletions: Int? = nil,
        detailText: String? = nil,
        childSessionId: String? = nil,
        opensChangesReview: Bool = false,
        changesInitialFilePath: String? = nil
    ) {
        self.toolName = toolName
        self.status = status
        self.title = title
        self.subtitle = subtitle
        self.errorText = errorText
        self.additions = additions
        self.deletions = deletions
        self.detailText = detailText
        self.childSessionId = childSessionId
        self.opensChangesReview = opensChangesReview
        self.changesInitialFilePath = changesInitialFilePath
    }
}

nonisolated struct SessionChatToolGroupBlock: Equatable, Sendable {
    let count: Int
    let status: ToolStatus
    let title: String
    let subtitle: String?
    let errorText: String?
}

nonisolated struct SessionChatChildSessionRowBlock: Equatable, Sendable {
    let agentName: String
    let letter: String
    let isBackground: Bool
    let secondLine: String?
    let timeStartEpochMs: Int64?
    let childSessionId: String?
}

nonisolated enum SessionChatTimelineBlocks {
    private static let contextToolNames: Set<String> = ["read", "glob", "grep"]

    static func build(role: MessageRole, parts: [MessagePart]) -> SessionChatTimelineBlocksResult {
        guard !parts.isEmpty else { return .ok([]) }

        let allowToolCards = role == .assistant
        var blocks: [SessionChatTimelineBlock] = []
        var pendingText: [(id: String, text: String)] = []

        func flushText() {
            guard !pendingText.isEmpty else { return }
            let merged = pendingText.map(\.text).joined(separator: "\n")
            let ids = pendingText.map(\.id)
            pendingText.removeAll()
            if !merged.isEmpty {
                blocks.append(
                    .text(
                        blockID: PresentationBlockId.mergedText(ids),
                        text: merged,
                        renderedMarkdown: nil
                    )
                )
            }
        }

        var index = 0
        while index < parts.count {
            switch parts[index] {
            case .text(let textPart):
                if textPart.isDisplayable {
                    let trimmed = textPart.text.trimmingCharacters(in: .whitespacesAndNewlines)
                    if !trimmed.isEmpty {
                        guard let partId = textPart.id else { return .missingPartIds }
                        pendingText.append((id: partId, text: trimmed))
                    }
                }
                index += 1
            case .file(let file):
                flushText()
                blocks.append(
                    .attachment(
                        blockID: PresentationBlockId.direct(file.id),
                        file: file
                    )
                )
                index += 1
            case .compaction:
                index += 1
            case .tool(let tool):
                guard allowToolCards, isVisibleTool(tool) else {
                    index += 1
                    continue
                }
                flushText()
                if isContextTool(tool.toolName) {
                    var groupTools: [ToolPart] = []
                    var lookAhead = index
                    while lookAhead < parts.count {
                        guard case .tool(let candidate) = parts[lookAhead],
                              isVisibleTool(candidate),
                              isContextTool(candidate.toolName)
                        else { break }
                        groupTools.append(candidate)
                        lookAhead += 1
                    }
                    if groupTools.count >= 2 {
                        guard let group = toToolGroup(groupTools) else { return .missingPartIds }
                        blocks.append(group)
                        index = lookAhead
                    } else {
                        guard let block = toToolBlock(tool) else { return .missingPartIds }
                        blocks.append(block)
                        index += 1
                    }
                } else {
                    guard let block = toToolBlock(tool) else { return .missingPartIds }
                    blocks.append(block)
                    index += 1
                }
            }
        }
        flushText()
        return .ok(blocks)
    }

    /// Convenience for call sites that fall back when identity is incomplete.
    /// Empty `parts` also falls back (parity with Android): fixtures and REST
    /// payloads may expose message text without part IDs.
    static func buildOrFallback(
        role: MessageRole,
        parts: [MessagePart],
        messageId: String,
        fallbackText: String
    ) -> [SessionChatTimelineBlock] {
        func fallbackBlocks() -> [SessionChatTimelineBlock] {
            let trimmed = fallbackText.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !trimmed.isEmpty else { return [] }
            return [
                .text(
                    blockID: PresentationBlockId.messageFallback(messageId),
                    text: fallbackText,
                    renderedMarkdown: nil
                ),
            ]
        }

        switch build(role: role, parts: parts) {
        case .ok(let blocks):
            return blocks.isEmpty ? fallbackBlocks() : blocks
        case .missingPartIds:
            return fallbackBlocks()
        }
    }

    /// Rewrites user text blocks that are expanded slash-command / skill
    /// templates into `[/name] args` for Session Chat display.
    static func collapseCommandInvocations(
        role: MessageRole,
        blocks: [SessionChatTimelineBlock],
        commands: [ServerCommand]
    ) -> [SessionChatTimelineBlock] {
        guard role == .user, !commands.isEmpty, !blocks.isEmpty else { return blocks }
        return blocks.map { block in
            guard case .text(let blockID, let text, _) = block,
                  let collapsed = CommandInvocationCollapser.collapse(text: text, commands: commands)
            else { return block }
            return .text(
                blockID: blockID,
                text: collapsed.text,
                renderedMarkdown: nil
            )
        }
    }

    private static func isVisibleTool(_ tool: ToolPart) -> Bool {
        let name = tool.toolName.lowercased()
        if name == "todowrite" { return false }
        if name == "question", tool.status == .pending || tool.status == .running {
            return false
        }
        return true
    }

    private static func isContextTool(_ toolName: String) -> Bool {
        contextToolNames.contains(toolName.lowercased())
    }

    private static func toToolGroup(_ tools: [ToolPart]) -> SessionChatTimelineBlock? {
        let memberIds = tools.compactMap(\.id)
        guard memberIds.count == tools.count else { return nil }
        let status = worstStatus(tools.map(\.status))
        let errorText = tools
            .first(where: { $0.status == .error })?
            .error
            .flatMap { $0.isEmpty ? nil : $0 }
        return .toolGroup(
            blockID: PresentationBlockId.toolGroup(memberIds),
            group: SessionChatToolGroupBlock(
                count: tools.count,
                status: status,
                title: contextGroupTitle(tools),
                subtitle: nil,
                errorText: errorText
            )
        )
    }

    private static func contextGroupTitle(_ tools: [ToolPart]) -> String {
        var counts: [(String, Int)] = [
            ("read", 0),
            ("grep", 0),
            ("glob", 0),
        ]
        for tool in tools {
            let key = tool.toolName.lowercased()
            if let index = counts.firstIndex(where: { $0.0 == key }) {
                counts[index].1 += 1
            }
        }
        return counts
            .filter { $0.1 > 0 }
            .map { name, count in
                let label: String =
                    switch name {
                    case "read": count == 1 ? "read" : "reads"
                    case "grep": count == 1 ? "grep" : "greps"
                    case "glob": count == 1 ? "glob" : "globs"
                    default: name
                    }
                return "\(count) \(label)"
            }
            .joined(separator: " · ")
    }

    private static func worstStatus(_ statuses: [ToolStatus]) -> ToolStatus {
        if statuses.contains(.error) { return .error }
        if statuses.contains(where: { $0 == .running || $0 == .pending }) {
            return .running
        }
        return .completed
    }

    private static func toToolBlock(_ tool: ToolPart) -> SessionChatTimelineBlock? {
        if tool.toolName.lowercased() == "task", tool.status == .pending || tool.status == .running {
            return toChildSessionRow(tool)
        }
        return toToolCard(tool)
    }

    private static func toChildSessionRow(_ tool: ToolPart) -> SessionChatTimelineBlock? {
        guard let partId = tool.id else { return nil }
        let agentName = taskAgentName(tool)
        let secondLine =
            nonEmpty(tool.title)
            ?? inputString(tool.input, keys: "description", "prompt")
        return .childSessionRow(
            blockID: PresentationBlockId.direct(partId),
            row: SessionChatChildSessionRowBlock(
                agentName: agentName,
                letter: SessionChatChildSessionElapsed.letter(from: agentName),
                isBackground: isTruthyMetadata(tool.metadata, key: "background"),
                secondLine: secondLine,
                timeStartEpochMs: tool.timeStartEpochMs,
                childSessionId: metadataString(tool.metadata, keys: "sessionId")
            )
        )
    }

    private static func toToolCard(_ tool: ToolPart) -> SessionChatTimelineBlock? {
        guard let partId = tool.id else { return nil }
        let name = tool.toolName.lowercased()
        let chrome = specializedChrome(name: name, tool: tool) ?? genericChrome(tool)
        let errorText: String? =
            switch tool.status {
            case .error:
                tool.error.flatMap { $0.isEmpty ? nil : $0 }
            case .pending, .running, .completed:
                nil
            }
        return .toolCard(
            blockID: PresentationBlockId.direct(partId),
            card: SessionChatToolCardBlock(
                toolName: tool.toolName,
                status: tool.status,
                title: chrome.title,
                subtitle: chrome.subtitle,
                errorText: errorText,
                additions: chrome.additions,
                deletions: chrome.deletions,
                detailText: chrome.detailText,
                childSessionId: chrome.childSessionId,
                opensChangesReview: chrome.opensChangesReview,
                changesInitialFilePath: chrome.changesInitialFilePath
            )
        )
    }

    private struct Chrome {
        let title: String
        let subtitle: String?
        let additions: Int?
        let deletions: Int?
        let detailText: String?
        let childSessionId: String?
        let opensChangesReview: Bool
        let changesInitialFilePath: String?

        init(
            title: String,
            subtitle: String?,
            additions: Int? = nil,
            deletions: Int? = nil,
            detailText: String? = nil,
            childSessionId: String? = nil,
            opensChangesReview: Bool = false,
            changesInitialFilePath: String? = nil
        ) {
            self.title = title
            self.subtitle = subtitle
            self.additions = additions
            self.deletions = deletions
            self.detailText = detailText
            self.childSessionId = childSessionId
            self.opensChangesReview = opensChangesReview
            self.changesInitialFilePath = changesInitialFilePath
        }
    }

    private static func genericChrome(_ tool: ToolPart) -> Chrome {
        let subtitle: String? =
            switch tool.status {
            case .running:
                tool.title.flatMap { $0.isEmpty ? nil : $0 }
            case .pending, .completed, .error:
                nil
            }
        return Chrome(title: "Called \(tool.toolName)", subtitle: subtitle)
    }

    private static func specializedChrome(name: String, tool: ToolPart) -> Chrome? {
        switch name {
        case "read":
            return contextChrome(tool: tool, displayTitle: "Read", subtitle: pathSubtitle(tool.input))
        case "glob":
            return contextChrome(tool: tool, displayTitle: "Glob", subtitle: patternPathSubtitle(tool.input))
        case "grep":
            return contextChrome(tool: tool, displayTitle: "Grep", subtitle: patternPathSubtitle(tool.input))
        case "bash":
            return shellChrome(tool)
        case "webfetch":
            return networkChrome(tool: tool, displayTitle: "Fetch", subtitleKey: "url")
        case "websearch":
            return networkChrome(tool: tool, displayTitle: "Search", subtitleKey: "query")
        case "question":
            return Chrome(
                title: "Question",
                subtitle: inputString(tool.input, keys: "prompt", "text", "question")
                    ?? runningTitleOverride(tool)
            )
        case "skill":
            return Chrome(
                title: "Skill",
                subtitle: inputString(tool.input, keys: "name", "skill")
                    ?? runningTitleOverride(tool)
            )
        case "task":
            return taskChrome(tool)
        case "edit":
            return fileChangeChrome(tool: tool, displayTitle: "Edit")
        case "write":
            return fileChangeChrome(tool: tool, displayTitle: "Write")
        case "apply_patch":
            return fileChangeChrome(tool: tool, displayTitle: "Patch")
        default:
            return nil
        }
    }

    private static func fileChangeChrome(tool: ToolPart, displayTitle: String) -> Chrome {
        let filesArray: [AnyCodable]? = {
            guard case let .array(array)? = tool.metadata?["files"] else { return nil }
            return array
        }()
        let patchFiles = metadataFiles(tool.metadata)
        if let filesArray, filesArray.count > 1 {
            let additions = positiveCount(patchFiles.reduce(0) { $0 + ($1.additions ?? 0) })
            let deletions = positiveCount(patchFiles.reduce(0) { $0 + ($1.deletions ?? 0) })
            return Chrome(
                title: "Patch",
                subtitle: "\(filesArray.count) files",
                additions: additions,
                deletions: deletions,
                opensChangesReview: canOpenChangesReview(tool.status),
                changesInitialFilePath: nil
            )
        }

        let singlePatchFile = patchFiles.count == 1 ? patchFiles[0] : nil
        let path =
            metadataFilediffString(tool.metadata, key: "file")
            ?? singlePatchFile?.path
            ?? pathSubtitle(tool.input)
        let additions = positiveCount(
            metadataFilediffInt(tool.metadata, key: "additions") ?? singlePatchFile?.additions
        )
        let deletions = positiveCount(
            metadataFilediffInt(tool.metadata, key: "deletions") ?? singlePatchFile?.deletions
        )
        let opens = path != nil && canOpenChangesReview(tool.status)
        return Chrome(
            title: displayTitle,
            subtitle: path,
            additions: path == nil ? nil : additions,
            deletions: path == nil ? nil : deletions,
            opensChangesReview: opens,
            changesInitialFilePath: opens ? path : nil
        )
    }

    private static func canOpenChangesReview(_ status: ToolStatus) -> Bool {
        status == .completed || status == .error
    }

    private struct PatchFileEntry {
        let path: String
        let additions: Int?
        let deletions: Int?
    }

    private static func metadataFiles(_ metadata: [String: AnyCodable]?) -> [PatchFileEntry] {
        guard case let .array(array)? = metadata?["files"] else { return [] }
        return array.compactMap { element in
            guard case let .dictionary(obj) = element else { return nil }
            guard let path = stringFromObject(obj, keys: "filePath", "relativePath", "file") else {
                return nil
            }
            return PatchFileEntry(
                path: path,
                additions: intFromObject(obj, key: "additions"),
                deletions: intFromObject(obj, key: "deletions")
            )
        }
    }

    private static func metadataFilediffString(_ metadata: [String: AnyCodable]?, key: String) -> String? {
        guard case let .dictionary(filediff)? = metadata?["filediff"] else { return nil }
        return stringFromObject(filediff, keys: key)
    }

    private static func metadataFilediffInt(_ metadata: [String: AnyCodable]?, key: String) -> Int? {
        guard case let .dictionary(filediff)? = metadata?["filediff"] else { return nil }
        return intFromObject(filediff, key: key)
    }

    private static func stringFromObject(_ obj: [String: AnyCodable], keys: String...) -> String? {
        for key in keys {
            if let value = obj[key]?.stringValue, !value.isEmpty {
                return value
            }
        }
        return nil
    }

    private static func intFromObject(_ obj: [String: AnyCodable], key: String) -> Int? {
        guard let value = obj[key] else { return nil }
        switch value {
        case .int(let number):
            return number
        case .double(let number):
            return Int(number)
        case .string(let text):
            return Int(text)
        default:
            return nil
        }
    }

    private static func positiveCount(_ value: Int?) -> Int? {
        guard let value, value > 0 else { return nil }
        return value
    }

    private static func taskChrome(_ tool: ToolPart) -> Chrome {
        let title = taskAgentName(tool)
        let description =
            inputString(tool.input, keys: "description", "prompt")
            ?? runningTitleOverride(tool)
        let subtitle: String?
        if let description, isTruthyMetadata(tool.metadata, key: "background") {
            subtitle = "\(description) (background)"
        } else {
            subtitle = description
        }
        return Chrome(
            title: title,
            subtitle: subtitle,
            childSessionId: metadataString(tool.metadata, keys: "sessionId")
        )
    }

    private static func taskAgentName(_ tool: ToolPart) -> String {
        inputString(tool.input, keys: "agent", "name")
            ?? metadataString(tool.metadata, keys: "agent")
            ?? "Task"
    }

    private static func contextChrome(tool: ToolPart, displayTitle: String, subtitle: String?) -> Chrome {
        guard let subtitle else {
            return Chrome(
                title: "Called \(tool.toolName)",
                subtitle: runningTitleOverride(tool)
            )
        }
        return Chrome(
            title: displayTitle,
            subtitle: runningTitleOverride(tool) ?? subtitle
        )
    }

    private static func shellChrome(_ tool: ToolPart) -> Chrome {
        let command = inputString(tool.input, keys: "command") ?? runningTitleOverride(tool)
        let detail = nonEmpty(tool.output) ?? nonEmpty(tool.error)
        return Chrome(title: "Shell", subtitle: command, detailText: detail)
    }

    private static func networkChrome(tool: ToolPart, displayTitle: String, subtitleKey: String) -> Chrome {
        let subtitle = inputString(tool.input, keys: subtitleKey) ?? runningTitleOverride(tool)
        let detail: String? =
            switch tool.status {
            case .completed, .error:
                nonEmpty(tool.output) ?? nonEmpty(tool.error)
            case .pending, .running:
                nil
            }
        return Chrome(title: displayTitle, subtitle: subtitle, detailText: detail)
    }

    private static func runningTitleOverride(_ tool: ToolPart) -> String? {
        switch tool.status {
        case .running:
            tool.title.flatMap { $0.isEmpty ? nil : $0 }
        case .pending, .completed, .error:
            nil
        }
    }

    private static func pathSubtitle(_ input: [String: AnyCodable]?) -> String? {
        inputString(input, keys: "filePath", "path", "file")
    }

    private static func patternPathSubtitle(_ input: [String: AnyCodable]?) -> String? {
        let pattern = inputString(input, keys: "pattern")
        let path = inputString(input, keys: "path", "directory")
        switch (pattern, path) {
        case let (pattern?, path?):
            return "\(pattern) · \(path)"
        case let (pattern?, nil):
            return pattern
        case let (nil, path?):
            return path
        case (nil, nil):
            return nil
        }
    }

    private static func inputString(_ input: [String: AnyCodable]?, keys: String...) -> String? {
        guard let input else { return nil }
        for key in keys {
            if let value = input[key]?.stringValue, !value.isEmpty {
                return value
            }
        }
        return nil
    }

    private static func metadataString(_ metadata: [String: AnyCodable]?, keys: String...) -> String? {
        guard let metadata else { return nil }
        for key in keys {
            if let value = metadata[key]?.stringValue, !value.isEmpty {
                return value
            }
        }
        return nil
    }

    private static func isTruthyMetadata(_ metadata: [String: AnyCodable]?, key: String) -> Bool {
        guard let value = metadata?[key] else { return false }
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

    private static func nonEmpty(_ value: String?) -> String? {
        value.flatMap { $0.isEmpty ? nil : $0 }
    }
}
