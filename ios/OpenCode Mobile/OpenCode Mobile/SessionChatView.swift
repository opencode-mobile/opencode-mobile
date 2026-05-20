import Factory
import SwiftUI

private enum ComposerPickerKind {
    case model
    case mode
    case thinking

    var sheetTitle: String {
        switch self {
        case .model:
            "Model"
        case .mode:
            "Agent mode"
        case .thinking:
            "Thinking level"
        }
    }
}

struct SessionChatView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var viewModel: SessionChatViewModel
    @State private var activePicker: ComposerPickerKind?

    init(session: SessionListItem, viewModel: SessionChatViewModel? = nil) {
        self.session = session
        _viewModel = State(
            initialValue: viewModel ?? Container.makeSessionChatViewModel(
                sessionID: session.id,
                sessionTitle: session.title
            )
        )
    }

    private let session: SessionListItem

    var body: some View {
        VStack(spacing: 0) {
            OpenCodeTopBar(
                title: "Session",
                leading: .back(action: { dismiss() }),
                showDivider: true
            )

            Text("Session ID: \(viewModel.state.sessionID)")
                .font(OpenCodeTypography.caption)
                .foregroundStyle(OpenCodeColors.textSecondary)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 16)
                .padding(.vertical, 8)

            timeline
            composer
        }
        .background(OpenCodeColors.backgroundPrimary)
        .openCodeBottomSheet(
            isPresented: pickerPresentedBinding,
            title: activePicker?.sheetTitle ?? "",
            content: { pickerSheetEntries },
        )
        .toolbar(.hidden, for: .navigationBar)
        .task {
            await viewModel.loadMessages()
        }
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
        case let .loaded(_, _, messages, _, _, isRefreshing, errorBanner):
            VStack(spacing: 0) {
                if let errorBanner {
                    Text(errorBanner)
                        .font(OpenCodeTypography.caption)
                        .foregroundStyle(OpenCodeColors.textSecondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 16)
                        .padding(.bottom, 4)
                }
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
                        }
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 12)
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
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
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
                .background(OpenCodeColors.surfacePrimary, in: RoundedRectangle(cornerRadius: 12))
                .overlay(
                    RoundedRectangle(cornerRadius: 12)
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
                    OpenCodeChoiceBadge(label: composer.selectedModel, selected: false) {
                        activePicker = .model
                    }
                    OpenCodeChoiceBadge(label: composer.selectedMode, selected: false) {
                        activePicker = .mode
                    }
                    OpenCodeChoiceBadge(label: composer.selectedThinkingEffort, selected: false) {
                        activePicker = .thinking
                    }
                }
            }

            HStack {
                OpenCodeButton(
                    variant: .iconCircle(.add, accessibilityLabel: "Attach context"),
                    contrast: .low
                ) {}

                Spacer(minLength: 0)

                OpenCodeButton(
                    variant: .iconCircle(.arrowUpward, accessibilityLabel: "Send prompt"),
                    enabled: !composer.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !isSending,
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
                if !presented { activePicker = nil }
            },
        )
    }

    @ViewBuilder
    private var pickerSheetEntries: some View {
        let composer = viewModel.state.composer
        switch activePicker {
        case .model:
            ForEach(SessionChatPickers.models, id: \.self) { model in
                OpenCodeBottomSheetEntry(
                    title: model,
                    endDecorator: model == composer.selectedModel
                        ? .icon(.check, accessibilityLabel: "Selected")
                        : .none,
                    action: {
                        viewModel.selectModel(model)
                        activePicker = nil
                    },
                )
            }
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
        case .thinking:
            ForEach(SessionChatPickers.thinkingEfforts, id: \.self) { level in
                OpenCodeBottomSheetEntry(
                    title: level,
                    endDecorator: level == composer.selectedThinkingEffort
                        ? .icon(.check, accessibilityLabel: "Selected")
                        : .none,
                    action: {
                        viewModel.selectThinkingEffort(level)
                        activePicker = nil
                    },
                )
            }
        case .none:
            EmptyView()
        }
    }
}

#Preview {
    NavigationStack {
        SessionChatView(session: .init(id: "1", title: "Importing icons explanation"))
    }
    .openCodeTheme()
}
