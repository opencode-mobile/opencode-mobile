import SwiftUI

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
    /// Extra bottom inset so the timeline can scroll under a floating composer.
    let bottomOverlayInset: CGFloat
    let onRefresh: () async -> Void
    let onLoadOlder: () -> Void
    let onSessionBranchTap: () -> Void
    let onSessionTodosTap: () -> Void
    let onMessageActionsTap: (SessionChatMessageActionsTarget) -> Void
    let onToolDetailTap: (SessionChatToolDetailTarget) -> Void
    let onToolGroupTap: (SessionChatToolGroupTarget) -> Void
    let onChildSessionTap: (String, String) -> Void
    let onChangesReviewTap: (String, String?) -> Void
    let onUserScrollDismissKeyboard: () -> Void
    let supportsSessionMessageDiff: Bool

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
        bottomOverlayInset: CGFloat = 0,
        onRefresh: @escaping () async -> Void,
        onLoadOlder: @escaping () -> Void = {},
        onSessionBranchTap: @escaping () -> Void = {},
        onSessionTodosTap: @escaping () -> Void = {},
        onMessageActionsTap: @escaping (SessionChatMessageActionsTarget) -> Void,
        onToolDetailTap: @escaping (SessionChatToolDetailTarget) -> Void = { _ in },
        onToolGroupTap: @escaping (SessionChatToolGroupTarget) -> Void = { _ in },
        onChildSessionTap: @escaping (String, String) -> Void = { _, _ in },
        onChangesReviewTap: @escaping (String, String?) -> Void = { _, _ in },
        onUserScrollDismissKeyboard: @escaping () -> Void = {},
        supportsSessionMessageDiff: Bool = true
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
        self.bottomOverlayInset = bottomOverlayInset
        self.onRefresh = onRefresh
        self.onLoadOlder = onLoadOlder
        self.onSessionBranchTap = onSessionBranchTap
        self.onSessionTodosTap = onSessionTodosTap
        self.onMessageActionsTap = onMessageActionsTap
        self.onToolDetailTap = onToolDetailTap
        self.onToolGroupTap = onToolGroupTap
        self.onChildSessionTap = onChildSessionTap
        self.onChangesReviewTap = onChangesReviewTap
        self.onUserScrollDismissKeyboard = onUserScrollDismissKeyboard
        self.supportsSessionMessageDiff = supportsSessionMessageDiff
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
                VStack {
                    Spacer()
                    Text(props.errorMessage ?? "Unable to load messages")
                        .font(OpenCodeTypography.body)
                        .foregroundStyle(OpenCodeColors.textBase)
                        .padding(.horizontal, 16)
                    Spacer()
                }
            case .ready:
                SessionChatTimelineReadyView(props: props)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding(.bottom, props.timeline == .ready ? 0 : props.bottomOverlayInset)
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
    @State private var topOverlayInset: CGFloat = 0

    private var showScrollToEnd: Bool {
        scrollController.shouldShowScrollToEnd
    }

    private var showSessionBranchPill: Bool {
        SessionBranchPillVisibility.shouldShow(
            pill: props.sessionBranchPill,
            hideFloatStack: props.hideFloatStack
        )
    }

    private var showSessionTodosPill: Bool {
        !props.hideFloatStack && props.sessionTodosVisible && props.sessionTodoTotalCount > 0
    }

    private var showTopOverlayStack: Bool {
        props.syncingIndicatorLabel != nil || showSessionTodosPill || showSessionBranchPill
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
                    onChildSessionTap: props.onChildSessionTap,
                    onChangesReviewTap: props.onChangesReviewTap,
                    supportsSessionMessageDiff: props.supportsSessionMessageDiff
                )
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            // Keep pill/pin animation state from animating hosted cells on open.
            .transaction { $0.animation = nil }
            .overlay {
                if props.isRefreshing {
                    ProgressView()
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
            refreshSessionTodosPillSnapshot(totalCount: props.sessionTodoTotalCount)
            scrollController.autoFollowEnabled = true
            logScroll("appear count=\(props.messages.count) pinned=\(scrollController.isPinnedToEnd)")
        }
        .onChange(of: mountedWorkingLabel) { _, newValue in
            previousMountedWorkingLabel = newValue
        }
        .onChange(of: props.workingIndicatorFading) { _, newValue in
            previousWorkingIndicatorFading = newValue
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
        let pills = VStack(spacing: 8) {
            if let label = props.syncingIndicatorLabel {
                OpenCodeSyncingPill(label: label)
                    .allowsHitTesting(false)
            }

            if showSessionTodosPill {
                OpenCodeSessionTodosPill(
                    completedCount: sessionTodosPillCompletedCount,
                    totalCount: sessionTodosPillTotalCount,
                    activePreview: sessionTodosPillActivePreview,
                    action: props.onSessionTodosTap
                )
            }

            if showSessionBranchPill, let pill = props.sessionBranchPill {
                OpenCodeSessionBranchPill(
                    branch: pill.branch,
                    additions: pill.additions,
                    deletions: pill.deletions,
                    action: props.onSessionBranchTap
                )
                .padding(.horizontal, 16)
            }
        }

        if #available(iOS 26, *) {
            GlassEffectContainer(spacing: 8) {
                pills
            }
        } else {
            pills
        }
    }

    private func refreshSessionTodosPillSnapshot(totalCount: Int) {
        guard totalCount > 0 else { return }
        sessionTodosPillCompletedCount = props.sessionTodoCompletedCount
        sessionTodosPillTotalCount = totalCount
        sessionTodosPillActivePreview = props.sessionTodoActivePreview
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
    let onChildSessionTap: (String, String) -> Void
    let onChangesReviewTap: (String, String?) -> Void
    let supportsSessionMessageDiff: Bool

    var body: some View {
        if message.id == SessionChatStatusChrome.workingRowID {
            SessionChatWorkingIndicatorRow(
                label: message.text,
                collapsed: message.isLayoutCollapsed,
                fading: workingIndicatorFading
            )
        } else {
            HStack {
                if message.role == .assistant || message.role == .command || message.role == .tool || message.role == .unknown {
                    SessionChatMessageRow(
                        message: message,
                        onMessageActionsTap: onMessageActionsTap,
                        onToolDetailTap: onToolDetailTap,
                        onToolGroupTap: onToolGroupTap,
                        onChildSessionTap: onChildSessionTap,
                        onChangesReviewTap: onChangesReviewTap,
                        supportsSessionMessageDiff: supportsSessionMessageDiff
                    )
                    Spacer(minLength: 0)
                } else {
                    Spacer(minLength: 0)
                    SessionChatMessageRow(
                        message: message,
                        onMessageActionsTap: onMessageActionsTap,
                        onToolDetailTap: onToolDetailTap,
                        onToolGroupTap: onToolGroupTap,
                        onChildSessionTap: onChildSessionTap,
                        onChangesReviewTap: onChangesReviewTap,
                        supportsSessionMessageDiff: supportsSessionMessageDiff
                    )
                }
            }
        }
    }
}

/// Feature-local Working/Retrying/Compacting status chrome (not prose).
private struct SessionChatWorkingIndicatorRow: View {
    let label: String
    var collapsed: Bool = false
    var fading: Bool = false

    var body: some View {
        HStack(alignment: .center, spacing: 8) {
            SessionChatWorkingStatusMark(isIdle: WorkingTurnIndicator.showsIdleMark(label: label))
            Text(label)
                .font(OpenCodeTypography.body)
                .foregroundStyle(OpenCodeColors.textWeak)
            Spacer(minLength: 0)
        }
        .opacity(collapsed ? 0 : (fading ? 0.35 : 1))
        .padding(.leading, collapsed ? 0 : 4)
        .frame(maxWidth: .infinity, alignment: .leading)
        // Hosted cells with minSize 0 treated `frame(height: nil)` as 0 and
        // never painted Working / Idle. Fixed expanded height matches the
        // list estimate so pin-to-end needs no estimate→real correction.
        .frame(height: collapsed ? 0 : WorkingTurnIndicator.rowHeight)
        .clipped()
        .accessibilityElement(children: .ignore)
        .accessibilityHidden(collapsed)
        .accessibilityLabel(label)
        .transaction { transaction in
            // Host clears animations so cells do not animate on open. Re-enable
            // Idle so the lamp can become a check, then dismiss.
            transaction.animation =
                WorkingTurnIndicator.showsIdleMark(label: label)
                ? .easeInOut(duration: 0.22)
                : nil
        }
    }
}

private struct SessionChatWorkingStatusMark: View {
    var isIdle: Bool = false
    @State private var pulse = false

    var body: some View {
        ZStack {
            Circle()
                .fill(OpenCodeColors.textWeak)
                .frame(width: 6, height: 6)
                .opacity(isIdle ? 0 : (pulse ? 0.35 : 1))
                .animation(
                    isIdle ? nil : .easeInOut(duration: 0.9).repeatForever(autoreverses: true),
                    value: pulse
                )
            OpenCodeIconImage(icon: .check, size: 12)
                .foregroundStyle(OpenCodeColors.textWeak)
                .opacity(isIdle ? 1 : 0)
                .scaleEffect(isIdle ? 1 : 0.4)
        }
        .frame(width: 12, height: 12)
        .onAppear { pulse = true }
        .onChange(of: isIdle) { _, idle in
            pulse = !idle
        }
        .accessibilityHidden(true)
    }
}

private struct SessionChatWorkingStatusDot: View {
    @State private var pulse = false

    var body: some View {
        Circle()
            .fill(OpenCodeColors.textWeak)
            .frame(width: 6, height: 6)
            .opacity(pulse ? 0.35 : 1)
            .animation(
                .easeInOut(duration: 0.9).repeatForever(autoreverses: true),
                value: pulse
            )
            .onAppear { pulse = true }
            .accessibilityHidden(true)
    }
}

private struct SessionChatMessageRow: View {
    let message: SessionChatRow
    let onMessageActionsTap: (SessionChatMessageActionsTarget) -> Void
    let onToolDetailTap: (SessionChatToolDetailTarget) -> Void
    let onToolGroupTap: (SessionChatToolGroupTarget) -> Void
    let onChildSessionTap: (String, String) -> Void
    let onChangesReviewTap: (String, String?) -> Void
    let supportsSessionMessageDiff: Bool

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
                    onChildSessionTap: onChildSessionTap,
                    onChangesReviewTap: onChangesReviewTap,
                    supportsSessionMessageDiff: supportsSessionMessageDiff
                )
                .frame(maxWidth: .infinity, alignment: .leading)
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
            case .toolCard, .toolGroup, .childSessionRow, .reasoning, .stepParagraph:
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
    let onToolGroupTap: (SessionChatToolGroupTarget) -> Void
    let onChildSessionTap: (String, String) -> Void
    let onChangesReviewTap: (String, String?) -> Void
    let supportsSessionMessageDiff: Bool

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

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ForEach(blocks, id: \.blockID) { block in
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
                        status: card.status.toolCallCardStatus,
                        title: card.title,
                        subtitle: card.subtitle,
                        additions: card.additions,
                        deletions: card.deletions,
                        errorText: card.errorText,
                        action: toolCardAction(for: card)
                    )
                case .toolGroup(let blockID, let group):
                    OpenCodeToolCallCard(
                        status: group.status.toolCallCardStatus,
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
                    ReasoningBlock(
                        text: text,
                        isStreaming: false,
                        durationMs: durationMs,
                        onTap: {
                            guard !text.isEmpty else { return }
                            onToolDetailTap(
                                SessionChatToolDetailTarget(
                                    title: "Thought",
                                    body: text
                                )
                            )
                        }
                    )
                case .stepParagraph(_, let paragraph):
                    StepParagraphBlock(
                        paragraph: paragraph,
                        renderedMarkdown: renderedMarkdown(for: paragraph.renderedMarkdown),
                        isStreaming: message.isStreaming,
                        onOpenMessageActions: onOpenMessageActions,
                        chipAction: { chip in toolCardAction(for: chip.card) }
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
                        body: detail
                    )
                )
            }
        }
        return nil
    }
}

/// Chip timeline (OPE-260): narration, then the tools that followed it as
/// chips, then one caption per failed tool. `+N more` expands in place; the
/// expansion is view state keyed by the paragraph's block identity.
private struct StepParagraphBlock: View {
    let paragraph: SessionChatStepParagraphBlock
    let renderedMarkdown: NativeMarkdownDocument?
    let isStreaming: Bool
    let onOpenMessageActions: () -> Void
    let chipAction: (SessionChatToolChipBlock) -> (() -> Void)?

    @State private var expanded = false

    private enum ChipRowItem: Identifiable {
        case chip(index: Int, chip: SessionChatToolChipBlock)
        case more(family: SessionChatToolChipFamily, count: Int)

        var id: String {
            switch self {
            case .chip(let index, _): "chip:\(index)"
            case .more(let family, _): "more:\(family.rawValue)"
            }
        }
    }

    /// Family order is fixed by the builder; here only the per-family limit applies.
    private var rowItems: [ChipRowItem] {
        var items: [ChipRowItem] = []
        var shownPerFamily: [SessionChatToolChipFamily: Int] = [:]
        var hiddenPerFamily: [SessionChatToolChipFamily: Int] = [:]
        for (index, chip) in paragraph.chips.enumerated() {
            let shown = shownPerFamily[chip.family, default: 0]
            if !expanded, let limit = chip.family.visibleLimit, shown >= limit {
                hiddenPerFamily[chip.family, default: 0] += 1
                continue
            }
            shownPerFamily[chip.family] = shown + 1
            items.append(.chip(index: index, chip: chip))
        }
        for family in SessionChatToolChipFamily.allCases.reversed() {
            guard let hidden = hiddenPerFamily[family], hidden > 0 else { continue }
            let lastOfFamily = items.lastIndex { item in
                if case .chip(_, let chip) = item { return chip.family == family }
                return false
            }
            let insertAt = lastOfFamily.map { $0 + 1 } ?? items.endIndex
            items.insert(.more(family: family, count: hidden), at: insertAt)
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

            if !paragraph.chips.isEmpty {
                OpenCodeChipFlowLayout(spacing: Self.chipSpacing, lineSpacing: Self.chipSpacing) {
                    ForEach(rowItems) { item in
                        switch item {
                        case .chip(_, let chip):
                            OpenCodeToolChip(
                                variant: chip.variant,
                                status: chip.status.toolChipStatus,
                                trailingText: chip.trailingText,
                                action: chipAction(chip)
                            )
                        case .more(_, let count):
                            OpenCodeToolChip(
                                variant: .more(count: count),
                                action: { expanded = true }
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
    var toolChipStatus: OpenCodeToolChipStatus {
        switch self {
        case .pending: .pending
        case .running: .running
        case .completed: .completed
        case .error: .error
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
    var toolCallCardStatus: OpenCodeToolCallCardStatus {
        switch self {
        case .pending: .pending
        case .running: .running
        case .completed: .completed
        case .error: .error
        }
    }
}

private struct ReasoningBlock: View {
    let text: String
    let isStreaming: Bool
    let durationMs: Int64?
    let onTap: () -> Void

    var body: some View {
        let label = thoughtLabel
        HStack(alignment: .center, spacing: 8) {
            if isStreaming {
                SessionChatWorkingStatusDot()
            }
            Text(label)
                .font(OpenCodeTypography.body)
                .foregroundStyle(OpenCodeColors.textWeak)
            Spacer(minLength: 0)
        }
        .padding(.leading, 4)
        .padding(.top, 12)
        .padding(.bottom, 8)
        .frame(maxWidth: .infinity, alignment: .leading)
        .contentShape(Rectangle())
        .onTapGesture {
            guard !text.isEmpty else { return }
            onTap()
        }
        .accessibilityElement(children: .ignore)
        .accessibilityAddTraits(text.isEmpty ? [] : .isButton)
        .accessibilityLabel(label)
        .accessibilityHint(text.isEmpty ? "" : "Shows the full thought")
    }

    private var thoughtLabel: String {
        if isStreaming { return "Thinking" }
        if let duration = SessionChatTimelineBlocks.thoughtDurationLabel(durationMs) {
            return "Thought · \(duration)"
        }
        return "Thought"
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
