import SwiftUI

/// Offline Session Chat chrome for on-demand snapshot evidence. No ViewModel, no network.
struct SessionChatSnapshotHost: View {
    let sessionTitle: String
    let messages: [SnapshotEvidenceMessage]
    let composerState: OpenCodeSessionComposerState

    var body: some View {
        VStack(spacing: 0) {
            OpenCodeTopBar(
                title: sessionTitle,
                leading: .back(action: {})
            )
            ScrollView {
                VStack(spacing: 16) {
                    ForEach(messages) { message in
                        snapshotRow(message)
                    }
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            OpenCodeSessionComposer(state: composerState, onAction: { _ in })
        }
        .background(OpenCodeColors.backgroundBase)
    }

    @ViewBuilder
    private func snapshotRow(_ message: SnapshotEvidenceMessage) -> some View {
        switch message.role {
        case .user:
            OpenCodeOutgoingMessage(
                text: message.text,
                metadataLabel: message.metadataLabel ?? ""
            )
            .frame(maxWidth: .infinity, alignment: .trailing)
        case .assistant:
            SnapshotEvidenceAssistantBubble(text: message.text)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

struct SnapshotEvidenceAssistantBubble: View {
    let text: String

    var body: some View {
        Text(text.isEmpty ? "…" : text)
            .font(OpenCodeTypography.body)
            .lineSpacing(4)
            .foregroundStyle(OpenCodeColors.textStrong)
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(OpenCodeColors.surfaceBase, in: RoundedRectangle(cornerRadius: 8))
            .overlay(
                RoundedRectangle(cornerRadius: 8)
                    .stroke(OpenCodeColors.borderWeakerBase, lineWidth: 1)
            )
    }
}
