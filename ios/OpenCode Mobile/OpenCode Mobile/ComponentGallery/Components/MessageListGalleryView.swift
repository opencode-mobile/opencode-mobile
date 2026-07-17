import SwiftUI

private let messageListGalleryBottomAnchorID = "message-list-gallery-bottom-anchor"

struct MessageListGalleryView: View {
    @Bindable var model: MessageListGalleryModel

    var body: some View {
        MessageListGalleryPreview(
            messages: model.messages,
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
    let autoFollowEnabled: Bool
    let onAddMessage: () -> Void
    let onRemoveLastMessage: () -> Void
    let onResizeLastMessage: () -> Void

    @State private var timeline = MessageListTimelineState(
        bottomAnchorID: messageListGalleryBottomAnchorID
    )

    private var messageCount: Int { messages.count }

    private var showScrollToEnd: Bool {
        timeline.shouldShowScrollToEnd(messageCount: messageCount)
    }

    var body: some View {
        ZStack(alignment: .bottom) {
            ScrollView {
                MessageListReversedTimelineStack(
                    items: messages,
                    bottomAnchorID: messageListGalleryBottomAnchorID,
                    itemID: \.id
                ) { message, _ in
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
                .scrollTargetLayout()
                .messageListTimelineContentHeight(timeline: timeline)
            }
            .defaultScrollAnchor(.bottom)
            .messageListTimelineScrollChrome(timeline: timeline)

            MessageListScrollToEndOverlay(
                visible: showScrollToEnd
            ) {
                timeline.animateToEnd(
                    messageCount: messageCount,
                    scrollTargetID: messages.last?.id
                )
            }

            MessageListAutoFollowIndicator(
                thresholdMet: timeline.isPinnedToEnd(messageCount: messageCount),
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
        .animation(.easeOut(duration: 0.2), value: showScrollToEnd)
        .messageListTimelineBehavior(
            timeline: timeline,
            messageCount: messageCount,
            firstMessageID: messages.first?.id,
            lastMessageID: messages.last?.id,
            autoFollowEnabled: autoFollowEnabled,
            layoutSignature: messages.layoutSignature
        )
        .background(OpenCodeColors.backgroundBase)
    }
}

private extension Array where Element == MessageListGalleryMessage {
    var layoutSignature: Int {
        reduce(count) { result, message in
            var hasher = Hasher()
            hasher.combine(result)
            hasher.combine(message.id)
            hasher.combine(message.text.count)
            return hasher.finalize()
        }
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
            OpenCodeButton(
                variant: .compactText(title: "-"),
                enabled: canRemove,
                contrast: .low,
                action: onRemoveLastMessage
            )
            OpenCodeButton(
                variant: .compactText(title: "/"),
                enabled: canRemove,
                contrast: .low,
                action: onResizeLastMessage
            )
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
