import SwiftUI

struct MessageListGalleryView: View {
    @Bindable var model: MessageListGalleryModel

    var body: some View {
        MessageListGalleryPreview(
            messages: model.messages,
            listRevision: model.listRevision,
            autoFollowEnabled: model.autoFollowEnabled,
            onAddMessage: { model.addMessage() },
            onRemoveLastMessage: { model.removeLastMessage() },
            onResizeLastMessage: { model.resizeLastMessage() }
        )
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

private struct MessageListGalleryPreview: View {
    let messages: [MessageListGalleryMessage]
    let listRevision: UInt64
    let autoFollowEnabled: Bool
    let onAddMessage: () -> Void
    let onRemoveLastMessage: () -> Void
    let onResizeLastMessage: () -> Void

    @State private var scrollController = TimelineScrollController()

    private var showScrollToEnd: Bool {
        scrollController.shouldShowScrollToEnd
    }

    private var applyPacket: MessageListTimelineApplyPacket {
        let ids = Set(messages.map(\.id))
        return MessageListTimelineApplyPacket(
            frameRevision: listRevision,
            insertedRowIDs: [],
            changedRowIDs: ids,
            removedRowIDs: [],
            layoutChangedRowIDs: ids,
            grewBottomRowIDs: [],
            decorateRowIDs: [],
            isRequired: false,
            kinds: ids.isEmpty ? [] : [.structure, .content]
        )
    }

    var body: some View {
        ZStack(alignment: .bottom) {
            MessageListTimelineHost(
                items: messages,
                applyPacket: applyPacket,
                scrollController: scrollController
            ) { message in
                HStack {
                    if message.role == .assistant {
                        MessageListGalleryMessageRow(message: message)
                        Spacer(minLength: 0)
                    } else {
                        Spacer(minLength: 0)
                        MessageListGalleryMessageRow(message: message)
                    }
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .transaction { $0.animation = nil }

            MessageListScrollToEndOverlay(
                visible: showScrollToEnd
            ) {
                scrollController.scrollToEndAnimated()
            }

            MessageListAutoFollowIndicator(
                thresholdMet: scrollController.isPinnedToEnd,
                autoFollowEnabled: autoFollowEnabled
            )
            .padding(.top, 64)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
            .allowsHitTesting(false)

            MessageListGalleryFloatingControls(
                canRemove: !messages.isEmpty,
                onAddMessage: onAddMessage,
                onRemoveLastMessage: onRemoveLastMessage,
                onResizeLastMessage: onResizeLastMessage
            )
            .padding(.top, 12)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        }
        .onChange(of: autoFollowEnabled, initial: true) { _, enabled in
            scrollController.autoFollowEnabled = enabled
        }
        .background(OpenCodeColors.backgroundBase)
    }
}

private struct MessageListGalleryFloatingControls: View {
    let canRemove: Bool
    let onAddMessage: () -> Void
    let onRemoveLastMessage: () -> Void
    let onResizeLastMessage: () -> Void

    var body: some View {
        HStack(spacing: 6) {
            OpenCodeButton(
                variant: .compactText(title: "+"),
                contrast: .low,
                action: onAddMessage
            )
            .accessibilityLabel("Add message")
            .accessibilityIdentifier("messageListAddMessage")
            OpenCodeButton(
                variant: .compactText(title: "-"),
                enabled: canRemove,
                contrast: .low,
                action: onRemoveLastMessage
            )
            .accessibilityLabel("Remove message")
            .accessibilityIdentifier("messageListRemoveMessage")
            OpenCodeButton(
                variant: .compactText(title: "/"),
                enabled: canRemove,
                contrast: .low,
                action: onResizeLastMessage
            )
            .accessibilityLabel("Resize message")
            .accessibilityIdentifier("messageListResizeMessage")
        }
        .padding(.horizontal, 6)
        .padding(.vertical, 6)
        .background(OpenCodeColors.surfaceBase, in: Capsule())
        .overlay(
            Capsule()
                .stroke(OpenCodeColors.borderWeakerBase, lineWidth: 1)
        )
    }
}

private struct MessageListAutoFollowIndicator: View {
    let thresholdMet: Bool
    let autoFollowEnabled: Bool

    private var label: String {
        if thresholdMet && autoFollowEnabled {
            return "Auto-follow armed"
        }

        return thresholdMet ? "Threshold met" : "Threshold missed"
    }

    var body: some View {
        Text(label)
            .font(OpenCodeTypography.caption)
            .foregroundStyle(thresholdMet ? OpenCodeColors.textInteractiveBase : OpenCodeColors.textBase)
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .background(OpenCodeColors.surfaceBase, in: Capsule())
            .overlay(
                Capsule()
                    .stroke(OpenCodeColors.borderWeakerBase, lineWidth: 1)
            )
            .accessibilityIdentifier("messageListAutoFollowIndicator")
            .accessibilityLabel(label)
    }
}

private struct MessageListGalleryMessageRow: View {
    let message: MessageListGalleryMessage

    var body: some View {
        Group {
            switch message.role {
            case .user:
                OpenCodeOutgoingMessage(
                    text: message.text,
                    metadataLabel: message.metadataLabel ?? ""
                )
            case .assistant:
                Text(message.text.isEmpty ? "…" : message.text)
                    .font(OpenCodeTypography.body)
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
    }
}

#Preview {
    MessageListGalleryView(model: MessageListGalleryModel())
        .openCodeTheme()
}
