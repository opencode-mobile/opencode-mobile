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
    @State private var activeModelProviderID: String?
    @State private var modelFilterText = ""
    @State private var hasInitializedTimelineScroll = false
    @State private var followingLatest = true
    @State private var lastTimelineMessageScrollSignature: String?
    @State private var latestRenderedMarkdownHeightRevision = 0
    @State private var markdownHeightCache: [String: CGFloat] = [:]
    @State private var showSessionContext = false

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
                sessionID: viewModel.uiState.sessionID,
                connectionStatus: viewModel.uiState.connectionStatus.openCodeStatus,
                contextUsageFraction: viewModel.uiState.contextUsageFraction,
                onCopySessionID: {
                    UIPasteboard.general.string = viewModel.uiState.sessionID
                },
                onConnectionClick: {},
                onContextClick: { showSessionContext = true }
            )

            if let statusLabel = viewModel.uiState.sessionStatusLabel {
                Text(statusLabel)
                    .font(OpenCodeTypography.caption)
                    .foregroundStyle(OpenCodeColors.textInteractiveBase)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 16)
                    .padding(.bottom, 8)
            }

            if let notice = viewModel.uiState.composer.modelNotice {
                Text(notice)
                    .font(OpenCodeTypography.caption)
                    .foregroundStyle(OpenCodeColors.textBase)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 16)
                    .padding(.bottom, 8)
            }

            timeline
            composer
        }
        .background(OpenCodeColors.backgroundBase)
        .openCodeBottomSheet(
            isPresented: pickerPresentedBinding,
            title: modelPickerSheetTitle,
            leadingAction: modelPickerHeaderLeadingAction,
            scrollTargetID: modelPickerScrollTargetID,
            pinnedContent: { modelPickerPinnedContent },
            content: { pickerSheetEntries }
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

    @ViewBuilder
    private var timeline: some View {
        if let errorMessage = viewModel.uiState.errorMessage, viewModel.uiState.timeline == .ready {
            Text(errorMessage)
                .font(OpenCodeTypography.caption)
                .foregroundStyle(OpenCodeColors.textBase)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 16)
                .padding(.bottom, 4)
        }

        switch viewModel.uiState.timeline {
        case .loading:
            Spacer()
            ProgressView()
                .accessibilityIdentifier("sessionChatLoading")
            Spacer()
        case .empty:
            Spacer()
            Text("No messages yet. Send a prompt to start.")
                .font(OpenCodeTypography.body)
                .foregroundStyle(OpenCodeColors.textBase)
                .padding(.horizontal, 16)
            Spacer()
        case .error:
            Spacer()
            Text(viewModel.uiState.errorMessage ?? "Unable to load messages")
                .font(OpenCodeTypography.body)
                .foregroundStyle(OpenCodeColors.textBase)
                .padding(.horizontal, 16)
            Spacer()
        case .ready:
            let sessionID = viewModel.uiState.sessionID
            let messages = viewModel.uiState.messages
            let isRefreshing = viewModel.uiState.isRefreshing
            VStack(spacing: 0) {
                GeometryReader { _ in
                    ScrollViewReader { proxy in
                        ScrollView {
                            LazyVStack(alignment: .leading, spacing: 0) {
                                ForEach(Array(messages.enumerated()), id: \.element.id) { index, message in
                                    HStack {
                                        if message.role == .assistant || message.role == .command || message.role == .tool || message.role == .unknown {
                                            messageRow(message, isLatest: index == messages.count - 1)
                                            Spacer(minLength: 0)
                                        } else {
                                            Spacer(minLength: 0)
                                            messageRow(message, isLatest: index == messages.count - 1)
                                        }
                                    }
                                    .id(message.id)
                                    .padding(.bottom, index == messages.count - 1 ? 0 : 8)
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
                            lastTimelineMessageScrollSignature = nil
                            hasInitializedTimelineScroll = false
                            followingLatest = true
                        }
                        .task(id: timelineMessageScrollSignature(sessionID: sessionID, messages: messages)) {
                            guard followingLatest || !hasInitializedTimelineScroll else { return }
                            let messageSignature = timelineMessageScrollSignature(sessionID: sessionID, messages: messages)
                            let heightOnlyCorrection = hasInitializedTimelineScroll && lastTimelineMessageScrollSignature == messageSignature
                            await scrollTimelineToBottom(
                                using: proxy,
                                animated: hasInitializedTimelineScroll && !heightOnlyCorrection
                            )
                            hasInitializedTimelineScroll = true
                            lastTimelineMessageScrollSignature = messageSignature
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
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }

    private func timelineMessageScrollSignature(sessionID: String, messages: [SessionChatRow]) -> String {
        let latest = messages.last
        return "\(sessionID)-\(messages.count)-\(latest?.id ?? "empty")-\(latest?.text.hashValue ?? 0)-\(latestRenderedMarkdownHeightRevision)"
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
        try? await Task.sleep(for: .milliseconds(120))
        if animated {
            withAnimation {
                proxy.scrollTo(TimelineScrollAnchor.bottom, anchor: .bottom)
            }
        } else {
            proxy.scrollTo(TimelineScrollAnchor.bottom, anchor: .bottom)
        }
    }

    private func messagesFromLoadedState() -> [SessionChatRow] {
        viewModel.uiState.timeline == .ready ? viewModel.uiState.messages : []
    }

    @ViewBuilder
    private func messageRow(_ message: SessionChatRow, isLatest: Bool) -> some View {
        let heightCacheKey = { (variant: OpenCodeRenderedMarkdownVariant) in
            "\(message.id)-\(variant.rawValue)"
        }
        let onHeightChange = { (variant: OpenCodeRenderedMarkdownVariant, height: CGFloat) in
            let key = heightCacheKey(variant)
            let previous = markdownHeightCache[key]
            markdownHeightCache[key] = height
            if isLatest && followingLatest {
                if previous == nil || abs((previous ?? 0) - height) > 1 {
                    latestRenderedMarkdownHeightRevision += 1
                }
            }
        }

        switch message.role {
        case .user:
            OpenCodeOutgoingMessage(
                text: message.text,
                renderedMarkdown: message.renderedMarkdown,
                metadataLabel: message.metadataLabel,
                initialRenderedMarkdownHeight: markdownHeightCache[heightCacheKey(.outgoingMessage)] ?? 1,
                onRenderedMarkdownHeightChange: { onHeightChange(.outgoingMessage, $0) },
                onRestoreClick: { viewModel.restoreComposerText(message.text) }
            )
        case .assistant:
            AssistantMessageBubble(
                text: message.text,
                renderedMarkdown: message.renderedMarkdown,
                initialRenderedMarkdownHeight: markdownHeightCache[heightCacheKey(.assistantMessage)] ?? 1,
                onRenderedMarkdownHeightChange: { onHeightChange(.assistantMessage, $0) }
            )
        case .command, .tool, .unknown:
            Text("\(message.role.displayLabel): \(message.text.isEmpty ? "…" : message.text)")
                .font(OpenCodeTypography.caption)
                .foregroundStyle(OpenCodeColors.textBase)
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(OpenCodeColors.surfaceRaisedBase, in: RoundedRectangle(cornerRadius: 12))
        }
    }

    private var composer: some View {
        let composer = viewModel.uiState.composer
        let isSending = viewModel.uiState.isSending

        return VStack(alignment: .leading, spacing: 10) {
            TextField("Ask anything...", text: composerTextBinding, axis: .vertical)
                .font(OpenCodeTypography.body)
                .foregroundStyle(OpenCodeColors.textStrong)
                .padding(.horizontal, 4)
                .padding(.vertical, 4)

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    OpenCodeChoiceBadge(label: composer.selectedModelLabel, selected: false) {
                        activeModelProviderID = composer.selectedModel?.option.providerID
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

                if viewModel.uiState.sessionStatusLabel != nil {
                    OpenCodeButton(
                        variant: .iconCircle(.stop, accessibilityLabel: "Abort active work"),
                        enabled: !viewModel.uiState.isAborting,
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
        .background(OpenCodeColors.surfaceBase, in: RoundedRectangle(cornerRadius: 26))
        .overlay(
            RoundedRectangle(cornerRadius: 26)
                .stroke(OpenCodeColors.borderWeakerBase, lineWidth: 1)
        )
        .padding(.horizontal, 12)
        .padding(.bottom, 10)
    }

    private var composerTextBinding: Binding<String> {
        Binding(
            get: { viewModel.uiState.composer.text },
            set: { viewModel.updateComposerText($0) }
        )
    }

    private var pickerPresentedBinding: Binding<Bool> {
        Binding(
            get: { activePicker != nil },
            set: { presented in
                if !presented {
                    activePicker = nil
                    activeModelProviderID = nil
                    modelFilterText = ""
                }
            },
        )
    }

    private var modelSelectionCatalog: ModelSelectionCatalog {
        let composer = viewModel.uiState.composer
        return ModelSelectionCatalog(options: composer.modelOptions, serverDefault: nil)
    }

    private var activeModelProvider: ModelSelectionProvider? {
        guard let activeModelProviderID else { return nil }
        return modelSelectionCatalog.providers.first { $0.id == activeModelProviderID }
    }

    private var modelPickerSheetTitle: String {
        guard activePicker == .model else { return activePicker?.sheetTitle ?? "" }
        return activeModelProvider?.label ?? ComposerPickerKind.model.sheetTitle
    }

    private var modelPickerHeaderLeadingAction: OpenCodeBottomSheetHeaderAction? {
        guard activePicker == .model, activeModelProvider != nil else { return nil }
        return OpenCodeBottomSheetHeaderAction(
            icon: .arrowBack,
            accessibilityLabel: "Back to providers",
            action: {
                activeModelProviderID = nil
                modelFilterText = ""
            }
        )
    }

    private func filteredModels(in provider: ModelSelectionProvider) -> [ModelSelectionOption] {
        provider.models.filter { $0.matchesFilter(modelFilterText) }
    }

    private var modelPickerScrollTargetID: String? {
        guard activePicker == .model else { return nil }
        guard let provider = activeModelProvider else { return nil }
        let composer = viewModel.uiState.composer
        guard let selected = composer.selectedModel?.option else { return nil }
        let filtered = filteredModels(in: provider)
        guard filtered.contains(where: { $0.id == selected.id }) else { return nil }
        return selected.id
    }

    @ViewBuilder
    private var modelPickerPinnedContent: some View {
        if activePicker == .model {
            if let provider = activeModelProvider,
               provider.models.count > SessionChatPickers.modelFilterThreshold {
                ModelPickerFilterField(text: $modelFilterText)
            }
        }
    }

    @ViewBuilder
    private func modelPickerEntries(composer: SessionChatComposerState) -> some View {
        if let provider = activeModelProvider {
            let filtered = filteredModels(in: provider)
            if filtered.isEmpty {
                OpenCodeBottomSheetEntry(
                    title: "No models match your filter",
                    enabled: false,
                    action: {}
                )
            } else {
                ForEach(filtered) { model in
                    OpenCodeBottomSheetEntry(
                        title: model.modelLabel,
                        endDecorator: model == composer.selectedModel?.option
                            ? .icon(.check, accessibilityLabel: "Selected")
                            : .none,
                        action: {
                            viewModel.selectModel(model)
                            activePicker = nil
                            activeModelProviderID = nil
                            modelFilterText = ""
                        },
                    )
                    .id(model.id)
                }
            }
        } else {
            ForEach(modelSelectionCatalog.providers) { provider in
                OpenCodeBottomSheetEntry(
                    title: provider.label,
                    endDecorator: provider.id == composer.selectedModel?.option.providerID
                        ? .icon(.check, accessibilityLabel: "Selected")
                        : .none,
                    action: {
                        activeModelProviderID = provider.id
                        modelFilterText = ""
                    },
                )
            }
        }
    }

    @ViewBuilder
    private var pickerSheetEntries: some View {
        let composer = viewModel.uiState.composer
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

private struct AssistantMessageBubble: View {
    let text: String
    let renderedMarkdown: NativeMarkdownDocument?
    let initialRenderedMarkdownHeight: CGFloat
    let onRenderedMarkdownHeightChange: (CGFloat) -> Void

    init(
        text: String,
        renderedMarkdown: NativeMarkdownDocument?,
        initialRenderedMarkdownHeight: CGFloat = 1,
        onRenderedMarkdownHeightChange: @escaping (CGFloat) -> Void = { _ in }
    ) {
        self.text = text
        self.renderedMarkdown = renderedMarkdown
        self.initialRenderedMarkdownHeight = max(1, initialRenderedMarkdownHeight)
        self.onRenderedMarkdownHeightChange = onRenderedMarkdownHeightChange
    }

    var body: some View {
        Group {
            if let renderedMarkdown {
                OpenCodeRenderedMarkdown(
                    document: renderedMarkdown,
                    variant: .assistantMessage,
                    initialHeight: initialRenderedMarkdownHeight,
                    measuresHeight: true,
                    onHeightChange: onRenderedMarkdownHeightChange
                )
            } else {
                Text(text.isEmpty ? "…" : text)
                    .font(OpenCodeTypography.body)
                    .foregroundStyle(OpenCodeColors.textStrong)
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(OpenCodeColors.surfaceBase, in: RoundedRectangle(cornerRadius: 8))
        .overlay(
            RoundedRectangle(cornerRadius: 8)
                .stroke(OpenCodeColors.borderWeakerBase, lineWidth: 1)
        )
    }

}

private struct ModelPickerFilterField: View {
    @Binding var text: String

    var body: some View {
        TextField("Filter models...", text: $text)
            .font(OpenCodeTypography.body)
            .foregroundStyle(OpenCodeColors.textStrong)
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
            .background(OpenCodeColors.surfaceRaisedBase, in: RoundedRectangle(cornerRadius: 12))
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
