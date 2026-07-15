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
        lhs.id == rhs.id &&
            lhs.role == rhs.role &&
            lhs.text == rhs.text &&
            lhs.metadataLabel == rhs.metadataLabel &&
            lhs.isPending == rhs.isPending &&
            lhs.isStreaming == rhs.isStreaming &&
            lhs.blocks == rhs.blocks &&
            lhs.modelProviderID == rhs.modelProviderID &&
            lhs.modelID == rhs.modelID &&
            lhs.contextInputTokens == rhs.contextInputTokens &&
            lhs.renderedMarkdown?.sourceHash == rhs.renderedMarkdown?.sourceHash &&
            lhs.renderedMarkdown?.blocks.count == rhs.renderedMarkdown?.blocks.count
    }
}

struct SessionChatComposerState: Equatable {
    var text: String
    var selectedModel: ModelSelection?
    var modelOptions: [ModelSelectionOption]
    var selectedMode: String
    var selectedVariant: String?
    var modelNotice: String?
    var isModelSelectionReady = false
    var modelCatalogError: String?

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
}

enum SessionChatTimelineState: Equatable {
    case loading
    case empty
    case ready
    case error
}

struct SessionChatUiState: Equatable {
    var sessionID: String
    var sessionTitle: String
    var timeline: SessionChatTimelineState
    var messages: [SessionChatRow]
    var composer: SessionChatComposerState
    var isSending: Bool = false
    var isRefreshing: Bool = false
    var isAborting: Bool = false
    var sessionStatusLabel: String?
    var connectionStatus: SessionConnectionStatus = .offline
    var contextUsageFraction: Double?
    var errorMessage: String?
    var permissionRequest: PermissionRequest?
    var permissionQueueCount: Int = 0
    var permissionSubmitting: Bool = false
    var permissionErrorMessage: String?
    var reviewChangesMessageID: String?
    var reviewChangesSummary: ReviewChangesSummary?

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

    static func initial(sessionID: String, sessionTitle: String) -> SessionChatUiState {
        SessionChatUiState(
            sessionID: sessionID,
            sessionTitle: sessionTitle,
            timeline: .loading,
            messages: [],
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

    init(sessionID: String, worktree: String, messageID: String?, initialSource: ChangeSource = .workingTree) {
        self.sessionID = sessionID
        self.worktree = worktree
        self.messageID = messageID
        self.initialSource = initialSource
    }
}

struct SessionChatTimelineRegionState: Equatable {
    var timeline: SessionChatTimelineState = .loading
    var messages: [SessionChatRow] = []
    var isRefreshing: Bool = false
    var errorMessage: String?
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
}

struct SessionInfoRegionState: Equatable {
    var sessionID: String = ""
    var sessionTitle: String = ""
    var sessionStatusLabel: String?
    var hasResolvedSessionStatus = false
    var connectionStatus: SessionConnectionStatus = .offline
}

struct SessionChatReviewChangesRegionState: Equatable {
    var messageID: String?
    var summary: ReviewChangesSummary?
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
