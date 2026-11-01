import SwiftUI

/// Offline Session Chat chrome for on-demand snapshot evidence. No ViewModel, no network.
struct SessionChatSnapshotHost: View {
    let sessionTitle: String
    let messages: [SnapshotEvidenceMessage]
    let composerState: OpenCodeSessionComposerState

    var body: some View {
        VStack(spacing: 0) {
            OpenCodeTopBar(
                title: .verbatim(sessionTitle),
                leading: .back(action: {})
            )
            // ImageRenderer collapses ScrollView + maxHeight infinity to zero
            // height, which also lets UIViewRepresentable composer chrome expand.
            VStack(spacing: 16) {
                ForEach(messages) { message in
                    snapshotRow(message)
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
            OpenCodeSessionComposer(state: composerState, onAction: { _ in })
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(OpenCodeColors.backgroundBase)
    }

    @ViewBuilder
    private func snapshotRow(_ message: SnapshotEvidenceMessage) -> some View {
        switch message.role {
        case .user:
            OpenCodeOutgoingMessage(
                content: (message.imageCount > 0
                    ? [.images(id: message.id, items: images(message), onOpen: nil)]
                    : []) + [.text(id: message.id, text: message.text)],
                metadataLabel: message.metadataLabel ?? ""
            )
            .frame(maxWidth: .infinity, alignment: .trailing)
        case .assistant:
            VStack(alignment: .leading, spacing: 8) {
                OpenCodeAssistantMessage(text: message.text)
                if message.imageCount > 0 {
                    OpenCodeImageGallery(items: images(message))
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        case .tool:
            VStack(alignment: .leading, spacing: 8) {
                OpenCodeToolCallCard(status: .completed, title: message.text, subtitle: nil, action: {})
                OpenCodeImageGallery(items: images(message))
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        case .childSession:
            if let childSession = message.childSession {
                SnapshotEvidenceChildSessionRow(mock: childSession)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
    }
}

private func images(_ message: SnapshotEvidenceMessage) -> [OpenCodeImageGalleryItem] {
    SnapshotEvidenceImageMocks.items(message.imageCount, prefix: message.id)
}

struct SnapshotEvidenceChildSessionRow: View {
    let mock: SnapshotEvidenceChildSession

    var body: some View {
        SessionChatChildSessionRow(
            agentName: mock.agentName,
            isBackground: mock.isBackground,
            secondLine: mock.secondLine,
            timeStartEpochMs: mock.timeStartEpochMs,
            action: mock.showsChevron ? {} : nil
        )
    }
}
