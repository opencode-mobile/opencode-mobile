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
    @State private var menuExpanded = false

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
                    onRefresh: { await viewModel.refreshMessages() },
                    onRestoreComposerText: { viewModel.restoreComposerText($0) }
                )
            )
            composer
        }
        .background(OpenCodeColors.backgroundBase)
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

        return SessionChatComposerView(
            props: SessionChatComposerViewProps(
                text: composer.text,
                selectedModelLabel: composer.selectedModelLabel,
                selectedMode: composer.selectedMode,
                selectedVariantLabel: composer.selectedVariantLabel,
                showVariant: composer.canSelectVariant,
                sendEnabled: !composer.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && composer.selectedModel != nil && !isSending,
                showStop: viewModel.uiState.sessionStatusLabel != nil,
                stopEnabled: !viewModel.uiState.isAborting
            ),
            onTextChange: { viewModel.updateComposerText($0) },
            onModelClick: {
                activeModelProviderID = composer.selectedModel?.option.providerID
                activePicker = .model
            },
            onModeClick: { activePicker = .mode },
            onVariantClick: { activePicker = .variant },
            onAttachClick: {},
            onSendClick: { Task { await viewModel.sendPrompt() } },
            onStopClick: { Task { await viewModel.abortSession() } }
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
