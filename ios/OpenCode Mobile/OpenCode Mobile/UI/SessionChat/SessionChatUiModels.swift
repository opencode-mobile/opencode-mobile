import Foundation

struct SessionChatRow: Identifiable, Equatable {
    let id: String
    let role: MessageRole
    let text: String
    let metadataLabel: String
    let isPending: Bool
    let isStreaming: Bool
    let renderedMarkdown: NativeMarkdownDocument?
    let modelProviderID: String?
    let modelID: String?
    let contextInputTokens: Int64?

    static func == (lhs: SessionChatRow, rhs: SessionChatRow) -> Bool {
        lhs.id == rhs.id &&
            lhs.role == rhs.role &&
            lhs.text == rhs.text &&
            lhs.metadataLabel == rhs.metadataLabel &&
            lhs.isPending == rhs.isPending &&
            lhs.isStreaming == rhs.isStreaming &&
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

    var selectedModelLabel: String {
        selectedModel?.modelLabel ?? "Model"
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
        ChangesNavigationTarget(sessionID: sessionID, worktree: worktree, messageID: latestCompletedUserMessageID)
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

struct ChangesNavigationTarget: Hashable {
    let sessionID: String
    let worktree: String
    let messageID: String?
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
    var connectionStatus: SessionConnectionStatus = .offline
}

struct LatestModelHint: Equatable {
    let providerID: String
    let modelID: String
    let variant: String?
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
            modelProviderID: modelProviderID,
            modelID: modelID,
            contextInputTokens: contextInputTokens
        )
    }
}
