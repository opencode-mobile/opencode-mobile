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
    /// Step rail timeline (#779): a run of tools and settled thoughts, one line each.
    case stepRail(blockID: String, rail: SessionChatStepRailBlock)
    /// Activity card timeline (#779): a run of tools and settled thoughts on one card.
    case activityCard(blockID: String, card: SessionChatActivityCardBlock)
    /// Persistent note that the reply ended early (cut off or interrupted).
    case stopNote(blockID: String, reason: MessageStopReason)
    /// A failed run under the reply. A provider body or an action opens the detail sheet.
    /// `coveredByLiveRetry` is a rate-limit or quota the composer status already shows.
    case failureLine(
        blockID: String,
        text: String,
        providerBody: String?,
        isNeutral: Bool,
        action: FailureAction?,
        actionLabel: String?,
        coveredByLiveRetry: Bool
    )
    /// A session-level change (moved, agent, model, instructions, restart) on its own row.
    case notice(blockID: String, notice: TimelineNotice)

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
        case .stepRail(let blockID, _):
            blockID
        case .activityCard(let blockID, _):
            blockID
        case .stopNote(let blockID, _):
            blockID
        case .failureLine(let blockID, _, _, _, _, _, _):
            blockID
        case .notice(let blockID, _):
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
        case .attachment, .toolCard, .toolGroup, .childSessionRow, .reasoning, .stepRail, .activityCard, .stopNote,
             .failureLine, .notice:
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
        case .attachment, .toolCard, .toolGroup, .childSessionRow, .reasoning, .stepRail, .activityCard, .stopNote,
             .failureLine, .notice:
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
        case let (.stepRail(lID, lRail), .stepRail(rID, rRail)):
            lID == rID && lRail == rRail
        case let (.activityCard(lID, lCard), .activityCard(rID, rCard)):
            lID == rID && lCard == rCard
        case let (.stopNote(lID, lReason), .stopNote(rID, rReason)):
            lID == rID && lReason == rReason
        case let (
            .failureLine(lID, lText, lBody, lNeutral, lAction, lLabel, lCovered),
            .failureLine(rID, rText, rBody, rNeutral, rAction, rLabel, rCovered)
        ):
            lID == rID && lText == rText && lBody == rBody && lNeutral == rNeutral
                && lAction == rAction && lLabel == rLabel && lCovered == rCovered
        case let (.notice(lID, lNotice), .notice(rID, rNotice)):
            lID == rID && lNotice == rNotice
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
    /// `status` is `.error` because the user stopped or declined it; render neutral.
    let isInterrupted: Bool
    /// The server closed the call without running it.
    let isMalformed: Bool
    /// v2 is still streaming this tool's arguments: `subtitle` is the value
    /// read so far from the partial input, or a placeholder until one appears.
    let isReceivingArguments: Bool
    /// Inspector body for the tool-result sheet. Nil when there is nothing to classify.
    let presentation: ToolResultPresentation?

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
        changesInitialFilePath: String? = nil,
        isInterrupted: Bool = false,
        isMalformed: Bool = false,
        isReceivingArguments: Bool = false,
        presentation: ToolResultPresentation? = nil
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
        self.isInterrupted = isInterrupted
        self.isMalformed = isMalformed
        self.isReceivingArguments = isReceivingArguments
        self.presentation = presentation
    }

    func markingFailed(_ errorText: String) -> SessionChatToolCardBlock {
        SessionChatToolCardBlock(
            toolName: toolName,
            status: .error,
            title: title,
            subtitle: subtitle,
            errorText: errorText,
            additions: additions,
            deletions: deletions,
            detailText: detailText,
            detailIsMarkdown: detailIsMarkdown,
            childSessionId: childSessionId,
            opensChangesReview: opensChangesReview,
            changesInitialFilePath: changesInitialFilePath,
            isInterrupted: isInterrupted,
            isMalformed: isMalformed,
            presentation: presentation
        )
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

nonisolated enum SessionChatStepKind: String, Sendable {
    case tool
    case childSession
    case thought
}

nonisolated enum SessionChatStepStatus: String, Sendable {
    case pending
    case running
    case completed
    case failed

    var isInFlight: Bool {
        self == .pending || self == .running
    }
}

nonisolated struct SessionChatStepChildSession: Equatable, Sendable {
    let agent: String
    let description: String?
    let childSessionId: String?
    /// Tools the child has run so far, when the server reports them.
    let toolsSoFar: Int?
    /// First line of a finished child's result, when the server returned one.
    var result: String? = nil
}

/// What a step did, for the activity card's family counts (#779).
nonisolated enum SessionChatStepFamily: String, Sendable {
    case read
    case search
    case fetch
    case edit
    case command
    case thought
    case agent

    /// "1 read", "2 searches".
    func counted(_ count: Int) -> String {
        guard count != 1 else { return "1 \(rawValue)" }
        switch self {
        case .search, .fetch: return "\(count) \(rawValue)es"
        default: return "\(count) \(rawValue)s"
        }
    }
}

/// One step of a run of tools (#779): a line on a step rail or an activity card.
nonisolated struct SessionChatStep: Equatable, Sendable, Identifiable {
    /// Part id behind the step.
    let id: String
    let kind: SessionChatStepKind
    let status: SessionChatStepStatus
    /// `runningTitle` while in flight, else `settledTitle`.
    let title: String
    /// With a verb: "Reading X", "Running cmd".
    let runningTitle: String
    /// Without the verb; a shell command keeps "Ran cmd".
    let settledTitle: String
    /// Nil for tools outside the counted families (skills, questions, other tools).
    let family: SessionChatStepFamily?
    /// One short line under or beside the title; nil when there is nothing to add.
    let detail: String?
    let additions: Int?
    let deletions: Int?
    let startedAtMs: Int64?
    let endedAtMs: Int64?
    /// A running step right after another running step (parallel tools).
    let isBranch: Bool
    /// `status` is `.failed` because the user stopped or declined it; render neutral.
    let isInterrupted: Bool
    let childSession: SessionChatStepChildSession?
    /// Tool steps: the standard card, so a tap does what the card's tap does.
    let card: SessionChatToolCardBlock?
    /// Thought steps: the thought text a tap opens.
    let thoughtText: String?

    init(
        id: String,
        kind: SessionChatStepKind,
        status: SessionChatStepStatus,
        title: String,
        runningTitle: String? = nil,
        settledTitle: String? = nil,
        family: SessionChatStepFamily? = nil,
        detail: String? = nil,
        additions: Int? = nil,
        deletions: Int? = nil,
        startedAtMs: Int64? = nil,
        endedAtMs: Int64? = nil,
        isBranch: Bool = false,
        isInterrupted: Bool = false,
        childSession: SessionChatStepChildSession? = nil,
        card: SessionChatToolCardBlock? = nil,
        thoughtText: String? = nil
    ) {
        self.id = id
        self.kind = kind
        self.status = status
        self.title = title
        self.runningTitle = runningTitle ?? title
        self.settledTitle = settledTitle ?? title
        self.family = family
        self.detail = detail
        self.additions = additions
        self.deletions = deletions
        self.startedAtMs = startedAtMs
        self.endedAtMs = endedAtMs
        self.isBranch = isBranch
        self.isInterrupted = isInterrupted
        self.childSession = childSession
        self.card = card
        self.thoughtText = thoughtText
    }

    func withBranch(_ isBranch: Bool) -> SessionChatStep {
        SessionChatStep(
            id: id,
            kind: kind,
            status: status,
            title: title,
            runningTitle: runningTitle,
            settledTitle: settledTitle,
            family: family,
            detail: detail,
            additions: additions,
            deletions: deletions,
            startedAtMs: startedAtMs,
            endedAtMs: endedAtMs,
            isBranch: isBranch,
            isInterrupted: isInterrupted,
            childSession: childSession,
            card: card,
            thoughtText: thoughtText
        )
    }

    /// The title in a list of steps (activity sheet): without the verb, except
    /// a command still running, whose settled title would read "Ran cmd".
    var listTitle: String {
        status.isInFlight && family == .command ? runningTitle : settledTitle
    }

    /// Settled tool and thought steps fold; running steps and child sessions never do.
    var isFoldable: Bool {
        kind != .childSession && !status.isInFlight
    }
}

/// Step rail timeline (#779): a maximal run of consecutive tools, child
/// sessions and settled thoughts. `id` is "sr:" + the first member's part id,
/// so the block keeps its identity while later steps stream in.
nonisolated struct SessionChatStepRailBlock: Equatable, Sendable {
    let id: String
    let steps: [SessionChatStep]
    /// The last rail of a turn that is still working. Set at render time from
    /// session status (`withLive`); blocks built from parts are never live.
    let isLive: Bool
    /// Live and nothing in flight: the rail ends with a "Thinking…" step.
    let thinking: Bool

    init(id: String, steps: [SessionChatStep], isLive: Bool = false) {
        self.id = id
        self.steps = steps
        self.isLive = isLive
        self.thinking = isLive && !steps.contains { $0.status.isInFlight }
    }

    func withLive(_ isLive: Bool) -> SessionChatStepRailBlock {
        guard isLive != self.isLive else { return self }
        return SessionChatStepRailBlock(id: id, steps: steps, isLive: isLive)
    }

    /// Failed if any step failed for real, else completed.
    var worstStatus: SessionChatStepStatus {
        SessionChatStepRailBlock.worstStatus(of: steps)
    }

    static func worstStatus(of steps: [SessionChatStep]) -> SessionChatStepStatus {
        steps.contains { $0.status == .failed && !$0.isInterrupted } ? .failed : .completed
    }

    /// More than this many settled steps fold all but the last `foldKeepCount`.
    static let foldThreshold = 4
    static let foldKeepCount = 3
}

/// What a step rail paints after folding, top to bottom.
nonisolated enum SessionChatStepRailRow: Equatable, Sendable, Identifiable {
    /// "N earlier steps"; `status` is the worst status of the folded steps.
    case fold(count: Int, status: SessionChatStepStatus)
    case step(SessionChatStep)
    case thinking

    var id: String {
        switch self {
        case .fold: "fold"
        case .step(let step): step.id
        case .thinking: "thinking"
        }
    }
}

/// Activity card timeline (#779): the same run of steps as a step rail, shown
/// as one card. `id` is "ac:" + the first member's part id, so the card keeps
/// its identity while later steps stream in.
nonisolated struct SessionChatActivityCardBlock: Equatable, Sendable {
    let id: String
    let steps: [SessionChatStep]
    /// The last card of a turn that is still working. Set at render time from
    /// session status (`withLive`); blocks built from parts are never live.
    let isLive: Bool

    init(id: String, steps: [SessionChatStep], isLive: Bool = false) {
        self.id = id
        self.steps = steps
        self.isLive = isLive
    }

    func withLive(_ isLive: Bool) -> SessionChatActivityCardBlock {
        guard isLive != self.isLive else { return self }
        return SessionChatActivityCardBlock(id: id, steps: steps, isLive: isLive)
    }

    /// Child-session steps, running or finished, in order: the sheet's Agent section.
    var childSteps: [SessionChatStep] { steps.filter { $0.kind == .childSession } }

    /// Every other step, in order: the sheet's Steps section.
    var toolSteps: [SessionChatStep] { steps.filter { $0.kind != .childSession } }

    /// Child sessions still in flight: the live card's Agent section.
    var runningChildren: [SessionChatStep] { childSteps.filter(\.status.isInFlight) }

    /// Tools still in flight, child sessions excluded.
    var runningTools: [SessionChatStep] { toolSteps.filter(\.status.isInFlight) }

    /// The live footer's tool: the most recently started running tool. A step
    /// without a start time counts as oldest; ties go to the later step.
    var latestRunningTool: SessionChatStep? {
        runningTools.enumerated().max { lhs, rhs in
            (lhs.element.startedAtMs ?? .min, lhs.offset) < (rhs.element.startedAtMs ?? .min, rhs.offset)
        }?.element
    }

    /// Failed if any step failed for real (not interrupted), else completed.
    var worstStatus: SessionChatStepStatus {
        SessionChatStepRailBlock.worstStatus(of: steps)
    }

    /// Earliest known start, for the live header's elapsed time.
    var startedAtMs: Int64? {
        steps.compactMap(\.startedAtMs).min()
    }

    /// Live card's second line: "6 steps · 3 reads · 1 search · 1 agent".
    /// Counts every step, running or settled.
    static func summary(_ steps: [SessionChatStep]) -> String {
        joined(steps.count == 1 ? "1 step" : "\(steps.count) steps", families(steps))
    }

    /// Family counts in first-seen order: "3 reads · 1 search".
    static func families(_ steps: [SessionChatStep]) -> String {
        var order: [SessionChatStepFamily] = []
        var counts: [SessionChatStepFamily: Int] = [:]
        for family in steps.compactMap(\.family) {
            if counts[family] == nil { order.append(family) }
            counts[family, default: 0] += 1
        }
        return order.map { $0.counted(counts[$0] ?? 0) }.joined(separator: " \u{00B7} ")
    }

    /// Settled line's tool names, deduplicated in first-seen order: "Read, Edit, Agent".
    static func usedToolNames(_ steps: [SessionChatStep]) -> [String] {
        var seen: Set<String> = []
        return steps.compactMap { step in
            let name = usedToolName(step)
            return seen.insert(name).inserted ? name : nil
        }
    }

    /// Live footer with no running tool: waiting on child sessions, on the
    /// user (a permission or question), or on the model.
    static func idleText(hasRunningChildren: Bool, awaitingUser: Bool) -> String {
        if hasRunningChildren { return "Waiting for agents" }
        if awaitingUser { return "Waiting for you" }
        return "Thinking\u{2026}"
    }

    private static func usedToolName(_ step: SessionChatStep) -> String {
        switch step.kind {
        case .thought: return "Thought"
        case .childSession: return "Agent"
        case .tool: break
        }
        let tool = step.card?.toolName.lowercased() ?? ""
        switch tool {
        case "read": return "Read"
        case "glob": return "Glob"
        case "grep": return "Grep"
        case "websearch", "codesearch": return "Search"
        case "webfetch": return "Fetch"
        case "edit", "apply_patch", "patch": return "Edit"
        case "write": return "Write"
        case "bash", "shell": return "Shell"
        case "task", "subagent": return "Agent"
        case "skill": return "Skill"
        case "question": return "Question"
        // An unavailable tool keeps the name the model asked for.
        case "invalid", "": return step.title
        default: return tool.prefix(1).uppercased() + tool.dropFirst()
        }
    }

    private static func joined(_ head: String, _ tail: String) -> String {
        tail.isEmpty ? head : "\(head) \u{00B7} \(tail)"
    }
}

/// What a running child session is doing, observed from its own messages
/// while an activity card shows it live (#779).
nonisolated struct SessionChatChildActivity: Equatable, Sendable {
    /// `runningTitle` of the child's most recently started running tool.
    let currentAction: String?
    /// Up to `recentLimit` of the child's latest steps, oldest first.
    let recentSteps: [SessionChatChildActivityStep]
    /// Visible tools the child has run or is running.
    let toolCount: Int

    static let recentLimit = 3
    static let empty = SessionChatChildActivity(currentAction: nil, recentSteps: [], toolCount: 0)
}

nonisolated struct SessionChatChildActivityStep: Equatable, Sendable, Identifiable {
    let id: String
    let title: String
    let isRunning: Bool
}

nonisolated enum SessionChatTimelineBlocks {
    private static let contextToolNames: Set<String> = ["read", "glob", "grep", "websearch"]

    static func build(
        role: MessageRole,
        parts: [MessagePart],
        collapseConsecutiveToolCalls: Bool = false,
        chipTimeline: Bool = false,
        stepRail: Bool = false,
        activityCard: Bool = false
    ) -> SessionChatTimelineBlocksResult {
        guard !parts.isEmpty else { return .ok([]) }
        if role == .notice {
            return .ok(noticeBlocks(parts))
        }
        if chipTimeline, role == .assistant {
            return buildStepParagraphs(parts: parts)
        }
        if stepRail, role == .assistant {
            return buildStepRails(parts: parts)
        }
        if activityCard, role == .assistant {
            return buildActivityCards(parts: parts)
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
                        file: AttachmentPreviewCache.shared.resolved(file)
                    )
                )
                index += 1
            case .notice(let notice):
                flushText()
                blocks.append(noticeBlock(notice))
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
        chipTimeline: Bool = false,
        stepRail: Bool = false,
        activityCard: Bool = false
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
            chipTimeline: chipTimeline,
            stepRail: stepRail,
            activityCard: activityCard
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

    /// Newest rate-limit or quota body, for the composer status to open.
    static func newestLiveRetryProviderBody(in rows: [SessionChatRow]) -> String? {
        for row in rows.reversed() {
            for block in row.blocks {
                if case .failureLine(_, _, let body, _, _, _, let covered) = block,
                   covered,
                   let body = body?.trimmingCharacters(in: .whitespacesAndNewlines),
                   !body.isEmpty
                {
                    return body
                }
            }
        }
        return nil
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
    /// `stepRail` merges the same way, so one turn is one row and the tools of
    /// consecutive model steps join one rail. `activityCard` merges like `stepRail`.
    static func mergeConsecutiveToolOnlyAssistantBodies(
        _ bodies: [DisplayMessageBody],
        collapseConsecutiveToolCalls: Bool,
        chipTimeline: Bool = false,
        stepRail: Bool = false,
        activityCard: Bool = false
    ) -> [DisplayMessageBody] {
        mergeBodies(
            bodies,
            collapseConsecutiveToolCalls: collapseConsecutiveToolCalls,
            chipTimeline: chipTimeline,
            stepRail: stepRail && !chipTimeline,
            activityCard: activityCard && !chipTimeline && !stepRail
        )
        .map(withStopNote)
    }

    /// An assistant reply that ended early keeps a note under its last block, then any failure caption.
    private static func withStopNote(_ body: DisplayMessageBody) -> DisplayMessageBody {
        var blocks = body.blocks
        if let reason = body.message.stopReason,
           body.message.role == .assistant,
           !blocks.contains(where: { if case .stopNote = $0 { return true } else { return false } })
        {
            blocks.append(.stopNote(blockID: "\(body.message.id):stop-note", reason: reason))
        }
        if let failure = body.message.failure, failure.type?.hasPrefix("compaction.") == true {
            blocks = blocks.map { block in
                guard case let .toolCard(id, card) = block, card.toolName == SessionCompactChrome.markerToolName else {
                    return block
                }
                return .toolCard(blockID: id, card: card.markingFailed(failure.line()))
            }
        }
        let compactionInline = body.message.failure?.type?.hasPrefix("compaction.") == true
            && blocks.contains { block in
                if case let .toolCard(_, card) = block {
                    return card.toolName == SessionCompactChrome.markerToolName
                }
                return false
            }
        if let failure = body.message.failure, !compactionInline,
           !blocks.contains(where: { if case .failureLine = $0 { return true } else { return false } })
        {
            blocks.append(
                .failureLine(
                    blockID: "\(body.message.id):failure",
                    text: failure.line(),
                    providerBody: failure.providerBody,
                    isNeutral: failure.isNeutral,
                    action: failure.action(),
                    actionLabel: failure.actionLabel(),
                    coveredByLiveRetry: failure.coversLiveRetry()
                )
            )
        }
        guard blocks != body.blocks else { return body }
        return DisplayMessageBody(
            message: body.message,
            isStreaming: body.isStreaming,
            blocks: blocks,
            renderedMarkdown: body.renderedMarkdown
        )
    }

    private static func mergeBodies(
        _ bodies: [DisplayMessageBody],
        collapseConsecutiveToolCalls: Bool,
        chipTimeline: Bool,
        stepRail: Bool,
        activityCard: Bool
    ) -> [DisplayMessageBody] {
        let mergesTurns = chipTimeline || stepRail || activityCard
        guard collapseConsecutiveToolCalls || mergesTurns, bodies.count >= 2 else { return bodies }
        func isMergeable(_ body: DisplayMessageBody) -> Bool {
            // A message that carries a compaction marker stays its own row.
            if isCompactionBody(body) { return false }
            if mergesTurns {
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
                chipTimeline: chipTimeline,
                stepRail: stepRail,
                activityCard: activityCard
            )
            if blocks.isEmpty {
                blocks = run.flatMap(\.blocks)
            } else if mergesTurns {
                blocks = attachMemberRootMarkdown(blocks, members: Array(run))
            }
            var message = first.message
            message.stopReason = run.last?.message.stopReason
            message.failure = run.reversed().compactMap(\.message.failure).first
            merged.append(
                DisplayMessageBody(
                    message: message,
                    isStreaming: run.contains(where: \.isStreaming),
                    blocks: blocks,
                    renderedMarkdown: nil
                )
            )
            index = end
        }
        return merged
    }

    /// History markdown rides on each member's message root; after a chip or
    /// step rail merge, hand it to the paragraph or text block whose text id is
    /// that member's sole text run.
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
            switch block {
            case .stepParagraph(_, let paragraph):
                guard paragraph.renderedMarkdown == nil,
                      let textBlockID = paragraph.textBlockID,
                      let root = rootByTextBlockID[textBlockID]
                else { return block }
                return block.withRenderedMarkdown(root)
            case .text(let blockID, _, nil):
                // Step rail merges keep plain text blocks.
                guard let root = rootByTextBlockID[blockID] else { return block }
                return block.withRenderedMarkdown(root)
            default:
                return block
            }
        }
    }

    /// Notices never merge with replies: each stays the line it was recorded as.
    private static func noticeBlocks(_ parts: [MessagePart]) -> [SessionChatTimelineBlock] {
        parts.compactMap { part in
            guard case .notice(let notice) = part else { return nil }
            return noticeBlock(notice)
        }
    }

    private static func noticeBlock(_ notice: TimelineNotice) -> SessionChatTimelineBlock {
        .notice(blockID: PresentationBlockId.direct(notice.id), notice: notice)
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
        case .text, .attachment, .childSessionRow, .reasoning, .stepParagraph, .stepRail, .activityCard, .stopNote,
             .failureLine, .notice:
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

    // MARK: - Step rail (#779)

    /// Text, attachments, notices and compaction markers render as in the
    /// standard timeline. Every maximal run of visible tools, child sessions and
    /// settled thoughts between them becomes one step rail. Hidden tools, empty
    /// text and a streaming thought are see-through: they neither show nor
    /// break a run.
    private static func buildStepRails(parts: [MessagePart]) -> SessionChatTimelineBlocksResult {
        buildStepRuns(parts: parts) { first, steps in
            let id = PresentationBlockId.stepRail(first)
            return .stepRail(blockID: id, rail: SessionChatStepRailBlock(id: id, steps: markBranches(steps)))
        }
    }

    /// What an activity card paints (#779): a settled card whose only step is a
    /// tool or a thought shows that step as the standard timeline does (its tool
    /// card or reasoning row); every other card stays a card. Liveness is known
    /// only at render time, so the card block stays in the row and this decides.
    static func activityCardDisplayBlock(_ card: SessionChatActivityCardBlock) -> SessionChatTimelineBlock {
        let cardBlock = SessionChatTimelineBlock.activityCard(blockID: card.id, card: card)
        guard !card.isLive, card.steps.count == 1, let step = card.steps.first, !step.status.isInFlight else {
            return cardBlock
        }
        switch step.kind {
        case .tool:
            guard let toolCard = step.card else { return cardBlock }
            return .toolCard(blockID: PresentationBlockId.direct(step.id), card: toolCard)
        case .thought:
            return .reasoning(
                blockID: PresentationBlockId.direct(step.id),
                text: step.thoughtText ?? "",
                isStreaming: false,
                durationMs: thoughtDurationMs(start: step.startedAtMs, end: step.endedAtMs)
            )
        case .childSession:
            return cardBlock
        }
    }

    /// The same runs as `buildStepRails`, each as one activity card.
    private static func buildActivityCards(parts: [MessagePart]) -> SessionChatTimelineBlocksResult {
        buildStepRuns(parts: parts) { first, steps in
            let id = PresentationBlockId.activityCard(first)
            return .activityCard(blockID: id, card: SessionChatActivityCardBlock(id: id, steps: steps))
        }
    }

    /// A child session's activity from its messages, with the same step rules
    /// as the parent's card: the latest started running tool names the current
    /// action; the last few steps feed the sheet's sub-rows.
    static func childActivity(from messages: [Message]) -> SessionChatChildActivity {
        var steps: [SessionChatStep] = []
        for message in messages where message.role == .assistant {
            for case .tool(let tool) in message.parts where isVisibleTool(tool) {
                if let step = toolStep(tool) { steps.append(step) }
            }
        }
        let card = SessionChatActivityCardBlock(id: "", steps: steps)
        let current = card.latestRunningTool ?? card.runningChildren.last
        return SessionChatChildActivity(
            currentAction: current.map { $0.kind == .childSession ? $0.title : $0.runningTitle },
            recentSteps: steps.suffix(SessionChatChildActivity.recentLimit).map { step in
                SessionChatChildActivityStep(
                    id: step.id,
                    title: step.kind == .childSession ? step.title : step.listTitle,
                    isRunning: step.status.isInFlight
                )
            },
            toolCount: steps.count
        )
    }

    /// Shared by the step rail and the activity card: text and the rest render
    /// as in the standard timeline; each maximal run of steps becomes one block
    /// from `makeRun(firstStepId, steps)`.
    private static func buildStepRuns(
        parts: [MessagePart],
        makeRun: (String, [SessionChatStep]) -> SessionChatTimelineBlock
    ) -> SessionChatTimelineBlocksResult {
        var blocks: [SessionChatTimelineBlock] = []
        var pendingText: [(id: String, text: String)] = []
        var pendingSteps: [SessionChatStep] = []

        func flushText() {
            guard !pendingText.isEmpty else { return }
            let merged = pendingText.map(\.text).joined(separator: "\n")
            let ids = pendingText.map(\.id)
            pendingText.removeAll()
            guard !merged.isEmpty else { return }
            blocks.append(.text(blockID: PresentationBlockId.mergedText(ids), text: merged, renderedMarkdown: nil))
        }

        func flushRail() {
            guard let first = pendingSteps.first else { return }
            blocks.append(makeRun(first.id, pendingSteps))
            pendingSteps.removeAll()
        }

        for part in parts {
            switch part {
            case .text(let textPart):
                guard textPart.isDisplayable else { continue }
                let trimmed = textPart.text.trimmingCharacters(in: .whitespacesAndNewlines)
                guard !trimmed.isEmpty else { continue }
                guard let partId = textPart.id else { return .missingPartIds }
                flushRail()
                pendingText.append((id: partId, text: trimmed))
            case .file(let file):
                flushText()
                flushRail()
                blocks.append(
                    .attachment(
                        blockID: PresentationBlockId.direct(file.id),
                        file: AttachmentPreviewCache.shared.resolved(file)
                    )
                )
            case .notice(let notice):
                flushText()
                flushRail()
                blocks.append(noticeBlock(notice))
            case .compaction(let compaction):
                flushText()
                flushRail()
                guard let card = compactionCard(compaction) else { return .missingPartIds }
                blocks.append(card)
            case .reasoning(let reasoning):
                // A live thought is the rail's trailing Thinking step, not a step of its own.
                guard ThoughtPartChrome.isVisible(reasoning), !reasoning.isStreaming else { continue }
                guard let partId = reasoning.id else { return .missingPartIds }
                flushText()
                pendingSteps.append(thoughtStep(partId: partId, reasoning: reasoning))
            case .tool(let tool):
                guard isVisibleTool(tool) else { continue }
                guard let step = toolStep(tool) else { return .missingPartIds }
                flushText()
                pendingSteps.append(step)
            }
        }
        flushText()
        flushRail()
        return .ok(blocks)
    }

    /// A running step right after another running step is a parallel branch.
    /// A child session between them ends the branch run.
    static func markBranches(_ steps: [SessionChatStep]) -> [SessionChatStep] {
        var previousInFlight = false
        return steps.map { step in
            if step.kind == .childSession {
                previousInFlight = false
                return step
            }
            let inFlight = step.status.isInFlight
            defer { previousInFlight = inFlight }
            return step.withBranch(inFlight && previousInFlight)
        }
    }

    private static func thoughtStep(partId: String, reasoning: ReasoningPart) -> SessionChatStep {
        let duration = thoughtDurationLabel(
            thoughtDurationMs(start: reasoning.timeStartEpochMs, end: reasoning.timeEndEpochMs)
        )
        let text = ThoughtPartChrome.displayText(reasoning)
        return SessionChatStep(
            id: partId,
            kind: .thought,
            status: .completed,
            title: duration.map { "Thought \u{00B7} \($0)" } ?? "Thought",
            family: .thought,
            startedAtMs: reasoning.timeStartEpochMs,
            endedAtMs: reasoning.timeEndEpochMs,
            thoughtText: text.isEmpty ? nil : text
        )
    }

    private static func toolStep(_ tool: ToolPart) -> SessionChatStep? {
        guard let partId = tool.id, case .toolCard(_, let card)? = toToolCard(tool) else { return nil }
        let status = stepStatus(card.status)
        let inFlight = status.isInFlight
        // Kept after the step settles so an activity card can time the whole run.
        let startedAtMs = tool.timeStartEpochMs
        if isChildSessionTool(tool.toolName) {
            let agent = taskAgentName(tool)
            let description = (inputString(tool.input, keys: "description", "prompt") ?? childSessionTitle(tool))
                .flatMap(firstLine)
            let toolsSoFar = childToolCount(tool)
            let detail: String? =
                if inFlight, let toolsSoFar {
                    [description, toolsSoFar == 1 ? "1 tool so far" : "\(toolsSoFar) tools so far"]
                        .compactMap { $0 }
                        .joined(separator: " \u{00B7} ")
                } else if status == .failed, !card.isInterrupted {
                    card.errorText.flatMap(firstLine) ?? description
                } else {
                    description
                }
            return SessionChatStep(
                id: partId,
                kind: .childSession,
                status: status,
                title: agent,
                family: .agent,
                detail: nonEmpty(detail),
                startedAtMs: startedAtMs,
                isInterrupted: card.isInterrupted,
                childSession: SessionChatStepChildSession(
                    agent: agent,
                    description: description,
                    childSessionId: card.childSessionId,
                    toolsSoFar: toolsSoFar,
                    result: status == .completed ? childResult(tool) : nil
                ),
                card: card
            )
        }
        if tool.toolName.lowercased() == "invalid" {
            // The server records a call to a tool it does not offer as `invalid`
            // and completes it; on the rail it is a step that did not run.
            let requested = inputString(tool.input, keys: "tool").map(capitalizedToolName)
            return SessionChatStep(
                id: partId,
                kind: .tool,
                status: .failed,
                title: requested ?? "Unknown tool",
                detail: "Tool not available",
                card: card
            )
        }
        let isChange = ["edit", "write", "apply_patch", "patch"].contains(tool.toolName.lowercased())
        return SessionChatStep(
            id: partId,
            kind: .tool,
            status: status,
            title: stepTitle(tool, card: card, running: inFlight),
            runningTitle: stepTitle(tool, card: card, running: true),
            settledTitle: stepTitle(tool, card: card, running: false),
            family: stepFamily(tool),
            detail: stepDetail(tool, card: card, status: status),
            additions: isChange ? card.additions : nil,
            deletions: isChange ? card.deletions : nil,
            startedAtMs: startedAtMs,
            isInterrupted: card.isInterrupted,
            card: card
        )
    }

    private static func stepFamily(_ tool: ToolPart) -> SessionChatStepFamily? {
        if isQuestionTool(tool) { return nil }
        switch tool.toolName.lowercased() {
        case "read", "list", "ls": return .read
        case "grep", "glob", "websearch", "codesearch": return .search
        case "webfetch": return .fetch
        case "edit", "write", "apply_patch", "patch": return .edit
        case "bash", "shell": return .command
        default: return nil
        }
    }

    private static func stepStatus(_ status: ToolStatus) -> SessionChatStepStatus {
        switch status {
        case .pending: .pending
        case .running: .running
        case .completed: .completed
        case .error: .failed
        }
    }

    /// First result line of a finished child session, without the `task_id`
    /// header, the result tags or wrapper tags such as `<subagent sessionID="…">`.
    private static func childResult(_ tool: ToolPart) -> String? {
        guard let output = tool.output, !output.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return nil
        }
        var body = Substring(output)
        if let open = body.range(of: "<task_result>") { body = body[open.upperBound...] }
        if let close = body.range(of: "</task_result>") { body = body[..<close.lowerBound] }
        return body
            .split(whereSeparator: \.isNewline)
            .lazy
            .map { line -> String in
                var trimmed = line.trimmingCharacters(in: .whitespaces)
                while let first = trimmed.first, "#*- ".contains(first) { trimmed.removeFirst() }
                while trimmed.last == "*" { trimmed.removeLast() }
                return trimmed
            }
            .first { !$0.isEmpty && !$0.hasPrefix("task_id:") && !$0.hasPrefix("<") }
    }

    /// Tools a child session reported in its task metadata (`summary`), if any.
    private static func childToolCount(_ tool: ToolPart) -> Int? {
        guard case let .array(summary)? = tool.metadata?["summary"] else { return nil }
        return summary.count
    }

    static let stepCommandMaxLength = 40

    /// Product words, no raw tool names. A running step leads with a verb
    /// ("Reading", "Searching", …); a settled one drops it, except a shell
    /// command ("Ran …") so it still reads as a command.
    private static func stepTitle(_ tool: ToolPart, card: SessionChatToolCardBlock, running: Bool) -> String {
        func titled(_ verb: String, _ target: String?) -> String {
            guard let target = nonEmpty(target) else { return running ? "\(verb)\u{2026}" : card.title }
            return running ? "\(verb) \(target)" : target
        }
        if isQuestionTool(tool) { return card.title }
        switch tool.toolName.lowercased() {
        case "read":
            return titled("Reading", readPath(tool).map(lastPathComponent))
        case "grep", "glob":
            return titled("Searching", inputString(tool.input, keys: "pattern") ?? patternPathSubtitle(tool.input))
        case "list", "ls":
            return titled("Listing", pathSubtitle(tool.input).map(lastPathComponent) ?? inputString(tool.input, keys: "directory"))
        case "websearch":
            return titled("Searching", inputString(tool.input, keys: "query"))
        case "webfetch":
            return titled("Fetching", inputString(tool.input, keys: "url").map(hostLabel))
        case "edit", "apply_patch", "patch":
            return titled("Editing", card.subtitle.map(lastPathComponent))
        case "write":
            return titled("Writing", card.subtitle.map(lastPathComponent))
        case "bash", "shell":
            let command = (inputString(tool.input, keys: "command") ?? card.subtitle).map(stepCommandLabel)
            guard let command = nonEmpty(command) else { return running ? "Running\u{2026}" : card.title }
            return running ? "Running \(command)" : "Ran \(command)"
        case "skill":
            let name = skillName(tool) ?? card.subtitle
            guard let name = nonEmpty(name) else { return running ? "Loading skill\u{2026}" : "Skill" }
            return running ? "Loading skill \(name)" : "Skill \(name)"
        case "lsp", "plan_exit", "execute":
            return card.subtitle.map { "\(card.title) \($0)" } ?? card.title
        default:
            if let title = nonEmpty(tool.title), title.compare(tool.toolName, options: .caseInsensitive) != .orderedSame {
                return title
            }
            return capitalizedToolName(tool.toolName)
        }
    }

    /// First line of a shell command, cut at `stepCommandMaxLength` characters.
    private static func stepCommandLabel(_ command: String) -> String {
        let line = command
            .split(omittingEmptySubsequences: true, whereSeparator: \.isNewline)
            .map { $0.split(whereSeparator: \.isWhitespace).joined(separator: " ") }
            .first(where: { !$0.isEmpty }) ?? ""
        guard line.count > stepCommandMaxLength else { return line }
        return String(line.prefix(stepCommandMaxLength)).trimmingCharacters(in: .whitespaces) + "\u{2026}"
    }

    private static func capitalizedToolName(_ name: String) -> String {
        let spaced = name.replacingOccurrences(of: "_", with: " ")
        guard let first = spaced.first else { return name }
        return first.uppercased() + spaced.dropFirst()
    }

    /// One short line: why it failed, or a match count the server reported.
    private static func stepDetail(
        _ tool: ToolPart,
        card: SessionChatToolCardBlock,
        status: SessionChatStepStatus
    ) -> String? {
        if status == .failed {
            if card.isInterrupted { return card.errorText }
            if card.isMalformed { return "Not run: malformed tool call" }
            return card.errorText.flatMap(firstLine)
        }
        guard status == .completed else { return nil }
        // A settled question reads like its card: the answer under "Asked N questions".
        if isQuestionTool(tool) { return card.subtitle }
        switch tool.toolName.lowercased() {
        case "grep", "glob":
            guard let raw = tool.metadata?["matches"] ?? tool.metadata?["count"], let count = intValue(raw) else {
                return nil
            }
            return count == 1 ? "1 match" : "\(count) matches"
        default:
            return nil
        }
    }

    /// The rows a rail paints: with more than `foldThreshold` settled tool and
    /// thought steps, all but the last `foldKeepCount` of them fold into one
    /// leading "N earlier steps" row unless the rail is unfolded. Running steps
    /// and child sessions never fold. A live rail with nothing in flight ends
    /// with a Thinking row.
    static func stepRailRows(_ rail: SessionChatStepRailBlock, unfolded: Bool) -> [SessionChatStepRailRow] {
        let foldable = rail.steps.filter(\.isFoldable)
        let foldCount =
            !unfolded && foldable.count > SessionChatStepRailBlock.foldThreshold
                ? foldable.count - SessionChatStepRailBlock.foldKeepCount
                : 0
        var rows: [SessionChatStepRailRow] = []
        if foldCount > 0 {
            let folded = Array(foldable.prefix(foldCount))
            rows.append(.fold(count: foldCount, status: SessionChatStepRailBlock.worstStatus(of: folded)))
        }
        var skipped = 0
        for step in rail.steps {
            if step.isFoldable, skipped < foldCount {
                skipped += 1
                continue
            }
            rows.append(.step(step))
        }
        if rail.thinking {
            rows.append(.thinking)
        }
        return rows
    }

    /// The step rail that shows live status: the last painted block of the
    /// last row, while the session is working. Text after a rail means the turn
    /// moved on, so the Working footer speaks for it instead.
    static func liveStepRailID(in rows: [SessionChatRow], sessionWorking: Bool) -> String? {
        liveStepRunID(in: rows, sessionWorking: sessionWorking)
    }

    /// The activity card shown live: the footer's live run, or, while the
    /// server still reports the turn active (a permission or question hides
    /// the Working footer), the last run of the last row.
    static func liveActivityCardID(in rows: [SessionChatRow], footerLiveID: String?, isTurnActive: Bool) -> String? {
        if let footerLiveID { return footerLiveID }
        guard isTurnActive else { return nil }
        return liveStepRunID(in: rows, sessionWorking: true)
    }

    /// `liveStepRailID` for either step-run style: the id of the live step
    /// rail or activity card.
    static func liveStepRunID(in rows: [SessionChatRow], sessionWorking: Bool) -> String? {
        guard sessionWorking, let last = rows.last, last.role == .assistant else { return nil }
        for block in last.blocks.reversed() {
            switch block {
            case .stepRail(_, let rail):
                return rail.id
            case .activityCard(_, let card):
                return card.id
            case .stopNote, .failureLine:
                return nil
            case .reasoning(_, _, true, _):
                continue
            default:
                return nil
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
                guard chip.status == .error, !chip.card.isInterrupted else { return nil }
                if chip.card.isMalformed { return "Not run: malformed tool call" }
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
                blocks.append(
                    .attachment(
                        blockID: PresentationBlockId.direct(file.id),
                        file: AttachmentPreviewCache.shared.resolved(file)
                    )
                )
            case .compaction(let compaction):
                guard flushParagraph(), let card = compactionCard(compaction) else { return .missingPartIds }
                blocks.append(card)
            case .notice(let notice):
                guard flushParagraph() else { return .missingPartIds }
                blocks.append(noticeBlock(notice))
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
            subtitle: exploredGroupSubtitle(tools),
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
                label = readPath(tool).map(lastPathComponent) ?? (card.isReceivingArguments ? card.subtitle : nil)
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
            case "edit", "write", "apply_patch", "patch":
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

    /// Builds the blocks Session Chat shows for one message: part blocks, then
    /// the slash token for a skill admitted beside the user's text, then
    /// template collapse. Skill admission has to happen before collapse so a
    /// skill-plus-image send displays as one `[/name] args` row and matches the
    /// optimistic send instead of leaving a second copy of the image.
    static func present(
        message: Message,
        commands: [ServerCommand],
        collapseConsecutiveToolCalls: Bool = false,
        chipTimeline: Bool = false,
        stepRail: Bool = false,
        activityCard: Bool = false
    ) -> [SessionChatTimelineBlock] {
        let built = buildOrFallback(
            role: message.role,
            parts: message.parts,
            messageId: message.id,
            fallbackText: message.text,
            collapseConsecutiveToolCalls: collapseConsecutiveToolCalls,
            chipTimeline: chipTimeline,
            stepRail: stepRail,
            activityCard: activityCard
        )
        return collapseCommandInvocations(
            role: message.role,
            blocks: admitPromptSkills(
                role: message.role,
                blocks: built,
                skills: message.promptSkills,
                commands: commands
            ),
            commands: commands
        )
    }

    /// A v2 skill prompt stores the user's arguments as the message text and the
    /// skill beside it. Restore `/name` so collapse can show `[/name] args` on
    /// that same row. Skip when the text is already a slash invocation or an
    /// expanded template — prefixing those would hide the command collapse.
    static func admitPromptSkills(
        role: MessageRole,
        blocks: [SessionChatTimelineBlock],
        skills: [PromptSkill],
        commands: [ServerCommand]
    ) -> [SessionChatTimelineBlock] {
        guard role == .user, !skills.isEmpty else { return blocks }
        var names: [String] = []
        for skill in skills {
            guard let name = Self.slashName(skill, commands: commands), !names.contains(name) else { continue }
            names.append(name)
        }
        guard !names.isEmpty else { return blocks }
        let textIndex = blocks.firstIndex { block in
            if case .text = block { return true }
            return false
        }
        let existing: String
        if let textIndex, case let .text(_, text, _) = blocks[textIndex] {
            existing = text
        } else {
            existing = ""
        }
        if !existing.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
           CommandInvocationCollapser.collapse(text: existing, commands: commands) != nil
        {
            return blocks
        }
        let missing = names.filter { !alreadyAdmitted(existing, name: $0, skills: skills) }
        guard !missing.isEmpty else { return blocks }
        let prefix = missing.map { "/\($0)" }.joined(separator: " ")
        let trimmedExisting = existing.trimmingCharacters(in: .whitespaces)
        let nextText = trimmedExisting.isEmpty ? prefix : "\(prefix) \(trimmedExisting)"
        if let textIndex {
            var next = blocks
            let blockID = blocks[textIndex].blockID
            next[textIndex] = .text(blockID: blockID, text: nextText, renderedMarkdown: nil)
            return next
        }
        let inserted = SessionChatTimelineBlock.text(
            blockID: PresentationBlockId.direct("prompt-skill:\(names[0])"),
            text: prefix,
            renderedMarkdown: nil
        )
        return [inserted] + blocks
    }

    private static func slashName(_ skill: PromptSkill, commands: [ServerCommand]) -> String? {
        let id = skill.id.trimmingCharacters(in: .whitespacesAndNewlines)
        let name = skill.name.trimmingCharacters(in: .whitespacesAndNewlines)
        let fromCatalog = commands.first { command in
            let skillID = command.skillID?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            return !skillID.isEmpty && (skillID == id || skillID == name)
        }?.name ?? commands.first { $0.name == name || (!id.isEmpty && $0.name == id) }?.name
        let catalog = fromCatalog?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        if !catalog.isEmpty { return catalog }
        if !name.isEmpty { return name }
        return id.isEmpty ? nil : id
    }

    private static func alreadyAdmitted(_ text: String, name: String, skills: [PromptSkill]) -> Bool {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.hasPrefix("[/\(name)]") || startsWithSlashToken(trimmed, name: name) { return true }
        return skills.contains { skill in
            let mention = skill.mentionText?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            return !mention.isEmpty && text.contains(mention) && (skill.name == name || skill.id == name)
        }
    }

    private static func startsWithSlashToken(_ text: String, name: String) -> Bool {
        let token = "/\(name)"
        guard text.hasPrefix(token) else { return false }
        if text.count == token.count { return true }
        let next = text[text.index(text.startIndex, offsetBy: token.count)]
        return next.isWhitespace
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
        case .file, .notice:
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
            return exploredGroupSubtitle(tools)
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

    /// One explore tool keeps its argument (a glob pattern, a path); several are `4 tools` (the title
    /// already counts each kind, and each row keeps its own argument).
    private static func exploredGroupSubtitle(_ tools: [ToolPart]) -> String? {
        if tools.count == 1, let tool = tools.first { return contextSubtitle(tool) }
        return "\(tools.count) tools"
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
        case "read": return readPath(tool)
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
        case "apply_patch", "patch": return count == 1 ? "Patch" : "\(count) patches"
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
        let interrupted = failedExit == nil && tool.status == .error && tool.isInterrupted
        let errorText: String? =
            if let failedExit {
                "Exited with code \(failedExit)"
            } else if interrupted {
                interruptedToolText(tool.error)
            } else if tool.status == .error {
                tool.error.flatMap { $0.isEmpty ? nil : $0 }
            } else {
                nil
            }
        let receivingArguments = ToolArgumentsPreview.isReceiving(tool)
        return .toolCard(
            blockID: PresentationBlockId.direct(partId),
            card: SessionChatToolCardBlock(
                toolName: tool.toolName,
                status: failedExit != nil ? .error : tool.status,
                title: chrome.title,
                subtitle: chrome.subtitle ?? (receivingArguments ? receivingArgumentsPlaceholder(name) : nil),
                errorText: errorText,
                additions: chrome.additions,
                deletions: chrome.deletions,
                detailText: chrome.detailText,
                childSessionId: chrome.childSessionId,
                opensChangesReview: chrome.opensChangesReview,
                changesInitialFilePath: chrome.changesInitialFilePath,
                isInterrupted: interrupted,
                isMalformed: tool.isMalformed,
                isReceivingArguments: receivingArguments,
                presentation: toolResultPresentation(tool, fallbackTitle: chrome.title)
            )
        )
    }

    /// Classifies the part for the inspector sheet. Duration is omitted: a tool
    /// part has a start but no end, and an exit is taken only from metadata.
    private static func toolResultPresentation(
        _ tool: ToolPart,
        fallbackTitle: String
    ) -> ToolResultPresentation? {
        ToolResultInspector.classify(
            ToolResultRequest(
                toolName: tool.toolName,
                fallbackTitle: fallbackTitle,
                command: inputString(tool.input, keys: "command"),
                path: inputString(tool.input, keys: "filePath", "file", "path"),
                pattern: inputString(tool.input, keys: "pattern"),
                searchPath: inputString(tool.input, keys: "path", "directory"),
                offset: inputInt(tool.input, key: "offset"),
                limit: inputInt(tool.input, key: "limit"),
                content: inputString(tool.input, keys: "content"),
                output: nonBlank(tool.output),
                error: nonBlank(tool.error),
                exitCode: metadataInt(tool.metadata, key: "exit"),
                patch: toolPatch(tool),
                oldString: inputString(tool.input, keys: "oldString"),
                newString: inputString(tool.input, keys: "newString")
            )
        )
    }

    private static func toolPatch(_ tool: ToolPart) -> String? {
        if let patch = metadataFilediffString(tool.metadata, key: "patch") {
            return patch
        }
        if case let .array(files)? = tool.metadata?["files"] {
            for element in files {
                guard case let .dictionary(object) = element else { continue }
                if let patch = stringFromObject(object, keys: "patch") {
                    return patch
                }
            }
        }
        if let output = tool.output, output.contains("@@") {
            return output
        }
        return nil
    }

    private static func inputInt(_ input: [String: AnyCodable]?, key: String) -> Int? {
        guard let value = input?[key] else { return nil }
        return intValue(value)
    }

    private static func metadataInt(_ metadata: [String: AnyCodable]?, key: String) -> Int? {
        guard let value = metadata?[key] else { return nil }
        return intValue(value)
    }

    private static func nonBlank(_ value: String?) -> String? {
        guard let value, !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
        return value
    }

    /// What a card says while its arguments stream in and none can be read yet (TUI v2 copy).
    private static func receivingArgumentsPlaceholder(_ name: String) -> String? {
        switch name {
        case "bash", "shell": "Writing command…"
        case "read": "Preparing read…"
        case "edit": "Preparing edit…"
        case "write": "Preparing write…"
        default: nil
        }
    }

    /// Neutral copy for a tool the user stopped or declined (never the raw abort text).
    static func interruptedToolText(_ error: String?) -> String {
        let text = (error ?? "").lowercased()
        return text.contains("declined") || text.contains("rejected") ? "Declined" : "Stopped"
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
            return contextChrome(tool: tool, subtitle: readPath(tool))
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
        case "apply_patch", "patch":
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
            ?? ToolArgumentsPreview.string(tool, keys: "filePath", "path", "file")
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
        let command = inputString(tool.input, keys: "command")
            ?? runningTitleOverride(tool)
            ?? ToolArgumentsPreview.string(tool, keys: "command")
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
            let parts = [
                tool.status == .error ? nonEmpty(tool.error) : nil,
                nonEmpty(tool.output),
            ].compactMap { $0 }
            return parts.isEmpty ? nil : parts.joined(separator: "\n\n")
        case .pending, .running:
            return nil
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
            guard key != ToolArgumentsPreview.inputKey, let text = scalarString(value), !text.isEmpty, text.count <= 80 else { return nil }
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

    /// Read chrome is the file, settled or still streaming in: v1 sends `filePath`, v2 sends `path`.
    private static func readPath(_ tool: ToolPart) -> String? {
        inputString(tool.input, keys: "filePath", "file", "path")
            ?? ToolArgumentsPreview.string(tool, keys: "filePath", "file", "path")
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
