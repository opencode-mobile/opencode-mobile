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

private struct RenderedMarkdownHeightCacheKey: Hashable {
    let messageID: String
    let variant: String
    let theme: String
    let width: Int
}

struct SessionChatView: View {
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.dismiss) private var dismiss
    @State private var viewModel: SessionChatViewModel
    @State private var activePicker: ComposerPickerKind?
    @State private var activeModelProviderID: String?
    @State private var modelFilterText = ""
    @State private var hasInitializedTimelineScroll = false
    @State private var followingLatest = true
    @State private var renderedMarkdownLayoutVersion = 0
    @State private var renderedMarkdownHeightCache: [RenderedMarkdownHeightCacheKey: CGFloat] = [:]
    @State private var renderedMarkdownScrollCorrectionTask: Task<Void, Never>?
    @State private var lastTimelineMessageScrollSignature: String?
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
                sessionID: viewModel.state.sessionID,
                connectionStatus: viewModel.connectionStatus.openCodeStatus,
                contextUsageFraction: viewModel.contextUsageFraction,
                onCopySessionID: {
                    UIPasteboard.general.string = viewModel.state.sessionID
                },
                onConnectionClick: {},
                onContextClick: { showSessionContext = true }
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
            title: modelPickerSheetTitle,
            leadingAction: modelPickerHeaderLeadingAction,
            scrollTargetID: modelPickerScrollTargetID,
            pinnedContent: { modelPickerPinnedContent },
            content: { pickerSheetEntries }
        )
        .toolbar(.hidden, for: .navigationBar)
        .navigationDestination(isPresented: $showSessionContext) {
            SessionContextView(sessionID: viewModel.state.sessionID, worktree: session.worktree)
        }
        .task {
            OpenCodeRenderedMarkdown.prewarm()
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
            renderedMarkdownScrollCorrectionTask?.cancel()
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
                GeometryReader { geometry in
                    ScrollViewReader { proxy in
                        ScrollView {
                            LazyVStack(alignment: .leading, spacing: 0) {
                                ForEach(Array(messages.enumerated()), id: \.element.id) { index, message in
                                    HStack {
                                        if message.role == .assistant || message.role == .command || message.role == .tool || message.role == .unknown {
                                            messageRow(message, availableWidth: geometry.size.width)
                                            Spacer(minLength: 0)
                                        } else {
                                            Spacer(minLength: 0)
                                            messageRow(message, availableWidth: geometry.size.width)
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
                            resetRenderedMarkdownLayoutState()
                            hasInitializedTimelineScroll = false
                            followingLatest = true
                        }
                        .task(id: timelineScrollTaskID(sessionID: sessionID, messages: messages)) {
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

    private func timelineScrollTaskID(sessionID: String, messages: [SessionChatRow]) -> String {
        "\(timelineMessageScrollSignature(sessionID: sessionID, messages: messages))-\(renderedMarkdownLayoutVersion)"
    }

    private func timelineMessageScrollSignature(sessionID: String, messages: [SessionChatRow]) -> String {
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
        if case let .loaded(_, _, messages, _, _, _, _) = viewModel.state {
            return messages
        }
        return []
    }

    private func resetRenderedMarkdownLayoutState() {
        renderedMarkdownScrollCorrectionTask?.cancel()
        renderedMarkdownScrollCorrectionTask = nil
        renderedMarkdownHeightCache.removeAll()
        renderedMarkdownLayoutVersion = 0
        lastTimelineMessageScrollSignature = nil
    }

    private func renderedMarkdownCacheKey(
        messageID: String,
        variant: OpenCodeRenderedMarkdownVariant,
        availableWidth: CGFloat
    ) -> RenderedMarkdownHeightCacheKey {
        RenderedMarkdownHeightCacheKey(
            messageID: messageID,
            variant: variant.rawValue,
            theme: colorScheme == .dark ? "dark" : "light",
            width: max(1, Int(availableWidth.rounded()))
        )
    }

    private func initialRenderedMarkdownHeight(
        messageID: String,
        variant: OpenCodeRenderedMarkdownVariant,
        text: String,
        availableWidth: CGFloat
    ) -> CGFloat {
        let key = renderedMarkdownCacheKey(messageID: messageID, variant: variant, availableWidth: availableWidth)
        if let cached = renderedMarkdownHeightCache[key] {
            return cached
        }
        return estimatedRenderedMarkdownHeight(for: text, variant: variant)
    }

    private func estimatedRenderedMarkdownHeight(
        for text: String,
        variant: OpenCodeRenderedMarkdownVariant
    ) -> CGFloat {
        let displayText = text.isEmpty ? "…" : text
        let lineCount = displayText.components(separatedBy: .newlines).count
        let fenceBonus: CGFloat = displayText.contains("```") || displayText.contains("~~~") ? 28 : 0
        let estimate = CGFloat(lineCount) * 22 + 24 + fenceBonus
        let maximum: CGFloat = variant == .outgoingMessage ? 180 : 320
        return min(max(44, estimate), maximum)
    }

    private func recordRenderedMarkdownHeight(
        messageID: String,
        variant: OpenCodeRenderedMarkdownVariant,
        availableWidth: CGFloat,
        height: CGFloat
    ) {
        let roundedHeight = max(1, height.rounded(.up))
        let key = renderedMarkdownCacheKey(messageID: messageID, variant: variant, availableWidth: availableWidth)
        if let previous = renderedMarkdownHeightCache[key],
           abs(previous - roundedHeight) <= 1 {
            return
        }
        renderedMarkdownHeightCache[key] = roundedHeight
        scheduleRenderedMarkdownScrollCorrection()
    }

    private func scheduleRenderedMarkdownScrollCorrection() {
        renderedMarkdownScrollCorrectionTask?.cancel()
        renderedMarkdownScrollCorrectionTask = Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(60))
            renderedMarkdownLayoutVersion += 1
        }
    }

    @ViewBuilder
    private func messageRow(_ message: SessionChatRow, availableWidth: CGFloat) -> some View {
        switch message.role {
        case .user:
            OpenCodeOutgoingMessage(
                text: message.text,
                metadataLabel: message.metadataLabel,
                initialRenderedMarkdownHeight: initialRenderedMarkdownHeight(
                    messageID: message.id,
                    variant: .outgoingMessage,
                    text: message.text,
                    availableWidth: availableWidth
                ),
                onRenderedMarkdownHeightChange: { height in
                    recordRenderedMarkdownHeight(
                        messageID: message.id,
                        variant: .outgoingMessage,
                        availableWidth: availableWidth,
                        height: height
                    )
                },
                onRestoreClick: { viewModel.restoreComposerText(message.text) }
            )
        case .assistant:
            AssistantMessageBubble(
                text: message.text,
                isStreaming: message.isStreaming,
                initialRenderedMarkdownHeight: initialRenderedMarkdownHeight(
                    messageID: message.id,
                    variant: .assistantMessage,
                    text: message.text,
                    availableWidth: availableWidth
                ),
                onRenderedMarkdownHeightChange: { height in
                    recordRenderedMarkdownHeight(
                        messageID: message.id,
                        variant: .assistantMessage,
                        availableWidth: availableWidth,
                        height: height
                    )
                }
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
                    activeModelProviderID = nil
                    modelFilterText = ""
                }
            },
        )
    }

    private var modelSelectionCatalog: ModelSelectionCatalog {
        let composer = viewModel.state.composer
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
        let composer = viewModel.state.composer
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

private struct AssistantMessageBubble: View {
    let text: String
    let isStreaming: Bool
    let initialRenderedMarkdownHeight: CGFloat
    let onRenderedMarkdownHeightChange: (CGFloat) -> Void

    var body: some View {
        Group {
            if OpenCodeRenderedMarkdownDetector.shouldRender(text) {
                OpenCodeRenderedMarkdown(
                    markdown: text.isEmpty ? "…" : text,
                    variant: .assistantMessage,
                    initialHeight: initialRenderedMarkdownHeight,
                    isStreaming: isStreaming,
                    onHeightChange: onRenderedMarkdownHeightChange
                )
            } else {
                Text(text.isEmpty ? "…" : text)
                    .font(OpenCodeTypography.body)
                    .foregroundStyle(OpenCodeColors.textPrimary)
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(OpenCodeColors.surfacePrimary, in: RoundedRectangle(cornerRadius: 8))
        .overlay(
            RoundedRectangle(cornerRadius: 8)
                .stroke(OpenCodeColors.borderSubtle, lineWidth: 1)
        )
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
