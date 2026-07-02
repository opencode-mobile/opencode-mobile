import SwiftUI

private let messageListGalleryBottomAnchorID = "message-list-gallery-bottom-anchor"

struct MessageListGalleryView: View {
    private let messages = MessageListGalleryMocks.messages

    var body: some View {
        MessageListGalleryPreview(messages: messages)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

private struct MessageListGalleryPreview: View {
    let messages: [MessageListGalleryMessage]

    @State private var scrollPositionID: String?
    @State private var scrollDistanceFromBottom = CGFloat.greatestFiniteMagnitude
    @State private var contentHeight: CGFloat = 0
    @State private var viewportHeight: CGFloat = 0
    @State private var isUserScrolling = false

    private var isScrollable: Bool {
        contentHeight > viewportHeight + 8
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
        }
        .animation(.easeOut(duration: 0.2), value: showScrollToEnd)
        .background(OpenCodeColors.backgroundBase)
    }

    private func scrollToEnd() {
        guard !messages.isEmpty else { return }
        Task { @MainActor in
            await Task.yield()
            withAnimation(.easeOut(duration: 0.2)) {
                scrollPositionID = messageListGalleryBottomAnchorID
            }
        }
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
    MessageListGalleryView()
        .openCodeTheme()
}
