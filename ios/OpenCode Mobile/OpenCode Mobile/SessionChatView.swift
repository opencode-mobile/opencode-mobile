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
        VStack(spacing: 8) {
            HStack(spacing: 8) {
                TextField("Ask anything...", text: $state.composerText)
                    .font(OpenCodeTypography.body)
                    .foregroundStyle(OpenCodeColors.textPrimary)

                Button {
                } label: {
                    Image(systemName: "plus")
                        .font(.system(size: 16, weight: .medium))
                        .frame(width: 40, height: 40)
                        .background(OpenCodeColors.surfaceSecondary, in: RoundedRectangle(cornerRadius: 12))
                }
                .buttonStyle(.plain)

                Button {
                    sendPrompt()
                } label: {
                    Image(systemName: "arrow.up")
                        .font(.system(size: 16, weight: .medium))
                        .frame(width: 40, height: 40)
                        .background(OpenCodeColors.surfaceSecondary, in: RoundedRectangle(cornerRadius: 12))
                }
                .buttonStyle(.plain)
                .disabled(state.composerText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }

            HStack {
                Button("Build") {}
                Spacer(minLength: 0)
                Button("DeepSeek V4 Flash") {}
                Spacer(minLength: 0)
                Button("Default") {}
            }
            .font(OpenCodeTypography.button)
            .tint(OpenCodeColors.textSecondary)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .background(OpenCodeColors.surfacePrimary, in: RoundedRectangle(cornerRadius: 20))
        .overlay(
            RoundedRectangle(cornerRadius: 20)
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

    static func initial(session: SessionListItem) -> SessionChatScreenState {
        SessionChatScreenState(
            sessionId: session.id,
            sessionTitle: session.title,
            messages: [
                SessionChatMessage(role: .assistant, text: "Session ready. Enter a prompt to continue.")
            ],
            composerText: ""
        )
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
