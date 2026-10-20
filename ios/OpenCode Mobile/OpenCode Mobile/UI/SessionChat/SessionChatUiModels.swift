import Foundation

struct SessionChatRow: Identifiable, Equatable {
    let id: String
    let role: MessageRole
    let text: String
    let metadataLabel: String
    let pendingDelivery: FollowUpDelivery?
    let isPending: Bool
    let isStreaming: Bool
    let renderedMarkdown: NativeMarkdownDocument?
    let blocks: [SessionChatTimelineBlock]
    let modelProviderID: String?
    let modelID: String?
    let contextInputTokens: Int64?
    /// Working-row only: reserved list identity collapsed after the Idle hold.
    let isLayoutCollapsed: Bool

    init(
        id: String,
        role: MessageRole,
        text: String,
        metadataLabel: String,
        pendingDelivery: FollowUpDelivery? = nil,
        isPending: Bool = false,
        isStreaming: Bool = false,
        renderedMarkdown: NativeMarkdownDocument? = nil,
        blocks: [SessionChatTimelineBlock] = [],
        modelProviderID: String? = nil,
        modelID: String? = nil,
        contextInputTokens: Int64? = nil,
        isLayoutCollapsed: Bool = false
    ) {
        self.id = id
        self.role = role
        self.text = text
        self.metadataLabel = metadataLabel
        self.pendingDelivery = pendingDelivery
        self.isPending = isPending
        self.isStreaming = isStreaming
        self.renderedMarkdown = renderedMarkdown
        self.blocks = blocks
        self.modelProviderID = modelProviderID
        self.modelID = modelID
        self.contextInputTokens = contextInputTokens
        self.isLayoutCollapsed = isLayoutCollapsed
    }

    static func == (lhs: SessionChatRow, rhs: SessionChatRow) -> Bool {
        guard lhs.id == rhs.id,
              lhs.role == rhs.role,
              lhs.isPending == rhs.isPending,
              lhs.isStreaming == rhs.isStreaming,
              lhs.modelProviderID == rhs.modelProviderID,
              lhs.modelID == rhs.modelID,
              lhs.contextInputTokens == rhs.contextInputTokens,
              lhs.renderedMarkdown?.sourceHash == rhs.renderedMarkdown?.sourceHash,
              lhs.renderedMarkdown?.renderTreeVersion == rhs.renderedMarkdown?.renderTreeVersion,
              lhs.renderedMarkdown?.blocks.count == rhs.renderedMarkdown?.blocks.count,
              lhs.text.count == rhs.text.count,
              lhs.metadataLabel == rhs.metadataLabel,
              lhs.pendingDelivery == rhs.pendingDelivery,
              lhs.isLayoutCollapsed == rhs.isLayoutCollapsed,
              lhs.blocks.count == rhs.blocks.count
        else { return false }
        // Huge assistant replies: compare text only after cheap fields match.
        return lhs.text == rhs.text && lhs.blocks == rhs.blocks
    }
}

extension SessionChatRow: MessageListTimelineHeightEstimating {
    var timelineRowHeightEstimate: CGFloat {
        if id == SessionChatStatusChrome.workingRowID {
            if isLayoutCollapsed { return 0 }
            return WorkingTurnIndicator.rowHeight
        }
        let style: MessageListTimelineRowHeightEstimate.Style =
            switch role {
            case .user:
                .outgoing
            case .assistant:
                .assistant
            case .notice:
                .line
            case .tool, .command, .unknown:
                .compact
            }
        return MessageListTimelineRowHeightEstimate.fallback(
            characterCount: text.count,
            style: style
        )
    }
}

struct SessionChatComposerState: Equatable {
    var text: String
    var attachments: [Attachment] = []
    var selectedModel: ModelSelection?
    var modelOptions: [ModelSelectionOption]
    var selectedMode: String
    var selectableAgents: [SelectableAgent] = SelectableAgent.fallback
    var selectedVariant: String?
    var modelNotice: String?
    var isModelSelectionReady = false
    var modelCatalogError: String?
    var agentMentions: [AgentMention] = []
    var fileMentions: [FileMention] = []
    var cursor: Int = 0
    var selectionRevision: Int = 0
    var followUpDelivery: FollowUpDelivery = .steer
    var supportsFollowUpDelivery = false

    var selectedModeLabel: String {
        selectableAgents.first { $0.id.compare(selectedMode, options: .caseInsensitive) == .orderedSame }?.displayName
            ?? SelectableAgent.displayName(for: selectedMode)
    }

    var selectedModelLabel: String {
        isModelSelectionReady ? selectedModel?.modelLabel ?? "Model" : "Loading model…"
    }

    var selectedVariantLabel: String {
        TitleCaseIdentifier.displayName(for: selectedVariant ?? ModelSelection.defaultVariantLabel)
    }

    var variantOptions: [String] {
        [ModelSelection.defaultVariantLabel] + (selectedModel?.option.variants ?? [])
    }

    var canSelectVariant: Bool {
        selectedModel?.option.supportsVariants == true
    }

    var mentionRanges: [Range<Int>] {
        (agentMentions.map { $0.start ..< $0.end } + fileMentions.map { $0.start ..< $0.end })
            .sorted { $0.lowerBound < $1.lowerBound }
    }

    /// A leading `!` makes Send run the draft as a shell command; see `ComposerShellCommand`.
    var isShellMode: Bool {
        ComposerShellCommand.isShellMode(text)
    }

    var hasSendableContent: Bool {
        if isShellMode {
            return ComposerShellCommand.command(text) != nil
        }
        return !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || !attachments.isEmpty
    }

    var canAttach: Bool {
        selectedModel?.option.canAttach == true
    }

    var canAttachPhotos: Bool {
        selectedModel?.option.canAttachPhotos == true
    }

    var canAttachFiles: Bool {
        selectedModel?.option.canAttachFiles == true
    }

    func attachmentSources(developerToolsUnlocked: Bool) -> ComposerAttachmentSources {
        ComposerAttachmentSources(
            photos: canAttachPhotos,
            files: canAttachFiles,
            logFile: developerToolsUnlocked
        )
    }
}

struct ComposerAttachmentSources: Equatable {
    let photos: Bool
    let files: Bool
    let logFile: Bool

    var isAttachEnabled: Bool { photos || files || logFile }
    var showsSourceSheet: Bool { logFile || (photos && files) }
}

enum SessionChatTimelineState: Equatable {
    case creating
    case loading
    case empty
    case ready
    case error
}

struct SessionRenameUiState: Equatable {
    var isDialogVisible = false
    var draftTitle = ""
    var isSubmitting = false
    var errorMessage: String?
}

enum SessionChatSnackbarTone: Equatable {
    case `default`
    case error
}

struct SessionChatSnackbar: Equatable, Identifiable {
    let id: UInt64
    let message: String
    let tone: SessionChatSnackbarTone
}

enum ComposerDictationPhase: Equatable {
    case listening
    case finishing
    case permissionDenied
    case modelNotInstalled
    case modelDownloading
    case modelDownloadFailed
    case recognizerError

    /// A card about the on-device speech model; download updates may replace it.
    var isModelPhase: Bool {
        switch self {
        case .modelNotInstalled, .modelDownloading, .modelDownloadFailed: true
        case .listening, .finishing, .permissionDenied, .recognizerError: false
        }
    }
}

struct ComposerDictationUiState: Equatable {
    var phase: ComposerDictationPhase
    var committed: String = ""
    var volatile: String = ""
    var level: Double = 0
    var languageLabel: String = ""
    var onDevice: Bool = true
    var startedAt: Date = .init()
    var errorMessage: String? = nil
    /// Model download progress, 0...1, while `phase` is `.modelDownloading`; nil when unknown.
    var downloadProgress: Double? = nil
}

struct SessionChatUiState: Equatable {
    var sessionID: String
    var sessionTitle: String
    /// Smart project label for top-bar chrome; nil when worktree missing.
    var projectLabel: String? = nil
    var projectLetter: Character? = nil
    var projectLetterAccent: LetterAccent? = nil
    /// True when the subtitle is a worktree checkout name instead of the project letter.
    var worktreeCheckout: Bool = false
    var sessionBranchPill: SessionChatBranchPillUiState? = nil
    var timeline: SessionChatTimelineState
    var messages: [SessionChatRow]
    var pendingPrompts: [PendingPrompt] = []
    /// Projected timeline frame with declared ID sets. Reused when only
    /// composer / session-info / review / command republish.
    var timelineFrame: TimelineFrame = .empty
    var composer: SessionChatComposerState
    var isSending: Bool = false
    var isRefreshing: Bool = false
    var hasOlder: Bool = false
    var isLoadingOlder: Bool = false
    var isAborting: Bool = false
    var sessionStatusLabel: String?
    /// In-chat Working/Thinking/Retrying label for an active turn.
    var workingIndicatorLabel: String? = nil
    /// Live rate-limit countdown. Hidden for content-filter retries.
    var retryCard: SessionRetryCard? = nil
    /// True after the Idle hold when the reserved footer has collapsed.
    var workingIndicatorCollapsed: Bool = false
    /// True while the reserved Working lamp/label is fading through the idle hold.
    var workingIndicatorFading: Bool = false
    /// Top Syncing pill label while cached or catching-up messages wait for network confirmation.
    var syncingIndicatorLabel: String? = nil
    var sessionTodos: [SessionTodo] = []
    var sessionTodosVisible: Bool = false
    var sessionTodosFinished: Bool = false
    var sessionTodoActivePreview: SessionTodo?
    var sessionTodoCompletedCount: Int = 0
    var sessionTodoTotalCount: Int = 0
    var connectionStatus: SessionConnectionStatus = .offline
    var canMutate = false
    var composerEnabled = false
    var pendingSend: PendingSend? = nil
    var messagesFromCache: Bool = false
    var contextUsageFraction: Double?
    var errorMessage: String?
    var snackbar: SessionChatSnackbar?
    var isLifecycleActionInFlight: Bool = false
    var shouldLeaveSession: Bool = false
    var permissionRequest: PermissionRequest?
    var permissionQueueCount: Int = 0
    /// The permission dock is collapsed to a "Review permission" pill above the composer.
    var permissionDockHidden: Bool = false
    var permissionSubmitting: Bool = false
    var permissionErrorMessage: String?
    var questionRequest: QuestionRequest?
    var questionWizardIndex: Int = 0
    var questionSelectedLabels: [String] = []
    var questionCustomText = ""
    var questionCustomSelected = false
    var questionSubmitting = false
    var questionErrorMessage: String?
    var reviewChangesMessageID: String?
    var reviewChangesSummary: ReviewChangesSummary?
    var supportsSessionMessageDiff: Bool = true
    var supportsSessionArchive: Bool = true
    var rename = SessionRenameUiState()
    var slashSuggestionsVisible = false
    var slashSuggestions: [SessionChatSlashSuggestion] = []
    var atSuggestionsVisible = false
    var atSuggestions: [SessionChatAtSuggestion] = []
    var commandsSheetVisible = false
    var visibleClientSlashActions: [ClientSlashAction] = []
    var mcpPageVisible = false
    var mcpServers: [McpServer] = []
    var mcpLoading = false
    var mcpErrorMessage: String?
    var mcpSummary: McpServerSummary = .empty
    var forkDialogVisible = false
    var forkDraftTitle = ""
    var forkSubmitting = false
    var forkErrorMessage: String?
    var forkResultVisible = false
    var forkedSessionTitle = ""
    var pendingSessionReplace: ChildSessionNavigationTarget?
    var pendingTerminalOpen: PendingTerminalOpen?
    var pendingFilesOpen: PendingFilesOpen?
    var hasPendingRevert = false
    /// Chip-timeline step paragraphs expanded past `+N more`:
    /// `SessionChatStepParagraphBlock.id` to the chip count at tap time.
    var expandedStepParagraphs: [String: Int] = [:]
    /// One-time Chip timeline intro sheet (OPE-270).
    var chipTimelineIntroVisible = false
    var dictation: ComposerDictationUiState? = nil

    /// A permission, question, or Screen Model-owned sheet or dialog would sit under the intro sheet.
    var blocksChipTimelineIntro: Bool {
        permissionRequest != nil
            || questionRequest != nil
            || commandsSheetVisible
            || mcpPageVisible
            || forkDialogVisible
            || forkResultVisible
            || rename.isDialogVisible
    }

    var latestCompletedUserMessageID: String? {
        var latest: String?
        var candidate: SessionChatRow?
        for message in messages {
            if message.role == .user {
                candidate = message.isPending || message.isStreaming ? nil : message
            } else if message.role != .notice, let candidate, !message.isPending, !message.isStreaming {
                latest = candidate.id
            }
        }
        return latest
    }

    func changesNavigationTarget(worktree: String) -> ChangesNavigationTarget {
        ChangesNavigationTarget(
            sessionID: sessionID,
            worktree: worktree,
            messageID: latestCompletedUserMessageID,
            initialSource: .workingTree
        )
    }

    func reviewChangesNavigationTarget(worktree: String) -> ChangesNavigationTarget? {
        guard supportsSessionMessageDiff,
              reviewChangesSummary?.fileCount ?? 0 > 0,
              let messageID = reviewChangesMessageID else { return nil }
        return ChangesNavigationTarget(
            sessionID: sessionID,
            worktree: worktree,
            messageID: messageID,
            initialSource: .lastTurn
        )
    }

    func owningTurnMessageID(assistantMessageID: String) -> String? {
        var candidate: String?
        for message in messages {
            if message.id == assistantMessageID {
                return candidate
            }
            if message.role == .user, !message.isPending, !message.isStreaming {
                candidate = message.id
            }
        }
        return nil
    }

    func fileChangeNavigationTarget(
        worktree: String,
        assistantMessageID: String,
        initialFilePath: String?
    ) -> ChangesNavigationTarget? {
        guard supportsSessionMessageDiff,
              let messageID = owningTurnMessageID(assistantMessageID: assistantMessageID) else { return nil }
        return ChangesNavigationTarget(
            sessionID: sessionID,
            worktree: worktree,
            messageID: messageID,
            initialSource: .lastTurn,
            initialFilePath: initialFilePath
        )
    }

    static func initial(
        sessionID: String,
        sessionTitle: String,
        timeline: SessionChatTimelineState = .loading
    ) -> SessionChatUiState {
        SessionChatUiState(
            sessionID: sessionID,
            sessionTitle: sessionTitle,
            timeline: timeline,
            messages: [],
            timelineFrame: .empty,
            composer: SessionChatComposerState(
                text: "",
                selectedModel: nil,
                modelOptions: [],
                selectedMode: SelectableAgent.defaultID,
                selectedVariant: nil,
                modelNotice: nil
            )
        )
    }
}

enum SessionChatComposerKind: Equatable {
    case permission
    case question
    case childLockout
    case prompt

    static func resolve(isChildSession: Bool, hasPermission: Bool, hasQuestion: Bool) -> SessionChatComposerKind {
        if hasPermission { return .permission }
        if hasQuestion { return .question }
        if isChildSession { return .childLockout }
        return .prompt
    }
}

enum ReviewChangesPillVisibility {
    static func shouldShow(summary: ReviewChangesSummary?) -> Bool {
        (summary?.fileCount ?? 0) > 0
    }
}

enum SessionBranchPillVisibility {
    static func shouldShow(pill: SessionChatBranchPillUiState?, hideFloatStack: Bool) -> Bool {
        pill != nil && !hideFloatStack
    }
}

/// The one pill shown at the top of the Session Chat timeline.
/// Priority: Syncing, then ToDo, then Branch / Changes.
enum SessionChatTopPill: Equatable {
    case syncing(label: String)
    case todos
    case branch(SessionChatBranchPillUiState)

    /// Syncing waits this long before it takes the slot, so a fast catch-up
    /// does not flash the ToDo or Branch pill out and back in.
    static let syncingShowDelay: Duration = .milliseconds(400)
    /// Once shown, Syncing keeps the slot at least this long.
    static let syncingMinimumVisible: Duration = .milliseconds(600)

    static func resolve(
        syncingLabel: String?,
        todosVisible: Bool,
        todoTotalCount: Int,
        branchPill: SessionChatBranchPillUiState?,
        hideFloatStack: Bool
    ) -> SessionChatTopPill? {
        if let syncingLabel {
            return .syncing(label: syncingLabel)
        }
        if !hideFloatStack, todosVisible, todoTotalCount > 0 {
            return .todos
        }
        if SessionBranchPillVisibility.shouldShow(pill: branchPill, hideFloatStack: hideFloatStack),
           let branchPill {
            return .branch(branchPill)
        }
        return nil
    }

    /// Stable per pill kind so label or count updates do not re-run the swap transition.
    var kind: Int {
        switch self {
        case .syncing: 0
        case .todos: 1
        case .branch: 2
        }
    }
}

struct ReviewChangesSummary: Equatable {
    let fileCount: Int
    let additions: Int
    let deletions: Int
    let messageID: String?

    init(fileCount: Int, additions: Int, deletions: Int, messageID: String? = nil) {
        self.fileCount = fileCount
        self.additions = additions
        self.deletions = deletions
        self.messageID = messageID
    }

    init?(files: [FileDiff], messageID: String? = nil) {
        guard !files.isEmpty else { return nil }
        self.init(
            fileCount: files.count,
            additions: files.reduce(0) { $0 + $1.additions },
            deletions: files.reduce(0) { $0 + $1.deletions },
            messageID: messageID
        )
    }
}

struct ChangesNavigationTarget: Hashable {
    let sessionID: String
    let worktree: String
    let messageID: String?
    let initialSource: ChangeSource
    let initialFilePath: String?

    init(
        sessionID: String,
        worktree: String,
        messageID: String?,
        initialSource: ChangeSource = .workingTree,
        initialFilePath: String? = nil
    ) {
        self.sessionID = sessionID
        self.worktree = worktree
        self.messageID = messageID
        self.initialSource = initialSource
        self.initialFilePath = initialFilePath
    }
}

/// A session Session Chat pushes: a child (subagent) session, a fork, or a new session.
/// Only a child carries `parentID`; forks and new sessions are top-level and promptable.
struct ChildSessionNavigationTarget: Hashable {
    let sessionID: String
    let sessionTitle: String
    let worktree: String
    let optimisticNavigationInstanceID: UUID?
    let parentID: String?

    init(
        sessionID: String,
        sessionTitle: String,
        worktree: String,
        optimisticNavigationInstanceID: UUID?,
        parentID: String? = nil
    ) {
        self.sessionID = sessionID
        self.sessionTitle = sessionTitle
        self.worktree = worktree
        self.optimisticNavigationInstanceID = optimisticNavigationInstanceID
        self.parentID = parentID
    }

    var sessionListItem: SessionListItem {
        SessionListItem(
            id: sessionID,
            title: sessionTitle,
            worktree: worktree,
            optimisticNavigationInstanceID: optimisticNavigationInstanceID,
            parentID: parentID
        )
    }
}

struct SessionChatTimelineRegionState: Equatable {
    var timeline: SessionChatTimelineState = .loading
    var messages: [SessionChatRow] = []
    var isRefreshing: Bool = false
    var messagesFromCache: Bool = false
    var isSyncingMessages: Bool = false
    var hasOlder: Bool = false
    var isLoadingOlder: Bool = false
    var errorMessage: String?

    var knownChildSessionIDs: Set<String> {
        Set(
            messages.flatMap { message in
                message.blocks.flatMap { block -> [String] in
                    switch block {
                    case .childSessionRow(_, let row):
                        [row.childSessionId].compactMap { $0 }
                    case .toolCard(_, let card):
                        if card.status == .pending || card.status == .running {
                            [card.childSessionId].compactMap { $0 }
                        } else {
                            []
                        }
                    case .stepParagraph(_, let paragraph):
                        paragraph.chips.compactMap { chip in
                            chip.status == .pending || chip.status == .running ? chip.card.childSessionId : nil
                        }
                    default:
                        []
                    }
                }
            }
        )
    }
}

struct SessionChatSlashSuggestion: Equatable, Identifiable {
    let id: String
    let title: String
    let subtitle: String?
    let badge: String?
    let commandName: String
}

struct SessionChatAtSuggestion: Equatable, Identifiable {
    let id: String
    let title: String
    let subtitle: String?
    let agentName: String?
    let filePath: String?
    let isDirectory: Bool

    init(
        id: String,
        title: String,
        subtitle: String?,
        agentName: String? = nil,
        filePath: String? = nil,
        isDirectory: Bool = false
    ) {
        self.id = id
        self.title = title
        self.subtitle = subtitle
        self.agentName = agentName
        self.filePath = filePath
        self.isDirectory = isDirectory
    }
}

extension SessionChatAtSuggestion {
    /// File suggestions show their file-type icon; agent suggestions show the agent glyph.
    var leadingIcon: OpenCodeComposerSuggestionItem.LeadingIcon? {
        if let filePath {
            return .file(path: filePath, kind: isDirectory ? .directory : .file)
        }
        return agentName == nil ? nil : .icon(.agent)
    }
}

struct SessionChatComposerRegionState: Equatable {
    var composer: SessionChatComposerState = SessionChatComposerState(
        text: "",
        selectedModel: nil,
        modelOptions: [],
        selectedMode: SelectableAgent.defaultID,
        selectedVariant: nil,
        modelNotice: nil
    )
    var isSending: Bool = false
    var sendProgressPercent: Int? = nil
    var isAborting: Bool = false
    var errorMessage: String?
    var abortErrorMessage: String?
    var serverCommands: [ServerCommand] = []
    var mentionableAgents: [MentionableAgent] = []
    var slashSuggestionsVisible = false
    var slashSuggestions: [SessionChatSlashSuggestion] = []
    var atSuggestionsVisible = false
    var atSuggestions: [SessionChatAtSuggestion] = []
    var fileSuggestions: [SessionChatAtSuggestion] = []
    var fileSuggestionQuery: String?
    var followUpNotice: String?
}

extension ServerCommand {
    func toSlashSuggestion() -> SessionChatSlashSuggestion {
        SessionChatSlashSuggestion(
            id: name,
            title: "/\(name)",
            subtitle: description,
            badge: {
                switch source {
                case .skill: "skill"
                case .mcp: "mcp"
                case .command: nil
                }
            }(),
            commandName: name
        )
    }
}

extension MentionableAgent {
    func toAtSuggestion() -> SessionChatAtSuggestion {
        SessionChatAtSuggestion(
            id: name,
            title: "@\(name)",
            subtitle: description,
            agentName: name
        )
    }
}

extension ProjectEntry {
    func toFileSuggestion() -> SessionChatAtSuggestion {
        SessionChatAtSuggestion(
            id: "file:\(relativePath)",
            title: relativePath,
            subtitle: "File",
            filePath: relativePath,
            isDirectory: isDirectory
        )
    }
}

struct SessionInfoRegionState: Equatable {
    var sessionID: String = ""
    var sessionTitle: String = ""
    var sessionStatusLabel: String?
    var retryCard: SessionRetryCard?
    var hasResolvedSessionStatus = false
    var connectionStatus: SessionConnectionStatus = .offline
    var canMutate = false
    var composerEnabled = false
    var rename = SessionRenameUiState()
    var isLifecycleActionInFlight = false
    var lifecycleErrorMessage: String?
    var shouldLeaveSession = false
    var errorMessage: String?

    mutating func applyStatus(
        authoritative: String?,
        hasResolved: Bool = true,
        retryCard: SessionRetryCard? = nil
    ) {
        sessionStatusLabel = authoritative
        hasResolvedSessionStatus = hasResolved
        self.retryCard = retryCard
    }
}

struct PendingSend: Equatable {
    let queuedAtMs: Int64
}

struct SessionChatInboxRegionState: Equatable {
    var permissionRequests: [PermissionRequest] = []
    /// The pending permission the user tucked away to reach the composer (never rejects it).
    var permissionHiddenID: String?
    var permissionSubmittingID: String?
    var permissionError: String?
    var permissionTopologyError: String?
    var questionRequests: [QuestionRequest] = []
    var questionSubmittingID: String?
    var questionError: String?
    var questionTopologyError: String?
    var questionWizardIndex = 0
    var questionDrafts: [Int: QuestionAnswerDraft] = [:]
    var sessionTodos: [SessionTodo] = []

    var visibleQuestionRequest: QuestionRequest? {
        permissionRequests.isEmpty ? questionRequests.first : nil
    }

    /// True while the first pending permission is the one the user hid; a new request shows again.
    var isPermissionDockHidden: Bool {
        permissionHiddenID != nil && permissionRequests.first?.id == permissionHiddenID
    }

    var questionDraft: QuestionAnswerDraft {
        questionDrafts[questionWizardIndex] ?? QuestionAnswerDraft()
    }
}

struct SessionChatReviewChangesRegionState: Equatable {
    var messageID: String?
    var summary: ReviewChangesSummary?
}

struct SessionChatRepoStripRegionState: Equatable {
    /// False until the server reports a current branch for the worktree.
    var isAvailable = false
    var branch = ""
    var defaultBranch: String?
    var files: [VcsFileStatus] = []
    var isExpanded = false
}

/// Floating session branch pill. Nil when the worktree branch is unknown.
struct SessionChatBranchPillUiState: Equatable {
    let branch: String
    let additions: Int
    let deletions: Int

    static func resolved(from region: SessionChatRepoStripRegionState) -> SessionChatBranchPillUiState? {
        guard region.isAvailable, !region.branch.isEmpty else { return nil }
        return SessionChatBranchPillUiState(
            branch: region.branch,
            additions: max(0, region.files.reduce(0) { $0 + $1.additions }),
            deletions: max(0, region.files.reduce(0) { $0 + $1.deletions })
        )
    }
}

struct SessionChatCommandsRegionState: Equatable {
    var commandsSheetVisible = false
    var visibleActions: [ClientSlashAction] = []
    var mcpPageVisible = false
    var mcpServers: [McpServer] = []
    var mcpLoading = false
    var mcpErrorMessage: String?
    var mcpSummary: McpServerSummary = .empty
    var forkDialogVisible = false
    var forkDraftTitle = ""
    var forkSubmitting = false
    var forkErrorMessage: String?
    var forkResultVisible = false
    var forkedSessionTitle = ""
    var isCompacting = false
    var actionErrorMessage: String?
    var actionSuccessMessage: String?
    var pendingSessionReplace: ChildSessionNavigationTarget?
    var pendingTerminalOpen: PendingTerminalOpen?
    var pendingFilesOpen: PendingFilesOpen?
    var hasPendingRevert = false
}

struct PendingTerminalOpen: Equatable {
    let worktree: String
    let projectTitle: String
}

/// Files opened from the Commands sheet, rooted at the Session Chat worktree.
struct PendingFilesOpen: Equatable {
    let worktree: String
}

struct LatestModelHint: Equatable {
    let providerID: String
    let modelID: String
    let variant: String?
    let sentAtEpochMs: Int64?
    let source: ModelSelectionHistorySource
}

struct LatestAgentHint: Equatable {
    let mode: String
    let sentAtEpochMs: Int64?
}

struct ModelSelectionHistoryHints: Equatable {
    let user: LatestModelHint?
    let assistant: LatestModelHint?
    let userAgent: LatestAgentHint?
    let assistantAgent: LatestAgentHint?
}

enum ModelSelectionHistorySource: Equatable {
    case user
    case assistant
}

enum SessionConnectionStatus: String, Equatable, Sendable {
    case live
    case connecting
    case offline
    case authFailed

    init(label: LiveUpdatesLabel) {
        switch label {
        case .live:
            self = .live
        case .connecting:
            self = .connecting
        case .offline:
            self = .offline
        case .authFailed:
            self = .authFailed
        }
    }

    var openCodeStatus: OpenCodeSessionConnectionStatus {
        switch self {
        case .live:
            .live
        case .connecting:
            .connecting
        case .offline:
            .offline
        case .authFailed:
            .authFailed
        }
    }
}

enum SessionChatPickers {
    static let modelFilterThreshold = 10
}

struct PendingPrompt: Equatable, Identifiable, Sendable {
    let id: String
    let text: String
    let delivery: FollowUpDelivery
    var isSettling: Bool = false
}

extension SessionChatRow {
    func withRenderedMarkdown(_ document: NativeMarkdownDocument?) -> SessionChatRow {
        SessionChatRow(
            id: id,
            role: role,
            text: text,
            metadataLabel: metadataLabel,
            pendingDelivery: pendingDelivery,
            isPending: isPending,
            isStreaming: isStreaming,
            renderedMarkdown: document,
            blocks: blocks,
            modelProviderID: modelProviderID,
            modelID: modelID,
            contextInputTokens: contextInputTokens
        )
    }
}
