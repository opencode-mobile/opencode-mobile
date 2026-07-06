import SwiftUI

private let messageListGalleryBottomAnchorID = "message-list-gallery-bottom-anchor"
private let messageListGalleryPinnedTolerance: CGFloat = 8
private let messageListGalleryAutoFollowExtraTolerance: CGFloat = 32

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

    @State private var scrollPositionID: String?
    @State private var scrollDistanceFromBottom = CGFloat.greatestFiniteMagnitude
    @State private var contentHeight: CGFloat = 0
    @State private var viewportHeight: CGFloat = 0
    @State private var isUserScrolling = false
    @State private var autoFollowState = MessageListAutoFollowState(initialMessageCount: 0)

    private var isScrollable: Bool {
        contentHeight > viewportHeight + 8
    }

    private var isPinnedToEnd: Bool {
        guard isScrollable else { return true }

        if #available(iOS 18, *) {
            return scrollDistanceFromBottom <=
                messageListGalleryPinnedTolerance + messageListGalleryAutoFollowExtraTolerance
        }

        return scrollPositionID == messageListGalleryBottomAnchorID
    }

    private var showScrollToEnd: Bool {
        guard !messages.isEmpty, isScrollable else { return false }

        if #available(iOS 18, *) {
            return ScrollToEndVisibility.shouldShow(
                scrollDistanceFromBottom: scrollDistanceFromBottom,
                viewportHeight: viewportHeight,
                isUserScrolling: isUserScrolling
            )
        }

        guard !isUserScrolling else { return false }
        return scrollPositionID != messageListGalleryBottomAnchorID
    }

    var body: some View {
        ZStack(alignment: .bottom) {
            ScrollView {
                MessageListGalleryMessageStack(messages: messages)
                    .scrollTargetLayout()
                    .background {
                        GeometryReader { geometry in
                            Color.clear
                                .onChange(of: geometry.size.height, initial: true) { _, height in
                                    contentHeight = height
                                }
                        }
                    }
            }
            .background {
                GeometryReader { geometry in
                    Color.clear
                        .onChange(of: geometry.size.height, initial: true) { _, height in
                            viewportHeight = height
                        }
                }
            }
            .scrollPosition(id: $scrollPositionID, anchor: .bottom)
            .scrollToEndScrollObserver(
                distanceFromBottom: $scrollDistanceFromBottom,
                isUserScrolling: $isUserScrolling,
                scrollPositionID: scrollPositionID
            )

            if showScrollToEnd {
                OpenCodeActionPill(variant: .scrollToEnd) {
                    scrollToEnd()
                }
                .padding(.bottom, 12)
                .transition(.opacity.combined(with: .move(edge: .bottom)))
            }

            MessageListAutoFollowIndicator(
                thresholdMet: isPinnedToEnd,
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
        .onAppear {
            autoFollowState = MessageListAutoFollowState(initialMessageCount: messages.count)
            autoFollowState.updatePinnedState(isPinnedToEnd: isPinnedToEnd, messageCount: messages.count)
        }
        .onChange(of: isPinnedToEnd, initial: true) { _, isPinnedToEnd in
            autoFollowState.updatePinnedState(isPinnedToEnd: isPinnedToEnd, messageCount: messages.count)
        }
        .onChange(of: messages.count) { _, newCount in
            guard autoFollowState.onMessageCountChanged(
                messageCount: newCount,
                autoFollowEnabled: autoFollowEnabled
            ) else { return }

            scrollToEnd()
        }
        .background(OpenCodeColors.backgroundBase)
    }

    private func scrollToEnd() {
        guard !messages.isEmpty else { return }
        scrollPositionID = nil
        Task { @MainActor in
            await Task.yield()
            withAnimation(.easeOut(duration: 0.2)) {
                scrollPositionID = messageListGalleryBottomAnchorID
            }
            await Task.yield()
            autoFollowState.markProgrammaticScrollSettled(isPinnedToEnd: isPinnedToEnd)
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

private struct MessageListGalleryMessageStack: View {
    let messages: [MessageListGalleryMessage]

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(Array(messages.enumerated()), id: \.element.id) { index, message in
                HStack {
                    if message.role == .assistant {
                        MessageListGalleryMessageRow(message: message)
                        Spacer(minLength: 0)
                    } else {
                        Spacer(minLength: 0)
                        MessageListGalleryMessageRow(message: message)
                    }
                }
                .id(message.id)
                .padding(.bottom, index == messages.count - 1 ? 0 : 8)
            }

            Color.clear
                .frame(height: 12)
                .id(messageListGalleryBottomAnchorID)
                .accessibilityHidden(true)
        }
        .padding(.horizontal, 16)
        .padding(.top, 12)
    }
}

private struct MessageListGalleryMessageRow: View {
    let message: MessageListGalleryMessage

    var body: some View {
        switch message.role {
        case .user:
            OpenCodeOutgoingMessage(
                text: message.text,
                metadataLabel: message.metadataLabel ?? "",
                onRestoreClick: {}
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

#Preview {
    MessageListGalleryView(model: MessageListGalleryModel())
        .openCodeTheme()
}
