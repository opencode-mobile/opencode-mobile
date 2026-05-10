import SwiftUI

private enum SessionChatPickers {
    static let models = ["DeepSeek V4 Flash", "GPT-5 Preview", "Claude Sonnet"]
    static let modes = ["Build", "Plan"]
    static let thinkingEfforts = ["Default", "Low", "Medium", "High"]
}

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
    let session: SessionListItem
    @State private var state: SessionChatScreenState
    @State private var activePicker: ComposerPickerKind?

    init(session: SessionListItem) {
        self.session = session
        _state = State(initialValue: SessionChatScreenState.initial(session: session))
    }

    var body: some View {
        VStack(spacing: 0) {
            header

            ScrollView {
                LazyVStack(alignment: .leading, spacing: 8) {
                    ForEach(state.messages) { message in
                        HStack {
                            if message.role == .assistant {
                                messageText(message)
                                Spacer(minLength: 0)
                            } else {
                                Spacer(minLength: 0)
                                messageText(message)
                            }
                        }
                    }
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)

            composer
        }
        .background(OpenCodeColors.backgroundPrimary)
        .openCodeBottomSheet(
            isPresented: pickerPresentedBinding,
            title: activePicker?.sheetTitle ?? "",
            content: { pickerSheetEntries },
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
        switch activePicker {
        case .model:
            ForEach(SessionChatPickers.models, id: \.self) { model in
                OpenCodeBottomSheetEntry(
                    title: model,
                    endDecorator: model == state.selectedModel
                        ? .icon(systemName: "checkmark", accessibilityLabel: "Selected")
                        : .none,
                    action: {
                        state.selectModel(model)
                        activePicker = nil
                    },
                )
            }
        case .mode:
            ForEach(SessionChatPickers.modes, id: \.self) { mode in
                OpenCodeBottomSheetEntry(
                    title: mode,
                    endDecorator: mode == state.selectedMode
                        ? .icon(systemName: "checkmark", accessibilityLabel: "Selected")
                        : .none,
                    action: {
                        state.selectMode(mode)
                        activePicker = nil
                    },
                )
            }
        case .thinking:
            ForEach(SessionChatPickers.thinkingEfforts, id: \.self) { level in
                OpenCodeBottomSheetEntry(
                    title: level,
                    endDecorator: level == state.selectedThinkingEffort
                        ? .icon(systemName: "checkmark", accessibilityLabel: "Selected")
                        : .none,
                    action: {
                        state.selectThinkingEffort(level)
                        activePicker = nil
                    },
                )
            }
        case .none:
            EmptyView()
        }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(state.sessionTitle)
                .font(OpenCodeTypography.title)
                .foregroundStyle(OpenCodeColors.textPrimary)
            Text("Session ID: \(state.sessionId)")
                .font(OpenCodeTypography.caption)
                .foregroundStyle(OpenCodeColors.textSecondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
    }

    private var composer: some View {
        VStack(alignment: .leading, spacing: 10) {
            TextField("Ask anything...", text: $state.composerText, axis: .vertical)
                .font(OpenCodeTypography.body)
                .foregroundStyle(OpenCodeColors.textPrimary)
                .padding(.horizontal, 4)
                .padding(.vertical, 4)

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    OpenCodeChoiceBadge(label: state.selectedModel, selected: false) {
                        activePicker = .model
                    }
                    OpenCodeChoiceBadge(label: state.selectedMode, selected: false) {
                        activePicker = .mode
                    }
                    OpenCodeChoiceBadge(label: state.selectedThinkingEffort, selected: false) {
                        activePicker = .thinking
                    }
                }
            }

            HStack {
                OpenCodeButton(
                    variant: .iconCircle(systemName: "plus", accessibilityLabel: "Attach context"),
                    contrast: .low
                ) {
                }

                Spacer(minLength: 0)

                OpenCodeButton(
                    variant: .iconCircle(systemName: "arrow.up", accessibilityLabel: "Send prompt"),
                    enabled: state.composerText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false,
                    contrast: .low
                ) {
                    sendPrompt()
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

    private func messageText(_ message: SessionChatMessage) -> some View {
        Text("\(message.role.label): \(message.text)")
            .font(OpenCodeTypography.body)
            .foregroundStyle(OpenCodeColors.textPrimary)
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(OpenCodeColors.surfacePrimary, in: RoundedRectangle(cornerRadius: 12))
            .overlay(
                RoundedRectangle(cornerRadius: 12)
                    .stroke(OpenCodeColors.borderSubtle, lineWidth: 1)
            )
    }

    private func sendPrompt() {
        let trimmed = state.composerText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        state.messages.append(SessionChatMessage(role: .user, text: trimmed))
        state.composerText = ""
    }
}

private struct SessionChatScreenState {
    let sessionId: String
    let sessionTitle: String
    var messages: [SessionChatMessage]
    var composerText: String
    var selectedModel: String
    var selectedMode: String
    var selectedThinkingEffort: String

    static func initial(session: SessionListItem) -> SessionChatScreenState {
        SessionChatScreenState(
            sessionId: session.id,
            sessionTitle: session.title,
            messages: [
                SessionChatMessage(role: .assistant, text: "Session ready. Enter a prompt to continue.")
            ],
            composerText: "",
            selectedModel: SessionChatPickers.models[0],
            selectedMode: SessionChatPickers.modes[0],
            selectedThinkingEffort: SessionChatPickers.thinkingEfforts[0],
        )
    }

    mutating func selectModel(_ model: String) {
        guard SessionChatPickers.models.contains(model) else { return }
        selectedModel = model
    }

    mutating func selectMode(_ mode: String) {
        guard SessionChatPickers.modes.contains(mode) else { return }
        selectedMode = mode
    }

    mutating func selectThinkingEffort(_ effort: String) {
        guard SessionChatPickers.thinkingEfforts.contains(effort) else { return }
        selectedThinkingEffort = effort
    }
}

private struct SessionChatMessage: Identifiable {
    let id = UUID()
    let role: SessionChatRole
    let text: String
}

private enum SessionChatRole {
    case user
    case assistant

    var label: String {
        switch self {
        case .user:
            "You"
        case .assistant:
            "Assistant"
        }
    }
}

#Preview {
    NavigationStack {
        SessionChatView(session: .init(id: "1", title: "Importing icons explanation"))
    }
    .openCodeTheme()
}
