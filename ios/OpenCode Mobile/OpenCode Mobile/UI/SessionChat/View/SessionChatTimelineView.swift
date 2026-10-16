import SwiftUI
import UIKit

struct SessionChatTimelineViewProps {
    let timeline: SessionChatTimelineState
    let messages: [SessionChatRow]
    let timelineFrame: TimelineFrame
    let isRefreshing: Bool
    let hasOlder: Bool
    let isLoadingOlder: Bool
    let errorMessage: String?
    let sessionBranchPill: SessionChatBranchPillUiState?
    let syncingIndicatorLabel: String?
    let workingIndicatorLabel: String?
    let workingIndicatorCollapsed: Bool
    let workingIndicatorFading: Bool
    let sessionTodosVisible: Bool
    let sessionTodoCompletedCount: Int
    let sessionTodoTotalCount: Int
    let sessionTodoActivePreview: String?
    let hideFloatStack: Bool
    /// Hide rate-limit and quota marks while the composer retry status is showing.
    let hideLiveRetryFailures: Bool
    /// Extra bottom inset so the timeline can scroll under a floating composer.
    let bottomOverlayInset: CGFloat
    let onRefresh: () async -> Void
    let onRetry: () async -> Void
    let onLoadOlder: () -> Void
    let onSessionBranchTap: () -> Void
    let onSessionTodosTap: () -> Void
    let onMessageActionsTap: (SessionChatMessageActionsTarget) -> Void
    let onToolDetailTap: (SessionChatToolDetailTarget) -> Void
    let onToolGroupTap: (SessionChatToolGroupTarget) -> Void
    let onSkillsTap: (SessionChatSkillsTarget) -> Void
    let onChildSessionTap: (String, String) -> Void
    let onChangesReviewTap: (String, String?) -> Void
    let onUserScrollDismissKeyboard: () -> Void
    let supportsSessionMessageDiff: Bool
    /// Chip-timeline step paragraphs expanded past `+N more`: paragraph id to
    /// the chip count at tap time (Screen Model state).
    let expandedStepParagraphs: [String: Int]
    let onExpandStepParagraph: (String, Int) -> Void
    /// Catalog names for model-switch notices.
    let noticeModelNames: TimelineNoticeModelNames

    init(
        timeline: SessionChatTimelineState,
        messages: [SessionChatRow],
        timelineFrame: TimelineFrame = .empty,
        isRefreshing: Bool,
        hasOlder: Bool = false,
        isLoadingOlder: Bool = false,
        errorMessage: String?,
        sessionBranchPill: SessionChatBranchPillUiState? = nil,
        syncingIndicatorLabel: String? = nil,
        workingIndicatorLabel: String? = nil,
        workingIndicatorCollapsed: Bool = false,
        workingIndicatorFading: Bool = false,
        sessionTodosVisible: Bool = false,
        sessionTodoCompletedCount: Int = 0,
        sessionTodoTotalCount: Int = 0,
        sessionTodoActivePreview: String? = nil,
        hideFloatStack: Bool = false,
        hideLiveRetryFailures: Bool = false,
        bottomOverlayInset: CGFloat = 0,
        onRefresh: @escaping () async -> Void,
        onRetry: @escaping () async -> Void = {},
        onLoadOlder: @escaping () -> Void = {},
        onSessionBranchTap: @escaping () -> Void = {},
        onSessionTodosTap: @escaping () -> Void = {},
        onMessageActionsTap: @escaping (SessionChatMessageActionsTarget) -> Void,
        onToolDetailTap: @escaping (SessionChatToolDetailTarget) -> Void = { _ in },
        onToolGroupTap: @escaping (SessionChatToolGroupTarget) -> Void = { _ in },
        onSkillsTap: @escaping (SessionChatSkillsTarget) -> Void = { _ in },
        onChildSessionTap: @escaping (String, String) -> Void = { _, _ in },
        onChangesReviewTap: @escaping (String, String?) -> Void = { _, _ in },
        onUserScrollDismissKeyboard: @escaping () -> Void = {},
        supportsSessionMessageDiff: Bool = true,
        expandedStepParagraphs: [String: Int] = [:],
        onExpandStepParagraph: @escaping (String, Int) -> Void = { _, _ in },
        noticeModelNames: TimelineNoticeModelNames = .empty
    ) {
        self.timeline = timeline
        self.messages = messages
        self.timelineFrame = timelineFrame
        self.isRefreshing = isRefreshing
        self.hasOlder = hasOlder
        self.isLoadingOlder = isLoadingOlder
        self.errorMessage = errorMessage
        self.sessionBranchPill = sessionBranchPill
        self.syncingIndicatorLabel = syncingIndicatorLabel
        self.workingIndicatorLabel = workingIndicatorLabel
        self.workingIndicatorCollapsed = workingIndicatorCollapsed
        self.workingIndicatorFading = workingIndicatorFading
        self.sessionTodosVisible = sessionTodosVisible
        self.sessionTodoCompletedCount = sessionTodoCompletedCount
        self.sessionTodoTotalCount = sessionTodoTotalCount
        self.sessionTodoActivePreview = sessionTodoActivePreview
        self.hideFloatStack = hideFloatStack
        self.hideLiveRetryFailures = hideLiveRetryFailures
        self.bottomOverlayInset = bottomOverlayInset
        self.onRefresh = onRefresh
        self.onRetry = onRetry
        self.onLoadOlder = onLoadOlder
        self.onSessionBranchTap = onSessionBranchTap
        self.onSessionTodosTap = onSessionTodosTap
        self.onMessageActionsTap = onMessageActionsTap
        self.onToolDetailTap = onToolDetailTap
        self.onToolGroupTap = onToolGroupTap
        self.onSkillsTap = onSkillsTap
        self.onChildSessionTap = onChildSessionTap
        self.onChangesReviewTap = onChangesReviewTap
        self.onUserScrollDismissKeyboard = onUserScrollDismissKeyboard
        self.supportsSessionMessageDiff = supportsSessionMessageDiff
        self.expandedStepParagraphs = expandedStepParagraphs
        self.onExpandStepParagraph = onExpandStepParagraph
        self.noticeModelNames = noticeModelNames
    }
}

struct SessionChatTimelineView: View {
    let props: SessionChatTimelineViewProps

    var body: some View {
        Group {
            switch props.timeline {
            case .creating:
                OpenCodeStatusStack(
                    title: "Starting session…",
                    subtitle: "Creating on server…",
                    accessibilityIdentifier: "sessionChatCreating"
                )
            case .loading:
                OpenCodeStatusStack(
                    title: "Loading messages",
                    subtitle: "Fetching timeline…",
                    accessibilityIdentifier: "sessionChatLoading"
                )
            case .empty:
                VStack {
                    Spacer()
                    Text("No messages yet. Send a prompt to start.")
                        .font(OpenCodeTypography.body)
                        .foregroundStyle(OpenCodeColors.textBase)
                        .padding(.horizontal, 16)
                    Spacer()
                }
            case .error:
                OpenCodeEmptyState(
                    title: "Unable to load messages",
                    message: openCodeNonBlank(props.errorMessage, fallback: "The timeline didn't load."),
                    primaryAction: OpenCodeEmptyStateAction(title: "Retry") {
                        Task { await props.onRetry() }
                    }
                )
            case .ready:
                SessionChatTimelineReadyView(props: props)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding(.bottom, props.timeline == .ready ? 0 : props.bottomOverlayInset)
        .environment(\.hideLiveRetryFailures, props.hideLiveRetryFailures)
    }
}

private struct HideLiveRetryFailuresKey: EnvironmentKey {
    static let defaultValue = false
}

private extension EnvironmentValues {
    var hideLiveRetryFailures: Bool {
        get { self[HideLiveRetryFailuresKey.self] }
        set { self[HideLiveRetryFailuresKey.self] = newValue }
    }
}

private struct SessionChatTimelineReadyView: View {
    let props: SessionChatTimelineViewProps

    @State private var scrollController = TimelineScrollController()
    /// Last positive pill values. Kept so a sudden totalCount=0
    /// cannot rebuild the pill with an invalid contract if exit animation is added.
    @State private var sessionTodosPillCompletedCount = 0
    @State private var sessionTodosPillTotalCount = 1
    @State private var sessionTodosPillActivePreview: String?
    @State private var previousMountedWorkingLabel: String?
    @State private var previousWorkingIndicatorFading = false
    @State private var previousExpandedStepParagraphs: [String: Int] = [:]
    @State private var previousNoticeModelNames: TimelineNoticeModelNames = .empty
    @State private var topOverlayInset: CGFloat = 0
    /// Syncing label the top pill slot shows, lagging the prop by the show delay
    /// and minimum visible time in `SessionChatTopPill`.
    @State private var displayedSyncingLabel: String?
    @State private var syncingShownAt: ContinuousClock.Instant?
    @State private var syncingPillTask: Task<Void, Never>?

    private var showScrollToEnd: Bool {
        scrollController.shouldShowScrollToEnd
    }

    private var topPill: SessionChatTopPill? {
        SessionChatTopPill.resolve(
            syncingLabel: displayedSyncingLabel,
            todosVisible: props.sessionTodosVisible,
            todoTotalCount: props.sessionTodoTotalCount,
            branchPill: props.sessionBranchPill,
            hideFloatStack: props.hideFloatStack
        )
    }

    private var showTopOverlayStack: Bool {
        topPill != nil
    }

    private var mountedWorkingLabel: String? {
        WorkingTurnIndicator.mountedLabel(
            label: props.workingIndicatorLabel,
            collapsed: props.workingIndicatorCollapsed
        )
    }

    private var displayMessages: [SessionChatRow] {
        var rows = props.messages
        if let label = mountedWorkingLabel {
            rows.append(
                SessionChatRow(
                    id: SessionChatStatusChrome.workingRowID,
                    role: .assistant,
                    text: label,
                    metadataLabel: ""
                )
            )
        }
        return rows
    }

    var body: some View {
        ZStack(alignment: .bottom) {
            MessageListTimelineHost(
                items: displayMessages,
                // Declared timeline sets stay; Working label folds into
                // revision, and only label *changes* reconfigure that stable
                // row without clearing the packet (OPE-136).
                applyPacket: .from(
                    props.timelineFrame,
                    workingIndicatorLabel: mountedWorkingLabel,
                    previousWorkingIndicatorLabel: previousMountedWorkingLabel,
                    workingIndicatorFading: props.workingIndicatorFading,
                    previousWorkingIndicatorFading: previousWorkingIndicatorFading
                )
                .addingStepParagraphExpansion(
                    rows: props.messages,
                    expanded: props.expandedStepParagraphs,
                    previousExpanded: previousExpandedStepParagraphs
                )
                .addingNoticeModelNames(
                    rows: props.messages,
                    names: props.noticeModelNames,
                    previous: previousNoticeModelNames
                ),
                scrollController: scrollController,
                onRefresh: props.onRefresh,
                onUserScrollDismissKeyboard: props.onUserScrollDismissKeyboard,
                canLoadOlder: props.hasOlder && !props.isLoadingOlder,
                onNearOldestVisible: props.onLoadOlder,
                bottomContentPadding: 0,
                bottomOverlayInset: props.bottomOverlayInset,
                topOverlayInset: topOverlayInset
            ) { message in
                SessionChatTimelineHostRow(
                    message: message,
                    workingIndicatorFading: props.workingIndicatorFading,
                    onMessageActionsTap: props.onMessageActionsTap,
                    onToolDetailTap: props.onToolDetailTap,
                    onToolGroupTap: props.onToolGroupTap,
                    onSkillsTap: props.onSkillsTap,
                    onChildSessionTap: props.onChildSessionTap,
                    onChangesReviewTap: props.onChangesReviewTap,
                    supportsSessionMessageDiff: props.supportsSessionMessageDiff,
                    expandedStepParagraphs: props.expandedStepParagraphs,
                    onExpandStepParagraph: props.onExpandStepParagraph,
                    noticeModelNames: props.noticeModelNames
                )
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            // Keep pill/pin animation state from animating hosted cells on open.
            .transaction { $0.animation = nil }
            .overlay {
                if props.isRefreshing {
                    OpenCodeStatusStack(spinner: "Refreshing messages")
                }
            }
            .overlay(alignment: .top) {
                if showTopOverlayStack {
                    topOverlayContent
                        .padding(.top, 10)
                        .background {
                            GeometryReader { geometry in
                                Color.clear
                                    .onAppear { updateMeasuredTopOverlayInset(geometry.size.height) }
                                    .onChange(of: geometry.size.height) { _, height in
                                        updateMeasuredTopOverlayInset(height)
                                    }
                            }
                        }
                }
            }
            .onChange(of: showTopOverlayStack) { _, visible in
                if !visible { updateMeasuredTopOverlayInset(0) }
            }
            .onChange(of: props.timelineFrame.frameRevision) { _, rev in
                let layoutIDs = props.timelineFrame.layoutAffectingRowIDs
                if !layoutIDs.isEmpty {
                    logScroll(
                        "layoutAffecting count=\(layoutIDs.count) rev=\(rev)"
                    )
                }
            }

            if !props.hideFloatStack, showScrollToEnd {
                OpenCodeActionPill(variant: .scrollToEnd) {
                    logScroll("pill-click count=\(props.messages.count)")
                    ProductAnalytics.logUserAction(
                        screenId: ProductAnalytics.Screen.sessionChat,
                        actionId: ProductAnalytics.Action.chatScrollToEnd
                    )
                    scrollController.scrollToEndAnimated()
                }
                .padding(.bottom, props.bottomOverlayInset)
            }
        }
        .onChange(of: props.sessionTodoTotalCount) { _, total in
            refreshSessionTodosPillSnapshot(totalCount: total)
        }
        .onChange(of: props.sessionTodoCompletedCount) { _, _ in
            refreshSessionTodosPillSnapshot(totalCount: props.sessionTodoTotalCount)
        }
        .onChange(of: props.sessionTodoActivePreview) { _, _ in
            refreshSessionTodosPillSnapshot(totalCount: props.sessionTodoTotalCount)
        }
        .onAppear {
            previousMountedWorkingLabel = mountedWorkingLabel
            previousWorkingIndicatorFading = props.workingIndicatorFading
            previousExpandedStepParagraphs = props.expandedStepParagraphs
            previousNoticeModelNames = props.noticeModelNames
            refreshSessionTodosPillSnapshot(totalCount: props.sessionTodoTotalCount)
            updateDisplayedSyncingLabel(props.syncingIndicatorLabel)
            scrollController.autoFollowEnabled = true
            logScroll("appear count=\(props.messages.count) pinned=\(scrollController.isPinnedToEnd)")
        }
        .onChange(of: props.syncingIndicatorLabel) { _, label in
            updateDisplayedSyncingLabel(label)
        }
        .onDisappear {
            syncingPillTask?.cancel()
        }
        .onChange(of: mountedWorkingLabel) { _, newValue in
            previousMountedWorkingLabel = newValue
        }
        .onChange(of: props.workingIndicatorFading) { _, newValue in
            previousWorkingIndicatorFading = newValue
        }
        .onChange(of: props.expandedStepParagraphs) { _, newValue in
            previousExpandedStepParagraphs = newValue
        }
        .onChange(of: props.noticeModelNames) { _, newValue in
            previousNoticeModelNames = newValue
        }
        .onChange(of: props.messages.count) { _, newCount in
            logScroll("message-count changed count=\(newCount)")
        }
    }

    private func logScroll(_ message: String) {
        OpenCodeLog.sessionChat.debug("scroll \(message)")
    }

    @ViewBuilder
    private var topOverlayContent: some View {
        let pill = ZStack {
            switch topPill {
            case let .syncing(label):
                OpenCodeSyncingPill(label: label)
                    .allowsHitTesting(false)
                    .transition(.opacity)
            case .todos:
                OpenCodeSessionTodosPill(
                    completedCount: sessionTodosPillCompletedCount,
                    totalCount: sessionTodosPillTotalCount,
                    activePreview: sessionTodosPillActivePreview,
                    action: props.onSessionTodosTap
                )
                .transition(.opacity)
            case let .branch(branchPill):
                OpenCodeSessionBranchPill(
                    branch: branchPill.branch,
                    additions: branchPill.additions,
                    deletions: branchPill.deletions,
                    action: props.onSessionBranchTap
                )
                .padding(.horizontal, 16)
                .transition(.opacity)
            case nil:
                EmptyView()
            }
        }
        .animation(.easeInOut(duration: 0.2), value: topPill?.kind)

        if #available(iOS 26, *) {
            GlassEffectContainer {
                pill
            }
        } else {
            pill
        }
    }

    private func refreshSessionTodosPillSnapshot(totalCount: Int) {
        guard totalCount > 0 else { return }
        sessionTodosPillCompletedCount = props.sessionTodoCompletedCount
        sessionTodosPillTotalCount = totalCount
        sessionTodosPillActivePreview = props.sessionTodoActivePreview
    }

    private func updateDisplayedSyncingLabel(_ label: String?) {
        syncingPillTask?.cancel()
        syncingPillTask = nil
        if let label {
            guard displayedSyncingLabel == nil else {
                displayedSyncingLabel = label
                return
            }
            syncingPillTask = Task { @MainActor in
                try? await Task.sleep(for: SessionChatTopPill.syncingShowDelay)
                guard !Task.isCancelled else { return }
                displayedSyncingLabel = label
                syncingShownAt = .now
            }
            return
        }
        guard displayedSyncingLabel != nil else { return }
        let remaining = SessionChatTopPill.syncingMinimumVisible
            - (syncingShownAt.map { ContinuousClock.now - $0 } ?? SessionChatTopPill.syncingMinimumVisible)
        guard remaining > .zero else {
            displayedSyncingLabel = nil
            return
        }
        syncingPillTask = Task { @MainActor in
            try? await Task.sleep(for: remaining)
            guard !Task.isCancelled else { return }
            displayedSyncingLabel = nil
        }
    }

    private func updateMeasuredTopOverlayInset(_ height: CGFloat) {
        let inset = max(0, height)
        guard abs(topOverlayInset - inset) > 0.5 else { return }
        topOverlayInset = inset
    }
}

private struct SessionChatTimelineHostRow: View {
    let message: SessionChatRow
    var workingIndicatorFading: Bool = false
    let onMessageActionsTap: (SessionChatMessageActionsTarget) -> Void
    let onToolDetailTap: (SessionChatToolDetailTarget) -> Void
    let onToolGroupTap: (SessionChatToolGroupTarget) -> Void
    let onSkillsTap: (SessionChatSkillsTarget) -> Void
    let onChildSessionTap: (String, String) -> Void
    let onChangesReviewTap: (String, String?) -> Void
    let supportsSessionMessageDiff: Bool
    let expandedStepParagraphs: [String: Int]
    let onExpandStepParagraph: (String, Int) -> Void
    let noticeModelNames: TimelineNoticeModelNames

    var body: some View {
        if message.id == SessionChatStatusChrome.workingRowID {
            OpenCodeWorkingIndicator(
                label: message.text,
                state: message.isLayoutCollapsed
                    ? .collapsed
                    : (WorkingTurnIndicator.showsIdleMark(label: message.text) ? .idle : .working),
                fading: workingIndicatorFading
            )
        } else if message.role == .notice {
            SessionChatNoticeRow(blocks: message.blocks, modelNames: noticeModelNames)
        } else {
            HStack {
                if message.role == .assistant || message.role == .command || message.role == .tool || message.role == .unknown {
                    SessionChatMessageRow(
                        message: message,
                        onMessageActionsTap: onMessageActionsTap,
                        onToolDetailTap: onToolDetailTap,
                        onToolGroupTap: onToolGroupTap,
                        onSkillsTap: onSkillsTap,
                        onChildSessionTap: onChildSessionTap,
                        onChangesReviewTap: onChangesReviewTap,
                        supportsSessionMessageDiff: supportsSessionMessageDiff,
                        expandedStepParagraphs: expandedStepParagraphs,
                        onExpandStepParagraph: onExpandStepParagraph
                    )
                    Spacer(minLength: 0)
                } else {
                    Spacer(minLength: 0)
                    SessionChatMessageRow(
                        message: message,
                        onMessageActionsTap: onMessageActionsTap,
                        onToolDetailTap: onToolDetailTap,
                        onToolGroupTap: onToolGroupTap,
                        onSkillsTap: onSkillsTap,
                        onChildSessionTap: onChildSessionTap,
                        onChangesReviewTap: onChangesReviewTap,
                        supportsSessionMessageDiff: supportsSessionMessageDiff,
                        expandedStepParagraphs: expandedStepParagraphs,
                        onExpandStepParagraph: onExpandStepParagraph
                    )
                }
            }
        }
    }
}

private struct SessionChatMessageRow: View {
    let message: SessionChatRow
    let onMessageActionsTap: (SessionChatMessageActionsTarget) -> Void
    let onToolDetailTap: (SessionChatToolDetailTarget) -> Void
    let onToolGroupTap: (SessionChatToolGroupTarget) -> Void
    let onSkillsTap: (SessionChatSkillsTarget) -> Void
    let onChildSessionTap: (String, String) -> Void
    let onChangesReviewTap: (String, String?) -> Void
    let supportsSessionMessageDiff: Bool
    let expandedStepParagraphs: [String: Int]
    let onExpandStepParagraph: (String, Int) -> Void

    private func openMessageActions(showsRevertMessage: Bool) {
        onMessageActionsTap(
            SessionChatMessageActionsTarget(
                id: message.id,
                text: message.text,
                showsRevertMessage: showsRevertMessage
            )
        )
    }

    var body: some View {
        Group {
            switch message.role {
            case .user:
                OpenCodeOutgoingMessage(
                    content: message.outgoingContent,
                    renderedMarkdown: message.renderedMarkdown,
                    metadataLabel: message.metadataLabel,
                    onTap: {
                        if message.renderedMarkdown == nil {
                            return { openMessageActions(showsRevertMessage: true) }
                        }
                        return nil
                    }(),
                    onLongPress: {
                        guard message.renderedMarkdown != nil else { return nil }
                        return { openMessageActions(showsRevertMessage: true) }
                    }()
                )
                .frame(maxWidth: .infinity, alignment: .trailing)
            case .assistant:
                AssistantMessageContent(
                    message: message,
                    onOpenMessageActions: { openMessageActions(showsRevertMessage: false) },
                    onToolDetailTap: onToolDetailTap,
                    onToolGroupTap: onToolGroupTap,
                    onSkillsTap: onSkillsTap,
                    onChildSessionTap: onChildSessionTap,
                    onChangesReviewTap: onChangesReviewTap,
                    supportsSessionMessageDiff: supportsSessionMessageDiff,
                    expandedStepParagraphs: expandedStepParagraphs,
                    onExpandStepParagraph: onExpandStepParagraph
                )
                .frame(maxWidth: .infinity, alignment: .leading)
            case .notice:
                SessionChatNoticeRow(blocks: message.blocks, modelNames: .empty)
            case .command, .tool, .unknown:
                FallbackMessageBubble(
                    roleLabel: message.role.displayLabel,
                    text: message.text
                )
            }
        }
    }
}

private extension SessionChatRow {
    var outgoingContent: [OpenCodeOutgoingMessageContent] {
        let content = blocks.compactMap { block -> OpenCodeOutgoingMessageContent? in
            switch block {
            case .text(let blockID, let text, _):
                .text(id: blockID, text: text)
            case .attachment(_, let file):
                .attachment(file.attachmentPresentation)
            case .toolCard, .toolGroup, .childSessionRow, .reasoning, .stepParagraph, .stopNote, .failureLine, .notice:
                nil
            }
        }
        return content.isEmpty
            ? [.text(id: PresentationBlockId.messageFallback(id), text: text)]
            : content
    }
}

private struct SessionChatMessageActionsGestureModifier: ViewModifier {
    let renderedMarkdown: NativeMarkdownDocument?
    let accessibilityLabel: String
    let onOpenMessageActions: () -> Void

    func body(content: Content) -> some View {
        if renderedMarkdown != nil {
            content
                .onLongPressGesture(perform: onOpenMessageActions)
                .accessibilityLabel(accessibilityLabel)
                .accessibilityHint("Long press to show message actions")
        } else {
            content
                .onTapGesture(perform: onOpenMessageActions)
                .accessibilityLabel(accessibilityLabel)
                .accessibilityHint("Shows message actions")
        }
    }
}

private struct AssistantMessageContent: View {
    let message: SessionChatRow
    let onOpenMessageActions: () -> Void
    let onToolDetailTap: (SessionChatToolDetailTarget) -> Void
    @Environment(\.hideLiveRetryFailures) private var hideLiveRetryFailures
    let onToolGroupTap: (SessionChatToolGroupTarget) -> Void
    let onSkillsTap: (SessionChatSkillsTarget) -> Void
    let onChildSessionTap: (String, String) -> Void
    let onChangesReviewTap: (String, String?) -> Void
    let supportsSessionMessageDiff: Bool
    let expandedStepParagraphs: [String: Int]
    let onExpandStepParagraph: (String, Int) -> Void

    /// Rows never render empty — unpaintable blocks are filtered and an empty
    /// result falls back to the live placeholder.
    private var blocks: [SessionChatTimelineBlock] {
        SessionChatTimelineBlocks.paintedBlocks(for: message)
    }

    /// Migration bridge: prefer per-block markdown; fall back to message-root
    /// only when the block has none and exactly one text block remains.
    private var textBlockCount: Int {
        blocks.reduce(0) { count, block in
            block.textContent != nil ? count + 1 : count
        }
    }

    private func renderedMarkdown(for blockMarkdown: NativeMarkdownDocument?) -> NativeMarkdownDocument? {
        if let blockMarkdown { return blockMarkdown }
        return textBlockCount == 1 ? message.renderedMarkdown : nil
    }

    private var streamingStepParagraphBlockID: String? {
        SessionChatTimelineBlocks.streamingStepParagraphBlockID(
            blocks: blocks,
            messageIsStreaming: message.isStreaming
        )
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            // A step paragraph's block id grows with every tool; identify it by
            // its stable paragraph id so a new chip updates the row in place.
            ForEach(blocks, id: \.rowIdentity) { block in
                switch block {
                case .text(_, let text, let blockMarkdown):
                    let markdown = renderedMarkdown(for: blockMarkdown)
                    AssistantMessageBubble(
                        text: text,
                        renderedMarkdown: markdown,
                        isStreaming: message.isStreaming
                    )
                    .modifier(
                        SessionChatMessageActionsGestureModifier(
                            renderedMarkdown: markdown,
                            accessibilityLabel: "Assistant message",
                            onOpenMessageActions: onOpenMessageActions
                        )
                    )
                case .attachment(_, let file):
                    OpenCodeMessageAttachment(attachment: file.attachmentPresentation)
                case .toolCard(_, let card):
                    OpenCodeToolCallCard(
                        status: card.status.toolCallCardStatus(interrupted: card.isInterrupted),
                        title: card.title,
                        subtitle: card.subtitle,
                        additions: card.additions,
                        deletions: card.deletions,
                        errorText: card.errorText,
                        action: toolCardAction(for: card)
                    )
                case .toolGroup(let blockID, let group):
                    OpenCodeToolCallCard(
                        status: group.status.toolCallCardStatus(interrupted: false),
                        title: group.title,
                        subtitle: group.subtitle,
                        errorText: group.errorText,
                        action: {
                            onToolGroupTap(
                                SessionChatToolGroupTarget(
                                    blockID: blockID,
                                    title: group.title,
                                    subtitle: group.subtitle,
                                    members: group.members,
                                    messageID: message.id
                                )
                            )
                        }
                    )
                case .childSessionRow(_, let row):
                    SessionChatChildSessionRow(
                        agentName: row.agentName,
                        isBackground: row.isBackground,
                        secondLine: row.secondLine,
                        timeStartEpochMs: row.timeStartEpochMs,
                        action: row.childSessionId.map { id in
                            { onChildSessionTap(id, row.agentName) }
                        }
                    )
                case .reasoning(_, let text, _, let durationMs):
                    OpenCodeReasoningRow(
                        state: .expanded,
                        durationLabel: SessionChatTimelineBlocks.thoughtDurationLabel(durationMs),
                        action: text.isEmpty
                            ? nil
                            : {
                                onToolDetailTap(
                                    SessionChatToolDetailTarget(
                                        title: "Thought",
                                        body: text
                                    )
                                )
                            }
                    )
                case .stopNote(_, let reason):
                    OpenCodeMessageNote(variant: reason == .length ? .truncated : .interrupted)
                case .notice(_, let notice):
                    SessionChatNoticeLine(notice: notice, modelNames: .empty)
                case .failureLine(_, let text, let providerBody, let isNeutral, let action, let actionLabel, let covered):
                    if !(hideLiveRetryFailures && covered) {
                        FailureLineBlock(
                            text: text,
                            providerBody: providerBody,
                            isNeutral: isNeutral,
                            action: action,
                            actionLabel: actionLabel,
                            onToolDetailTap: onToolDetailTap
                        )
                    }
                case .stepParagraph(_, let paragraph):
                    StepParagraphBlock(
                        paragraph: paragraph,
                        renderedMarkdown: renderedMarkdown(for: paragraph.renderedMarkdown),
                        isStreaming: block.blockID == streamingStepParagraphBlockID,
                        expandedThrough: expandedStepParagraphs[paragraph.id],
                        onOpenMessageActions: onOpenMessageActions,
                        onExpand: { onExpandStepParagraph(paragraph.id, paragraph.chips.count) },
                        chipAction: { chip in
                            if chip.kind == .skill {
                                return {
                                    onSkillsTap(
                                        SessionChatSkillsTarget(skills: SessionChatTimelineBlocks.skillUses(in: blocks))
                                    )
                                }
                            }
                            return toolCardAction(for: chip.card)
                        },
                        exploreAction: { summary in
                            onToolGroupTap(
                                SessionChatToolGroupTarget(
                                    blockID: summary.blockID,
                                    title: summary.title,
                                    subtitle: summary.subtitle,
                                    members: summary.members,
                                    messageID: message.id
                                )
                            )
                        }
                    )
                }
            }
        }
    }

    private func toolCardAction(for card: SessionChatToolCardBlock) -> (() -> Void)? {
        if let childSessionId = card.childSessionId {
            return { onChildSessionTap(childSessionId, card.title) }
        }
        if card.opensChangesReview, supportsSessionMessageDiff {
            return { onChangesReviewTap(message.id, card.changesInitialFilePath) }
        }
        if let detail = card.detailText {
            return {
                onToolDetailTap(
                    SessionChatToolDetailTarget(
                        title: card.title,
                        body: detail,
                        bodyIsMarkdown: card.detailIsMarkdown
                    )
                )
            }
        }
        return nil
    }
}

/// One step-paragraph chip; an in-flight tool also shows its live elapsed
/// time (`m:ss`, as the child-session row does) after any trailing text.
private struct StepParagraphToolChip: View {
    let chip: SessionChatToolChipBlock
    let action: (() -> Void)?

    var body: some View {
        if let start = chip.runningSinceEpochMs {
            TimelineView(.periodic(from: .now, by: 1)) { context in
                toolChip(
                    elapsed: SessionChatChildSessionElapsed.format(
                        startEpochMs: start,
                        nowEpochMs: Int64(context.date.timeIntervalSince1970 * 1000)
                    )
                )
            }
        } else {
            toolChip(elapsed: nil)
        }
    }

    private func toolChip(elapsed: String?) -> OpenCodeToolChip {
        let trailing = [chip.displayTrailingText, elapsed].compactMap { $0 }.filter { !$0.isEmpty }
        return OpenCodeToolChip(
            variant: chip.variant,
            status: chip.status.toolChipStatus(interrupted: chip.card.isInterrupted),
            trailingText: trailing.isEmpty ? nil : trailing.joined(separator: " \u{00B7} "),
            action: action
        )
    }
}

/// Chip timeline (OPE-260): narration, then the tools that followed it as
/// chips (the explore summary first), then one caption per failed tool. A
/// single trailing `+N more` reveals the chips the paragraph had at tap time;
/// expansion lives in the Screen Model keyed by the paragraph's stable id so
/// it survives cell reuse while scrolling.
private struct StepParagraphBlock: View {
    let paragraph: SessionChatStepParagraphBlock
    let renderedMarkdown: NativeMarkdownDocument?
    let isStreaming: Bool
    let expandedThrough: Int?
    let onOpenMessageActions: () -> Void
    let onExpand: () -> Void
    let chipAction: (SessionChatToolChipBlock) -> (() -> Void)?
    let exploreAction: (SessionChatExploreSummaryBlock) -> Void

    private enum ChipRowItem: Identifiable {
        case explore(SessionChatExploreSummaryBlock)
        case chip(SessionChatToolChipBlock)
        case more(count: Int)

        /// Stable per tool so a new chip never re-identifies its neighbours.
        var id: String {
            switch self {
            case .explore: "explore"
            case .chip(let chip): chip.id
            case .more: "more"
            }
        }
    }

    /// Limits and the lone-hidden rule live in `chipRow`.
    private var rowItems: [ChipRowItem] {
        let row = SessionChatTimelineBlocks.chipRow(paragraph.chips, expandedThrough: expandedThrough)
        var items: [ChipRowItem] = []
        if let summary = paragraph.exploreSummary {
            items.append(.explore(summary))
        }
        items += row.visible.map(ChipRowItem.chip)
        if row.hiddenCount > 0 {
            items.append(.more(count: row.hiddenCount))
        }
        return items
    }

    var body: some View {
        VStack(alignment: .leading, spacing: Self.sectionSpacing) {
            if let text = paragraph.text {
                AssistantMessageBubble(
                    text: text,
                    renderedMarkdown: renderedMarkdown,
                    isStreaming: isStreaming,
                    presentation: .plain
                )
                .modifier(
                    SessionChatMessageActionsGestureModifier(
                        renderedMarkdown: renderedMarkdown,
                        accessibilityLabel: "Assistant message",
                        onOpenMessageActions: onOpenMessageActions
                    )
                )
            } else {
                Text(SessionChatStepParagraphBlock.placeholderText)
                    .font(OpenCodeTypography.bodySecondary)
                    .italic()
                    .foregroundStyle(OpenCodeColors.textWeak)
                    .padding(.horizontal, Self.placeholderHorizontalPadding)
            }

            if !paragraph.chips.isEmpty || paragraph.exploreSummary != nil {
                OpenCodeChipFlowLayout(spacing: Self.chipSpacing, lineSpacing: Self.chipSpacing) {
                    ForEach(rowItems) { item in
                        switch item {
                        case .explore(let summary):
                            OpenCodeToolChip(
                                variant: .explore(label: summary.label),
                                status: summary.status.toolChipStatus(interrupted: false),
                                action: { exploreAction(summary) }
                            )
                        case .chip(let chip):
                            StepParagraphToolChip(chip: chip, action: chipAction(chip))
                        case .more(let count):
                            OpenCodeToolChip(
                                variant: .more(count: count),
                                action: onExpand
                            )
                        }
                    }
                }
                .padding(.horizontal, Self.chipRowHorizontalPadding)
            }

            ForEach(Array(paragraph.errorLines.enumerated()), id: \.offset) { _, line in
                Text(line)
                    .font(OpenCodeTypography.caption)
                    .foregroundStyle(OpenCodeColors.error)
                    .lineLimit(3)
                    .padding(.horizontal, Self.chipRowHorizontalPadding)
            }
        }
    }

    private static let sectionSpacing: CGFloat = 8
    private static let chipSpacing: CGFloat = 6
    private static let placeholderHorizontalPadding: CGFloat = 4
    private static let chipRowHorizontalPadding: CGFloat = 4
}

private extension SessionChatToolChipBlock {
    var variant: OpenCodeToolChipVariant {
        switch kind {
        case .file: .file(label: label, additions: additions, deletions: deletions)
        case .command: .command(label: label)
        case .agent: .agent(name: label, brief: brief)
        case .skill: .skill(name: label)
        }
    }
}

private extension ToolStatus {
    func toolChipStatus(interrupted: Bool) -> OpenCodeToolChipStatus {
        switch self {
        case .pending: .pending
        case .running: .running
        case .completed: .completed
        case .error: interrupted ? .interrupted : .error
        }
    }
}

private extension FilePart {
    var attachmentPresentation: OpenCodeAttachmentPresentation {
        AttachmentPresentationMapper.presentation(from: self)
    }
}

private struct AssistantMessageBubble: View {
    /// Huge markdown trees can stall layout when a doc arrives on an already
    /// visible plain bubble. Defer ≥3k only in that late-arrival path — not
    /// when markdown is already prepared on first appear.
    static let deferredMarkdownCharacterThreshold = 3_000

    let text: String
    let renderedMarkdown: NativeMarkdownDocument?
    var isStreaming: Bool = false
    var presentation: OpenCodeAssistantMessagePresentation = .bubble

    @State private var paintMarkdown: Bool

    init(
        text: String,
        renderedMarkdown: NativeMarkdownDocument?,
        isStreaming: Bool = false,
        presentation: OpenCodeAssistantMessagePresentation = .bubble
    ) {
        self.text = text
        self.renderedMarkdown = renderedMarkdown
        self.isStreaming = isStreaming
        self.presentation = presentation
        // If the pipeline already prepared markdown (including hold-ready open),
        // paint it immediately. Defer only applies when a doc arrives later on an
        // already-visible plain bubble (enrich / stream) — see .task below.
        _paintMarkdown = State(initialValue: renderedMarkdown != nil)
    }

    var body: some View {
        OpenCodeAssistantMessage(
            text: text,
            renderedMarkdown: paintMarkdown ? renderedMarkdown : nil,
            isStreaming: isStreaming,
            presentation: presentation
        )
        .task(id: paintTaskID) {
            guard renderedMarkdown != nil, !paintMarkdown else { return }
            // Doc arrived after plain paint (enrich while visible). Let the open
            // frame finish so scroll stays responsive before hosting TextMate trees.
            guard text.count >= Self.deferredMarkdownCharacterThreshold else {
                paintMarkdown = true
                return
            }
            await Task.yield()
            try? await Task.sleep(for: .milliseconds(32))
            guard !Task.isCancelled else { return }
            paintMarkdown = true
        }
    }

    private var paintTaskID: String {
        "\(text.count):\(renderedMarkdown?.sourceHash ?? 0):\(renderedMarkdown?.renderTreeVersion ?? 0)"
    }
}

private extension ToolStatus {
    func toolCallCardStatus(interrupted: Bool) -> OpenCodeToolCallCardStatus {
        switch self {
        case .pending: .pending
        case .running: .running
        case .completed: .completed
        case .error: interrupted ? .interrupted : .error
        }
    }
}

/// Quiet mark under a failed reply. A body or an action opens the detail sheet.
private struct FailureLineBlock: View {
    let text: String
    let providerBody: String?
    let isNeutral: Bool
    let action: FailureAction?
    let actionLabel: String?
    let onToolDetailTap: (SessionChatToolDetailTarget) -> Void

    var body: some View {
        let target = SessionChatToolDetailTarget.failure(
            text: text,
            providerBody: providerBody,
            action: action,
            actionLabel: actionLabel
        )
        HStack(alignment: .center, spacing: 8) {
            Circle()
                .fill(isNeutral ? OpenCodeColors.textWeak : OpenCodeColors.error)
                .frame(width: 6, height: 6)
            Text(text)
                .font(OpenCodeTypography.caption)
                .foregroundStyle(OpenCodeColors.textWeak)
                .lineLimit(3)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.horizontal, 4)
        .contentShape(Rectangle())
        .modifier(FailureMarkTap(target: target, onToolDetailTap: onToolDetailTap))
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(target == nil ? [] : .isButton)
    }
}

private struct FailureMarkTap: ViewModifier {
    let target: SessionChatToolDetailTarget?
    let onToolDetailTap: (SessionChatToolDetailTarget) -> Void

    func body(content: Content) -> some View {
        if let target {
            content.onTapGesture { onToolDetailTap(target) }
        } else {
            content
        }
    }
}

/// A notice row: one Design System notice line per notice, full width and leading.
private struct SessionChatNoticeRow: View {
    let blocks: [SessionChatTimelineBlock]
    let modelNames: TimelineNoticeModelNames

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(blocks, id: \.blockID) { block in
                if case .notice(_, let notice) = block {
                    SessionChatNoticeLine(notice: notice, modelNames: modelNames)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// Expansion is local view state: it only changes how much of the value shows.
private struct SessionChatNoticeLine: View {
    let notice: TimelineNotice
    let modelNames: TimelineNoticeModelNames
    @State private var state: OpenCodeTimelineNoticeState = .collapsed

    var body: some View {
        let presentation = TimelineNoticeCopy.presentation(for: notice, modelNames: modelNames)
        OpenCodeTimelineNotice(
            variant: presentation.isMove ? .moved : .plain,
            label: presentation.label,
            value: presentation.value,
            state: state,
            onToggle: { state = state.toggled }
        )
        .id(notice.id)
    }
}

private struct FallbackMessageBubble: View {
    let roleLabel: String
    let text: String

    var body: some View {
        Text("\(roleLabel): \(text.isEmpty ? "…" : text)")
            .font(OpenCodeTypography.caption)
            .foregroundStyle(OpenCodeColors.textBase)
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(OpenCodeColors.surfaceRaisedBase, in: RoundedRectangle(cornerRadius: 12))
    }
}
