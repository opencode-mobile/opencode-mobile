import Foundation

struct SessionChatRow: Identifiable, Equatable {
    let id: String
    let role: MessageRole
    let text: String
    let metadataLabel: String
    let isPending: Bool
    let isStreaming: Bool
    let renderedMarkdown: NativeMarkdownDocument?
    let blocks: [SessionChatTimelineBlock]
    let modelProviderID: String?
    let modelID: String?
    let contextInputTokens: Int64?

    init(
        id: String,
        role: MessageRole,
        text: String,
        metadataLabel: String,
        isPending: Bool = false,
        isStreaming: Bool = false,
        renderedMarkdown: NativeMarkdownDocument? = nil,
        blocks: [SessionChatTimelineBlock] = [],
        modelProviderID: String? = nil,
        modelID: String? = nil,
        contextInputTokens: Int64? = nil
    ) {
        self.id = id
        self.role = role
        self.text = text
        self.metadataLabel = metadataLabel
        self.isPending = isPending
        self.isStreaming = isStreaming
        self.renderedMarkdown = renderedMarkdown
        self.blocks = blocks
        self.modelProviderID = modelProviderID
        self.modelID = modelID
        self.contextInputTokens = contextInputTokens
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
              lhs.blocks.count == rhs.blocks.count
        else { return false }
        // Huge assistant replies: compare text only after cheap fields match.
        return lhs.text == rhs.text && lhs.blocks == rhs.blocks
    }
}

extension SessionChatRow: MessageListTimelineHeightEstimating {
    var timelineRowHeightEstimate: CGFloat {
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
    var selectedVariant: String?
    var modelNotice: String?
    var isModelSelectionReady = false
    var modelCatalogError: String?
    var agentMentions: [AgentMention] = []
    var cursor: Int = 0
    var selectionRevision: Int = 0

    var selectedModelLabel: String {
        isModelSelectionReady ? selectedModel?.modelLabel ?? "Model" : "Loading model…"
    }

    var selectedVariantLabel: String {
        selectedVariant ?? ModelSelection.defaultVariantLabel
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
    /// Projected timeline frame with declared ID sets. Reused when only
    /// composer / session-info / review / command republish.
    var timelineFrame: TimelineFrame = .empty
    var composer: SessionChatComposerState
    var isSending: Bool = false
    var isRefreshing: Bool = false
    var isAborting: Bool = false
    var sessionStatusLabel: String?
    /// In-chat Working/Retrying label when busy with no assistant content yet.
    var workingIndicatorLabel: String? = nil
    /// Top Syncing pill label while cached messages wait for network confirmation.
    var syncingIndicatorLabel: String? = nil
    var sessionTodos: [SessionTodo] = []
    var sessionTodosVisible: Bool = false
    var sessionTodosFinished: Bool = false
    var sessionTodoActivePreview: SessionTodo?
    var sessionTodoCompletedCount: Int = 0
    var sessionTodoTotalCount: Int = 0
    var connectionStatus: SessionConnectionStatus = .offline
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
        guard reviewChangesSummary?.fileCount ?? 0 > 0, let messageID = reviewChangesMessageID else { return nil }
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
        guard let messageID = owningTurnMessageID(assistantMessageID: assistantMessageID) else { return nil }
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
                selectedMode: SessionChatPickers.modes[0],
                selectedVariant: nil,
                modelNotice: nil
            )
        )
    }
}

enum ReviewChangesPillVisibility {
    static func shouldShow(summary: ReviewChangesSummary?, isPinnedToEnd: Bool, showScrollToEnd: Bool) -> Bool {
        (summary?.fileCount ?? 0) > 0 && isPinnedToEnd && !showScrollToEnd
    }
}

struct ReviewChangesSummary: Equatable {
    let fileCount: Int
    let additions: Int
    let deletions: Int

    init(fileCount: Int, additions: Int, deletions: Int) {
        self.fileCount = fileCount
        self.additions = additions
        self.deletions = deletions
    }

    init?(files: [FileDiff]) {
        guard !files.isEmpty else { return nil }
        self.init(
            fileCount: files.count,
            additions: files.reduce(0) { $0 + $1.additions },
            deletions: files.reduce(0) { $0 + $1.deletions }
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
    var errorMessage: String?
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
        selectedMode: SessionChatPickers.modes[0],
        selectedVariant: nil,
        modelNotice: nil
    )
    var isSending: Bool = false
    var isAborting: Bool = false
    var errorMessage: String?
    var abortErrorMessage: String?
    var serverCommands: [ServerCommand] = []
    var mentionableAgents: [MentionableAgent] = []
    var slashSuggestionsVisible = false
    var slashSuggestions: [SessionChatSlashSuggestion] = []
    var atSuggestionsVisible = false
    var atSuggestions: [SessionChatAtSuggestion] = []
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
    var authoritativeStatusLabel: String?
    var optimisticBusy = false
    var hasResolvedSessionStatus = false
    var connectionStatus: SessionConnectionStatus = .offline
    var rename = SessionRenameUiState()
    var isLifecycleActionInFlight = false
    var lifecycleErrorMessage: String?
    var shouldLeaveSession = false
    var errorMessage: String?

    /// Effective Working/Retrying label: authoritative status, else optimistic busy.
    /// Non-null `authoritative` always clears optimistic. Resolved idle (`authoritative == nil`
    /// with `hasResolved`) also clears unless `optimisticBusy` is set explicitly (send mark).
    mutating func applyStatus(authoritative: String?, optimisticBusy: Bool? = nil, hasResolved: Bool? = nil) {
        authoritativeStatusLabel = authoritative
        if let hasResolved {
            hasResolvedSessionStatus = hasResolved
        } else if authoritative != nil {
            hasResolvedSessionStatus = true
        }
        if authoritative != nil {
            self.optimisticBusy = false
        } else if let optimisticBusy {
            self.optimisticBusy = optimisticBusy
        } else if hasResolvedSessionStatus {
            self.optimisticBusy = false
        }
        sessionStatusLabel = authoritative ?? (self.optimisticBusy ? "Working" : nil)
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
}

struct LatestModelHint: Equatable {
    let providerID: String
    let modelID: String
    let variant: String?
    let sentAtEpochMs: Int64?
    let source: ModelSelectionHistorySource
}

struct ModelSelectionHistoryHints: Equatable {
    let user: LatestModelHint?
    let assistant: LatestModelHint?
}

enum ModelSelectionHistorySource: Equatable {
    case user
    case assistant
}

enum SessionConnectionStatus: String, Equatable, Sendable {
    case live
    case connecting
    case offline

    init(connectionState: EventStreamConnectionState) {
        switch connectionState {
        case .connected:
            self = .live
        case .connecting, .reconnecting:
            self = .connecting
        case .idle, .disconnected:
            self = .offline
        }
    }
}

enum SessionChatPickers {
    static let modes = ["Build", "Plan"]
    static let modelFilterThreshold = 10
}

extension SessionChatRow {
    func withRenderedMarkdown(_ document: NativeMarkdownDocument?) -> SessionChatRow {
        SessionChatRow(
            id: id,
            role: role,
            text: text,
            metadataLabel: metadataLabel,
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
