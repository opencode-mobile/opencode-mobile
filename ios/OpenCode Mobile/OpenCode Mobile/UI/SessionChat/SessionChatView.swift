import Factory
import PhotosUI
import SwiftUI
import UIKit
import UniformTypeIdentifiers

struct SessionChatView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var viewModelStorage: SessionChatViewModel?
    @State private var activePicker: ComposerPickerKind?
    @State private var activeModelProviderID: String?
    @State private var modelFilterText = ""
    @State private var showSessionContext = false
    @State private var changesTarget: ChangesNavigationTarget?
    @State private var menuExpanded = false
    @State private var showDeleteConfirmation = false
    @State private var messageActionsTarget: SessionChatMessageActionsTarget?
    @State private var showPendingPromptsSheet = false
    @State private var toolDetailTarget: SessionChatToolDetailTarget?
    @State private var toolGroupTarget: SessionChatToolGroupTarget?
    @State private var skillsTarget: SessionChatSkillsTarget?
    @State private var childSessionTarget: ChildSessionNavigationTarget?
    @State private var terminalRoute: TerminalRoute?
    @State private var developerToolsPreferences = DeveloperToolsPreferences.shared
    @State private var showAttachmentSources = false
    @State private var showFilesPicker = false
    @State private var showPhotosPicker = false
    @State private var selectedPhotoItems: [PhotosPickerItem] = []
    @State private var showSessionTodosSheet = false
    @State private var holdingFinishedTodos = false
    @State private var sawUnfinishedTodos = false
    @State private var finishedHoldTask: Task<Void, Never>?
    /// Measured floating composer chrome height (composer + optional pending-prompts strip).
    @State private var composerOverlayHeight: CGFloat = 0
    @State private var bottomSafeAreaInset: CGFloat = 0
    /// Keyboard overlap reported by `keyboardOverlapPadding`. Zero when hidden.
    @State private var keyboardOverlap: CGFloat = 0

    private let session: SessionListItem
    private let injectedViewModel: SessionChatViewModel?

    init(session: SessionListItem, viewModel: SessionChatViewModel? = nil) {
        self.session = session
        // Do not construct the ViewModel in `State(initialValue:)` — SwiftUI re-runs
        // View.init (and that expression) whenever the parent body refreshes, while
        // keeping one live `@State`. Eager make() was spawning bind storms and
        // exhausting URLSession.shared during compaction status churn.
        self.injectedViewModel = viewModel
    }

    /// Available after `.task` bootstrap; only read from content that is gated on storage.
    private var viewModel: SessionChatViewModel {
        viewModelStorage!
    }

    /// Temporarily off: bar currently only shows context usage.
    private static let showsSessionStatusBar = false

    var body: some View {
        Group {
            if viewModelStorage != nil {
                sessionChatBody
            } else {
                ProgressView()
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
            viewModelStorage?.stopObserving()
            viewModelStorage = injectedViewModel ?? Container.makeSessionChatViewModel(
                sessionID: session.id,
                sessionTitle: session.title,
                worktree: session.worktree
            )
            viewModel.startObserving()
            await viewModel.loadMessages()
        }
    }

    private var sessionNavigationTaskID: String {
        if let instanceID = session.optimisticNavigationInstanceID {
            return "\(session.id)-\(instanceID.uuidString)"
        }
        return session.id
    }

    @ViewBuilder
    private var sessionChatBody: some View {
        VStack(spacing: 0) {
            OpenCodeTopBarOverflowHost(
                isPresented: $menuExpanded,
                entries: sessionOverflowMenuEntries
            ) {
                OpenCodeTopBar(
                    title: viewModel.uiState.sessionTitle.isEmpty ? session.title : viewModel.uiState.sessionTitle,
                    subtitle: viewModel.uiState.projectLabel,
                    subtitleLeading: {
                        if let letter = viewModel.uiState.projectLetter,
                           let accent = viewModel.uiState.projectLetterAccent {
                            return .letter(letter, accent)
                        }
                        return nil
                    }(),
                    subtitleAccessory: .statusDot(viewModel.uiState.connectionStatus.openCodeStatus),
                    leading: .back(action: {
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
                Text(notice)
                    .font(OpenCodeTypography.caption)
                    .foregroundStyle(OpenCodeColors.textBase)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 16)
                    .padding(.bottom, 8)
                    .openCodeReadableColumn()
            }

            // Keyboard avoidance wraps only the timeline + composer so the top bar
            // stays fixed. Bottom alignment inside the modifier keeps multiline
            // growth above the keyboard (OPE-118) without sliding chrome under the
            // status bar.
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
                        sessionTodosVisible: sessionTodosPillVisible,
                        sessionTodoCompletedCount: viewModel.uiState.sessionTodoCompletedCount,
                        sessionTodoTotalCount: viewModel.uiState.sessionTodoTotalCount,
                        sessionTodoActivePreview: viewModel.uiState.sessionTodoActivePreview?.content,
                        hideFloatStack: showSessionTodosSheet,
                        // Composer + 16 end-pin gap; Scroll to end uses this as-is
                        // (no extra scrollToEndPillBottomPadding).
                        bottomOverlayInset: sessionChatTimelineBottomInset(composerOverlayHeight)
                            + composerHomeIndicatorLift
                            + keyboardOverlap,
                        onRefresh: { await viewModel.refreshMessages() },
                        onLoadOlder: { Task { await viewModel.loadOlderMessages() } },
                        onSessionBranchTap: {
                            logChatAction(ProductAnalytics.Action.chatOpenChanges)
                            changesTarget = viewModel.uiState.changesNavigationTarget(worktree: session.worktree)
                        },
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
                                worktree: session.worktree,
                                optimisticNavigationInstanceID: nil,
                                parentID: session.id
                            )
                        },
                        onChangesReviewTap: { assistantMessageID, initialFilePath in
                            logChatAction(ProductAnalytics.Action.chatOpenChangesFromTool)
                            changesTarget = viewModel.uiState.fileChangeNavigationTarget(
                                worktree: session.worktree,
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
                        }
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
                    onDismissed: { viewModel.consumeSnackbar() }
                )
                .padding(.bottom, max(composerOverlayHeight, 168) + composerHomeIndicatorLift)

                VStack(spacing: 0) {
                    if SessionChatComposerKind.resolve(
                        isChildSession: session.parentID != nil,
                        hasPermission: viewModel.uiState.permissionRequest != nil && !viewModel.uiState.permissionDockHidden,
                        hasQuestion: viewModel.uiState.questionRequest != nil
                    ) == .prompt {
                        if viewModel.uiState.atSuggestionsVisible {
                            OpenCodeComposerSuggestionOverlay(
                                items: viewModel.uiState.atSuggestions.map { suggestion in
                                    OpenCodeComposerSuggestionItem(
                                        id: suggestion.id,
                                        title: suggestion.title,
                                        subtitle: suggestion.subtitle
                                    )
                                },
                                emptyMessage: "No agents",
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
                        // Suggestions float above this stack. Keep the pending strip
                        // in the measured height so the timeline inset does not jump.
                        if viewModel.uiState.permissionDockHidden {
                            OpenCodeActionPill(variant: .reviewPermission) {
                                viewModel.showPermissionDock()
                            }
                            .padding(.bottom, 6)
                        }
                        if SessionChatComposerKind.resolve(
                            isChildSession: session.parentID != nil,
                            hasPermission: viewModel.uiState.permissionRequest != nil && !viewModel.uiState.permissionDockHidden,
                            hasQuestion: viewModel.uiState.questionRequest != nil
                        ) == .prompt,
                            !viewModel.uiState.pendingPrompts.isEmpty {
                            OpenCodePendingPromptsStrip(
                                prompts: viewModel.uiState.pendingPrompts,
                                onTap: {
                                    ProductAnalytics.logScreenView(
                                        screenId: ProductAnalytics.Screen.sessionPendingPrompts,
                                        presentation: ProductAnalytics.Presentation.sheet,
                                        trigger: ProductAnalytics.Trigger.present
                                    )
                                    showPendingPromptsSheet = true
                                }
                            )
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
        .sessionChatMessageActionsSheet(
            target: $messageActionsTarget,
            onRevertMessage: { viewModel.revertMessage($0) }
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
        .sessionChatToolDetailSheet(target: $toolDetailTarget)
        .sessionChatSkillsSheet(target: $skillsTarget)
        // Waits for any view-owned sheet to close so the intro never stacks on one.
        .sessionChatChipTimelineIntroSheet(
            isPresented: viewModel.uiState.chipTimelineIntroVisible && !viewSheetOpen,
            onEnable: { viewModel.enableChipTimelineFromIntro() },
            onLater: { viewModel.postponeChipTimelineIntro() },
            onNever: { viewModel.dismissChipTimelineIntro() }
        )
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
        .openCodeBottomSheet(isPresented: attachmentSourcesBinding, title: "Add attachment") {
            let sources = composerAttachmentSources
            if sources.photos {
                OpenCodeBottomSheetEntry(
                    title: "Photos",
                    startDecorator: .icon(.description, accessibilityLabel: "Photos"),
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
                    startDecorator: .icon(.folder, accessibilityLabel: "Files"),
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
                    startDecorator: .icon(.list, accessibilityLabel: "Log file"),
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
        .navigationDestination(isPresented: $showSessionContext) {
            SessionContextView(sessionID: viewModel.uiState.sessionID, worktree: session.worktree)
        }
        .navigationDestination(item: $changesTarget) { target in
            ChangesView(
                sessionID: target.sessionID,
                worktree: target.worktree,
                messageID: target.messageID,
                initialSource: target.initialSource,
                initialFilePath: target.initialFilePath
            )
        }
        .navigationDestination(item: $toolGroupTarget) { target in
            SessionChatToolGroupView(
                target: target,
                worktree: session.worktree,
                parentSessionID: session.id,
                supportsSessionMessageDiff: viewModel.uiState.supportsSessionMessageDiff,
                makeChangesTarget: { assistantMessageID, initialFilePath in
                    viewModel.uiState.fileChangeNavigationTarget(
                        worktree: session.worktree,
                        assistantMessageID: assistantMessageID,
                        initialFilePath: initialFilePath
                    )
                }
            )
        }
        .navigationDestination(item: $childSessionTarget) { target in
            SessionChatView(session: target.sessionListItem)
        }
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
        .onDisappear {
            viewModelStorage?.stopObserving()
        }
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
        .navigationDestination(item: $terminalRoute) { route in
            TerminalView(worktree: route.worktree, projectTitle: route.projectTitle, ptyId: route.ptyId)
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
        viewModelStorage?.stopObserving()
        dismiss()
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
                    changesTarget = viewModel.uiState.changesNavigationTarget(worktree: session.worktree)
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

    private var composer: some View {
        let composer = viewModel.uiState.composer
        let isSending = viewModel.uiState.isSending
        let kind = SessionChatComposerKind.resolve(
            isChildSession: session.parentID != nil,
            hasPermission: viewModel.uiState.permissionRequest != nil && !viewModel.uiState.permissionDockHidden,
            hasQuestion: viewModel.uiState.questionRequest != nil
        )
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
                    queueCount: viewModel.uiState.permissionQueueCount,
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
                    errorMessage: draft.questionErrorMessage
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
            state = .prompt(
                .init(
                    text: composer.text,
                    mentionRanges: composer.agentMentions.map { $0.start ..< $0.end },
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
                        composer.hasSendableContent &&
                        composer.selectedModel != nil &&
                        composer.isModelSelectionReady &&
                        !isSending &&
                        !OptimisticSessionCreate.isPlaceholder(viewModel.uiState.sessionID) &&
                        viewModel.uiState.timeline != .creating &&
                        viewModel.uiState.composerEnabled &&
                        viewModel.uiState.pendingSend == nil,
                    attachEnabled: composerAttachmentSources.isAttachEnabled &&
                        !OptimisticSessionCreate.isPlaceholder(viewModel.uiState.sessionID) &&
                        composer.isModelSelectionReady,
                    showStop: viewModel.uiState.sessionStatusLabel != nil,
                    stopEnabled: !viewModel.uiState.isAborting &&
                        viewModel.uiState.canMutate,
                    followUpDelivery: composer.supportsFollowUpDelivery ? composer.followUpDelivery : nil
                )
            )
        }

        return OpenCodeSessionComposer(
            state: state,
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
                case .cancelRevert:
                    viewModel.cancelRevert()
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
                }
            }
        )
    }

    private func attachmentPresentation(_ attachment: Attachment) -> OpenCodeAttachmentPresentation {
        AttachmentPresentationMapper.presentation(from: attachment)
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
            id: id
        )
    }
}

private extension Optional where Wrapped == SessionChatSnackbar {
    func mapToOpenCodeSnackbarMessage() -> OpenCodeSnackbarMessage? {
        self?.mapToOpenCodeSnackbarMessage()
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
        let requiresLive = action == .new || action == .fork || action == .terminal
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
        case .fork: .callSplit
        case .undo: .undo
        case .redo: .redo
        case .compact: .verticalAlignCenter
        case .mcp: .plug
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
