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
                    isRefreshing: viewModel.uiState.isRefreshing,
                    errorMessage: viewModel.uiState.errorMessage,
                    reviewChangesSummary: viewModel.uiState.reviewChangesSummary,
                    onRefresh: { await viewModel.refreshMessages() },
                    onReviewChangesTap: {
                        changesTarget = viewModel.uiState.reviewChangesNavigationTarget(worktree: session.worktree)
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
                    }
                )
            )
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .overlay(alignment: .bottom) {
                if viewModel.uiState.permissionRequest == nil {
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
                        .padding(.vertical, 4)
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
                        .padding(.vertical, 4)
                    }
                }
            }
            composer
        }
        .background(OpenCodeColors.backgroundBase)
        .sessionChatMessageActionsSheet(
            target: $messageActionsTarget,
            onRestoreToComposer: { viewModel.restoreComposerText($0) }
        )
        .sessionChatToolDetailSheet(target: $toolDetailTarget)
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
                case .send:
                    Task { await viewModel.sendPrompt() }
                case .stop:
                    Task { await viewModel.abortSession() }
                case let .allowPermissionOnce(requestID, sessionID):
                    Task { await viewModel.allowPermissionOnce(requestID: requestID, sessionID: sessionID) }
                case let .denyPermission(requestID, sessionID):
                    Task { await viewModel.denyPermission(requestID: requestID, sessionID: sessionID) }
                case .toggleQuestionOption, .toggleQuestionCustom, .questionCustomTextChanged,
                     .questionBack, .questionNext, .submitQuestion, .dismissQuestion:
                    // Question request actions are wired in OPE-66.
                    break
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

#Preview {
    NavigationStack {
        SessionChatView(
            session: .init(id: "1", title: "Importing icons explanation", worktree: "/Users/demo/opencode-mobile")
        )
    }
    .openCodeTheme()
}
