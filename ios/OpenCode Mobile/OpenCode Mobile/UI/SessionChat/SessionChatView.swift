import Factory
import SwiftUI
import UIKit

struct SessionChatView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var viewModel: SessionChatViewModel
    @State private var activePicker: ComposerPickerKind?
    @State private var activeModelProviderID: String?
    @State private var modelFilterText = ""
    @State private var showSessionContext = false
    @State private var changesTarget: ChangesNavigationTarget?
    @State private var menuExpanded = false
    @State private var messageActionsTarget: SessionChatMessageActionsTarget?

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
                    title: "Session",
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
                    reviewChangesFileCount: viewModel.uiState.reviewChangesFileCount,
                    onRefresh: { await viewModel.refreshMessages() },
                    onReviewChangesTap: {
                        changesTarget = viewModel.uiState.reviewChangesNavigationTarget(worktree: session.worktree)
                    },
                    onMessageActionsTap: { messageActionsTarget = $0 }
                )
            )
            composer
        }
        .background(OpenCodeColors.backgroundBase)
        .sessionChatMessageActionsSheet(
            target: $messageActionsTarget,
            onRestoreToComposer: { viewModel.restoreComposerText($0) }
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
        .toolbar(.hidden, for: .navigationBar)
        .navigationDestination(isPresented: $showSessionContext) {
            SessionContextView(sessionID: viewModel.uiState.sessionID, worktree: session.worktree)
        }
        .navigationDestination(item: $changesTarget) { target in
            ChangesView(
                sessionID: target.sessionID,
                worktree: target.worktree,
                messageID: target.messageID,
                initialSource: target.initialSource
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
        .onDisappear {
            viewModel.stopObserving()
        }
    }

    private func leaveSession() {
        viewModel.stopObserving()
        dismiss()
    }

    private var sessionOverflowMenuEntries: [OpenCodeOverflowMenuEntry] {
        [
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
                    selectedModelLabel: composer.selectedModelLabel,
                    selectedModeLabel: composer.selectedMode,
                    selectedVariantLabel: composer.selectedVariantLabel,
                    showVariant: composer.canSelectVariant,
                    sendEnabled: !composer.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && composer.selectedModel != nil && !isSending,
                    showStop: viewModel.uiState.sessionStatusLabel != nil,
                    stopEnabled: !viewModel.uiState.isAborting
                )
            )
        }

        return OpenCodeSessionComposer(
            state: state,
            onAction: { action in
                switch action {
                case let .textChanged(text):
                    viewModel.updateComposerText(text)
                case .selectModel:
                    activeModelProviderID = composer.selectedModel?.option.providerID
                    activePicker = .model
                case .selectMode:
                    activePicker = .mode
                case .selectVariant:
                    activePicker = .variant
                case .attach:
                    break
                case .send:
                    Task { await viewModel.sendPrompt() }
                case .stop:
                    Task { await viewModel.abortSession() }
                case let .allowPermissionOnce(requestID, sessionID):
                    Task { await viewModel.allowPermissionOnce(requestID: requestID, sessionID: sessionID) }
                case let .denyPermission(requestID, sessionID):
                    Task { await viewModel.denyPermission(requestID: requestID, sessionID: sessionID) }
                }
            }
        )
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
