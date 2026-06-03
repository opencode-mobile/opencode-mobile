import Factory
import SwiftUI
import UIKit

private enum ComposerPickerKind {
    case model
    case mode
    case variant

    var sheetTitle: String {
        switch self {
        case .model:
            "Model"
        case .mode:
            "Agent mode"
        case .variant:
            "Variant"
        }
    }
}

private enum TimelineScrollAnchor {
    static let bottom = "session-chat-timeline-bottom"
}

struct SessionChatView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var viewModel: SessionChatViewModel
    @State private var activePicker: ComposerPickerKind?
    @State private var modelFilterText = ""
    @State private var hasInitializedTimelineScroll = false
    @State private var followingLatest = true

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
            OpenCodeTopBar(
                title: "Session",
                leading: .back(action: leaveSession),
                showDivider: true
            )

            OpenCodeSessionStatusBar(
                sessionID: viewModel.state.sessionID,
                connectionStatus: viewModel.connectionStatus.openCodeStatus,
                contextUsageFraction: viewModel.contextUsageFraction,
                onCopySessionID: {
                    UIPasteboard.general.string = viewModel.state.sessionID
                },
                onConnectionClick: {},
                onContextClick: {}
            )

            if let statusLabel = viewModel.sessionStatusLabel {
                Text(statusLabel)
                    .font(OpenCodeTypography.caption)
                    .foregroundStyle(OpenCodeColors.textInteractive)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 16)
                    .padding(.bottom, 8)
            }

            if let notice = viewModel.state.composer.modelNotice {
                Text(notice)
                    .font(OpenCodeTypography.caption)
                    .foregroundStyle(OpenCodeColors.textSecondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 16)
                    .padding(.bottom, 8)
            }

            timeline
            composer
        }
        .background(OpenCodeColors.backgroundPrimary)
        .openCodeBottomSheet(
            isPresented: pickerPresentedBinding,
            title: activePicker?.sheetTitle ?? "",
            scrollTargetID: modelPickerScrollTargetID,
            pinnedContent: { modelPickerPinnedContent },
            content: { pickerSheetEntries }
        )
        .toolbar(.hidden, for: .navigationBar)
        .task {
            viewModel.startObserving()
            await viewModel.loadMessages()
        }
        .onChange(of: activePicker) { _, picker in
            if picker != .model {
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

    @ViewBuilder
    private var timeline: some View {
        switch viewModel.state {
        case .loading:
            Spacer()
            ProgressView()
            Spacer()
        case .empty:
            Spacer()
            Text("No messages yet. Send a prompt to start.")
                .font(OpenCodeTypography.body)
                .foregroundStyle(OpenCodeColors.textSecondary)
                .padding(.horizontal, 16)
            Spacer()
        case let .error(_, _, message, _):
            Spacer()
            Text(message)
                .font(OpenCodeTypography.body)
                .foregroundStyle(OpenCodeColors.textSecondary)
                .padding(.horizontal, 16)
            Spacer()
        case let .loaded(sessionID, _, messages, _, _, isRefreshing, errorBanner):
            VStack(spacing: 0) {
                if let errorBanner {
                    Text(errorBanner)
                        .font(OpenCodeTypography.caption)
                        .foregroundStyle(OpenCodeColors.textSecondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 16)
                        .padding(.bottom, 4)
                }
                ScrollViewReader { proxy in
                    ScrollView {
                        LazyVStack(alignment: .leading, spacing: 8) {
                            ForEach(messages) { message in
                                HStack {
                                    if message.role == .assistant || message.role == .command || message.role == .tool || message.role == .unknown {
                                        messageRow(message)
                                        Spacer(minLength: 0)
                                    } else {
                                        Spacer(minLength: 0)
                                        messageRow(message)
                                    }
                                }
                                .id(message.id)
                            }
                            Color.clear
                                .frame(height: 1)
                                .id(TimelineScrollAnchor.bottom)
                                .onAppear {
                                    followingLatest = true
                                }
                        }
                        .padding(.horizontal, 16)
                        .padding(.vertical, 12)
                    }
                    .simultaneousGesture(
                        DragGesture().onChanged { _ in
                            followingLatest = false
                        }
                    )
                    .onChange(of: sessionID) { _, _ in
                        hasInitializedTimelineScroll = false
                        followingLatest = true
                    }
                    .task(id: timelineScrollTaskID(sessionID: sessionID, messages: messages)) {
                        guard followingLatest || !hasInitializedTimelineScroll else { return }
                        await scrollTimelineToBottom(
                            using: proxy,
                            animated: hasInitializedTimelineScroll
                        )
                        hasInitializedTimelineScroll = true
                    }
                    .refreshable {
                        await viewModel.refreshMessages()
                    }
                    .overlay {
                        if isRefreshing {
                            ProgressView()
                        }
                    }
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }

    private func timelineScrollTaskID(sessionID: String, messages: [SessionChatRow]) -> String {
        let latest = messages.last
        return "\(sessionID)-\(messages.count)-\(latest?.id ?? "empty")-\(latest?.text.hashValue ?? 0)"
    }

    @MainActor
    private func scrollTimelineToBottom(using proxy: ScrollViewProxy, animated: Bool) async {
        guard !messagesFromLoadedState().isEmpty else { return }
        if animated {
            withAnimation {
                proxy.scrollTo(TimelineScrollAnchor.bottom, anchor: .bottom)
            }
        } else {
            proxy.scrollTo(TimelineScrollAnchor.bottom, anchor: .bottom)
        }
        await Task.yield()
        try? await Task.sleep(for: .milliseconds(50))
        if animated {
            withAnimation {
                proxy.scrollTo(TimelineScrollAnchor.bottom, anchor: .bottom)
            }
        } else {
            proxy.scrollTo(TimelineScrollAnchor.bottom, anchor: .bottom)
        }
    }

    private func messagesFromLoadedState() -> [SessionChatRow] {
        if case let .loaded(_, _, messages, _, _, _, _) = viewModel.state {
            return messages
        }
        return []
    }

    @ViewBuilder
    private func messageRow(_ message: SessionChatRow) -> some View {
        switch message.role {
        case .user:
            OpenCodeOutgoingMessage(
                text: message.text,
                metadataLabel: message.metadataLabel,
                onRestoreClick: { viewModel.restoreComposerText(message.text) }
            )
        case .assistant:
            Text(message.text.isEmpty ? "…" : message.text)
                .font(OpenCodeTypography.body)
                .foregroundStyle(OpenCodeColors.textPrimary)
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(OpenCodeColors.surfacePrimary, in: RoundedRectangle(cornerRadius: 8))
                .overlay(
                    RoundedRectangle(cornerRadius: 8)
                        .stroke(OpenCodeColors.borderSubtle, lineWidth: 1)
                )
        case .command, .tool, .unknown:
            Text("\(message.role.displayLabel): \(message.text.isEmpty ? "…" : message.text)")
                .font(OpenCodeTypography.caption)
                .foregroundStyle(OpenCodeColors.textSecondary)
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(OpenCodeColors.surfaceSecondary, in: RoundedRectangle(cornerRadius: 12))
        }
    }

    private var composer: some View {
        let composer = viewModel.state.composer
        let isSending: Bool = {
            if case let .loaded(_, _, _, _, isSending, _, _) = viewModel.state {
                return isSending
            }
            return false
        }()

        return VStack(alignment: .leading, spacing: 10) {
            TextField("Ask anything...", text: composerTextBinding, axis: .vertical)
                .font(OpenCodeTypography.body)
                .foregroundStyle(OpenCodeColors.textPrimary)
                .padding(.horizontal, 4)
                .padding(.vertical, 4)

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    OpenCodeChoiceBadge(label: composer.selectedModelLabel, selected: false) {
                        activePicker = .model
                    }
                    OpenCodeChoiceBadge(label: composer.selectedMode, selected: false) {
                        activePicker = .mode
                    }
                    if composer.canSelectVariant {
                        OpenCodeChoiceBadge(label: composer.selectedVariantLabel, selected: false) {
                            activePicker = .variant
                        }
                    }
                }
            }

            HStack {
                OpenCodeButton(
                    variant: .iconCircle(.add, accessibilityLabel: "Attach context"),
                    contrast: .low
                ) {}

                Spacer(minLength: 0)

                if viewModel.sessionStatusLabel != nil {
                    OpenCodeButton(
                        variant: .iconCircle(.stop, accessibilityLabel: "Abort active work"),
                        enabled: !viewModel.isAborting,
                        contrast: .low
                    ) {
                        Task { await viewModel.abortSession() }
                    }
                }

                OpenCodeButton(
                    variant: .iconCircle(.arrowUpward, accessibilityLabel: "Send prompt"),
                    enabled: !composer.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && composer.selectedModel != nil && !isSending,
                    contrast: .low
                ) {
                    Task { await viewModel.sendPrompt() }
                }
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 12)
        .background(OpenCodeColors.surfacePrimary, in: RoundedRectangle(cornerRadius: 26))
        .overlay(
            RoundedRectangle(cornerRadius: 26)
                .stroke(OpenCodeColors.borderSubtle, lineWidth: 1)
        )
        .padding(.horizontal, 12)
        .padding(.bottom, 10)
    }

    private var composerTextBinding: Binding<String> {
        Binding(
            get: { viewModel.state.composer.text },
            set: { viewModel.updateComposerText($0) }
        )
    }

    private var pickerPresentedBinding: Binding<Bool> {
        Binding(
            get: { activePicker != nil },
            set: { presented in
                if !presented {
                    activePicker = nil
                    modelFilterText = ""
                }
            },
        )
    }

    private func filteredModelOptions(from options: [ModelSelectionOption]) -> [ModelSelectionOption] {
        options.filter { $0.matchesFilter(modelFilterText) }
    }

    private var modelPickerScrollTargetID: String? {
        guard activePicker == .model else { return nil }
        let composer = viewModel.state.composer
        guard let selected = composer.selectedModel?.option else { return nil }
        let filtered = filteredModelOptions(from: composer.modelOptions)
        guard filtered.contains(where: { $0.id == selected.id }) else { return nil }
        return selected.id
    }

    @ViewBuilder
    private var modelPickerPinnedContent: some View {
        if activePicker == .model {
            let composer = viewModel.state.composer
            if composer.modelOptions.count > SessionChatPickers.modelFilterThreshold {
                ModelPickerFilterField(text: $modelFilterText)
            }
        }
    }

    @ViewBuilder
    private func modelPickerEntries(composer: SessionChatComposerState) -> some View {
        let filtered = filteredModelOptions(from: composer.modelOptions)
        if filtered.isEmpty {
            OpenCodeBottomSheetEntry(
                title: "No models match your filter",
                enabled: false,
                action: {}
            )
        } else {
            ForEach(filtered) { model in
                OpenCodeBottomSheetEntry(
                    title: model.displayLabel,
                    endDecorator: model == composer.selectedModel?.option
                        ? .icon(.check, accessibilityLabel: "Selected")
                        : .none,
                    action: {
                        viewModel.selectModel(model)
                        activePicker = nil
                        modelFilterText = ""
                    },
                )
                .id(model.id)
            }
        }
    }

    @ViewBuilder
    private var pickerSheetEntries: some View {
        let composer = viewModel.state.composer
        switch activePicker {
        case .model:
            modelPickerEntries(composer: composer)
        case .mode:
            ForEach(SessionChatPickers.modes, id: \.self) { mode in
                OpenCodeBottomSheetEntry(
                    title: mode,
                    endDecorator: mode == composer.selectedMode
                        ? .icon(.check, accessibilityLabel: "Selected")
                        : .none,
                    action: {
                        viewModel.selectMode(mode)
                        activePicker = nil
                    },
                )
            }
        case .variant:
            ForEach(composer.variantOptions, id: \.self) { level in
                OpenCodeBottomSheetEntry(
                    title: level,
                    endDecorator: level == composer.selectedVariantLabel
                        ? .icon(.check, accessibilityLabel: "Selected")
                        : .none,
                    action: {
                        viewModel.selectVariant(level)
                        activePicker = nil
                    },
                )
            }
        case .none:
            EmptyView()
        }
    }
}

private struct ModelPickerFilterField: View {
    @Binding var text: String

    var body: some View {
        TextField("Filter models...", text: $text)
            .font(OpenCodeTypography.body)
            .foregroundStyle(OpenCodeColors.textPrimary)
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
            .background(OpenCodeColors.surfaceSecondary, in: RoundedRectangle(cornerRadius: 12))
            .padding(.horizontal, 20)
            .padding(.bottom, 8)
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
