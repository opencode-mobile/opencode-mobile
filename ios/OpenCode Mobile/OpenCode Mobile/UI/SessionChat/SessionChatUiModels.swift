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
    /// Working-row only: reserved list identity collapsed while Thinking is last.
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
            return MessageListTimelineRowHeightEstimate.fallback(
                characterCount: text.count,
                style: .compact
            )
        }
        let style: MessageListTimelineRowHeightEstimate.Style =
            switch role {
            case .user:
                .outgoing
            case .assistant:
                .assistant
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

    var hasSendableContent: Bool {
        !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || !attachments.isEmpty
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

struct SessionChatUiState: Equatable {
    var sessionID: String
    var sessionTitle: String
    /// Smart project label for top-bar chrome; nil when worktree missing.
    var projectLabel: String? = nil
    var projectLetter: Character? = nil
    var projectLetterAccent: LetterAccent? = nil
    var timeline: SessionChatTimelineState
    var messages: [SessionChatRow]
    var queuedPrompts: [QueuedPrompt] = []
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
    /// True while Working is reserved but collapsed under last-row Thinking.
    var workingIndicatorCollapsed: Bool = false
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
    var forkDialogVisible = false
    var forkDraftTitle = ""
    var forkSubmitting = false
    var forkErrorMessage: String?
    var forkResultVisible = false
    var forkedSessionTitle = ""
    var pendingSessionReplace: ChildSessionNavigationTarget?
    var pendingTerminalOpen: PendingTerminalOpen?
    var hasPendingRevert = false

    var latestCompletedUserMessageID: String? {
        var latest: String?
        var candidate: SessionChatRow?
        for message in messages {
            if message.role == .user {
                candidate = message.isPending || message.isStreaming ? nil : message
            } else if let candidate, !message.isPending, !message.isStreaming {
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

struct ChildSessionNavigationTarget: Hashable {
    let sessionID: String
    let sessionTitle: String
    let worktree: String
    let optimisticNavigationInstanceID: UUID?
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
                message.blocks.compactMap { block -> String? in
                    switch block {
                    case .childSessionRow(_, let row):
                        row.childSessionId
                    case .toolCard(_, let card):
                        if card.status == .pending || card.status == .running {
                            card.childSessionId
                        } else {
                            nil
                        }
                    default:
                        nil
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
    let agentName: String
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

struct SessionInfoRegionState: Equatable {
    var sessionID: String = ""
    var sessionTitle: String = ""
    var sessionStatusLabel: String?
    var hasResolvedSessionStatus = false
    var connectionStatus: SessionConnectionStatus = .offline
    var canMutate = false
    var composerEnabled = false
    var rename = SessionRenameUiState()
    var isLifecycleActionInFlight = false
    var lifecycleErrorMessage: String?
    var shouldLeaveSession = false
    var errorMessage: String?

    mutating func applyStatus(authoritative: String?, hasResolved: Bool = true) {
        sessionStatusLabel = authoritative
        hasResolvedSessionStatus = hasResolved
    }
}

struct PendingSend: Equatable {
    let queuedAtMs: Int64
}

struct SessionChatInboxRegionState: Equatable {
    var permissionRequests: [PermissionRequest] = []
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

    var questionDraft: QuestionAnswerDraft {
        questionDrafts[questionWizardIndex] ?? QuestionAnswerDraft()
    }
}

struct SessionChatReviewChangesRegionState: Equatable {
    var messageID: String?
    var summary: ReviewChangesSummary?
}

struct SessionChatCommandsRegionState: Equatable {
    var commandsSheetVisible = false
    var visibleActions: [ClientSlashAction] = []
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
    var hasPendingRevert = false
}

struct PendingTerminalOpen: Equatable {
    let worktree: String
    let projectTitle: String
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

struct QueuedPrompt: Equatable, Identifiable, Sendable {
    let id: String
    let text: String
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
