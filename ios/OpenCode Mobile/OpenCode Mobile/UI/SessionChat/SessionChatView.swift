import AVFoundation
import Combine
import FactoryKit
import PhotosUI
import SwiftUI
import UIKit
import UniformTypeIdentifiers

struct SessionChatView: View {
    /// Server-supplied links (retry cards, failure sheets) open as web pages only.
    static func openServerLink(_ url: URL) {
        guard ExternalLinkPolicy.isOpenable(url) else { return }
        UIApplication.shared.open(url)
    }

    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    @State private var viewModelStorage: SessionChatViewModel?
    /// `sessionNavigationTaskID` the current `viewModelStorage` was built for.
    @State private var viewModelTaskID: String?
    @State private var activePicker: ComposerPickerKind?
    @State private var activeModelProviderID: String?
    @State private var modelFilterText = ""
    @State private var showSessionContext = false
    @State private var changesTarget: ChangesNavigationTarget?
    @State private var menuExpanded = false
    @State private var showDeleteConfirmation = false
    /// Report-a-problem sheet (#721); set to open it.
    @State private var feedbackModel: FeedbackViewModel?
    @State private var messageActionsTarget: SessionChatMessageActionsTarget?
    @State private var showPendingPromptsSheet = false
    @State private var toolDetailTarget: SessionChatToolDetailTarget?
    @State private var toolGroupTarget: SessionChatToolGroupTarget?
    @State private var skillsTarget: SessionChatSkillsTarget?
    @State private var childSessionTarget: ChildSessionNavigationTarget?
    @State private var terminalRoute: TerminalRoute?
    @State private var filesRoute: FilesRoute?
    @State private var developerToolsPreferences = DeveloperToolsPreferences.shared
    @State private var showAttachmentSources = false
    @State private var showFilesPicker = false
    @State private var showPhotosPicker = false
    /// The drag-over card while images or files are dragged over the chat (#870).
    @State private var attachmentDropSummary: AttachmentDropSummary?
    @State private var selectedPhotoItems: [PhotosPickerItem] = []
    @State private var showSessionTodosSheet = false
    @State private var showSubagentsSheet = false
    @State private var holdingFinishedTodos = false
    @State private var sawUnfinishedTodos = false
    @State private var finishedHoldTask: Task<Void, Never>?
    /// Measured floating composer chrome height (composer + optional pending-prompts strip).
    @State private var composerOverlayHeight: CGFloat = 0
    /// A shown snackbar floats above the composer; the timeline lifts its
    /// end-pinned last row over it so the row keeps the same end gap.
    @State private var snackbarOverlayHeight: CGFloat = 0
    @State private var bottomSafeAreaInset: CGFloat = 0
    /// Keyboard overlap reported by `keyboardOverlapPadding`. Zero when hidden.
    @State private var keyboardOverlap: CGFloat = 0
    /// Bumped by the Focus Composer keyboard shortcut.
    @State private var composerFocusRequest = 0
    /// False while another screen is pushed over this chat.
    @State private var isOnScreen = false
    /// This screen's lease on a view model it may share with the other Home layout.
    @State private var hostID = UUID()
    @State private var dictationSession: any ComposerDictationSession = ComposerDictationSessions.make()
    @State private var dictationSupport = DictationSupport.hidden
    /// Set once the server moved this session to another worktree; the chat rebinds there.
    @State private var movedWorktree: String?
    @State private var homeDisplayPreferences = HomeDisplayPreferences.shared
    @Environment(\.homeProjects) private var homeProjects
    @Environment(\.refreshHomeWorktrees) private var refreshHomeWorktrees

    private let session: SessionListItem
    private let injectedViewModel: SessionChatViewModel?
    /// Set when this chat is the trailing root of a tablet split view.
    private let detailControl: OpenCodeSplitPaneDetailControl?
    /// Replaces `dismiss()` when the chat is a split-view root with nothing to pop to.
    private let onLeaveSession: (() -> Void)?
    /// Set only by the Session Chat Playground: screens that load from a server (Changes,
    /// a child chat, Terminal, Files, Session context, tool groups) and the problem report
    /// do not open; their name is passed here instead.
    private let onUnavailableRoute: ((String) -> Void)?

    init(
        session: SessionListItem,
        viewModel: SessionChatViewModel? = nil,
        detailControl: OpenCodeSplitPaneDetailControl? = nil,
        onLeaveSession: (() -> Void)? = nil,
        onUnavailableRoute: ((String) -> Void)? = nil
    ) {
        self.session = session
        self.detailControl = detailControl
        self.onLeaveSession = onLeaveSession
        self.onUnavailableRoute = onUnavailableRoute
        // Do not construct the ViewModel in `State(initialValue:)` — SwiftUI re-runs
        // View.init (and that expression) whenever the parent body refreshes, while
        // keeping one live `@State`. Eager make() was spawning bind storms and
        // exhausting URLSession.shared during compaction status churn.
        self.injectedViewModel = viewModel
    }

    /// Available after `.task` bootstrap, or on the first frame when injected; only
    /// read from content that is gated on `hasViewModel`.
    private var viewModel: SessionChatViewModel {
        (viewModelStorage ?? injectedViewModel)!
    }

    private var hasViewModel: Bool {
        viewModelStorage != nil || injectedViewModel != nil
    }

    /// Temporarily off: bar currently only shows context usage.
    private static let showsSessionStatusBar = false

    var body: some View {
        Group {
            if hasViewModel {
                sessionChatBody
            } else {
                OpenCodeStatusStack(spinner: "Loading session")
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(OpenCodeColors.backgroundBase)
            }
        }
        // Hide system nav chrome on the root destination immediately — including the
        // pre-VM ProgressView — so NavigationStack never flashes the Liquid Glass
        // back button before OpenCodeTopBar mounts.
        .toolbar(.hidden, for: .navigationBar)
        .navigationBarBackButtonHidden(true)
        .task(id: sessionNavigationTaskID) {
            ProductAnalytics.logScreenView(
                screenId: ProductAnalytics.Screen.sessionChat,
                presentation: ProductAnalytics.Presentation.route
            )
            let taskID = sessionNavigationTaskID
            switch SessionChatViewModelReuse.decision(
                currentTaskID: taskID,
                boundTaskID: viewModelTaskID,
                hasViewModel: viewModelStorage != nil
            ) {
            case .reuse:
                // Back from a pushed screen: `.onDisappear` stopped observing. Resume on
                // the same view model so the composer and timeline keep their state;
                // the timeline's warm path refreshes quietly.
                viewModel.attachHost(hostID)
                if !viewModel.hasCompletedInitialLoad {
                    await viewModel.loadMessages()
                }
            case .rebuild:
                dictationSession.stop()
                viewModelStorage?.detachHost(hostID)
                // After a move every region binds to the new directory, so the handed-in
                // view model (bound to the old one) is not reused.
                viewModelStorage = (movedWorktree == nil ? injectedViewModel : nil) ?? Container.makeSessionChatViewModel(
                    sessionID: boundSession.id,
                    sessionTitle: session.title,
                    worktree: boundSession.worktree
                )
                viewModelTaskID = taskID
                // A view model handed over from the other Home layout may still be
                // observing and loaded; it keeps its subscription and timeline.
                viewModel.attachHost(hostID)
                if !viewModel.hasCompletedInitialLoad {
                    await viewModel.loadMessages()
                }
            }
        }
    }

    private var sessionNavigationTaskID: String {
        guard let movedWorktree else { return session.navigationID }
        return session.navigationID + "@" + movedWorktree
    }

    /// The session as opened, or in the worktree the server moved it to.
    private var boundSession: SessionListItem {
        guard let movedWorktree else { return session }
        return SessionListItem(
            id: SessionChatMoveToWorktree.reboundSessionID(
                routeID: session.id,
                currentID: viewModelStorage?.uiState.sessionID
            ),
            title: session.title,
            worktree: movedWorktree,
            parentID: session.parentID,
            directory: movedWorktree
        )
    }

    // The modifier chain is split into small steps so each one type-checks quickly;
    // one long chain took the compiler several seconds and timed out on CI.
    private var sessionChatBody: some View {
        let sheets = sessionChatPickerSheets(sessionChatSheets(sessionChatLayout))
        let observed = sessionChatObservers(sessionChatDestinations(sheets))
        return sessionChatMoveToWorktree(sessionChatRoutes(sessionChatDialogs(sessionChatLifecycle(observed))))
            .onDrop(
                of: [.item],
                delegate: SessionChatAttachmentDropDelegate(
                    isEnabled: attachmentDropEnabled,
                    context: { attachmentDropContext },
                    summary: $attachmentDropSummary,
                    onDrop: { attachItemProviders($0, source: .drop) }
                )
            )
            .overlay {
                if let summary = attachmentDropSummary {
                    OpenCodeDropOverlay(
                        variant: summary.rejected ? .reject : .accept,
                        title: summary.title,
                        subtitle: summary.subtitle,
                        previews: summary.previews.map(\.attachmentVariant)
                    )
                    .allowsHitTesting(false)
                    .transition(.opacity)
                }
            }
            .animation(.easeInOut(duration: 0.12), value: attachmentDropSummary)
            .onChange(of: attachmentDropEnabled) { _, enabled in
                if !enabled { attachmentDropSummary = nil }
            }
            .feedbackSheet(model: $feedbackModel)
            .sessionChatImageViewer(
                state: viewModel.uiState.imageViewer,
                loader: viewModel.attachmentImageLoader,
                directory: boundSession.directory ?? boundSession.worktree,
                onIndexChange: { viewModel.showImageViewerPage($0) },
                onDismiss: { viewModel.closeImageViewer() }
            )
            .environment(\.attachmentImageLoading, viewModel.attachmentImageLoader)
            .environment(\.attachmentImageDirectory, boundSession.directory ?? boundSession.worktree)
            .environment(\.openImageViewer, SessionChatOpenImageViewerAction { images, index in
                viewModel.openImageViewer(images: images, index: index)
            })
    }

    private var sessionChatLayout: some View {
        VStack(spacing: 0) {
            OpenCodeTopBarOverflowHost(
                isPresented: $menuExpanded,
                entries: sessionOverflowMenuEntries
            ) {
                OpenCodeTopBar(
                    title: viewModel.uiState.sessionTitle.isEmpty ? session.title : viewModel.uiState.sessionTitle,
                    subtitle: viewModel.uiState.projectLabel,
                    subtitleLeading: {
                        if viewModel.uiState.worktreeCheckout {
                            return .worktree
                        }
                        if let letter = viewModel.uiState.projectLetter,
                           let accent = viewModel.uiState.projectLetterAccent {
                            return .letter(letter, accent)
                        }
                        return nil
                    }(),
                    subtitleAccessory: .statusDot(viewModel.uiState.connectionStatus.openCodeStatus),
                    leading: OpenCodeSplitPane.detailLeading(detailControl, back: {
                        logChatAction(ProductAnalytics.Action.chatBack)
                        leaveSession()
                    }),
                    actions: [
                        .icon(.moreVert, accessibilityLabel: "More options") {
                            logChatAction(ProductAnalytics.Action.chatOpenOverflow)
                            ProductAnalytics.logScreenView(
                                screenId: ProductAnalytics.Screen.sessionOverflow,
                                presentation: ProductAnalytics.Presentation.overlay,
                                trigger: ProductAnalytics.Trigger.present
                            )
                            menuExpanded = true
                        },
                    ],
                    showDivider: true
                )
            }

            // Hidden for now: status bar currently only shows context usage.
            // Session context remains available from the overflow menu.
            if Self.showsSessionStatusBar {
                OpenCodeSessionStatusBar(
                    contextUsageFraction: viewModel.uiState.contextUsageFraction,
                    onContextClick: {
                        logChatAction(ProductAnalytics.Action.chatOpenSessionContext)
                        showSessionContext = true
                    }
                )
            }

            if let notice = viewModel.uiState.composer.modelNotice {
                OpenCodeInlineNotice(message: notice)
                    .padding(.horizontal, 16)
                    .padding(.bottom, 8)
                    .openCodeReadableColumn()
            }

            // Keyboard avoidance wraps only the timeline + composer so the top bar
            // stays fixed. Bottom alignment inside the modifier keeps multiline
            // growth above the keyboard (OPE-118) without sliding chrome under the
            // status bar.
            sessionChatTimelineArea
                .openCodeReadableColumn()
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .keyboardOverlapPadding(overlap: $keyboardOverlap)
        }
        .background(OpenCodeColors.backgroundBase)
        .background {
            GeometryReader { geometry in
                Color.clear
                    .onAppear { bottomSafeAreaInset = geometry.safeAreaInsets.bottom }
                    .onChange(of: geometry.safeAreaInsets.bottom) { _, inset in
                        bottomSafeAreaInset = inset
                    }
            }
        }
    }

    private var sessionChatTimelineArea: some View {
        ZStack(alignment: .bottom) {
            SessionChatTimelineView(
                props: SessionChatTimelineViewProps(
                    timeline: viewModel.uiState.timeline,
                    messages: viewModel.uiState.messages,
                    timelineFrame: viewModel.uiState.timelineFrame,
                    isRefreshing: viewModel.uiState.isRefreshing,
                    hasOlder: viewModel.uiState.hasOlder,
                    isLoadingOlder: viewModel.uiState.isLoadingOlder,
                    errorMessage: viewModel.uiState.errorMessage,
                    sessionBranchPill: viewModel.uiState.sessionBranchPill,
                    syncingIndicatorLabel: viewModel.uiState.syncingIndicatorLabel,
                    workingIndicatorLabel: viewModel.uiState.workingIndicatorLabel,
                    workingIndicatorCollapsed: viewModel.uiState.workingIndicatorCollapsed,
                    workingIndicatorFading: viewModel.uiState.workingIndicatorFading,
                    isTurnActive: viewModel.uiState.sessionStatusLabel != nil,
                    isAwaitingUser: viewModel.uiState.permissionRequest != nil
                        || viewModel.uiState.questionRequest != nil,
                    sessionTodosVisible: sessionTodosPillVisible,
                    sessionTodoCompletedCount: viewModel.uiState.sessionTodoCompletedCount,
                    sessionTodoTotalCount: viewModel.uiState.sessionTodoTotalCount,
                    sessionTodoActivePreview: viewModel.uiState.sessionTodoActivePreview?.content,
                    hideFloatStack: showSessionTodosSheet || showSubagentsSheet,
                    hideLiveRetryFailures: viewModel.uiState.retryCard != nil,
                    hideRetryCountdown: viewModel.uiState.isRetrying,
                    // Composer (+ shown snackbar) + 16 end-pin gap; Scroll to end
                    // uses this as-is (no extra scrollToEndPillBottomPadding).
                    bottomOverlayInset: sessionChatTimelineBottomInset(composerOverlayHeight)
                        + snackbarOverlayHeight
                        + composerHomeIndicatorLift
                        + keyboardOverlap,
                    activityItems: sessionChatActivityItems,
                    keyboardVisible: keyboardOverlap > 0,
                    onActivityTap: handleActivityTap,
                    onActivityClose: handleActivityClose,
                    onRefresh: { await viewModel.refreshMessages() },
                    onRetry: { await viewModel.retryFailedTimeline() },
                    onLoadOlder: { Task { await viewModel.loadOlderMessages() } },
                    onSessionBranchTap: {
                        logChatAction(ProductAnalytics.Action.chatOpenChanges)
                        changesTarget = viewModel.uiState.changesNavigationTarget(worktree: boundSession.worktree)
                    },
                    onSessionBranchCopied: { viewModel.branchNameCopied() },
                    onSessionTodosTap: {
                        guard !blocksSessionTodosSheet else { return }
                        logChatAction(ProductAnalytics.Action.chatOpenTodos)
                        ProductAnalytics.logScreenView(
                            screenId: ProductAnalytics.Screen.sessionTodos,
                            presentation: ProductAnalytics.Presentation.sheet,
                            trigger: ProductAnalytics.Trigger.present
                        )
                        showSessionTodosSheet = true
                    },
                    onMessageActionsTap: { target in
                        logChatAction(ProductAnalytics.Action.chatOpenMessageActions)
                        ProductAnalytics.logScreenView(
                            screenId: ProductAnalytics.Screen.sessionMessageActions,
                            presentation: ProductAnalytics.Presentation.sheet,
                            trigger: ProductAnalytics.Trigger.present
                        )
                        messageActionsTarget = target
                    },
                    onToolDetailTap: { target in
                        logChatAction(ProductAnalytics.Action.chatOpenToolDetail)
                        ProductAnalytics.logScreenView(
                            screenId: ProductAnalytics.Screen.sessionToolDetail,
                            presentation: ProductAnalytics.Presentation.sheet,
                            trigger: ProductAnalytics.Trigger.present
                        )
                        toolDetailTarget = target
                    },
                    onToolGroupTap: { target in
                        logChatAction(ProductAnalytics.Action.chatOpenToolGroup)
                        toolGroupTarget = target
                    },
                    onSkillsTap: { target in
                        logChatAction(ProductAnalytics.Action.chatOpenSkills)
                        ProductAnalytics.logScreenView(
                            screenId: ProductAnalytics.Screen.sessionSkills,
                            presentation: ProductAnalytics.Presentation.sheet,
                            trigger: ProductAnalytics.Trigger.present
                        )
                        skillsTarget = target
                    },
                    onChildSessionTap: { sessionID, title in
                        logChatAction(ProductAnalytics.Action.chatOpenChildSession)
                        childSessionTarget = ChildSessionNavigationTarget(
                            sessionID: sessionID,
                            sessionTitle: title,
                            worktree: boundSession.worktree,
                            optimisticNavigationInstanceID: nil,
                            parentID: session.id
                        )
                    },
                    onChangesReviewTap: { assistantMessageID, initialFilePath in
                        logChatAction(ProductAnalytics.Action.chatOpenChangesFromTool)
                        openLastTurnChanges(
                            assistantMessageID: assistantMessageID,
                            initialFilePath: initialFilePath
                        )
                    },
                    onUserScrollDismissKeyboard: {
                        // Composer UITextView is outside the timeline collection
                        // view; keyboardDismissMode alone does not resign it.
                        UIApplication.shared.sendAction(
                            #selector(UIResponder.resignFirstResponder),
                            to: nil,
                            from: nil,
                            for: nil
                        )
                    },
                    supportsSessionMessageDiff: viewModel.uiState.supportsSessionMessageDiff,
                    expandedStepParagraphs: viewModel.uiState.expandedStepParagraphs,
                    onExpandStepParagraph: { paragraphID, chipCount in
                        viewModel.expandStepParagraph(paragraphID, chipCount: chipCount)
                    },
                    unfoldedStepRails: viewModel.uiState.unfoldedStepRails,
                    onToggleStepRailFold: { railID in viewModel.toggleStepRailFold(railID) },
                    presentedActivityCard: viewModel.uiState.presentedActivityCard,
                    activityCardDetailStepId: viewModel.uiState.activityCardDetailStepId,
                    childActivities: viewModel.uiState.childActivities,
                    activityCardFooters: viewModel.uiState.activityCardFooters,
                    debouncedStepIDs: viewModel.uiState.debouncedStepIDs,
                    onOpenActivityCard: { cardID in viewModel.openActivityCard(cardID) },
                    onDismissActivityCard: { viewModel.dismissActivityCard() },
                    onOpenActivityCardStep: { stepID in viewModel.openActivityCardStep(stepID) },
                    onCloseActivityCardStep: { viewModel.closeActivityCardStep() },
                    noticeModelNames: TimelineNoticeModelNames(options: viewModel.uiState.composer.modelOptions)
                )
            )
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .ignoresSafeArea(edges: .bottom)

            OpenCodeBottomFade(
                height: sessionChatTimelineBottomInset(composerOverlayHeight)
                    + composerHomeIndicatorLift
            )
            .allowsHitTesting(false)

            OpenCodeSnackbarHost(
                message: viewModel.uiState.snackbar.mapToOpenCodeSnackbarMessage(),
                onDismissed: { viewModel.consumeSnackbar() },
                onAction: { message in
                    guard message.id == viewModel.uiState.snackbar?.id else { return }
                    switch viewModel.uiState.snackbar?.action {
                    case let .retrySideQuestion(id):
                        viewModel.retrySideQuestions(id: id)
                    case nil:
                        break
                    }
                },
                onHeightChange: { height in
                    guard abs(snackbarOverlayHeight - height) > 0.5 else { return }
                    snackbarOverlayHeight = height
                }
            )
            // Sit just above the measured composer, like Android; 168 only
            // covers the first frame before the composer reports its height.
            .padding(.bottom, (composerOverlayHeight > 0 ? composerOverlayHeight : 168) + composerHomeIndicatorLift)

            VStack(spacing: 0) {
                if SessionChatComposerKind.resolve(
                    isChildSession: session.parentID != nil,
                    hasPermission: viewModel.uiState.permissionRequest != nil && !viewModel.uiState.permissionDockHidden,
                    hasQuestion: viewModel.uiState.questionRequest != nil && !viewModel.uiState.questionDockHidden
                ) == .prompt, viewModel.uiState.dictation == nil {
                    if viewModel.uiState.atSuggestionsVisible {
                        OpenCodeComposerSuggestionOverlay(
                            items: viewModel.uiState.atSuggestions.map { suggestion in
                                OpenCodeComposerSuggestionItem(
                                    id: suggestion.id,
                                    title: suggestion.title,
                                    subtitle: suggestion.subtitle,
                                    leadingIcon: suggestion.leadingIcon
                                )
                            },
                            emptyMessage: "No matches",
                            onSelect: { item in
                                viewModel.selectAtSuggestion(agentName: item.id)
                            }
                        )
                        .padding(.horizontal, 12)
                        .padding(.bottom, 4)
                    } else if viewModel.uiState.slashSuggestionsVisible {
                        OpenCodeComposerSuggestionOverlay(
                            items: viewModel.uiState.slashSuggestions.map { suggestion in
                                OpenCodeComposerSuggestionItem(
                                    id: suggestion.id,
                                    title: suggestion.title,
                                    subtitle: suggestion.subtitle,
                                    badge: suggestion.badge
                                )
                            },
                            emptyMessage: "No commands",
                            onSelect: { item in
                                viewModel.selectSlashSuggestion(commandName: item.id)
                            }
                        )
                        .padding(.horizontal, 12)
                        .padding(.bottom, 4)
                    }
                }
                VStack(spacing: 0) {
                    // Suggestions float above this stack. Corner pills float over the
                    // timeline, which reserves their row (`SessionChatCornerSlotsLayout`).
                    if let notice = viewModel.uiState.sideQuestions.unsupportedNotice {
                        OpenCodeInlineNotice(message: notice, variant: .banner)
                            .padding(.horizontal, 12)
                            .padding(.bottom, 6)
                    }
                    composer
                }
                // Match home SessionList dock measurement — preference keys from a
                // background GeometryReader were staying at 0, so timeline clearance
                // never applied (LLDB: adjustedContentInset all zero, no footer).
                .background {
                    GeometryReader { geometry in
                        Color.clear
                            .onAppear { composerOverlayHeight = geometry.size.height }
                            .onChange(of: geometry.size.height) { _, height in
                                guard abs(composerOverlayHeight - height) > 0.5 else { return }
                                composerOverlayHeight = height
                            }
                    }
                }
            }
            .padding(.bottom, composerHomeIndicatorLift)
        }
    }

    /// Bottom-left activity the screen owns: collapsed requests, `/btw` side questions and queued prompts.
    private var sessionChatActivityItems: [SessionChatActivityItem] {
        var items: [SessionChatActivityItem] = []
        if viewModel.uiState.permissionDockHidden {
            items.append(.reviewPermission)
        }
        if viewModel.uiState.questionDockHidden {
            items.append(.answerQuestion)
        }
        if let pill = viewModel.uiState.backgroundWorkPill {
            items.append(.subagents(pill))
        }
        let sideQuestions = viewModel.uiState.sideQuestions
        if sideQuestions.failedCount > 0 {
            items.append(.sideQuestionsFailed(count: sideQuestions.failedCount))
        }
        if sideQuestions.readyCount > 0 {
            items.append(.sideQuestionsReady(count: sideQuestions.readyCount))
        }
        if sideQuestions.pendingCount > 0 {
            items.append(.sideQuestionsRunning(count: sideQuestions.pendingCount))
        }
        if SessionChatComposerKind.resolve(
            isChildSession: session.parentID != nil,
            hasPermission: viewModel.uiState.permissionRequest != nil && !viewModel.uiState.permissionDockHidden,
            hasQuestion: viewModel.uiState.questionRequest != nil && !viewModel.uiState.questionDockHidden
        ) == .prompt, !viewModel.uiState.pendingPrompts.isEmpty {
            items.append(.queued(count: viewModel.uiState.pendingPrompts.count))
        }
        return items
    }

    private func handleActivityTap(_ item: SessionChatActivityItem) {
        switch item {
        case .reviewPermission:
            viewModel.showPermissionDock()
        case .answerQuestion:
            viewModel.showQuestionDock()
        case .subagents:
            logChatAction(ProductAnalytics.Action.chatOpenSubagents)
            ProductAnalytics.logScreenView(
                screenId: ProductAnalytics.Screen.sessionSubagents,
                presentation: ProductAnalytics.Presentation.sheet,
                trigger: ProductAnalytics.Trigger.present
            )
            showSubagentsSheet = true
        case .sideQuestionsFailed:
            viewModel.retrySideQuestions()
        case .sideQuestionsReady:
            viewModel.openSideQuestionAnswer()
        case .queued:
            ProductAnalytics.logScreenView(
                screenId: ProductAnalytics.Screen.sessionPendingPrompts,
                presentation: ProductAnalytics.Presentation.sheet,
                trigger: ProductAnalytics.Trigger.present
            )
            showPendingPromptsSheet = true
        case .requestFailed, .sideQuestionsRunning, .syncing, .finished:
            break
        }
    }

    private func handleActivityClose(_ item: SessionChatActivityItem) {
        if case .sideQuestionsFailed = item {
            viewModel.dismissFailedSideQuestions()
        }
    }

    private func sessionChatSheets(_ content: some View) -> some View {
        content
            .sessionChatSideQuestionAnswerSheet(
                answer: viewModel.uiState.sideQuestions.presentedAnswer,
                onCopy: { viewModel.sideQuestionAnswerCopied() },
                onClose: { viewModel.closeSideQuestionAnswer() }
            )
            .onChange(of: viewSheetOpen, initial: true) { _, open in
                viewModel.setScreenSheetOpen(open)
            }
            .sessionChatMessageActionsSheet(
                target: $messageActionsTarget,
                onRevertMessage: { viewModel.revertMessage($0) },
                onForkFromMessage: { viewModel.openForkDialog(atMessageID: $0) }
            )
            .sessionChatPendingPromptsSheet(
                isPresented: $showPendingPromptsSheet,
                prompts: viewModel.uiState.pendingPrompts,
                onSteer: { viewModel.steerPendingPrompt($0) },
                onQueue: { viewModel.queuePendingPrompt($0) },
                onDelete: { viewModel.cancelPendingPrompt($0) },
                onClearAll: { viewModel.clearPendingPrompts() }
            )
            .onChange(of: viewModel.uiState.pendingPrompts.count) { _, count in
                if count == 0 {
                    showPendingPromptsSheet = false
                }
            }
            .sessionChatToolDetailSheet(
                target: $toolDetailTarget,
                onFailureAction: { action in
                    switch action {
                    case .pickModel:
                        activePicker = .model
                    case .compact:
                        viewModel.compactCurrentSession()
                    case .copy:
                        break
                    }
                },
                onOpenLink: { SessionChatView.openServerLink($0) }
            )
            .sessionChatSkillsSheet(target: $skillsTarget)
            // Waits for any view-owned sheet to close so the intro never stacks on one.
            .sessionChatChipTimelineIntro(
                isPresented: viewModel.uiState.chipTimelineIntroVisible && !viewSheetOpen,
                onEnable: { viewModel.enableChipTimelineFromIntro() },
                onLater: { viewModel.postponeChipTimelineIntro() },
                onNever: { viewModel.dismissChipTimelineIntro() }
            )
            .sessionChatTimelineStylePicker(
                isPresented: viewModel.uiState.timelineStylePickerVisible && !viewSheetOpen,
                styles: SessionChatDisplayPreferences.shared.availableTimelineStyles,
                currentStyle: SessionChatDisplayPreferences.shared.effectiveTimelineStyle,
                onUse: { viewModel.chooseTimelineStyleFromPicker($0) },
                onDismiss: { viewModel.dismissTimelineStylePicker() }
            )
            .sessionChatSubagentsSheet(
                isPresented: $showSubagentsSheet,
                subagents: viewModel.uiState.subagents
            ) { subagent in
                guard let childID = subagent.childSessionID else { return }
                logChatAction(ProductAnalytics.Action.chatOpenChildSession)
                childSessionTarget = ChildSessionNavigationTarget(
                    sessionID: childID,
                    sessionTitle: subagent.title ?? subagent.agentName,
                    worktree: boundSession.worktree,
                    optimisticNavigationInstanceID: nil,
                    parentID: session.id
                )
            }
            .sessionChatTodosSheet(
                isPresented: $showSessionTodosSheet,
                todos: viewModel.uiState.sessionTodos,
                completedCount: viewModel.uiState.sessionTodoCompletedCount,
                totalCount: max(viewModel.uiState.sessionTodoTotalCount, 1)
            )
            .sessionChatPickerSheet(
                activePicker: $activePicker,
                activeModelProviderID: $activeModelProviderID,
                modelFilterText: $modelFilterText,
                composer: viewModel.uiState.composer,
                onSelectModel: { viewModel.selectModel($0) },
                onSelectMode: { viewModel.selectMode($0) },
                onSelectVariant: { viewModel.selectVariant($0) }
            )
    }

    private func sessionChatPickerSheets(_ content: some View) -> some View {
        content
            .openCodeBottomSheet(isPresented: attachmentSourcesBinding, title: "Add attachment") {
                let sources = composerAttachmentSources
                if sources.photos {
                    OpenCodeBottomSheetEntry(
                        title: "Photos",
                        startDecorator: .icon(.description),
                        action: {
                            logAttachmentSourceAction(ProductAnalytics.Action.attachPickPhotos)
                            showAttachmentSources = false
                            showPhotosPicker = true
                        }
                    )
                }
                if sources.files {
                    OpenCodeBottomSheetEntry(
                        title: "Files",
                        startDecorator: .icon(.folder),
                        action: {
                            logAttachmentSourceAction(ProductAnalytics.Action.attachPickFiles)
                            showAttachmentSources = false
                            showFilesPicker = true
                        }
                    )
                }
                if sources.logFile {
                    OpenCodeBottomSheetEntry(
                        title: "Log file",
                        startDecorator: .icon(.list),
                        action: {
                            logAttachmentSourceAction(ProductAnalytics.Action.attachPickLogFile)
                            showAttachmentSources = false
                            attachDiagnosticLog()
                        }
                    )
                }
            }
            .openCodeBottomSheet(
                isPresented: commandsSheetBinding,
                title: viewModel.uiState.mcpPageVisible ? "MCP servers" : "Commands",
                leadingAction: commandsSheetLeadingAction
            ) {
                if viewModel.uiState.mcpPageVisible {
                    SessionChatMcpServersSheetContent(
                        servers: viewModel.uiState.mcpServers,
                        loading: viewModel.uiState.mcpLoading,
                        errorMessage: viewModel.uiState.mcpErrorMessage,
                        summary: viewModel.uiState.mcpSummary
                    )
                } else {
                    CommandsActionTileGrid(
                        actions: viewModel.uiState.visibleClientSlashActions,
                        canMutate: !OptimisticSessionCreate.isPlaceholder(viewModel.uiState.sessionID) &&
                            viewModel.uiState.canMutate,
                        mcpBadge: viewModel.uiState.mcpSummary.actionTileBadge,
                        run: { viewModel.runClientSlashAction($0) }
                    )
                }
            }
            .photosPicker(
                isPresented: $showPhotosPicker,
                selection: $selectedPhotoItems,
                matching: .images
            )
            .fileImporter(
                isPresented: $showFilesPicker,
                allowedContentTypes: [.image, .pdf, .text, .sourceCode],
                allowsMultipleSelection: true
            ) { result in
                guard case let .success(urls) = result else { return }
                Task { await viewModel.addAttachmentFiles(urls) }
            }
    }

    private func sessionChatDestinations(_ content: some View) -> some View {
        content
            .navigationDestination(isPresented: availableRoute($showSessionContext)) {
                SessionContextView(sessionID: viewModel.uiState.sessionID, worktree: boundSession.worktree)
            }
            .navigationDestination(item: availableRoute($changesTarget)) { target in
                ChangesView(
                    sessionID: target.sessionID,
                    worktree: target.worktree,
                    messageID: target.messageID,
                    initialSource: target.initialSource,
                    initialFilePath: target.initialFilePath
                )
            }
            .navigationDestination(item: availableRoute($toolGroupTarget)) { target in
                SessionChatToolGroupView(
                    target: target,
                    worktree: boundSession.worktree,
                    parentSessionID: session.id,
                    supportsSessionMessageDiff: viewModel.uiState.supportsSessionMessageDiff,
                    makeChangesTarget: { assistantMessageID, initialFilePath in
                        viewModel.uiState.fileChangeNavigationTarget(
                            worktree: boundSession.worktree,
                            assistantMessageID: assistantMessageID,
                            initialFilePath: initialFilePath
                        )
                    },
                    lastTurnDiffUnavailable: lastTurnDiffUnavailable(assistantMessageID: target.messageID)
                )
            }
            .navigationDestination(item: availableRoute($childSessionTarget)) { target in
                SessionChatView(session: target.sessionListItem)
            }
            .onChange(of: showSessionContext) { _, open in
                if open { refuseRoute("Session context") { showSessionContext = false } }
            }
            .onChange(of: changesTarget != nil) { _, open in
                if open { refuseRoute("Changes") { changesTarget = nil } }
            }
            .onChange(of: toolGroupTarget != nil) { _, open in
                if open { refuseRoute("Tool groups") { toolGroupTarget = nil } }
            }
            .onChange(of: childSessionTarget != nil) { _, open in
                if open { refuseRoute("Opening a subagent") { childSessionTarget = nil } }
            }
            .onChange(of: terminalRoute != nil) { _, open in
                if open { refuseRoute("Terminal") { terminalRoute = nil } }
            }
            .onChange(of: filesRoute != nil) { _, open in
                if open { refuseRoute("Files") { filesRoute = nil } }
            }
    }

    /// The route as is, or one that never presents while the playground refuses routes.
    private func availableRoute<Target>(_ route: Binding<Target?>) -> Binding<Target?> {
        onUnavailableRoute == nil ? route : .constant(nil)
    }

    private func availableRoute(_ route: Binding<Bool>) -> Binding<Bool> {
        onUnavailableRoute == nil ? route : .constant(false)
    }

    private func refuseRoute(_ name: String, reset: () -> Void) {
        guard let onUnavailableRoute else { return }
        reset()
        onUnavailableRoute(name)
    }

    private func sessionChatObservers(_ content: some View) -> some View {
        content
            .onChange(of: activePicker) { _, picker in
                if picker != .model {
                    activeModelProviderID = nil
                    modelFilterText = ""
                }
                if let picker {
                    ProductAnalytics.logScreenView(
                        screenId: pickerScreenId(picker),
                        presentation: ProductAnalytics.Presentation.sheet,
                        trigger: ProductAnalytics.Trigger.present
                    )
                }
            }
            .onChange(of: viewModel.uiState.forkResultVisible) { _, visible in
                guard visible else { return }
                ProductAnalytics.logScreenView(
                    screenId: ProductAnalytics.Screen.sessionForkResult,
                    presentation: ProductAnalytics.Presentation.dialog,
                    trigger: ProductAnalytics.Trigger.present
                )
            }
            .onChange(of: viewModel.uiState.shouldLeaveSession) { _, shouldLeave in
                if shouldLeave {
                    leaveSession()
                }
            }
            .onChange(of: viewModel.uiState.permissionRequest?.id) { _, _ in
                dismissSessionTodosSheetIfBlocked()
            }
            .onChange(of: viewModel.uiState.questionRequest?.id) { _, _ in
                dismissSessionTodosSheetIfBlocked()
            }
            .onChange(of: viewModel.uiState.sessionTodosFinished) { _, finished in
                updateFinishedTodosHold(finished: finished)
            }
            .onChange(of: viewModel.uiState.sessionTodos.isEmpty) { _, isEmpty in
                if isEmpty {
                    finishedHoldTask?.cancel()
                    holdingFinishedTodos = false
                    sawUnfinishedTodos = false
                    showSessionTodosSheet = false
                } else if !viewModel.uiState.sessionTodosFinished {
                    sawUnfinishedTodos = true
                }
            }
            .onChange(of: selectedPhotoItems) { _, items in
                guard !items.isEmpty else { return }
                Task { await importPhotos(items) }
            }
    }

    private func sessionChatLifecycle(_ content: some View) -> some View {
        content
            .openCodeDialog(
                isPresented: deleteDialogBinding,
                title: "Delete session",
                subtitle: "Delete session “\(viewModel.uiState.sessionTitle)”?",
                primaryOption: OpenCodeDialogOption(
                    title: "Delete",
                    enabled: !viewModel.uiState.isLifecycleActionInFlight
                ) {
                    ProductAnalytics.logUserAction(
                        screenId: ProductAnalytics.Screen.sessionDeleteConfirm,
                        actionId: ProductAnalytics.Action.chatDeleteConfirm
                    )
                    showDeleteConfirmation = false
                    viewModel.deleteSession()
                },
                secondaryOption: OpenCodeDialogOption(
                    title: "Cancel",
                    enabled: !viewModel.uiState.isLifecycleActionInFlight
                ) {
                    ProductAnalytics.logUserAction(
                        screenId: ProductAnalytics.Screen.sessionDeleteConfirm,
                        actionId: ProductAnalytics.Action.chatDeleteCancel
                    )
                    showDeleteConfirmation = false
                }
            )
            .onAppear {
                isOnScreen = true
            }
            .onDisappear {
                isOnScreen = false
                dictationSession.stop()
                viewModelStorage?.detachHost(hostID)
            }
            .onChange(of: scenePhase) { _, phase in
                guard viewModelStorage != nil else { return }
                if phase == .background {
                    viewModel.cancelDictationForBackground()
                } else if phase == .active {
                    Task { await refreshDictationSupport() }
                }
            }
            .onChange(of: composerKind) { _, kind in
                if kind != .prompt {
                    viewModel.cancelDictationForModeChange()
                }
            }
            .onChange(of: viewModel.uiState.dictation?.phase) { _, phase in
                // Stop as soon as the card leaves listening, including the insert beat,
                // so a late recognizer error cannot replace a confirmed transcript.
                if phase != .listening {
                    dictationSession.stop()
                }
            }
            .task {
                await refreshDictationSupport()
            }
            .openCodeKeyboardShortcut(
                .focusComposer,
                action: acceptsComposerFocusShortcut ? { composerFocusRequest += 1 } : nil
            )
    }

    private func sessionChatDialogs(_ content: some View) -> some View {
        content
            .openCodeDialog(
                isPresented: renameDialogBinding,
                title: "Rename session",
                primaryOption: OpenCodeDialogOption(
                    title: "Save",
                    enabled: !viewModel.uiState.rename.isSubmitting &&
                        viewModel.uiState.canMutate
                ) {
                    Task { await viewModel.confirmRenameSession() }
                },
                secondaryOption: OpenCodeDialogOption(
                    title: "Cancel",
                    enabled: !viewModel.uiState.rename.isSubmitting,
                    action: viewModel.cancelRenameSession
                ),
                textField: OpenCodeDialogTextField(
                    text: Binding(
                        get: { viewModel.uiState.rename.draftTitle },
                        set: viewModel.updateRenameDraft
                    ),
                    hint: "Title",
                    enabled: !viewModel.uiState.rename.isSubmitting,
                    errorMessage: viewModel.uiState.rename.errorMessage
                )
            )
            .openCodeDialog(
                isPresented: forkDialogBinding,
                title: "Fork session",
                primaryOption: OpenCodeDialogOption(
                    title: "Fork",
                    enabled: !viewModel.uiState.forkSubmitting &&
                        viewModel.uiState.canMutate
                ) {
                    viewModel.confirmFork()
                },
                secondaryOption: OpenCodeDialogOption(
                    title: "Cancel",
                    enabled: !viewModel.uiState.forkSubmitting,
                    action: viewModel.cancelForkDialog
                ),
                textField: OpenCodeDialogTextField(
                    text: Binding(
                        get: { viewModel.uiState.forkDraftTitle },
                        set: viewModel.updateForkDraft
                    ),
                    hint: "Title",
                    enabled: !viewModel.uiState.forkSubmitting,
                    errorMessage: viewModel.uiState.forkErrorMessage
                )
            )
            .openCodeDialog(
                isPresented: forkResultDialogBinding,
                title: "Session forked",
                subtitle: "Forked as “\(viewModel.uiState.forkedSessionTitle)”.",
                primaryOption: OpenCodeDialogOption(title: "Open") {
                    viewModel.chooseForkOpen()
                },
                secondaryOption: OpenCodeDialogOption(title: "Stay") {
                    viewModel.chooseForkStay()
                }
            )
    }

    /// Screens the view model asks to open: a replacement session, Terminal, and Files.
    private func sessionChatRoutes(_ content: some View) -> some View {
        content
            .onChange(of: viewModel.uiState.pendingSessionReplace) { _, _ in
                if let target = viewModel.uiState.pendingSessionReplace {
                    childSessionTarget = target
                    viewModel.consumePendingSessionReplace()
                }
            }
            .onChange(of: viewModel.uiState.pendingTerminalOpen) { _, _ in
                if let target = viewModel.uiState.pendingTerminalOpen {
                    terminalRoute = TerminalRoute(worktree: target.worktree, projectTitle: target.projectTitle)
                    viewModel.consumePendingTerminalOpen()
                }
            }
            .navigationDestination(item: availableRoute($terminalRoute)) { route in
                TerminalView(worktree: route.worktree, projectTitle: route.projectTitle, ptyId: route.ptyId)
            }
            .onChange(of: viewModel.uiState.pendingFilesOpen) { _, _ in
                if let target = viewModel.uiState.pendingFilesOpen {
                    filesRoute = FilesRoute(worktree: target.worktree)
                    viewModel.consumePendingFilesOpen()
                }
            }
            .navigationDestination(item: availableRoute($filesRoute)) { route in
                FilesView(worktree: route.worktree)
            }
    }

    /// Home-indicator lift for the floating composer. The timeline host ignores
    /// the bottom safe area so messages can fade to the physical edge; the
    /// composer stays above the indicator unless the keyboard already replaced it.
    private var composerHomeIndicatorLift: CGFloat {
        keyboardOverlap > 0 ? 0 : bottomSafeAreaInset
    }

    private var viewSheetOpen: Bool {
        messageActionsTarget != nil
            || showPendingPromptsSheet
            || toolDetailTarget != nil
            || skillsTarget != nil
            || showSessionTodosSheet
            || showSubagentsSheet
            || showAttachmentSources
            || activePicker != nil
            || showDeleteConfirmation
    }

    private var blocksSessionTodosSheet: Bool {
        viewModel.uiState.permissionRequest != nil || viewModel.uiState.questionRequest != nil
    }

    private var sessionTodosPillVisible: Bool {
        viewModel.uiState.sessionTodosVisible || holdingFinishedTodos
    }

    private func dismissSessionTodosSheetIfBlocked() {
        if blocksSessionTodosSheet {
            showSessionTodosSheet = false
        }
    }

    private func updateFinishedTodosHold(finished: Bool) {
        finishedHoldTask?.cancel()
        if !finished {
            if !viewModel.uiState.sessionTodos.isEmpty {
                sawUnfinishedTodos = true
            }
            holdingFinishedTodos = false
            return
        }
        guard SessionTodoList.shouldHoldFinished(
            sawUnfinished: sawUnfinishedTodos,
            todos: viewModel.uiState.sessionTodos
        ) else {
            holdingFinishedTodos = false
            return
        }
        holdingFinishedTodos = true
        finishedHoldTask = Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(1500))
            guard !Task.isCancelled else { return }
            holdingFinishedTodos = false
            showSessionTodosSheet = false
        }
    }

    private var deleteDialogBinding: Binding<Bool> {
        Binding(
            get: { showDeleteConfirmation },
            set: { visible in
                if !visible, !viewModel.uiState.isLifecycleActionInFlight {
                    showDeleteConfirmation = false
                }
            }
        )
    }

    private var renameDialogBinding: Binding<Bool> {
        Binding(
            get: { viewModel.uiState.rename.isDialogVisible },
            set: { visible in
                if !visible, !viewModel.uiState.rename.isSubmitting {
                    viewModel.cancelRenameSession()
                }
            }
        )
    }

    private var commandsSheetLeadingAction: OpenCodeBottomSheetHeaderAction? {
        guard viewModel.uiState.mcpPageVisible else { return nil }
        return OpenCodeBottomSheetHeaderAction(
            icon: .arrowBack,
            accessibilityLabel: "Back to commands",
            action: { viewModel.closeMcpServers() }
        )
    }

    private var commandsSheetBinding: Binding<Bool> {
        Binding(
            get: { viewModel.uiState.commandsSheetVisible },
            set: { visible in
                if !visible {
                    viewModel.dismissCommands()
                }
            }
        )
    }

    private var forkDialogBinding: Binding<Bool> {
        Binding(
            get: { viewModel.uiState.forkDialogVisible },
            set: { visible in
                if !visible, !viewModel.uiState.forkSubmitting {
                    viewModel.cancelForkDialog()
                }
            }
        )
    }

    private var forkResultDialogBinding: Binding<Bool> {
        Binding(
            get: { viewModel.uiState.forkResultVisible },
            set: { visible in
                if !visible {
                    viewModel.chooseForkStay()
                }
            }
        )
    }

    private func leaveSession() {
        // Release this screen's lease rather than stopping the shared model outright:
        // during a fold/unfold hand-off another screen may still host it.
        viewModelStorage?.detachHost(hostID)
        if detailControl != nil {
            onLeaveSession?()
        } else {
            dismiss()
        }
    }

    /// Pasted or dropped items (#870): each is copied to a temporary file, then attached like a file
    /// from the Files picker. Paste names an unnamed image `Pasted image N.png`.
    private func attachItemProviders(_ providers: [NSItemProvider], source: SessionChatAttachmentSource) {
        Task {
            let directory = FileManager.default.temporaryDirectory
                .appendingPathComponent("attachment-\(source.rawValue)-\(UUID().uuidString)", isDirectory: true)
            defer { try? FileManager.default.removeItem(at: directory) }
            var names = viewModel.uiState.composer.attachments.map(\.filename)
            var files: [URL] = []
            var folders = 0
            var unreadable = 0
            for provider in providers {
                switch AttachmentItemProviders.kind(of: provider) {
                case .folder:
                    folders += 1
                case nil:
                    continue
                case .some:
                    let unnamedImageName: (() -> String)? = source == .paste ? {
                        let name = AttachmentDropRouting.pastedImageName(draftFilenames: names)
                        names.append(name)
                        return name
                    } : nil
                    do {
                        files.append(try await AttachmentItemProviders.copy(provider, into: directory, unnamedImageName: unnamedImageName))
                    } catch {
                        unreadable += 1
                    }
                }
            }
            let added = await viewModel.addPastedOrDroppedFiles(files, skippedFolders: folders, unreadable: unreadable)
            if added {
                logAttachmentSourceAction(source == .paste ? ProductAnalytics.Action.attachPaste : ProductAnalytics.Action.attachDrop)
            }
        }
    }

    private func importPhotos(_ items: [PhotosPickerItem]) async {
        defer { selectedPhotoItems = [] }
        for (index, item) in items.enumerated() {
            guard let data = try? await item.loadTransferable(type: Data.self) else {
                viewModel.reportAttachmentReadFailure()
                continue
            }
            let type = item.supportedContentTypes.first(where: { $0.conforms(to: .image) })
            let fileExtension = type?.preferredFilenameExtension ?? "jpg"
            viewModel.addAttachmentData(
                data,
                filename: "Photo \(index + 1).\(fileExtension)",
                declaredMime: type?.preferredMIMEType
            )
        }
    }

    // MARK: Move to worktree

    /// The Home project this session's directory belongs to.
    private var moveProject: HomeProject? {
        SessionChatMoveToWorktree.project(containing: boundSession.worktree, in: homeProjects)
    }

    /// The Move sheet's New worktree row.
    private func newWorktreeAvailability(sessionCreated: Bool) -> NewWorktreeAvailability {
        SessionChatMoveToWorktree.newWorktreeAvailability(
            supportsMove: viewModel.uiState.supportsSessionMove,
            worktreesEnabled: homeDisplayPreferences.showsWorktrees,
            sessionCreated: sessionCreated,
            project: moveProject,
            serverCanCreate: viewModel.serverCanCreateWorktrees
        )
    }

    private func moveToWorktreeMenuEntry(sessionCreated: Bool) -> OpenCodeOverflowMenuEntry {
        let availability = SessionChatMoveToWorktree.availability(
            supportsMove: viewModel.uiState.supportsSessionMove,
            worktreesEnabled: homeDisplayPreferences.showsWorktrees,
            sessionCreated: sessionCreated,
            project: moveProject,
            newWorktree: newWorktreeAvailability(sessionCreated: sessionCreated)
        )
        return OpenCodeOverflowMenuEntry(
            title: SessionChatMoveToWorktree.menuTitle,
            subtitle: availability.subtitle,
            icon: .callSplit,
            enabled: availability.enabled,
            accessibilityIdentifier: "sessionMoveToWorktree",
            action: {
                logOverflowAction(ProductAnalytics.Action.chatOverflowMoveToWorktree)
                menuExpanded = false
                viewModel.openMoveToWorktree()
            }
        )
    }

    private var moveSheetBinding: Binding<Bool> {
        Binding(
            get: { viewModel.uiState.moveSheetVisible },
            set: { presented in
                if !presented { viewModel.dismissMoveToWorktree() }
            }
        )
    }

    private func sessionChatMoveToWorktree(_ content: some View) -> some View {
        content
            .openCodeBottomSheet(
                isPresented: moveSheetBinding,
                title: viewModel.worktreeCreate.state?.title ?? SessionChatMoveToWorktree.sheetTitle,
                leadingAction: WorktreeCreateSheetChrome.backAction(viewModel.worktreeCreate),
                keyboard: WorktreeCreateSheetChrome.keyboard(viewModel.worktreeCreate)
            ) {
                if viewModel.worktreeCreate.isPresented {
                    WorktreeCreateForm(model: viewModel.worktreeCreate) {
                        let refresh = refreshHomeWorktrees
                        let source = viewModel.worktreeCreate.state?.sourceDirectory ?? boundSession.worktree
                        Task {
                            await viewModel.submitMoveToNewWorktree { _ in await refresh(source) }
                        }
                    }
                } else if let project = moveProject {
                    ForEach(
                        SessionChatMoveToWorktree.options(project: project, currentDirectory: boundSession.worktree)
                    ) { option in
                        OpenCodeListItem(
                            title: option.title,
                            subtitle: option.subtitle,
                            selected: option.isCurrent,
                            enabled: option.isCurrent || !viewModel.uiState.moveInFlight,
                            startDecorator: .icon(.callSplit),
                            action: option.isCurrent ? nil : {
                                Task { await viewModel.moveSession(to: option.directory, worktreeTitle: option.title) }
                            }
                        ) {
                            if option.isCurrent {
                                OpenCodeIconImage(icon: .check, size: 18)
                                    .accessibilityHidden(true)
                            }
                        }
                        .accessibilityAddTraits(option.isCurrent ? .isSelected : [])
                        .accessibilityIdentifier(
                            option.isCurrent ? "sessionMoveToWorktreeCurrent" : "sessionMoveToWorktreeRow"
                        )
                    }
                    moveToNewWorktreeRow(project: project)
                } else {
                    moveToNewWorktreeRow(project: nil)
                }
            }
            .onChange(of: viewModel.uiState.movedDirectory, initial: true) { _, directory in
                // Also catches a view model handed over from the other Home layout after a move.
                guard let directory, directory != boundSession.worktree else { return }
                movedWorktree = directory
            }
    }

    private func moveToNewWorktreeRow(project: HomeProject?) -> some View {
        let sessionCreated = !OptimisticSessionCreate.isPlaceholder(viewModel.uiState.sessionID)
        let availability = newWorktreeAvailability(sessionCreated: sessionCreated)
        return NewWorktreeRow(
            availability: NewWorktreeAvailability(
                enabled: availability.enabled && !viewModel.uiState.moveInFlight,
                subtitle: availability.subtitle
            ),
            accessibilityIdentifier: "moveToNewWorktreeRow",
            action: {
                let source = boundSession.worktree
                viewModel.openMoveToNewWorktree(
                    projectID: project?.projectID,
                    sourceDirectory: source,
                    sourceBranch: project?.worktree(at: source)?.branch
                )
            }
        )
    }

    private var sessionOverflowMenuEntries: [OpenCodeOverflowMenuEntry] {
        let sessionCreated = !OptimisticSessionCreate.isPlaceholder(viewModel.uiState.sessionID)
        let lifecycleEnabled = sessionCreated && !viewModel.uiState.isLifecycleActionInFlight
        var entries = [
            OpenCodeOverflowMenuEntry(
                title: "Rename session",
                icon: .edit,
                enabled: sessionCreated,
                action: {
                    logOverflowAction(ProductAnalytics.Action.chatOverflowRename)
                    menuExpanded = false
                    viewModel.openRenameSession()
                }
            ),
            OpenCodeOverflowMenuEntry(
                title: "Review changes",
                icon: .description,
                enabled: sessionCreated,
                action: {
                    logOverflowAction(ProductAnalytics.Action.chatOverflowReviewChanges)
                    menuExpanded = false
                    changesTarget = viewModel.uiState.changesNavigationTarget(worktree: boundSession.worktree)
                }
            ),
            OpenCodeOverflowMenuEntry(
                title: "Fork session",
                icon: .callSplit,
                enabled: sessionCreated,
                action: {
                    logOverflowAction(ProductAnalytics.Action.chatOverflowFork)
                    menuExpanded = false
                    viewModel.openForkDialog()
                }
            ),
            moveToWorktreeMenuEntry(sessionCreated: sessionCreated),
            OpenCodeOverflowMenuEntry(
                title: "Browse files",
                icon: .folder,
                enabled: sessionCreated,
                action: {
                    logOverflowAction(ProductAnalytics.Action.chatOverflowBrowseFiles)
                    menuExpanded = false
                    filesRoute = FilesRoute(worktree: boundSession.worktree)
                }
            ),
            OpenCodeOverflowMenuEntry(
                title: "Session context",
                icon: .architecture,
                enabled: sessionCreated,
                action: {
                    logOverflowAction(ProductAnalytics.Action.chatOverflowSessionContext)
                    menuExpanded = false
                    showSessionContext = true
                }
            ),
            OpenCodeOverflowMenuEntry(
                title: "Copy session ID",
                icon: .contentCopy,
                enabled: sessionCreated,
                action: {
                    logOverflowAction(ProductAnalytics.Action.chatOverflowCopySessionId)
                    menuExpanded = false
                    UIPasteboard.general.string = viewModel.uiState.sessionID
                }
            ),
        ]
        if viewModel.uiState.supportsSessionArchive {
            entries.append(
                OpenCodeOverflowMenuEntry(
                    title: "Archive",
                    icon: .archive,
                    enabled: lifecycleEnabled,
                    action: {
                        logOverflowAction(ProductAnalytics.Action.chatOverflowArchive)
                        menuExpanded = false
                        viewModel.archiveSession()
                    }
                )
            )
        }
        entries.append(
            OpenCodeOverflowMenuEntry(
                title: "Report a problem",
                icon: .edit,
                action: {
                    menuExpanded = false
                    reportProblem()
                }
            )
        )
        entries.append(
            OpenCodeOverflowMenuEntry(
                title: "Delete",
                icon: .close,
                enabled: lifecycleEnabled,
                destructive: true,
                action: {
                    logOverflowAction(ProductAnalytics.Action.chatOverflowDelete)
                    ProductAnalytics.logScreenView(
                        screenId: ProductAnalytics.Screen.sessionDeleteConfirm,
                        presentation: ProductAnalytics.Presentation.dialog,
                        trigger: ProductAnalytics.Trigger.present
                    )
                    menuExpanded = false
                    showDeleteConfirmation = true
                }
            )
        )
        return entries
    }

    /// Captures this screen once the menu is gone, then opens the report sheet. The capture
    /// is only attached when the user taps "+ Screenshot" (the sheet itself draws over the window).
    private func reportProblem() {
        if let onUnavailableRoute {
            // The playground sends no reports: its content is fake and must stay on the device.
            onUnavailableRoute("Report a problem")
            return
        }
        resignComposerKeyboard()
        Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(250))
            let screenshot = FeedbackScreenCapture.captureKeyWindow()
            feedbackModel = Container.makeFeedbackViewModel(screen: .sessionChat, screenshot: screenshot)
        }
    }

    private var composerKind: SessionChatComposerKind {
        SessionChatComposerKind.resolve(
            isChildSession: session.parentID != nil,
            hasPermission: viewModel.uiState.permissionRequest != nil && !viewModel.uiState.permissionDockHidden,
            hasQuestion: viewModel.uiState.questionRequest != nil && !viewModel.uiState.questionDockHidden
        )
    }

    /// The prompt takes attachments: the Attach button, paste of images and files, and drops.
    private var promptAttachEnabled: Bool {
        let composer = viewModel.uiState.composer
        return composerAttachmentSources.isAttachEnabled &&
            !OptimisticSessionCreate.isPlaceholder(viewModel.uiState.sessionID) &&
            composer.isModelSelectionReady &&
            !composer.isShellMode &&
            !viewModel.uiState.sideQuestions.composerMode
    }

    /// Drops attach only where the Attach button would: a prompt composer that takes attachments,
    /// not mid-dictation. Otherwise drops fall through to the system default.
    private var attachmentDropEnabled: Bool {
        composerKind == .prompt && promptAttachEnabled && viewModel.uiState.dictation == nil
    }

    private var attachmentDropContext: AttachmentDropContext {
        let composer = viewModel.uiState.composer
        let option = composer.selectedModel?.option
        return AttachmentDropContext(
            imagesSupported: option?.supportsImageInput ?? true,
            pdfSupported: option?.supportsPdfInput ?? true,
            uploadAvailable: composer.streamedUploadAvailable,
            draft: composer.attachments
        )
    }

    /// Focus Composer applies while this chat is the visible screen and shows the
    /// prompt (not a permission, question, or child-session lockout) with no
    /// system picker on top.
    private var acceptsComposerFocusShortcut: Bool {
        isOnScreen && composerKind == .prompt && !showPhotosPicker && !showFilesPicker
    }

    private var composer: some View {
        let composer = viewModel.uiState.composer
        let isSending = viewModel.uiState.isSending
        let kind = composerKind
        let state: OpenCodeSessionComposerState

        if kind == .permission, let request = viewModel.uiState.permissionRequest {
            state = .permission(
                .init(
                    requestID: request.id,
                    sessionID: request.sessionID,
                    permission: request.permission,
                    patterns: request.patterns,
                    files: request.fileChanges.map {
                        .init(path: $0.path, additions: $0.additions, deletions: $0.deletions)
                    },
                    alwaysPatterns: request.always,
                    queuePosition: viewModel.uiState.permissionQueuePosition,
                    queueCount: viewModel.uiState.permissionQueueCount,
                    attribution: viewModel.uiState.permissionAttribution.map {
                        composerAttribution(
                            $0,
                            summary: $0.permissionSummary(
                                permission: request.permission,
                                target: request.fileChanges.first?.path ?? request.patterns.first
                            )
                        )
                    },
                    submitting: viewModel.uiState.permissionSubmitting,
                    actionsEnabled: viewModel.uiState.canMutate,
                    errorMessage: viewModel.uiState.permissionErrorMessage
                )
            )
        } else if kind == .question, let request = viewModel.uiState.questionRequest {
            let draft = viewModel.uiState
            let questionCount = max(request.questions.count, 1)
            let index = min(max(draft.questionWizardIndex, 0), questionCount - 1)
            let question = request.questions.indices.contains(index) ? request.questions[index] : nil
            let isValid = question.map {
                isQuestionAnswerValid(
                    question: $0,
                    draft: QuestionAnswerDraft(
                        selectedLabels: draft.questionSelectedLabels,
                        customText: draft.questionCustomText,
                        customSelected: draft.questionCustomSelected
                    )
                )
            } ?? false
            state = .question(
                .init(
                    requestID: request.id,
                    sessionID: request.sessionID,
                    questionIndex: index,
                    questionCount: questionCount,
                    header: question?.header ?? "",
                    question: question?.question ?? "",
                    options: (question?.options ?? []).map { .init(label: $0.label, description: $0.description) },
                    multiple: question?.multiple ?? false,
                    minSelections: question?.minSelections,
                    maxSelections: question?.maxSelections,
                    customAllowed: question?.custom ?? true,
                    selectedLabels: draft.questionSelectedLabels,
                    customText: draft.questionCustomText,
                    customSelected: draft.questionCustomSelected,
                    backEnabled: index > 0 && !draft.questionSubmitting,
                    nextEnabled: isValid && index < questionCount - 1 && !draft.questionSubmitting,
                    submitEnabled: isValid && index == questionCount - 1 && !draft.questionSubmitting &&
                        viewModel.uiState.canMutate,
                    submitting: draft.questionSubmitting,
                    errorMessage: draft.questionErrorMessage,
                    requestPosition: draft.questionQueuePosition,
                    requestCount: draft.questionQueueCount,
                    attribution: draft.questionAttribution.map {
                        composerAttribution($0, summary: $0.questionSummary)
                    }
                )
            )
        } else if kind == .childLockout {
            state = .childLockout(
                .init(
                    selectedModelLabel: composer.selectedModelLabel,
                    selectedVariantLabel: composer.selectedVariantLabel,
                    showVariant: composer.canSelectVariant
                )
            )
        } else {
            let sideQuestions = viewModel.uiState.sideQuestions
            let sideQuestionMode = sideQuestions.composerMode
            let blockedSideQuestion = !sideQuestions.supported && SideQuestion.question(inDraft: composer.text) != nil
            let promptSendEnabled =
                composer.hasSendableContent &&
                !composer.hasPendingUploads &&
                composer.selectedModel != nil &&
                composer.isModelSelectionReady &&
                !isSending &&
                !blockedSideQuestion
            // A side question answers with the session's own model, so it needs no model pick.
            let sideQuestionSendEnabled =
                !composer.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            state = .prompt(
                .init(
                    text: composer.text,
                    mentionRanges: composer.mentionRanges,
                    selectionStart: composer.cursor,
                    selectionEnd: composer.cursor,
                    selectionRevision: composer.selectionRevision,
                    attachments: composer.attachments.map(attachmentPresentation),
                    showPendingRevert: viewModel.uiState.hasPendingRevert,
                    selectedModelLabel: composer.selectedModelLabel,
                    selectedModeLabel: composer.selectedModeLabel,
                    selectedVariantLabel: composer.selectedVariantLabel,
                    showVariant: composer.canSelectVariant,
                    sendEnabled:
                        (sideQuestionMode ? sideQuestionSendEnabled : promptSendEnabled) &&
                        !OptimisticSessionCreate.isPlaceholder(viewModel.uiState.sessionID) &&
                        viewModel.uiState.timeline != .creating &&
                        viewModel.uiState.composerEnabled &&
                        viewModel.uiState.pendingSend == nil,
                    attachEnabled: promptAttachEnabled,
                    showStop: viewModel.uiState.sessionStatusLabel != nil,
                    stopEnabled: !viewModel.uiState.isAborting &&
                        viewModel.uiState.canMutate,
                    followUpDelivery: composer.supportsFollowUpDelivery && !sideQuestionMode ? composer.followUpDelivery : nil,
                    showDictationButton: dictationSupport.showsMic,
                    shellMode: composer.isShellMode && !sideQuestionMode,
                    sideQuestionMode: sideQuestionMode
                )
            )
        }

        let liveRetry = viewModel.uiState.retryCard
        let liveBody = SessionChatTimelineBlocks.newestLiveRetryProviderBody(
            in: viewModel.uiState.messages
        )
        let status = liveRetry.map { card in
            AnyView(
                SessionRetryCountdownCard(
                    card: card,
                    onOpenLink: { SessionChatView.openServerLink($0) },
                    onOpenBody: liveBody.map { body in
                        {
                            guard let target = SessionChatToolDetailTarget.failure(
                                text: card.message,
                                providerBody: body,
                                action: nil,
                                actionLabel: nil,
                                linkLabel: card.actionLabel,
                                link: card.actionLink
                            ) else { return }
                            toolDetailTarget = target
                        }
                    }
                )
            )
        }

        return OpenCodeSessionComposer(
            state: state,
            focusRequestToken: composerFocusRequest,
            dictation: kind == .prompt ? viewModel.uiState.dictation.map(composerDictation) : nil,
            attachmentDragActive: attachmentDropSummary != nil,
            onAction: { action in
                switch action {
                case let .textChanged(text, selectionStart, _):
                    viewModel.updateComposerText(text, cursor: selectionStart)
                case .selectModel:
                    guard !composer.modelOptions.isEmpty else {
                        Task { await viewModel.retryModelCatalog() }
                        return
                    }
                    logChatAction(ProductAnalytics.Action.composerOpenModelPicker)
                    activeModelProviderID = composer.selectedModel?.option.providerID
                    activePicker = .model
                case .selectMode:
                    logChatAction(ProductAnalytics.Action.composerOpenModePicker)
                    activePicker = .mode
                case .selectVariant:
                    logChatAction(ProductAnalytics.Action.composerOpenVariantPicker)
                    activePicker = .variant
                case .attach:
                    logChatAction(ProductAnalytics.Action.composerAttach)
                    let sources = composerAttachmentSources
                    if sources.showsSourceSheet {
                        ProductAnalytics.logScreenView(
                            screenId: ProductAnalytics.Screen.sessionAttachmentSource,
                            presentation: ProductAnalytics.Presentation.sheet,
                            trigger: ProductAnalytics.Trigger.present
                        )
                        showAttachmentSources = true
                    } else if sources.photos {
                        showPhotosPicker = true
                    } else if sources.files {
                        showFilesPicker = true
                    }
                case let .removeAttachment(id):
                    viewModel.removeAttachment(id: id)
                case let .pasteAttachments(providers):
                    attachItemProviders(providers, source: .paste)
                case .cancelRevert:
                    viewModel.cancelRevert()
                case .exitShellMode:
                    viewModel.exitShellMode()
                case .exitSideQuestionMode:
                    viewModel.exitSideQuestionMode()
                case .openCommands:
                    viewModel.openCommands()
                case .send:
                    Task { await viewModel.sendPrompt() }
                case .toggleFollowUp:
                    viewModel.toggleFollowUp()
                case .stop:
                    Task { await viewModel.abortSession() }
                case let .allowPermissionOnce(requestID, sessionID):
                    Task { await viewModel.allowPermissionOnce(requestID: requestID, sessionID: sessionID) }
                case let .denyPermission(requestID, sessionID):
                    Task { await viewModel.denyPermission(requestID: requestID, sessionID: sessionID) }
                case let .alwaysAllowPermission(requestID, sessionID):
                    Task { await viewModel.alwaysAllowPermission(requestID: requestID, sessionID: sessionID) }
                case let .hidePermission(requestID):
                    viewModel.hidePermissionDock(requestID: requestID)
                case let .hideQuestion(requestID):
                    viewModel.hideQuestionDock(requestID: requestID)
                case let .pageRequest(delta):
                    if composerKind == .permission {
                        viewModel.pagePermission(by: delta)
                    } else {
                        viewModel.pageQuestion(by: delta)
                    }
                case let .openRequestSession(sessionID):
                    openRequestSession(sessionID: sessionID)
                case let .toggleQuestionOption(requestID, sessionID, label):
                    viewModel.toggleQuestionOption(requestID: requestID, sessionID: sessionID, label: label)
                case let .toggleQuestionCustom(requestID, sessionID):
                    viewModel.toggleQuestionCustom(requestID: requestID, sessionID: sessionID)
                case let .questionCustomTextChanged(requestID, sessionID, text):
                    viewModel.updateQuestionCustomText(requestID: requestID, sessionID: sessionID, text: text)
                case let .questionBack(requestID, sessionID):
                    viewModel.questionBack(requestID: requestID, sessionID: sessionID)
                case let .questionNext(requestID, sessionID):
                    viewModel.questionNext(requestID: requestID, sessionID: sessionID)
                case let .submitQuestion(requestID, sessionID):
                    Task { await viewModel.submitQuestion(requestID: requestID, sessionID: sessionID) }
                case let .dismissQuestion(requestID, sessionID):
                    Task { await viewModel.dismissQuestion(requestID: requestID, sessionID: sessionID) }
                case .backToMainSession:
                    logChatAction(ProductAnalytics.Action.chatBack)
                    leaveSession()
                case .beginDictation:
                    Task { await beginComposerDictation() }
                case .dictationCancel:
                    viewModel.dictationCancel()
                case .dictationInsert:
                    viewModel.dictationInsert()
                case .dictationOpenSettings:
                    UIApplication.shared.open(URL(string: UIApplication.openSettingsURLString)!)
                case .dictationDownload:
                    Task { await downloadDictationModel() }
                case .dictationRetry:
                    Task { await beginComposerDictation() }
                }
            },
            status: status
        )
    }

    private func composerAttribution(
        _ attribution: InboxRequestAttribution,
        summary: String
    ) -> OpenCodeSessionComposerState.RequestAttribution {
        .init(sessionID: attribution.sessionID, letter: attribution.letter, summary: summary)
    }

    /// Opens the subagent session a permission or question comes from, as a child session.
    private func openRequestSession(sessionID: String) {
        let attribution = [viewModel.uiState.permissionAttribution, viewModel.uiState.questionAttribution]
            .compactMap { $0 }
            .first { $0.sessionID == sessionID }
        guard let attribution else { return }
        logChatAction(ProductAnalytics.Action.chatOpenChildSession)
        childSessionTarget = ChildSessionNavigationTarget(
            sessionID: attribution.sessionID,
            sessionTitle: attribution.sessionTitle,
            worktree: boundSession.worktree,
            optimisticNavigationInstanceID: nil,
            parentID: attribution.parentID
        )
    }

    private func attachmentPresentation(_ attachment: Attachment) -> OpenCodeAttachmentPresentation {
        AttachmentPresentationMapper.presentation(from: attachment)
    }

    private func composerDictation(_ state: ComposerDictationUiState) -> OpenCodeSessionComposerDictation {
        OpenCodeSessionComposerDictation(
            variant: dictationCardVariant(state),
            committed: state.committed,
            volatileText: state.volatile,
            level: state.level,
            status: dictationStatus(state),
            startedAt: state.startedAt,
            languageLabel: state.languageLabel
        )
    }

    private func dictationCardVariant(_ state: ComposerDictationUiState) -> OpenCodeDictationCardVariant {
        switch state.phase {
        case .listening:
            .listening
        case .finishing:
            .finishing
        case .permissionDenied:
            .unavailable(.permissionDenied)
        case .modelNotInstalled:
            .unavailable(.modelNotInstalled)
        case .modelDownloading:
            .unavailable(.modelDownloading(progress: state.downloadProgress))
        case .modelDownloadFailed:
            .unavailable(.modelDownloadFailed)
        case .recognizerError:
            .unavailable(.recognizerError(state.errorMessage ?? ""))
        }
    }

    private func dictationStatus(_ state: ComposerDictationUiState) -> String {
        if state.onDevice {
            return "Listening · \(state.languageLabel) · on-device"
        }
        return "Listening · \(state.languageLabel)"
    }

    private func refreshDictationSupport() async {
        dictationSupport = await dictationSession.probe()
    }

    private func beginComposerDictation() async {
        let support = await dictationSession.probe()
        dictationSupport = support
        switch support {
        case .hidden:
            return
        case .permissionDenied:
            viewModel.beginDictationUnavailable(
                .permissionDenied,
                message: "Microphone access is off"
            )
        case let .modelNotInstalled(languageLabel):
            viewModel.beginDictationUnavailable(.modelNotInstalled, languageLabel: languageLabel)
        case let .modelDownloading(languageLabel):
            viewModel.beginDictationUnavailable(.modelNotInstalled, languageLabel: languageLabel)
            await downloadDictationModel()
        case let .ready(languageLabel, onDevice):
            let granted = await requestMicrophoneAccess()
            guard granted else {
                dictationSupport = .permissionDenied
                viewModel.beginDictationUnavailable(
                    .permissionDenied,
                    message: "Microphone access is off"
                )
                return
            }
            resignComposerKeyboard()
            viewModel.beginDictation(languageLabel: languageLabel, onDevice: onDevice)
            guard viewModel.uiState.dictation?.phase == .listening else { return }
            startDictationSession()
        }
    }

    private func downloadDictationModel() async {
        viewModel.dictationModelDownload(.modelDownloading)
        let installed = await dictationSession.downloadModel { progress in
            viewModel.dictationModelDownload(.modelDownloading, progress: progress)
        }
        // Cancel closes the card; the system download carries on without it.
        guard viewModel.uiState.dictation?.phase.isModelPhase == true else { return }
        guard installed else {
            viewModel.dictationModelDownload(.modelDownloadFailed)
            return
        }
        let support = await dictationSession.probe()
        dictationSupport = support
        switch support {
        case let .ready(languageLabel, onDevice):
            resignComposerKeyboard()
            viewModel.beginDictation(languageLabel: languageLabel, onDevice: onDevice)
            guard viewModel.uiState.dictation?.phase == .listening else { return }
            startDictationSession()
        case .permissionDenied:
            viewModel.beginDictationUnavailable(
                .permissionDenied,
                message: "Microphone access is off"
            )
        case .modelNotInstalled, .modelDownloading:
            viewModel.dictationModelDownload(.modelDownloadFailed)
        case .hidden:
            viewModel.dictationCancel()
        }
    }

    private func startDictationSession() {
        dictationSession.start(
            onPartial: { committed, volatileText in
                viewModel.dictationPartial(committed: committed, volatile: volatileText)
            },
            onLevel: { level in
                viewModel.dictationLevel(level)
            },
            onError: { message in
                if AVAudioApplication.shared.recordPermission == .denied {
                    viewModel.beginDictationUnavailable(
                        .permissionDenied,
                        message: "Microphone access is off"
                    )
                } else {
                    viewModel.beginDictationUnavailable(.recognizerError, message: message)
                }
            }
        )
    }

    private func requestMicrophoneAccess() async -> Bool {
        await withCheckedContinuation { continuation in
            AVAudioApplication.requestRecordPermission { granted in
                continuation.resume(returning: granted)
            }
        }
    }

    private func resignComposerKeyboard() {
        UIApplication.shared.sendAction(
            #selector(UIResponder.resignFirstResponder),
            to: nil,
            from: nil,
            for: nil
        )
    }

    private var composerAttachmentSources: ComposerAttachmentSources {
        viewModel.uiState.composer.attachmentSources(
            developerToolsUnlocked: developerToolsPreferences.unlocked
        )
    }

    private func attachDiagnosticLog() {
        do {
            viewModel.addAttachments([try DiagnosticLogAttachment.make()])
        } catch {
            viewModel.reportAttachmentReadFailure()
        }
    }

    private func openLastTurnChanges(assistantMessageID: String, initialFilePath: String?) {
        guard let target = viewModel.uiState.fileChangeNavigationTarget(
            worktree: boundSession.worktree,
            assistantMessageID: assistantMessageID,
            initialFilePath: initialFilePath
        ) else { return }
        if let messageID = target.messageID, viewModel.uiState.lastTurnSpansLocationChange(messageID: messageID) {
            viewModel.presentTurnDiffUnavailable()
            return
        }
        changesTarget = target
    }

    private func lastTurnDiffUnavailable(assistantMessageID: String) -> Bool {
        guard let messageID = viewModel.uiState.owningTurnMessageID(assistantMessageID: assistantMessageID) else {
            return false
        }
        return viewModel.uiState.lastTurnSpansLocationChange(messageID: messageID)
    }

    private func logChatAction(_ actionId: String) {
        ProductAnalytics.logUserAction(
            screenId: ProductAnalytics.Screen.sessionChat,
            actionId: actionId
        )
    }

    private func logOverflowAction(_ actionId: String) {
        ProductAnalytics.logUserAction(
            screenId: ProductAnalytics.Screen.sessionOverflow,
            actionId: actionId
        )
    }

    private func logAttachmentSourceAction(_ actionId: String) {
        ProductAnalytics.logUserAction(
            screenId: ProductAnalytics.Screen.sessionAttachmentSource,
            actionId: actionId
        )
    }

    private func pickerScreenId(_ picker: ComposerPickerKind) -> String {
        switch picker {
        case .model: ProductAnalytics.Screen.sessionModelPicker
        case .mode: ProductAnalytics.Screen.sessionModePicker
        case .variant: ProductAnalytics.Screen.sessionVariantPicker
        }
    }

    // Sheet-chrome dismissal only: entry taps clear the flag directly.
    private var attachmentSourcesBinding: Binding<Bool> {
        Binding(
            get: { showAttachmentSources },
            set: { presented in
                if !presented {
                    logAttachmentSourceAction(ProductAnalytics.Action.attachSourceDismiss)
                }
                showAttachmentSources = presented
            }
        )
    }
}

private func sessionChatTimelineBottomInset(_ composerOverlayHeight: CGFloat) -> CGFloat {
    // Home list uses a fixed scroll spacer; keep a fallback so the first frame
    // before GeometryReader measures still clears the floating composer.
    let height = composerOverlayHeight > 0 ? composerOverlayHeight : 168
    return height + OpenCodeBottomFade.overhang
}

private extension SessionChatSnackbar {
    func mapToOpenCodeSnackbarMessage() -> OpenCodeSnackbarMessage {
        OpenCodeSnackbarMessage(
            text: message,
            variant: tone == .error ? .error : .default,
            actionLabel: action?.label,
            id: id
        )
    }
}

private extension Optional where Wrapped == SessionChatSnackbar {
    func mapToOpenCodeSnackbarMessage() -> OpenCodeSnackbarMessage? {
        self?.mapToOpenCodeSnackbarMessage()
    }
}

/// Live retry status fused to the top of the composer. The link is the only button while the sheet is closed.
private struct SessionRetryCountdownCard: View {
    let card: SessionRetryCard
    let onOpenLink: (URL) -> Void
    let onOpenBody: (() -> Void)?
    @State private var now = Date()

    var body: some View {
        let seconds = max(0, Int((Double(card.nextAtMs) / 1000.0) - now.timeIntervalSince1970))
        let attempt = card.attempt.map { "attempt \($0), " } ?? ""
        let label = card.actionLabel?.trimmingCharacters(in: .whitespacesAndNewlines)
        let link = card.actionLink?.trimmingCharacters(in: .whitespacesAndNewlines)
        // Server-supplied links open as web pages only; other schemes get no button.
        let url = ExternalLinkPolicy.openableURL(link)
        HStack(alignment: .center, spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(card.message)
                    .font(OpenCodeTypography.body)
                    .foregroundStyle(OpenCodeColors.textStrong)
                    .lineLimit(1)
                Text("\(attempt)retrying in \(seconds)s")
                    .font(OpenCodeTypography.caption)
                    .foregroundStyle(OpenCodeColors.textWeak)
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
            .modifier(RetryStatusBodyTap(onOpenBody: onOpenBody))
            if let label, !label.isEmpty, let url {
                OpenCodeButton(variant: .compactText(title: label)) {
                    onOpenLink(url)
                }
            }
        }
        .padding(.leading, 12)
        .padding(.trailing, 10)
        .padding(.vertical, 8)
        .onReceive(Timer.publish(every: 1, on: .main, in: .common).autoconnect()) { now = $0 }
    }
}

private struct RetryStatusBodyTap: ViewModifier {
    let onOpenBody: (() -> Void)?

    func body(content: Content) -> some View {
        if let onOpenBody {
            content
                .onTapGesture(perform: onOpenBody)
                .accessibilityAddTraits(.isButton)
        } else {
            content
        }
    }
}

private struct CommandsActionTileGrid: View {
    let actions: [ClientSlashAction]
    let canMutate: Bool
    let mcpBadge: OpenCodeActionTileBadge?
    let run: (ClientSlashAction) -> Void

    var body: some View {
        OpenCodeActionTileGrid(items: actions) { action in
            tile(action)
        }
    }

    private func tile(_ action: ClientSlashAction) -> OpenCodeActionTile {
        let requiresLive = action == .new || action == .fork || action == .terminal || action == .files
        return OpenCodeActionTile(
            icon: action.tileIcon,
            label: action.tileLabel,
            accessibilityLabel: action.descriptionText,
            enabled: !requiresLive || canMutate,
            badge: action == .mcp ? mcpBadge : nil,
            action: { run(action) }
        )
    }
}

private extension ClientSlashAction {
    var tileIcon: OpenCodeIcon {
        switch self {
        case .new: .add
        case .terminal: .terminal
        case .files: .folder
        case .fork: .callSplit
        case .undo: .undo
        case .redo: .redo
        case .compact: .verticalAlignCenter
        case .mcp: .plug
        case .btw: .subdirectoryArrowRight
        }
    }
}

#Preview {
    NavigationStack {
        SessionChatView(
            session: .init(id: "1", title: "Importing icons explanation", worktree: "/Users/demo/opencode-mobile")
        )
    }
    .openCodeTheme()
}
