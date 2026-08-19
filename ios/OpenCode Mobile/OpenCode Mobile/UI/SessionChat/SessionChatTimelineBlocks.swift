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
    case reasoning(blockID: String, text: String, isStreaming: Bool, durationMs: Int64?)

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
        case .reasoning(let blockID, _, _, _):
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
        case let (.reasoning(lID, lText, lStreaming, lDuration), .reasoning(rID, rText, rStreaming, rDuration)):
            lID == rID && lText == rText && lStreaming == rStreaming && lDuration == rDuration
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
    let members: [SessionChatToolCardBlock]
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

    static func build(
        role: MessageRole,
        parts: [MessagePart],
        collapseConsecutiveToolCalls: Bool = false
    ) -> SessionChatTimelineBlocksResult {
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
            case .reasoning(let reasoning):
                flushText()
                let trimmed = reasoning.text.trimmingCharacters(in: .whitespacesAndNewlines)
                if !trimmed.isEmpty || reasoning.isStreaming {
                    guard let partId = reasoning.id else { return .missingPartIds }
                    blocks.append(
                        .reasoning(
                            blockID: PresentationBlockId.direct(partId),
                            text: trimmed,
                            isStreaming: reasoning.isStreaming,
                            durationMs: Self.thoughtDurationMs(
                                start: reasoning.timeStartEpochMs,
                                end: reasoning.timeEndEpochMs
                            )
                        )
                    )
                }
                index += 1
            case .tool(let tool):
                guard allowToolCards, isVisibleTool(tool) else {
                    index += 1
                    continue
                }
                flushText()
                if isGroupableTool(tool, collapse: collapseConsecutiveToolCalls) {
                    var groupTools: [ToolPart] = []
                    var lookAhead = index
                    while lookAhead < parts.count {
                        let candidatePart = parts[lookAhead]
                        if isTransparentPart(candidatePart) {
                            lookAhead += 1
                            continue
                        }
                        guard case .tool(let candidate) = candidatePart,
                              isGroupableTool(candidate, collapse: collapseConsecutiveToolCalls)
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
        fallbackText: String,
        collapseConsecutiveToolCalls: Bool = false
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

        switch build(role: role, parts: parts, collapseConsecutiveToolCalls: collapseConsecutiveToolCalls) {
        case .ok(let blocks):
            return blocks.isEmpty ? fallbackBlocks() : blocks
        case .missingPartIds:
            return fallbackBlocks()
        }
    }

    /// TUI starts a fragment on `session.text.started` before any delta. Keep a
    /// stable row so an empty streaming assistant does not vanish from the timeline.
    /// Hidden-tool-only messages stay omitted.
    static func ensureLiveRow(
        message: Message,
        isStreaming: Bool,
        blocks: [SessionChatTimelineBlock]
    ) -> [SessionChatTimelineBlock] {
        if !blocks.isEmpty { return blocks }
        let hasTextPart = message.parts.contains { part in
            if case .text = part { return true }
            return false
        }
        guard isStreaming || hasTextPart else { return [] }
        return [
            .text(
                blockID: PresentationBlockId.messageFallback(message.id),
                text: message.text,
                renderedMarkdown: nil
            ),
        ]
    }

    /// When collapse is on, consecutive assistant messages whose visible blocks
    /// are only groupable tools become one tool group. OpenCode emits each
    /// sequential tool step as its own assistant message (`parentID` = user
    /// turn); grouping only inside one message would leave a stack of Shell
    /// cards. Visible text, attachments, and `task` tools still break a run.
    static func mergeConsecutiveToolOnlyAssistantBodies(
        _ bodies: [DisplayMessageBody],
        collapseConsecutiveToolCalls: Bool
    ) -> [DisplayMessageBody] {
        guard collapseConsecutiveToolCalls, bodies.count >= 2 else { return bodies }
        var merged: [DisplayMessageBody] = []
        var index = 0
        while index < bodies.count {
            guard isMergeableToolOnlyAssistant(bodies[index]) else {
                merged.append(bodies[index])
                index += 1
                continue
            }
            var end = index + 1
            while end < bodies.count, isMergeableToolOnlyAssistant(bodies[end]) {
                end += 1
            }
            if end == index + 1 {
                merged.append(bodies[index])
                index += 1
                continue
            }
            let run = bodies[index..<end]
            let parts = run.flatMap(\.message.parts)
            let first = bodies[index]
            let blocks = buildOrFallback(
                role: .assistant,
                parts: parts,
                messageId: first.message.id,
                fallbackText: "",
                collapseConsecutiveToolCalls: true
            )
            merged.append(
                DisplayMessageBody(
                    message: first.message,
                    isStreaming: run.contains(where: \.isStreaming),
                    blocks: blocks,
                    renderedMarkdown: nil
                )
            )
            index = end
        }
        return merged
    }

    private static func isMergeableToolOnlyAssistant(_ body: DisplayMessageBody) -> Bool {
        guard body.message.role == .assistant, !body.blocks.isEmpty else { return false }
        return body.blocks.allSatisfy(isGroupableToolBlock)
    }

    private static func isGroupableToolBlock(_ block: SessionChatTimelineBlock) -> Bool {
        switch block {
        case .toolGroup:
            true
        case .toolCard(_, let card):
            !Self.isChildSessionTool(card.toolName)
        case .text, .attachment, .childSessionRow, .reasoning:
            false
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

    static func thoughtDurationLabel(_ durationMs: Int64?) -> String? {
        guard let duration = durationMs, duration >= 0 else { return nil }
        if duration < 1_000 { return "\(duration)ms" }
        if duration < 60_000 {
            return String(format: "%.1fs", Double(duration) / 1000.0)
        }
        if duration < 3_600_000 {
            let minutes = duration / 60_000
            let seconds = (duration % 60_000) / 1_000
            return "\(minutes)m \(seconds)s"
        }
        let hours = duration / 3_600_000
        let minutes = (duration % 3_600_000) / 60_000
        return "\(hours)h \(minutes)m"
    }

    private static func thoughtDurationMs(start: Int64?, end: Int64?) -> Int64? {
        guard let start, let end else { return nil }
        let duration = end - start
        return duration >= 0 ? duration : nil
    }

    private static func isVisibleTool(_ tool: ToolPart) -> Bool {
        let name = tool.toolName.lowercased()
        if name == "todowrite" { return false }
        if name == "question", tool.status == .pending || tool.status == .running {
            return false
        }
        return true
    }

    /// Parts that never become timeline blocks. Grouping looks through them so
    /// hidden todos, empty text, and compaction markers do not split a visible
    /// tool run (web filters to renderable parts before grouping).
    private static func isTransparentPart(_ part: MessagePart) -> Bool {
        switch part {
        case .compaction:
            true
        case .text(let textPart):
            !textPart.isDisplayable
                || textPart.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        case .tool(let tool):
            !isVisibleTool(tool)
        case .file:
            false
        case .reasoning(let reasoning):
            !reasoning.isStreaming
                && reasoning.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        }
    }

    private static func isContextTool(_ toolName: String) -> Bool {
        contextToolNames.contains(toolName.lowercased())
    }

    /// A visible tool can join a group. Child-session tools (`task` / v2
    /// `subagent`) never group so their child-session tap stays available; when
    /// collapse is on every other visible tool groups, otherwise only context
    /// tools do.
    private static func isGroupableTool(_ tool: ToolPart, collapse: Bool) -> Bool {
        if isChildSessionTool(tool.toolName) { return false }
        if collapse { return true }
        return isContextTool(tool.toolName)
    }

    private static func toToolGroup(_ tools: [ToolPart]) -> SessionChatTimelineBlock? {
        let memberIds = tools.compactMap(\.id)
        guard memberIds.count == tools.count else { return nil }
        let members: [SessionChatToolCardBlock] = tools.compactMap { tool in
            guard case .toolCard(_, let card) = toToolCard(tool) else { return nil }
            return card
        }
        guard members.count == tools.count else { return nil }
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
                title: groupTitle(tools.count),
                subtitle: groupSubtitle(tools),
                errorText: errorText,
                members: members
            )
        )
    }

    /// Count headline for the Tool Call Card (`7 tools`).
    private static func groupTitle(_ count: Int) -> String {
        "\(count) tools"
    }

    /// Family one-liner in encounter order (`Edit · Write · 5 shells`).
    /// A single member of a family is just the verb; multiples get a count.
    private static func groupSubtitle(_ tools: [ToolPart]) -> String {
        var order: [String] = []
        var counts: [String: Int] = [:]
        for tool in tools {
            let key = tool.toolName.lowercased()
            if counts[key] == nil {
                order.append(key)
            }
            counts[key, default: 0] += 1
        }
        return order.map { name in
            familyPhrase(name, count: counts[name, default: 0])
        }.joined(separator: " · ")
    }

    private static func familyPhrase(_ name: String, count: Int) -> String {
        switch name {
        case "read": return count == 1 ? "Read" : "\(count) reads"
        case "grep": return count == 1 ? "Grep" : "\(count) greps"
        case "glob": return count == 1 ? "Glob" : "\(count) globs"
        case "bash", "shell": return count == 1 ? "Shell" : "\(count) shells"
        case "execute": return count == 1 ? "Execute" : "\(count) executes"
        case "lsp": return count == 1 ? "LSP" : "\(count) LSPs"
        case "plan_exit": return count == 1 ? "Plan" : "\(count) plans"
        case "edit": return count == 1 ? "Edit" : "\(count) edits"
        case "write": return count == 1 ? "Write" : "\(count) writes"
        case "webfetch": return count == 1 ? "Fetch" : "\(count) fetches"
        case "websearch": return count == 1 ? "Search" : "\(count) searches"
        case "question": return count == 1 ? "Question" : "\(count) questions"
        case "skill": return count == 1 ? "Skill" : "\(count) skills"
        case "apply_patch": return count == 1 ? "Patch" : "\(count) patches"
        case "linear_get_issue": return count == 1 ? "Get issue" : "\(count) Get issues"
        case "linear_save_issue": return count == 1 ? "Save issue" : "\(count) Save issues"
        default:
            return count == 1 ? name : "\(count) \(name)"
        }
    }

    private static func worstStatus(_ statuses: [ToolStatus]) -> ToolStatus {
        if statuses.contains(.error) { return .error }
        if statuses.contains(where: { $0 == .running || $0 == .pending }) {
            return .running
        }
        return .completed
    }

    private static func toToolBlock(_ tool: ToolPart) -> SessionChatTimelineBlock? {
        if isChildSessionTool(tool.toolName), tool.status != .error {
            return toChildSessionRow(tool)
        }
        return toToolCard(tool)
    }

    private static func toChildSessionRow(_ tool: ToolPart) -> SessionChatTimelineBlock? {
        guard let partId = tool.id else { return nil }
        let agentName = taskAgentName(tool)
        let secondLine =
            inputString(tool.input, keys: "description", "prompt")
            ?? childSessionTitle(tool)
        return .childSessionRow(
            blockID: PresentationBlockId.direct(partId),
            row: SessionChatChildSessionRowBlock(
                agentName: agentName,
                letter: SessionChatChildSessionElapsed.letter(from: agentName),
                isBackground: isTruthyMetadata(tool.metadata, key: "background"),
                secondLine: secondLine,
                timeStartEpochMs: (tool.status == .pending || tool.status == .running)
                    ? tool.timeStartEpochMs
                    : nil,
                childSessionId: childSessionId(tool)
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
        let subtitle = inputPreview(tool.input)
            ?? tool.title.flatMap { title in
                title.isEmpty || title.compare(tool.toolName, options: .caseInsensitive) == .orderedSame
                    ? nil
                    : title
            }
            ?? runningTitleOverride(tool)
        return Chrome(
            title: "Called \(tool.toolName)",
            subtitle: subtitle,
            detailText: settledDetail(tool)
        )
    }

    private static func specializedChrome(name: String, tool: ToolPart) -> Chrome? {
        switch name {
        case "read":
            return contextChrome(tool: tool, displayTitle: "Read", subtitle: pathSubtitle(tool.input))
        case "glob":
            return contextChrome(tool: tool, displayTitle: "Glob", subtitle: patternPathSubtitle(tool.input))
        case "grep":
            return contextChrome(tool: tool, displayTitle: "Grep", subtitle: patternPathSubtitle(tool.input))
        case "bash", "shell":
            return shellChrome(tool)
        case "execute":
            return executeChrome(tool)
        case "lsp":
            return lspChrome(tool)
        case "plan_exit":
            return planExitChrome(tool)
        case "webfetch":
            return networkChrome(tool: tool, displayTitle: "Fetch", subtitleKey: "url")
        case "websearch":
            return networkChrome(tool: tool, displayTitle: "Search", subtitleKey: "query")
        case "question":
            return questionChrome(tool)
        case "skill":
            return Chrome(
                title: "Skill",
                subtitle: inputString(tool.input, keys: "name", "skill")
                    ?? runningTitleOverride(tool)
            )
        case "task", "subagent":
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
            childSessionId: childSessionId(tool)
        )
    }

    private static func taskAgentName(_ tool: ToolPart) -> String {
        inputString(tool.input, keys: "agent", "subagent_type")
            ?? metadataString(tool.metadata, keys: "agent")
            ?? (tool.toolName.compare("subagent", options: .caseInsensitive) == .orderedSame ? "General" : "Task")
    }

    private static func childSessionTitle(_ tool: ToolPart) -> String? {
        guard let title = nonEmpty(tool.title) else { return nil }
        if title.lowercased() == tool.toolName.lowercased() { return nil }
        if isCompositeChildTitle(title) { return nil }
        return title
    }

    private static func isCompositeChildTitle(_ title: String) -> Bool {
        let lower = title.lowercased()
        return lower.contains(" subagent")
            || title.contains(" — ")
            || title.contains(" – ")
    }

    private static func isChildSessionTool(_ toolName: String) -> Bool {
        let name = toolName.lowercased()
        return name == "task" || name == "subagent"
    }

    private static func childSessionId(_ tool: ToolPart) -> String? {
        metadataString(tool.metadata, keys: "sessionId", "sessionID")
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
        return Chrome(title: "Shell", subtitle: command, detailText: settledDetail(tool))
    }

    private static func executeChrome(_ tool: ToolPart) -> Chrome {
        let calls = executeCallPreviews(tool.metadata)
        let subtitle: String? =
            if calls.isEmpty {
                runningTitleOverride(tool) ?? nonEmpty(tool.title)
            } else if calls.count == 1 {
                calls[0]
            } else {
                "\(calls.count) tools"
            }
        var detailParts = calls
        if let extra = settledDetail(tool) {
            detailParts.append(extra)
        }
        return Chrome(
            title: "Execute",
            subtitle: subtitle,
            detailText: detailParts.isEmpty ? nil : detailParts.joined(separator: calls.isEmpty || settledDetail(tool) == nil ? "\n" : "\n\n")
        )
    }

    private static func executeCallPreviews(_ metadata: [String: AnyCodable]?) -> [String] {
        guard case let .array(array)? = metadata?["toolCalls"] else { return [] }
        return array.compactMap { element in
            guard case let .dictionary(obj) = element else { return nil }
            guard let name = scalarString(obj["tool"]), !name.isEmpty else { return nil }
            let input: [String: AnyCodable]? =
                if case let .dictionary(dict)? = obj["input"] { dict } else { nil }
            if let preview = inputPreview(input) { return "\(name) \(preview)" }
            return name
        }
    }

    private static func lspChrome(_ tool: ToolPart) -> Chrome {
        let operation = inputString(tool.input, keys: "operation")
        let path = pathSubtitle(tool.input)
        let line = inputScalar(tool.input, keys: "line")
        let query = inputString(tool.input, keys: "query")
        let location: String? =
            if let path, let line {
                "\(path):\(line)"
            } else {
                path ?? query
            }
        let subtitle: String? =
            if let operation, let location {
                "\(operation) · \(location)"
            } else {
                operation ?? location ?? runningTitleOverride(tool) ?? nonEmpty(tool.title)
            }
        return Chrome(title: "LSP", subtitle: subtitle, detailText: settledDetail(tool))
    }

    private static func planExitChrome(_ tool: ToolPart) -> Chrome {
        Chrome(
            title: "Plan",
            subtitle: nonEmpty(tool.title) ?? "Ready to implement?",
            detailText: settledDetail(tool)
        )
    }

    private static func settledDetail(_ tool: ToolPart) -> String? {
        switch tool.status {
        case .completed, .error:
            nonEmpty(tool.output) ?? nonEmpty(tool.error)
        case .pending, .running:
            nil
        }
    }

    private static func inputPreview(_ input: [String: AnyCodable]?) -> String? {
        if let preferred = inputString(
            input,
            keys: "command", "filePath", "path", "file", "url", "query", "pattern", "name", "description", "prompt", "text", "id"
        ) {
            return preferred
        }
        guard let input else { return nil }
        let parts = input.compactMap { key, value -> String? in
            guard let text = scalarString(value), !text.isEmpty, text.count <= 80 else { return nil }
            return "\(key)=\(text)"
        }
        return parts.isEmpty ? nil : "[\(parts.joined(separator: ", "))]"
    }

    private static func inputScalar(_ input: [String: AnyCodable]?, keys: String...) -> String? {
        guard let input else { return nil }
        for key in keys {
            if let value = scalarString(input[key]), !value.isEmpty {
                return value
            }
        }
        return nil
    }

    private static func scalarString(_ value: AnyCodable?) -> String? {
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

    private static func questionChrome(_ tool: ToolPart) -> Chrome {
        let questions = questionTexts(tool.input)
        let answers = answerTexts(tool.metadata)
        let firstQuestion = questions.first
            ?? inputString(tool.input, keys: "prompt", "text", "question")
            ?? runningTitleOverride(tool)
        let dismissed = tool.status == .error ? nonEmpty(tool.error) : nil
        return Chrome(
            title: firstQuestion ?? "Question",
            subtitle: answers.first,
            detailText: questionDetail(
                questions: questions.isEmpty ? [firstQuestion].compactMap { $0 } : questions,
                answers: answers,
                error: dismissed
            )
        )
    }

    private static func questionTexts(_ input: [String: AnyCodable]?) -> [String] {
        guard case let .array(array)? = input?["questions"] else { return [] }
        return array.compactMap { element in
            guard case let .dictionary(obj) = element else { return nil }
            return stringFromObject(obj, keys: "question")
        }
    }

    private static func answerTexts(_ metadata: [String: AnyCodable]?) -> [String] {
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

    private static func questionDetail(questions: [String], answers: [String], error: String? = nil) -> String? {
        let count = max(questions.count, answers.count)
        let pairs = (0..<count).map { index in
            let question = questions.indices.contains(index) ? questions[index] : ""
            let answer = answers.indices.contains(index) ? answers[index] : ""
            if !question.isEmpty, !answer.isEmpty { return "\(question)\n\(answer)" }
            return question.isEmpty ? answer : question
        }.filter { !$0.isEmpty }
        let parts = pairs + [error].compactMap { $0 }
        return parts.isEmpty ? nil : parts.joined(separator: "\n\n")
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
