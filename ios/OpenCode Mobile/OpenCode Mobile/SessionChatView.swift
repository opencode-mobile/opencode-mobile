import SwiftUI

struct SessionChatView: View {
    let session: SessionListItem
    @State private var state: SessionChatScreenState

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
                        state.selectNextModel()
                    }
                    OpenCodeChoiceBadge(label: state.selectedMode, selected: false) {
                        state.selectNextMode()
                    }
                    OpenCodeChoiceBadge(label: state.selectedThinkingEffort, selected: false) {
                        state.selectNextThinkingEffort()
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

    private static let availableModels = ["DeepSeek V4 Flash"]
    private static let availableModes = ["Build", "Plan"]
    private static let availableThinkingEfforts = ["Default", "Low", "Medium", "High"]

    static func initial(session: SessionListItem) -> SessionChatScreenState {
        SessionChatScreenState(
            sessionId: session.id,
            sessionTitle: session.title,
            messages: [
                SessionChatMessage(role: .assistant, text: "Session ready. Enter a prompt to continue.")
            ],
            composerText: "",
            selectedModel: availableModels[0],
            selectedMode: availableModes[0],
            selectedThinkingEffort: availableThinkingEfforts[0]
        )
    }

    mutating func selectNextModel() {
        selectedModel = nextValue(in: Self.availableModels, current: selectedModel)
    }

    mutating func selectNextMode() {
        selectedMode = nextValue(in: Self.availableModes, current: selectedMode)
    }

    mutating func selectNextThinkingEffort() {
        selectedThinkingEffort = nextValue(in: Self.availableThinkingEfforts, current: selectedThinkingEffort)
    }

    private func nextValue(in values: [String], current: String) -> String {
        guard values.isEmpty == false else { return current }
        guard let currentIndex = values.firstIndex(of: current) else { return values[0] }
        return values[(currentIndex + 1) % values.count]
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
