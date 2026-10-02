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
    /// Chip timeline (OPE-260): narration plus the tools that followed it.
    case stepParagraph(blockID: String, paragraph: SessionChatStepParagraphBlock)

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
        case .stepParagraph(let blockID, _):
            blockID
        }
    }

    /// Text-bearing blocks: a `.text` block or a step paragraph with narration.
    /// Used wherever "the sole text block" decides markdown attach and copy text.
    /// Identity for view diffing: a step paragraph's stable paragraph id (its
    /// block id changes as tools stream in); every other block's block id.
    var rowIdentity: String {
        if case .stepParagraph(_, let paragraph) = self { return paragraph.id }
        return blockID
    }

    var textContent: (blockID: String, text: String, renderedMarkdown: NativeMarkdownDocument?)? {
        switch self {
        case .text(let blockID, let text, let markdown):
            return (blockID, text, markdown)
        case .stepParagraph(_, let paragraph):
            guard let textBlockID = paragraph.textBlockID, let text = paragraph.text else { return nil }
            return (textBlockID, text, paragraph.renderedMarkdown)
        case .attachment, .toolCard, .toolGroup, .childSessionRow, .reasoning:
            return nil
        }
    }

    /// Same block with `renderedMarkdown` replaced; no-op for blocks without text.
    func withRenderedMarkdown(_ document: NativeMarkdownDocument?) -> SessionChatTimelineBlock {
        switch self {
        case .text(let blockID, let text, _):
            return .text(blockID: blockID, text: text, renderedMarkdown: document)
        case .stepParagraph(let blockID, let paragraph):
            return .stepParagraph(blockID: blockID, paragraph: paragraph.withRenderedMarkdown(document))
        case .attachment, .toolCard, .toolGroup, .childSessionRow, .reasoning:
            return self
        }
    }

    /// False for blocks the timeline paints as nothing (a live thought is footer
    /// chrome). Rows must not render empty: see AssistantMessageContent fallback.
    var paintsInTimeline: Bool {
        if case .reasoning(_, _, let isStreaming, _) = self { return !isStreaming }
        return true
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
        case let (.stepParagraph(lID, lParagraph), .stepParagraph(rID, rParagraph)):
            lID == rID && lParagraph == rParagraph
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
    /// True when `detailText` is markdown (a compaction summary), not tool output.
    let detailIsMarkdown: Bool
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
        detailIsMarkdown: Bool = false,
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
        self.detailIsMarkdown = detailIsMarkdown
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

nonisolated enum SessionChatToolChipKind: String, Sendable {
    case file
    case command
    case agent
    case skill
}

/// Truncation family; declaration order is the chip group order (explore
/// summary, changes, commands, agents, skills) so a new chip only appends at
/// the end of its group.
nonisolated enum SessionChatToolChipFamily: String, Sendable, CaseIterable {
    case explore
    case change
    case command
    case agent
    case skill

    /// Completed chips shown before `+N more`; nil never truncates. Attention
    /// chips (failed, running, pending) never count toward the limit. Explore
    /// tools fold into the paragraph's summary chip, so an individual Explore
    /// chip is a lone one or a failed one and never hides.
    var visibleLimit: Int? {
        switch self {
        case .change: 3
        case .command: 3
        case .explore: nil
        case .agent: nil
        case .skill: nil
        }
    }
}

/// The explore tools of a step paragraph as one chip: `Explored · 4 reads · 2
/// greps`, or `Exploring · …` while any member is still running. Tapping it
/// opens the tool group sheet with `title`, `subtitle` and `members`.
nonisolated struct SessionChatExploreSummaryBlock: Equatable, Sendable {
    let blockID: String
    let label: String
    let title: String
    let subtitle: String?
    let status: ToolStatus
    let members: [SessionChatToolCardBlock]
}

/// One skill a message loaded, for the Skills sheet a skill chip opens.
nonisolated struct SessionChatSkillUse: Equatable, Sendable, Identifiable {
    /// Chip id of the first run of this skill in the message.
    let id: String
    /// Chip label (`/name`).
    let label: String
    /// Skill directory reported by the server, when it settled.
    let directory: String?
    let status: ToolStatus
}

nonisolated struct SessionChatToolChipBlock: Equatable, Sendable {
    /// First part id behind the chip; stays put while later identical runs fold into it.
    let id: String
    let kind: SessionChatToolChipKind
    let family: SessionChatToolChipFamily
    let label: String
    /// Agent chips: first line of the description / prompt. Skill chips: the
    /// skill directory the server reported.
    let brief: String?
    /// Skill chips only: the requested skill id, stable from the first
    /// in-flight update on, while `label` switches to the server-reported
    /// name once the run settles.
    let skillID: String?
    let additions: Int?
    let deletions: Int?
    let status: ToolStatus
    let trailingText: String?
    /// Carries detailText / childSessionId / changes review so chip taps reuse card actions.
    let card: SessionChatToolCardBlock
    /// Identical consecutive completed runs folded into this chip (last run's card).
    let repeatCount: Int
    /// Position in arrival order before the family sort; `+N more` expansion
    /// is scoped to chips that existed at tap time by comparing against it.
    let arrivalIndex: Int
    /// The tool's start while it is pending or running, so the view can show
    /// a live elapsed time; nil once it settles.
    let runningSinceEpochMs: Int64?

    init(
        id: String,
        kind: SessionChatToolChipKind,
        family: SessionChatToolChipFamily,
        label: String,
        brief: String? = nil,
        skillID: String? = nil,
        additions: Int? = nil,
        deletions: Int? = nil,
        status: ToolStatus,
        trailingText: String? = nil,
        card: SessionChatToolCardBlock,
        repeatCount: Int = 1,
        arrivalIndex: Int = 0,
        runningSinceEpochMs: Int64? = nil
    ) {
        self.id = id
        self.kind = kind
        self.family = family
        self.label = label
        self.brief = brief
        self.skillID = skillID
        self.additions = additions
        self.deletions = deletions
        self.status = status
        self.trailingText = trailingText
        self.card = card
        self.repeatCount = repeatCount
        self.arrivalIndex = arrivalIndex
        self.runningSinceEpochMs = runningSinceEpochMs
    }

    /// Failed, running or pending chips are never hidden behind `+N more`
    /// and never count toward a visible limit.
    var needsAttention: Bool {
        switch status {
        case .error, .running, .pending: true
        case .completed: false
        }
    }

    /// Trailing text the chip shows: `×N` for folded repeats, joined with any
    /// existing trailing text as `×N · text`.
    var displayTrailingText: String? {
        guard repeatCount > 1 else { return trailingText }
        let count = "\u{00D7}\(repeatCount)"
        guard let trailingText, !trailingText.isEmpty else { return count }
        return "\(count) · \(trailingText)"
    }

    func withRepeatCount(_ repeatCount: Int) -> SessionChatToolChipBlock {
        SessionChatToolChipBlock(
            id: id,
            kind: kind,
            family: family,
            label: label,
            brief: brief,
            skillID: skillID,
            additions: additions,
            deletions: deletions,
            status: status,
            trailingText: trailingText,
            card: card,
            repeatCount: repeatCount,
            arrivalIndex: arrivalIndex,
            runningSinceEpochMs: runningSinceEpochMs
        )
    }

    func withArrivalIndex(_ arrivalIndex: Int) -> SessionChatToolChipBlock {
        SessionChatToolChipBlock(
            id: id,
            kind: kind,
            family: family,
            label: label,
            brief: brief,
            skillID: skillID,
            additions: additions,
            deletions: deletions,
            status: status,
            trailingText: trailingText,
            card: card,
            repeatCount: repeatCount,
            arrivalIndex: arrivalIndex,
            runningSinceEpochMs: runningSinceEpochMs
        )
    }
}

/// Chips a step paragraph shows, plus how many sit behind its single `+N more`.
nonisolated struct SessionChatToolChipRow: Equatable, Sendable {
    let visible: [SessionChatToolChipBlock]
    let hiddenCount: Int
}

nonisolated struct SessionChatStepParagraphBlock: Equatable, Sendable {
    /// Stable identity across streaming growth (first part id of the paragraph);
    /// keys the paragraph's `+N more` expansion in the Screen Model.
    let id: String
    /// Merged-text id the equivalent `.text` block would have had; nil without text.
    let textBlockID: String?
    /// nil => placeholder paragraph ("Worked without commentary").
    let text: String?
    let renderedMarkdown: NativeMarkdownDocument?
    /// Every tool that is not in `exploreSummary`, one chip per tool.
    let chips: [SessionChatToolChipBlock]
    /// One per failed chip: "<label>: <error>".
    let errorLines: [String]
    /// Two or more explore tools that did not fail, folded into one chip.
    let exploreSummary: SessionChatExploreSummaryBlock?

    init(
        id: String,
        textBlockID: String?,
        text: String?,
        renderedMarkdown: NativeMarkdownDocument?,
        chips: [SessionChatToolChipBlock],
        errorLines: [String],
        exploreSummary: SessionChatExploreSummaryBlock? = nil
    ) {
        self.id = id
        self.textBlockID = textBlockID
        self.text = text
        self.renderedMarkdown = renderedMarkdown
        self.chips = chips
        self.errorLines = errorLines
        self.exploreSummary = exploreSummary
    }

    static let placeholderText = "Worked without commentary"

    func withRenderedMarkdown(_ document: NativeMarkdownDocument?) -> SessionChatStepParagraphBlock {
        SessionChatStepParagraphBlock(
            id: id,
            textBlockID: textBlockID,
            text: text,
            renderedMarkdown: document,
            chips: chips,
            errorLines: errorLines,
            exploreSummary: exploreSummary
        )
    }

    static func == (lhs: SessionChatStepParagraphBlock, rhs: SessionChatStepParagraphBlock) -> Bool {
        lhs.id == rhs.id
            && lhs.textBlockID == rhs.textBlockID
            && lhs.text == rhs.text
            && lhs.renderedMarkdown?.sourceHash == rhs.renderedMarkdown?.sourceHash
            && lhs.renderedMarkdown?.renderTreeVersion == rhs.renderedMarkdown?.renderTreeVersion
            && lhs.chips == rhs.chips
            && lhs.errorLines == rhs.errorLines
            && lhs.exploreSummary == rhs.exploreSummary
    }
}

nonisolated enum SessionChatTimelineBlocks {
    private static let contextToolNames: Set<String> = ["read", "glob", "grep", "websearch"]

    static func build(
        role: MessageRole,
        parts: [MessagePart],
        collapseConsecutiveToolCalls: Bool = false,
        chipTimeline: Bool = false
    ) -> SessionChatTimelineBlocksResult {
        guard !parts.isEmpty else { return .ok([]) }
        if chipTimeline, role == .assistant {
            return buildStepParagraphs(parts: parts)
        }

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
            case .compaction(let compaction):
                // v1 marks compaction on the user message that asked for it;
                // only the v2 compaction item (an assistant row) gets a marker.
                if allowToolCards {
                    flushText()
                    guard let card = compactionCard(compaction) else { return .missingPartIds }
                    blocks.append(card)
                }
                index += 1
            case .reasoning(let reasoning):
                flushText()
                // Live thought is footer chrome (Thinking); only settled thoughts become rows.
                if ThoughtPartChrome.isVisible(reasoning), !reasoning.isStreaming {
                    guard let partId = reasoning.id else { return .missingPartIds }
                    let text = ThoughtPartChrome.displayText(reasoning)
                    blocks.append(
                        .reasoning(
                            blockID: PresentationBlockId.direct(partId),
                            text: text,
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
        collapseConsecutiveToolCalls: Bool = false,
        chipTimeline: Bool = false
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

        switch build(
            role: role,
            parts: parts,
            collapseConsecutiveToolCalls: collapseConsecutiveToolCalls,
            chipTimeline: chipTimeline
        ) {
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
        guard isStreaming else { return [] }
        return [
            .text(
                blockID: PresentationBlockId.messageFallback(message.id),
                text: message.text,
                renderedMarkdown: nil
            ),
        ]
    }

    /// Rows never render empty: drop blocks the timeline paints as nothing
    /// (live thought is footer chrome) and fall back to the live placeholder.
    static func paintedBlocks(for message: SessionChatRow) -> [SessionChatTimelineBlock] {
        let painted = message.blocks.filter(\.paintsInTimeline)
        if painted.isEmpty {
            return [
                .text(
                    blockID: PresentationBlockId.messageFallback(message.id),
                    text: message.text,
                    renderedMarkdown: nil
                ),
            ]
        }
        return painted
    }

    /// When collapse is on, consecutive assistant messages whose visible blocks
    /// are only groupable tools become one tool group. OpenCode emits each
    /// sequential tool step as its own assistant message (`parentID` = user
    /// turn); grouping only inside one message would leave a stack of Shell
    /// cards. Visible text, attachments, and `task` tools still break a run.
    ///
    /// With `chipTimeline` every run of consecutive assistant bodies (text
    /// included) merges so a paragraph joins the tools of the next model step.
    static func mergeConsecutiveToolOnlyAssistantBodies(
        _ bodies: [DisplayMessageBody],
        collapseConsecutiveToolCalls: Bool,
        chipTimeline: Bool = false
    ) -> [DisplayMessageBody] {
        guard collapseConsecutiveToolCalls || chipTimeline, bodies.count >= 2 else { return bodies }
        func isMergeable(_ body: DisplayMessageBody) -> Bool {
            // A message that carries a compaction marker stays its own row.
            if isCompactionBody(body) { return false }
            if chipTimeline {
                return body.message.role == .assistant && !body.blocks.isEmpty
            }
            return isMergeableToolOnlyAssistant(body)
        }
        var merged: [DisplayMessageBody] = []
        var index = 0
        while index < bodies.count {
            guard isMergeable(bodies[index]) else {
                merged.append(bodies[index])
                index += 1
                continue
            }
            var end = index + 1
            while end < bodies.count, isMergeable(bodies[end]) {
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
            var blocks = buildOrFallback(
                role: .assistant,
                parts: parts,
                messageId: first.message.id,
                fallbackText: "",
                collapseConsecutiveToolCalls: collapseConsecutiveToolCalls,
                chipTimeline: chipTimeline
            )
            if blocks.isEmpty {
                blocks = run.flatMap(\.blocks)
            } else if chipTimeline {
                blocks = attachMemberRootMarkdown(blocks, members: Array(run))
            }
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

    /// History markdown rides on each member's message root; after a chip
    /// merge, hand it to the paragraph whose text id is that member's sole text run.
    private static func attachMemberRootMarkdown(
        _ blocks: [SessionChatTimelineBlock],
        members: [DisplayMessageBody]
    ) -> [SessionChatTimelineBlock] {
        var rootByTextBlockID: [String: NativeMarkdownDocument] = [:]
        for member in members {
            guard let root = member.renderedMarkdown,
                  StreamingTextBlockIdentity.isSoleTextBlock(message: member.message),
                  let primary = StreamingTextBlockIdentity.resolvePrimary(message: member.message)
            else { continue }
            rootByTextBlockID[primary.blockID] = root
        }
        guard !rootByTextBlockID.isEmpty else { return blocks }
        return blocks.map { block in
            guard case .stepParagraph(_, let paragraph) = block,
                  paragraph.renderedMarkdown == nil,
                  let textBlockID = paragraph.textBlockID,
                  let root = rootByTextBlockID[textBlockID]
            else { return block }
            return block.withRenderedMarkdown(root)
        }
    }

    private static func isCompactionBody(_ body: DisplayMessageBody) -> Bool {
        body.message.parts.contains { if case .compaction = $0 { return true }; return false }
    }

    /// The compaction marker: a card that opens the summary in the tool detail
    /// sheet, so the summary never reads as reply text.
    private static func compactionCard(_ part: CompactionPart) -> SessionChatTimelineBlock? {
        guard let partId = part.id else { return nil }
        let summary = part.summary.flatMap { $0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : $0 }
        return .toolCard(
            blockID: PresentationBlockId.direct(partId),
            card: SessionChatToolCardBlock(
                toolName: SessionCompactChrome.markerToolName,
                status: part.completed ? .completed : .running,
                title: part.completed ? SessionCompactChrome.markerTitle : SessionCompactChrome.statusLabel,
                subtitle: part.auto ? SessionCompactChrome.markerAutoSubtitle : nil,
                detailText: summary,
                detailIsMarkdown: true
            )
        )
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
        case .text, .attachment, .childSessionRow, .reasoning, .stepParagraph:
            false
        }
    }

    /// Only the latest narration paragraph is live. Earlier paragraphs in a
    /// merged row stay settled while a later step is still streaming.
    static func streamingStepParagraphBlockID(
        blocks: [SessionChatTimelineBlock],
        messageIsStreaming: Bool
    ) -> String? {
        guard messageIsStreaming else { return nil }
        for block in blocks.reversed() {
            if case .stepParagraph(_, let paragraph) = block, paragraph.text != nil {
                return block.blockID
            }
        }
        return nil
    }

    // MARK: - Chip timeline (OPE-260)

    /// Narration accumulates into the pending paragraph; visible tools become
    /// chips on it. Text arriving after chips closes the paragraph. Attachments
    /// and settled thoughts are emitted as today without closing it.
    private static func buildStepParagraphs(parts: [MessagePart]) -> SessionChatTimelineBlocksResult {
        var blocks: [SessionChatTimelineBlock] = []
        var pendingText: [(id: String, text: String)] = []
        var pendingTools: [(id: String, tool: ToolPart)] = []

        /// False when a tool cannot become a chip (missing ids).
        func flushParagraph() -> Bool {
            guard !pendingText.isEmpty || !pendingTools.isEmpty else { return true }
            let textIds = pendingText.map(\.id)
            let toolIds = pendingTools.map(\.id)
            let merged = pendingText.map(\.text).joined(separator: "\n")
            let summaryTools = exploreSummaryTools(pendingTools.map(\.tool))
            let summaryIds = Set(summaryTools.compactMap(\.id))
            var summaryBlock: SessionChatExploreSummaryBlock?
            if !summaryTools.isEmpty {
                guard let summary = Self.exploreSummary(summaryTools) else { return false }
                summaryBlock = summary
            }
            var chipInputs: [SessionChatToolChipBlock] = []
            for entry in pendingTools where !summaryIds.contains(entry.id) {
                guard let chip = toToolChip(entry.tool) else { return false }
                chipInputs.append(chip)
            }
            let chips = orderedChips(foldRepeatedChips(chipInputs))
            // Text always precedes chips inside one paragraph, so the first
            // part id stays put while later tools stream in.
            let firstPartId = textIds.first ?? toolIds[0]
            let errorLines = chips.compactMap { chip -> String? in
                guard chip.status == .error else { return nil }
                return "\(chip.label): \(chip.card.errorText ?? "Failed")"
            }
            pendingText.removeAll()
            pendingTools.removeAll()
            blocks.append(
                .stepParagraph(
                    blockID: PresentationBlockId.stepParagraph(textIds + toolIds),
                    paragraph: SessionChatStepParagraphBlock(
                        id: PresentationBlockId.stepParagraphIdentity(firstPartId),
                        textBlockID: textIds.isEmpty ? nil : PresentationBlockId.mergedText(textIds),
                        text: merged.isEmpty ? nil : merged,
                        renderedMarkdown: nil,
                        chips: chips,
                        errorLines: errorLines,
                        exploreSummary: summaryBlock
                    )
                )
            )
            return true
        }

        for part in parts {
            switch part {
            case .text(let textPart):
                guard textPart.isDisplayable else { continue }
                let trimmed = textPart.text.trimmingCharacters(in: .whitespacesAndNewlines)
                guard !trimmed.isEmpty else { continue }
                guard let partId = textPart.id else { return .missingPartIds }
                if !pendingTools.isEmpty, !flushParagraph() {
                    return .missingPartIds
                }
                pendingText.append((id: partId, text: trimmed))
            case .file(let file):
                blocks.append(.attachment(blockID: PresentationBlockId.direct(file.id), file: file))
            case .compaction(let compaction):
                guard flushParagraph(), let card = compactionCard(compaction) else { return .missingPartIds }
                blocks.append(card)
            case .reasoning(let reasoning):
                guard ThoughtPartChrome.isVisible(reasoning), !reasoning.isStreaming else { continue }
                guard let partId = reasoning.id else { return .missingPartIds }
                let text = ThoughtPartChrome.displayText(reasoning)
                let duration = thoughtDurationMs(
                    start: reasoning.timeStartEpochMs,
                    end: reasoning.timeEndEpochMs
                )
                // Pending narration and tools have not flushed a paragraph yet, so
                // this thought is still consecutive with the previous row.
                if case .reasoning(let prevID, let prevText, false, let prevDuration)? = blocks.last {
                    blocks[blocks.count - 1] = .reasoning(
                        blockID: PresentationBlockId.mergedThought([prevID, partId]),
                        text: joinThoughts(prevText, text),
                        isStreaming: false,
                        durationMs: sumThoughtDurations(prevDuration, duration)
                    )
                    continue
                }
                blocks.append(
                    .reasoning(
                        blockID: PresentationBlockId.direct(partId),
                        text: text,
                        isStreaming: false,
                        durationMs: duration
                    )
                )
            case .tool(let tool):
                guard isVisibleTool(tool) else { continue }
                guard let partId = tool.id else { return .missingPartIds }
                pendingTools.append((id: partId, tool: tool))
            }
        }
        guard flushParagraph() else { return .missingPartIds }
        return .ok(blocks)
    }

    /// Explore tools that fold into the paragraph's summary chip: two or more
    /// that did not fail. A single explore tool keeps its own chip (its path is
    /// more useful than `1 read`); a failed one stays an attention chip with
    /// its error line.
    private static func exploreSummaryTools(_ tools: [ToolPart]) -> [ToolPart] {
        let candidates = tools.filter { isExploreTool($0.toolName) && $0.status != .error }
        return candidates.count >= 2 ? candidates : []
    }

    private static func isExploreTool(_ toolName: String) -> Bool {
        let name = toolName.lowercased()
        return isContextTool(name) || name == "webfetch"
    }

    private static func exploreSummary(_ tools: [ToolPart]) -> SessionChatExploreSummaryBlock? {
        let memberIds = tools.compactMap(\.id)
        guard memberIds.count == tools.count else { return nil }
        let members: [SessionChatToolCardBlock] = tools.compactMap { tool in
            guard case .toolCard(_, let card) = toToolCard(tool) else { return nil }
            return card
        }
        guard members.count == tools.count else { return nil }
        let running = tools.contains { $0.status == .running || $0.status == .pending }
        let counts = exploreCounts(tools)
        return SessionChatExploreSummaryBlock(
            blockID: PresentationBlockId.toolGroup(memberIds),
            label: (running ? "Exploring" : "Explored") + " \u{00B7} " + counts,
            title: "Explored \u{2014} \(counts)",
            subtitle: tools.lazy.compactMap(contextSubtitle).first,
            status: running ? .running : .completed,
            members: members
        )
    }

    /// `4 reads · 2 greps` in first-encounter order.
    private static func exploreCounts(_ tools: [ToolPart]) -> String {
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
            exploreCountPhrase(name, count: counts[name, default: 0])
        }.joined(separator: " \u{00B7} ")
    }

    private static func exploreCountPhrase(_ name: String, count: Int) -> String {
        switch name {
        case "read": return count == 1 ? "1 read" : "\(count) reads"
        case "grep": return count == 1 ? "1 grep" : "\(count) greps"
        case "glob": return count == 1 ? "1 glob" : "\(count) globs"
        case "websearch": return count == 1 ? "1 search" : "\(count) searches"
        case "webfetch": return count == 1 ? "1 fetch" : "\(count) fetches"
        default: return "\(count) \(name)"
        }
    }

    /// Skill name: the settled server output names it in metadata; before
    /// that the request carries the skill id (older servers sent `name`).
    private static func skillName(_ tool: ToolPart) -> String? {
        metadataString(tool.metadata, keys: "name") ?? requestedSkillID(tool)
    }

    /// The skill the request asked for; present from the first update on.
    private static func requestedSkillID(_ tool: ToolPart) -> String? {
        inputString(tool.input, keys: "id", "name", "skill")
    }

    private static func skillDirectory(_ tool: ToolPart) -> String? {
        metadataString(tool.metadata, keys: "directory")
    }

    /// Skills a message loaded, in first-use order across its step paragraphs.
    /// Repeated loads of one skill collapse into one entry, keyed by the
    /// requested skill id (the label once no id is known), that keeps the
    /// first chip's id and the latest run's label, status and directory.
    static func skillUses(in blocks: [SessionChatTimelineBlock]) -> [SessionChatSkillUse] {
        var uses: [SessionChatSkillUse] = []
        var indexByKey: [String: Int] = [:]
        for block in blocks {
            guard case .stepParagraph(_, let paragraph) = block else { continue }
            for chip in paragraph.chips where chip.kind == .skill {
                let key = chip.skillID ?? chip.label
                if let index = indexByKey[key] {
                    uses[index] = SessionChatSkillUse(
                        id: uses[index].id,
                        label: chip.label,
                        directory: chip.brief ?? uses[index].directory,
                        status: chip.status
                    )
                } else {
                    indexByKey[key] = uses.count
                    uses.append(
                        SessionChatSkillUse(id: chip.id, label: chip.label, directory: chip.brief, status: chip.status)
                    )
                }
            }
        }
        return uses
    }

    /// Fixed group order (explore, change, command, agent, skill), arrival order inside a group.
    static func orderedChips(_ chips: [SessionChatToolChipBlock]) -> [SessionChatToolChipBlock] {
        SessionChatToolChipFamily.allCases.flatMap { family in
            chips.filter { $0.family == family }
        }
    }

    /// Chips consecutive in time (before the family sort) that are completed
    /// and share kind, family and label fold into one chip with `repeatCount`;
    /// the first run's id and the last run's card win, so identity is stable
    /// and a tap opens the latest output. Change chips sum their diff counts.
    /// Attention chips (failed, running, pending) never fold and break a run.
    /// Chips are then numbered in arrival order so expansion can be scoped.
    static func foldRepeatedChips(_ chips: [SessionChatToolChipBlock]) -> [SessionChatToolChipBlock] {
        var folded: [SessionChatToolChipBlock] = []
        for chip in chips {
            if let previous = folded.last,
               !chip.needsAttention,
               !previous.needsAttention,
               previous.kind == chip.kind,
               previous.family == chip.family,
               previous.label == chip.label,
               previous.status == chip.status
            {
                folded[folded.count - 1] = SessionChatToolChipBlock(
                    id: previous.id,
                    kind: chip.kind,
                    family: chip.family,
                    label: chip.label,
                    brief: chip.brief,
                    skillID: chip.skillID,
                    additions: sumCounts(previous.additions, chip.additions),
                    deletions: sumCounts(previous.deletions, chip.deletions),
                    status: chip.status,
                    trailingText: chip.trailingText,
                    card: chip.card,
                    repeatCount: previous.repeatCount + chip.repeatCount
                )
                continue
            }
            folded.append(chip)
        }
        return folded.enumerated().map { index, chip in chip.withArrivalIndex(index) }
    }

    private static func sumCounts(_ lhs: Int?, _ rhs: Int?) -> Int? {
        switch (lhs, rhs) {
        case (nil, nil): nil
        case (let value?, nil), (nil, let value?): value
        case (let first?, let second?): first + second
        }
    }

    /// Visibility for one step paragraph's chip row: per-family `visibleLimit`
    /// over counting chips (attention chips never hide and never count).
    /// `expandedThrough` is the paragraph's chip count when its `+N more` was
    /// tapped, or nil: chips whose `arrivalIndex` is below it always show, so
    /// a tap reveals what existed then while later arrivals still collapse. A
    /// lone hidden chip is shown instead of `+1 more`. Order of the remaining
    /// chips is preserved.
    static func chipRow(
        _ chips: [SessionChatToolChipBlock],
        expandedThrough: Int? = nil
    ) -> SessionChatToolChipRow {
        var hidden = Array(repeating: false, count: chips.count)
        var shownPerFamily: [SessionChatToolChipFamily: Int] = [:]
        for (index, chip) in chips.enumerated() where !chip.needsAttention {
            if let expandedThrough, chip.arrivalIndex < expandedThrough { continue }
            let shown = shownPerFamily[chip.family, default: 0]
            if let limit = chip.family.visibleLimit, shown >= limit {
                hidden[index] = true
                continue
            }
            shownPerFamily[chip.family] = shown + 1
        }
        let hiddenCount = hidden.lazy.filter { $0 }.count
        guard hiddenCount > 1 else { return SessionChatToolChipRow(visible: chips, hiddenCount: 0) }
        let visible = chips.indices.filter { !hidden[$0] }.map { chips[$0] }
        return SessionChatToolChipRow(visible: visible, hiddenCount: hiddenCount)
    }

    private static func toToolChip(_ tool: ToolPart) -> SessionChatToolChipBlock? {
        guard let partId = tool.id, case .toolCard(_, let card)? = toToolCard(tool) else { return nil }
        let name = tool.toolName.lowercased()
        let kind: SessionChatToolChipKind
        let family: SessionChatToolChipFamily
        var label: String?
        var brief: String?
        var skillID: String?
        if isChildSessionTool(name) {
            kind = .agent
            family = .agent
            label = taskAgentName(tool)
            brief = (inputString(tool.input, keys: "description", "prompt") ?? childSessionTitle(tool))
                .flatMap(firstLine)
        } else {
            switch name {
            case "read":
                kind = .file
                family = .explore
                label = readPathSubtitle(tool.input).map(lastPathComponent)
            case "glob":
                kind = .file
                family = .explore
                label = inputString(tool.input, keys: "pattern") ?? patternPathSubtitle(tool.input)
            case "grep":
                kind = .file
                family = .explore
                label = inputString(tool.input, keys: "pattern") ?? patternPathSubtitle(tool.input)
            case "websearch":
                kind = .file
                family = .explore
                label = inputString(tool.input, keys: "query")
            case "webfetch":
                kind = .file
                family = .explore
                label = inputString(tool.input, keys: "url").map(hostLabel)
            case "edit", "write", "apply_patch":
                kind = .file
                family = .change
                label = card.subtitle.map(lastPathComponent)
            case "bash", "shell", "execute":
                kind = .command
                family = .command
                label = (inputString(tool.input, keys: "command") ?? card.subtitle).map(commandChipLabel)
            case "skill":
                kind = .skill
                family = .skill
                label = (skillName(tool) ?? card.subtitle).map { "/\($0)" }
                brief = skillDirectory(tool)
                skillID = requestedSkillID(tool)
            case "lsp", "plan_exit":
                kind = .command
                family = .command
                label = card.title
            default:
                kind = .command
                family = .command
                label = isQuestionTool(tool) ? card.title : customToolLabel(tool)
            }
        }
        return SessionChatToolChipBlock(
            id: PresentationBlockId.direct(partId),
            kind: kind,
            family: family,
            label: nonEmpty(label) ?? tool.toolName,
            brief: nonEmpty(brief),
            skillID: nonEmpty(skillID),
            additions: family == .change ? card.additions : nil,
            deletions: family == .change ? card.deletions : nil,
            status: card.status,
            trailingText: nil,
            card: card,
            repeatCount: 1,
            runningSinceEpochMs: (tool.status == .pending || tool.status == .running) ? tool.timeStartEpochMs : nil
        )
    }

    /// Custom and MCP tools: the live progress title while running, then the
    /// tool's own `title` input (or the first line of its `description`). The
    /// tool name stays the fallback and the detail sheet title.
    private static func customToolLabel(_ tool: ToolPart) -> String? {
        runningTitleOverride(tool)
            ?? inputString(tool.input, keys: "title")
            ?? inputString(tool.input, keys: "description").flatMap(firstLine)
    }

    private static func lastPathComponent(_ path: String) -> String {
        let trimmed = path.hasSuffix("/") ? String(path.dropLast()) : path
        guard let slash = trimmed.lastIndex(of: "/") else { return trimmed }
        let component = String(trimmed[trimmed.index(after: slash)...])
        return component.isEmpty ? trimmed : component
    }

    static let commandChipLabelMaxLength = 48

    /// Chip label for a shell command (the card keeps the full command):
    /// first non-empty line with whitespace runs collapsed, leading `cd … &&`,
    /// `export VAR=… &&` and `VAR=value ` prefixes stripped (the original is
    /// kept if nothing would remain), then cut at the last whitespace before
    /// `commandChipLabelMaxLength` with a trailing ellipsis. The first token is
    /// never cut.
    static func commandChipLabel(_ command: String) -> String {
        let line = command
            .split(omittingEmptySubsequences: true, whereSeparator: \.isNewline)
            .map { $0.split(whereSeparator: \.isWhitespace).joined(separator: " ") }
            .first(where: { !$0.isEmpty }) ?? ""
        let stripped = stripCommandPrefixes(line)
        let label = stripped.isEmpty ? line : stripped
        return truncateCommandLabel(label)
    }

    private static func stripCommandPrefixes(_ line: String) -> String {
        var rest = Substring(line)
        while true {
            rest = rest.drop(while: \.isWhitespace)
            if let next = dropCdSegment(rest) ?? dropExportSegment(rest) ?? dropAssignment(rest) {
                rest = next
                continue
            }
            break
        }
        return rest.trimmingCharacters(in: .whitespaces)
    }

    /// `cd <dir> &&` / `cd <dir>;` → the text after the separator. The directory
    /// runs to the first `;` or `&`, which must start the separator.
    private static func dropCdSegment(_ text: Substring) -> Substring? {
        guard text.hasPrefix("cd ") else { return nil }
        let body = text.dropFirst(3)
        guard let stop = body.firstIndex(where: { $0 == ";" || $0 == "&" }),
              !body[..<stop].trimmingCharacters(in: .whitespaces).isEmpty
        else { return nil }
        return dropSeparator(body[stop...])
    }

    /// `export VAR=… [VAR=…] &&` / `…;` → the text after the separator.
    private static func dropExportSegment(_ text: Substring) -> Substring? {
        guard text.hasPrefix("export ") else { return nil }
        var rest = text.dropFirst(7).drop(while: \.isWhitespace)
        guard let first = assignmentEnd(rest) else { return nil }
        rest = rest[first...].drop(while: \.isWhitespace)
        while let end = assignmentEnd(rest) {
            rest = rest[end...].drop(while: \.isWhitespace)
        }
        return dropSeparator(rest)
    }

    /// Leading `VAR=value ` environment assignment → the text after it.
    private static func dropAssignment(_ text: Substring) -> Substring? {
        guard let end = assignmentEnd(text), end < text.endIndex, text[end].isWhitespace else { return nil }
        return text[end...].drop(while: \.isWhitespace)
    }

    private static func dropSeparator(_ text: Substring) -> Substring? {
        if text.hasPrefix("&&") { return text.dropFirst(2) }
        if text.hasPrefix(";") { return text.dropFirst() }
        return nil
    }

    /// End of a leading `NAME=value` token. The value may hold quoted runs;
    /// otherwise it stops at whitespace, `;` or `&`.
    private static func assignmentEnd(_ text: Substring) -> Substring.Index? {
        func isNameStart(_ c: Character) -> Bool { c == "_" || (c.isASCII && c.isLetter) }
        func isNameChar(_ c: Character) -> Bool { isNameStart(c) || (c.isASCII && c.isNumber) }
        guard let first = text.first, isNameStart(first) else { return nil }
        var index = text.index(after: text.startIndex)
        while index < text.endIndex, isNameChar(text[index]) {
            index = text.index(after: index)
        }
        guard index < text.endIndex, text[index] == "=" else { return nil }
        index = text.index(after: index)
        while index < text.endIndex {
            let c = text[index]
            if c == "\"" || c == "'",
               let close = text[text.index(after: index)...].firstIndex(of: c)
            {
                index = text.index(after: close)
                continue
            }
            if c.isWhitespace || c == ";" || c == "&" { break }
            index = text.index(after: index)
        }
        return index
    }

    private static func truncateCommandLabel(_ label: String) -> String {
        guard label.count > commandChipLabelMaxLength else { return label }
        let firstTokenEnd = label.firstIndex(of: " ") ?? label.endIndex
        if label.distance(from: label.startIndex, to: firstTokenEnd) >= commandChipLabelMaxLength {
            let firstToken = String(label[..<firstTokenEnd])
            return firstTokenEnd == label.endIndex ? firstToken : firstToken + "\u{2026}"
        }
        // A space right at the limit still counts as a boundary.
        let limit = label.index(label.startIndex, offsetBy: commandChipLabelMaxLength + 1)
        let cut = label[..<limit].lastIndex(of: " ") ?? firstTokenEnd
        return String(label[..<cut]) + "\u{2026}"
    }

    private static func firstLine(_ text: String) -> String? {
        let line = text
            .split(omittingEmptySubsequences: true, whereSeparator: \.isNewline)
            .first
            .map { $0.trimmingCharacters(in: .whitespaces) }
        return nonEmpty(line)
    }

    private static func hostLabel(_ url: String) -> String {
        if let host = URL(string: url)?.host, !host.isEmpty { return host }
        let stripped = url.replacingOccurrences(of: "^[a-zA-Z]+://", with: "", options: .regularExpression)
        return stripped.split(separator: "/").first.map(String.init) ?? url
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

    private static func joinThoughts(_ lhs: String, _ rhs: String) -> String {
        switch (lhs.isEmpty, rhs.isEmpty) {
        case (true, _): return rhs
        case (_, true): return lhs
        default: return lhs + "\n\n" + rhs
        }
    }

    private static func sumThoughtDurations(_ lhs: Int64?, _ rhs: Int64?) -> Int64? {
        switch (lhs, rhs) {
        case (nil, nil): return nil
        case (let value?, nil), (nil, let value?): return value
        case (let first?, let second?): return first + second
        }
    }

    private static func thoughtDurationMs(start: Int64?, end: Int64?) -> Int64? {
        guard let start, let end else { return nil }
        let duration = end - start
        return duration >= 0 ? duration : nil
    }

    private static func isLiveReasoning(_ part: ReasoningPart) -> Bool {
        ThoughtPartChrome.isVisible(part)
    }

    private static func isVisibleTool(_ tool: ToolPart) -> Bool {
        ToolPartIdentity.isVisibleOnTimeline(tool)
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
            !isLiveReasoning(reasoning)
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
        let status = worstStatus(members.map(\.status))
        let errorText = members.first(where: { $0.status == .error })?.errorText
        return .toolGroup(
            blockID: PresentationBlockId.toolGroup(memberIds),
            group: SessionChatToolGroupBlock(
                count: tools.count,
                status: status,
                title: groupTitle(tools),
                subtitle: groupSubtitle(tools),
                errorText: errorText,
                members: members
            )
        )
    }

    /// Context-only groups match TUI (`Explored — 1 read`). Mixed groups stay `N tools`.
    private static func groupTitle(_ tools: [ToolPart]) -> String {
        if !tools.isEmpty, tools.allSatisfy({ isContextTool($0.toolName) }) {
            return exploredTitle(tools)
        }
        return "\(tools.count) tools"
    }

    /// Family one-liner in encounter order (`Edit · Write · 5 shells`).
    /// Context-only groups keep the first path as subtitle.
    private static func groupSubtitle(_ tools: [ToolPart]) -> String? {
        if !tools.isEmpty, tools.allSatisfy({ isContextTool($0.toolName) }) {
            return tools.lazy.compactMap(contextSubtitle).first
        }
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

    private static func exploredTitle(_ tools: [ToolPart]) -> String {
        var order: [String] = []
        var counts: [String: Int] = [:]
        for tool in tools {
            let key = tool.toolName.lowercased()
            if counts[key] == nil {
                order.append(key)
            }
            counts[key, default: 0] += 1
        }
        let summary = order.map { name in
            exploredFamilyPhrase(name, count: counts[name, default: 0])
        }.joined(separator: " · ")
        return "Explored — \(summary)"
    }

    private static func exploredFamilyPhrase(_ name: String, count: Int) -> String {
        switch name {
        case "read": return count == 1 ? "1 read" : "\(count) reads"
        case "grep": return count == 1 ? "1 grep" : "\(count) greps"
        case "glob": return count == 1 ? "1 glob" : "\(count) globs"
        case "websearch": return count == 1 ? "1 search" : "\(count) searches"
        default: return familyPhrase(name, count: count)
        }
    }

    private static func contextSubtitle(_ tool: ToolPart) -> String? {
        switch tool.toolName.lowercased() {
        case "read": return readPathSubtitle(tool.input)
        case "glob", "grep": return patternPathSubtitle(tool.input)
        case "websearch": return inputString(tool.input, keys: "query")
        default: return nil
        }
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
        case "question": return askedQuestionsTitle(count)
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
        let failedExit = failedShellExitCode(name: name, tool: tool)
        let errorText: String? =
            if let failedExit {
                "Exited with code \(failedExit)"
            } else if tool.status == .error {
                tool.error.flatMap { $0.isEmpty ? nil : $0 }
            } else {
                nil
            }
        return .toolCard(
            blockID: PresentationBlockId.direct(partId),
            card: SessionChatToolCardBlock(
                toolName: tool.toolName,
                status: failedExit != nil ? .error : tool.status,
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

    /// A shell run the server reports as completed but whose command exited
    /// non-zero (`metadata.exit`): shown as failed with the exit code.
    private static func failedShellExitCode(name: String, tool: ToolPart) -> Int? {
        guard name == "bash" || name == "shell", tool.status == .completed,
              let raw = tool.metadata?["exit"], let exit = intValue(raw), exit != 0
        else { return nil }
        return exit
    }

    private static func intValue(_ value: AnyCodable) -> Int? {
        switch value {
        case .int(let number): number
        case .double(let number): Int(number)
        case .string(let text): Int(text)
        default: nil
        }
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
        func distinctFromName(_ title: String?) -> String? {
            title.flatMap { title in
                title.isEmpty || title.compare(tool.toolName, options: .caseInsensitive) == .orderedSame
                    ? nil
                    : title
            }
        }
        let subtitle = distinctFromName(runningTitleOverride(tool))
            ?? inputPreview(tool.input)
            ?? distinctFromName(tool.title)
        return Chrome(
            title: ToolPartIdentity.calledTitle(tool.toolName),
            subtitle: subtitle,
            detailText: settledDetail(tool)
        )
    }

    private static func specializedChrome(name: String, tool: ToolPart) -> Chrome? {
        if isQuestionTool(tool) { return questionChrome(tool) }
        switch name {
        case "read":
            return contextChrome(tool: tool, subtitle: readPathSubtitle(tool.input))
        case "glob":
            return contextChrome(tool: tool, subtitle: patternPathSubtitle(tool.input))
        case "grep":
            return contextChrome(tool: tool, subtitle: patternPathSubtitle(tool.input))
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
            return contextChrome(tool: tool, subtitle: inputString(tool.input, keys: "query"))
        case "skill":
            return Chrome(
                title: "Skill",
                subtitle: skillName(tool) ?? runningTitleOverride(tool)
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
            metadataFilediffInt(tool.metadata, key: "additions") ?? singlePatchFile?.additions ?? newFileLineCount(tool)
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

    /// A write that reports no diff counts (v2 sends none) and did not
    /// overwrite an existing file adds every line of its `content`.
    private static func newFileLineCount(_ tool: ToolPart) -> Int? {
        guard tool.toolName.lowercased() == "write",
              !isTruthyMetadata(tool.metadata, key: "exists"),
              let content = tool.input?["content"]?.stringValue
        else { return nil }
        return lineCount(content)
    }

    /// Lines in a file body; a trailing newline does not start another line.
    private static func lineCount(_ content: String) -> Int {
        guard !content.isEmpty else { return 0 }
        let lines = content.components(separatedBy: "\n")
        return lines.last?.isEmpty == true ? lines.count - 1 : lines.count
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

    private static func contextChrome(tool: ToolPart, subtitle: String?) -> Chrome {
        Chrome(
            title: exploredTitle([tool]),
            subtitle: runningTitleOverride(tool) ?? subtitle,
            detailText: settledDetail(tool)
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
            keys: "command", "filePath", "path", "file", "url", "query", "pattern", "name", "title", "description", "prompt",
            "text", "id"
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

    /// Read chrome is the file: v1 sends `filePath`, v2 sends `path`.
    private static func readPathSubtitle(_ input: [String: AnyCodable]?) -> String? {
        inputString(input, keys: "filePath", "file", "path")
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

    private static func isQuestionTool(_ tool: ToolPart) -> Bool {
        ToolPartIdentity.isQuestion(name: tool.toolName, input: tool.input)
    }

    private static func askedQuestionsTitle(_ count: Int) -> String {
        count == 1 ? "Asked 1 question" : "Asked \(count) questions"
    }

    private static func questionChrome(_ tool: ToolPart) -> Chrome {
        let questions = questionTexts(tool.input)
        let answers = answerTexts(tool.metadata)
        let firstQuestion = questions.first
            ?? inputString(tool.input, keys: "prompt", "text", "question")
            ?? runningTitleOverride(tool)
        let dismissed = tool.status == .error ? nonEmpty(tool.error) : nil
        let count = max(questions.count, firstQuestion == nil ? 0 : 1)
        return Chrome(
            title: askedQuestionsTitle(count),
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
