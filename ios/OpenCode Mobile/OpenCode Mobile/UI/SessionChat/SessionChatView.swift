import Factory
import PhotosUI
import SwiftUI
import UIKit
import UniformTypeIdentifiers

struct SessionChatView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var viewModel: SessionChatViewModel
    @State private var activePicker: ComposerPickerKind?
    @State private var activeModelProviderID: String?
    @State private var modelFilterText = ""
    @State private var showSessionContext = false
    @State private var changesTarget: ChangesNavigationTarget?
    @State private var menuExpanded = false
    @State private var showDeleteConfirmation = false
    @State private var messageActionsTarget: SessionChatMessageActionsTarget?
    @State private var toolDetailTarget: SessionChatToolDetailTarget?
    @State private var childSessionTarget: ChildSessionNavigationTarget?
    @State private var showAttachmentSources = false
    @State private var showFilesPicker = false
    @State private var showPhotosPicker = false
    @State private var selectedPhotoItems: [PhotosPickerItem] = []
    @State private var showSessionTodosSheet = false
    @State private var holdingFinishedTodos = false
    @State private var finishedHoldTask: Task<Void, Never>?

    init(session: SessionListItem, viewModel: SessionChatViewModel? = nil) {
        self.session = session
        _viewModel = State(
            initialValue: viewModel ?? Container.makeSessionChatViewModel(
                sessionID: session.id,
                sessionTitle: session.title,
                worktree: session.worktree
            )
        )
    }

    private let session: SessionListItem

    var body: some View {
        VStack(spacing: 0) {
            OpenCodeTopBarOverflowHost(
                isPresented: $menuExpanded,
                entries: sessionOverflowMenuEntries
            ) {
                OpenCodeTopBar(
                    title: viewModel.uiState.sessionTitle.isEmpty ? session.title : viewModel.uiState.sessionTitle,
                    subtitleAccessory: .statusDot(viewModel.uiState.connectionStatus.openCodeStatus),
                    leading: .back(action: leaveSession),
                    actions: [
                        .icon(.moreVert, accessibilityLabel: "More options") {
                            menuExpanded = true
                        },
                    ],
                    showDivider: true
                )
            }

            OpenCodeSessionStatusBar(
                contextUsageFraction: viewModel.uiState.contextUsageFraction,
                onContextClick: { showSessionContext = true }
            )

            if let notice = viewModel.uiState.composer.modelNotice {
                Text(notice)
                    .font(OpenCodeTypography.caption)
                    .foregroundStyle(OpenCodeColors.textBase)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 16)
                    .padding(.bottom, 8)
            }

            SessionChatTimelineView(
                props: SessionChatTimelineViewProps(
                    timeline: viewModel.uiState.timeline,
                    messages: viewModel.uiState.messages,
                    timelineFrame: viewModel.uiState.timelineFrame,
                    isRefreshing: viewModel.uiState.isRefreshing,
                    errorMessage: viewModel.uiState.errorMessage,
                    reviewChangesSummary: viewModel.uiState.reviewChangesSummary,
                    sessionTodosVisible: sessionTodosPillVisible,
                    sessionTodoCompletedCount: viewModel.uiState.sessionTodoCompletedCount,
                    sessionTodoTotalCount: viewModel.uiState.sessionTodoTotalCount,
                    sessionTodoActivePreview: viewModel.uiState.sessionTodoActivePreview?.content,
                    hideFloatStack: showSessionTodosSheet,
                    onRefresh: { await viewModel.refreshMessages() },
                    onReviewChangesTap: {
                        changesTarget = viewModel.uiState.reviewChangesNavigationTarget(worktree: session.worktree)
                    },
                    onSessionTodosTap: {
                        guard !blocksSessionTodosSheet else { return }
                        showSessionTodosSheet = true
                    },
                    onMessageActionsTap: { messageActionsTarget = $0 },
                    onToolDetailTap: { toolDetailTarget = $0 },
                    onChildSessionTap: { sessionID, title in
                        childSessionTarget = ChildSessionNavigationTarget(
                            sessionID: sessionID,
                            sessionTitle: title,
                            worktree: session.worktree
                        )
                    },
                    onChangesReviewTap: { assistantMessageID, initialFilePath in
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
                    }
                )
            )
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .overlay(alignment: .bottom) {
                // ZStack keeps suggestions and snackbar independently bottom-anchored.
                // A bare ViewBuilder tuple lays out like a VStack, which parks the
                // suggestion panel at the top of the timeline instead of above the composer.
                ZStack(alignment: .bottom) {
                    OpenCodeSnackbarHost(
                        message: viewModel.uiState.snackbar.mapToOpenCodeSnackbarMessage(),
                        onDismissed: { viewModel.consumeSnackbar() }
                    )
                    if viewModel.uiState.permissionRequest == nil, viewModel.uiState.questionRequest == nil {
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
                }
            }
            composer
        }
        .background(OpenCodeColors.backgroundBase)
        .keyboardOverlapPadding()
        .sessionChatMessageActionsSheet(
            target: $messageActionsTarget,
            onRestoreToComposer: { viewModel.restoreComposerText($0) }
        )
        .sessionChatToolDetailSheet(target: $toolDetailTarget)
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
        .openCodeBottomSheet(isPresented: $showAttachmentSources, title: "Add attachment") {
            if viewModel.uiState.composer.canAttachPhotos {
                OpenCodeBottomSheetEntry(
                    title: "Photos",
                    startDecorator: .icon(.description, accessibilityLabel: "Photos"),
                    action: {
                        showAttachmentSources = false
                        showPhotosPicker = true
                    }
                )
            }
            if viewModel.uiState.composer.canAttachFiles {
                OpenCodeBottomSheetEntry(
                    title: "Files",
                    startDecorator: .icon(.folder, accessibilityLabel: "Files"),
                    action: {
                        showAttachmentSources = false
                        showFilesPicker = true
                    }
                )
            }
        }
        .openCodeBottomSheet(isPresented: commandsSheetBinding, title: "Commands") {
            ForEach(viewModel.uiState.visibleClientSlashActions, id: \.rawValue) { action in
                OpenCodeBottomSheetEntry(
                    title: action.rowTitle,
                    action: { viewModel.runClientSlashAction(action) }
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
        .toolbar(.hidden, for: .navigationBar)
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
        .navigationDestination(item: $childSessionTarget) { target in
            SessionChatView(
                session: SessionListItem(
                    id: target.sessionID,
                    title: target.sessionTitle,
                    worktree: target.worktree
                )
            )
        }
        .task {
            viewModel.startObserving()
            await viewModel.loadMessages()
        }
        .onChange(of: activePicker) { _, picker in
            if picker != .model {
                activeModelProviderID = nil
                modelFilterText = ""
            }
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
                showSessionTodosSheet = false
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
                showDeleteConfirmation = false
                viewModel.deleteSession()
            },
            secondaryOption: OpenCodeDialogOption(
                title: "Cancel",
                enabled: !viewModel.uiState.isLifecycleActionInFlight
            ) {
                showDeleteConfirmation = false
            }
        )
        .onDisappear {
            viewModel.stopObserving()
        }
        .openCodeDialog(
            isPresented: renameDialogBinding,
            title: "Rename session",
            primaryOption: OpenCodeDialogOption(
                title: "Save",
                enabled: !viewModel.uiState.rename.isSubmitting
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
                enabled: !viewModel.uiState.forkSubmitting
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
        .onChange(of: viewModel.uiState.pendingSessionReplace?.sessionID) { _, _ in
            if let target = viewModel.uiState.pendingSessionReplace {
                childSessionTarget = target
                viewModel.consumePendingSessionReplace()
            }
        }
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
        guard finished, !viewModel.uiState.sessionTodos.isEmpty else {
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
        viewModel.stopObserving()
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
        let lifecycleEnabled = !viewModel.uiState.isLifecycleActionInFlight
        return [
            OpenCodeOverflowMenuEntry(
                title: "Rename session",
                icon: .edit,
                action: {
                    menuExpanded = false
                    viewModel.openRenameSession()
                }
            ),
            OpenCodeOverflowMenuEntry(
                title: "Review changes",
                icon: .description,
                action: {
                    menuExpanded = false
                    changesTarget = viewModel.uiState.changesNavigationTarget(worktree: session.worktree)
                }
            ),
            OpenCodeOverflowMenuEntry(
                title: "Session context",
                icon: .architecture,
                action: {
                    menuExpanded = false
                    showSessionContext = true
                }
            ),
            OpenCodeOverflowMenuEntry(
                title: "Copy session ID",
                icon: .contentCopy,
                action: {
                    menuExpanded = false
                    UIPasteboard.general.string = viewModel.uiState.sessionID
                }
            ),
            OpenCodeOverflowMenuEntry(
                title: "Archive",
                icon: .archive,
                enabled: lifecycleEnabled,
                action: {
                    menuExpanded = false
                    viewModel.archiveSession()
                }
            ),
            OpenCodeOverflowMenuEntry(
                title: "Delete",
                icon: .close,
                enabled: lifecycleEnabled,
                destructive: true,
                action: {
                    menuExpanded = false
                    showDeleteConfirmation = true
                }
            ),
        ]
    }

    private var composer: some View {
        let composer = viewModel.uiState.composer
        let isSending = viewModel.uiState.isSending
        let state: OpenCodeSessionComposerState

        if let request = viewModel.uiState.permissionRequest {
            state = .permission(
                .init(
                    requestID: request.id,
                    sessionID: request.sessionID,
                    permission: request.permission,
                    patterns: request.patterns,
                    queueCount: viewModel.uiState.permissionQueueCount,
                    submitting: viewModel.uiState.permissionSubmitting,
                    errorMessage: viewModel.uiState.permissionErrorMessage
                )
            )
        } else if let request = viewModel.uiState.questionRequest {
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
                    customAllowed: question?.custom ?? true,
                    selectedLabels: draft.questionSelectedLabels,
                    customText: draft.questionCustomText,
                    customSelected: draft.questionCustomSelected,
                    backEnabled: index > 0 && !draft.questionSubmitting,
                    nextEnabled: isValid && index < questionCount - 1 && !draft.questionSubmitting,
                    submitEnabled: isValid && index == questionCount - 1 && !draft.questionSubmitting,
                    submitting: draft.questionSubmitting,
                    errorMessage: draft.questionErrorMessage
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
                    selectedModelLabel: composer.selectedModelLabel,
                    selectedModeLabel: composer.selectedMode,
                    selectedVariantLabel: composer.selectedVariantLabel,
                    showVariant: composer.canSelectVariant,
                    sendEnabled:
                        composer.hasSendableContent &&
                        composer.selectedModel != nil &&
                        composer.isModelSelectionReady &&
                        !isSending,
                    attachEnabled: composer.canAttach && composer.isModelSelectionReady,
                    showStop: viewModel.uiState.sessionStatusLabel != nil,
                    stopEnabled: !viewModel.uiState.isAborting
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
                    activeModelProviderID = composer.selectedModel?.option.providerID
                    activePicker = .model
                case .selectMode:
                    activePicker = .mode
                case .selectVariant:
                    activePicker = .variant
                case .attach:
                    let photos = composer.canAttachPhotos
                    let files = composer.canAttachFiles
                    if photos, files {
                        showAttachmentSources = true
                    } else if photos {
                        showPhotosPicker = true
                    } else if files {
                        showFilesPicker = true
                    }
                case let .removeAttachment(id):
                    viewModel.removeAttachment(id: id)
                case .openCommands:
                    viewModel.openCommands()
                case .send:
                    Task { await viewModel.sendPrompt() }
                case .stop:
                    Task { await viewModel.abortSession() }
                case let .allowPermissionOnce(requestID, sessionID):
                    Task { await viewModel.allowPermissionOnce(requestID: requestID, sessionID: sessionID) }
                case let .denyPermission(requestID, sessionID):
                    Task { await viewModel.denyPermission(requestID: requestID, sessionID: sessionID) }
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
                }
            }
        )
    }

    private func attachmentPresentation(_ attachment: Attachment) -> OpenCodeAttachmentPresentation {
        AttachmentPresentationMapper.presentation(from: attachment)
    }
}

private extension SessionConnectionStatus {
    var openCodeStatus: OpenCodeSessionConnectionStatus {
        switch self {
        case .live:
            .live
        case .connecting:
            .connecting
        case .offline:
            .offline
        }
    }
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

#Preview {
    NavigationStack {
        SessionChatView(
            session: .init(id: "1", title: "Importing icons explanation", worktree: "/Users/demo/opencode-mobile")
        )
    }
    .openCodeTheme()
}
