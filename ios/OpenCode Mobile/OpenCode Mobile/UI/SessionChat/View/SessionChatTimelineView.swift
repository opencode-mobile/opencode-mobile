import os
import SwiftUI

private let sessionChatTimelineBottomAnchorID = "session-chat-timeline-bottom-anchor"
private let sessionChatPinnedTolerance: CGFloat = 8
private let sessionChatAutoFollowExtraTolerance: CGFloat = 32

struct SessionChatTimelineViewProps {
    let timeline: SessionChatTimelineState
    let messages: [SessionChatRow]
    let isRefreshing: Bool
    let errorMessage: String?
    let onRefresh: () async -> Void
    let onRestoreComposerText: (String) -> Void
}

struct SessionChatTimelineView: View {
    let props: SessionChatTimelineViewProps

    var body: some View {
        Group {
            if let errorMessage = props.errorMessage, props.timeline == .ready {
                Text(errorMessage)
                    .font(OpenCodeTypography.caption)
                    .foregroundStyle(OpenCodeColors.textBase)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 16)
                    .padding(.bottom, 4)
            }

            switch props.timeline {
            case .loading:
                Spacer()
                ProgressView()
                    .accessibilityIdentifier("sessionChatLoading")
                Spacer()
            case .empty:
                Spacer()
                Text("No messages yet. Send a prompt to start.")
                    .font(OpenCodeTypography.body)
                    .foregroundStyle(OpenCodeColors.textBase)
                    .padding(.horizontal, 16)
                Spacer()
            case .error:
                Spacer()
                Text(props.errorMessage ?? "Unable to load messages")
                    .font(OpenCodeTypography.body)
                    .foregroundStyle(OpenCodeColors.textBase)
                    .padding(.horizontal, 16)
                Spacer()
            case .ready:
                SessionChatTimelineReadyView(props: props)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

private struct SessionChatTimelineReadyView: View {
    let props: SessionChatTimelineViewProps

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
                sessionChatPinnedTolerance + sessionChatAutoFollowExtraTolerance
        }

        return scrollPositionID == sessionChatTimelineBottomAnchorID
    }

    private var showScrollToEnd: Bool {
        guard !props.messages.isEmpty, isScrollable else { return false }

        if #available(iOS 18, *) {
            return ScrollToEndVisibility.shouldShow(
                scrollDistanceFromBottom: scrollDistanceFromBottom,
                viewportHeight: viewportHeight,
                isUserScrolling: isUserScrolling
            )
        }

        guard !isUserScrolling else { return false }
        return scrollPositionID != sessionChatTimelineBottomAnchorID
    }

    var body: some View {
        ZStack(alignment: .bottom) {
            ScrollView {
                SessionChatTimelineMessageStack(
                    messages: props.messages,
                    onRestoreComposerText: props.onRestoreComposerText
                )
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
            .refreshable {
                await props.onRefresh()
            }
            .overlay {
                if props.isRefreshing {
                    ProgressView()
                }
            }

            if showScrollToEnd {
                OpenCodeActionPill(variant: .scrollToEnd) {
                    logScroll("pill-click count=\(props.messages.count)")
                    scrollToLastMessage()
                }
                .padding(.bottom, 12)
                .transition(.opacity.combined(with: .move(edge: .bottom)))
            }
        }
        .animation(.easeOut(duration: 0.2), value: showScrollToEnd)
        .onAppear {
            logScroll("appear count=\(props.messages.count) pinned=\(isPinnedToEnd)")
            autoFollowState = MessageListAutoFollowState(initialMessageCount: props.messages.count)
            autoFollowState.updatePinnedState(isPinnedToEnd: isPinnedToEnd, messageCount: props.messages.count)
        }
        .onChange(of: isPinnedToEnd, initial: true) { _, isPinnedToEnd in
            logScroll("pinned-change count=\(props.messages.count) pinned=\(isPinnedToEnd) userScrolling=\(isUserScrolling)")
            if isUserScrolling || isPinnedToEnd {
                autoFollowState.updatePinnedState(isPinnedToEnd: isPinnedToEnd, messageCount: props.messages.count)
            } else {
                followLayoutChangeIfNeeded()
            }
        }
        .onChange(of: contentHeight) { _, _ in
            logScroll("content-height count=\(props.messages.count) height=\(contentHeight) viewport=\(viewportHeight)")
            followLayoutChangeIfNeeded()
        }
        .onChange(of: viewportHeight) { _, _ in
            logScroll("viewport-height count=\(props.messages.count) height=\(contentHeight) viewport=\(viewportHeight)")
            followLayoutChangeIfNeeded()
        }
        .onChange(of: props.messages.layoutSignature) { _, _ in
            logScroll("layout-signature changed count=\(props.messages.count) signature=\(props.messages.layoutSignature)")
            followLayoutChangeIfNeededAfterLayoutSettles()
        }
        .onChange(of: props.messages.count) { _, newCount in
            logScroll("message-count changed count=\(newCount)")
            guard autoFollowState.onMessageCountChanged(
                messageCount: newCount,
                autoFollowEnabled: true
            ) else { return }

            logScroll("append-follow count=\(newCount)")
            scrollToLastMessage()
        }
    }

    private func followLayoutChangeIfNeeded() {
        if isUserScrolling {
            logScroll("layout-check no-op count=\(props.messages.count) pinned=\(isPinnedToEnd) userScrolling=\(isUserScrolling)")
            autoFollowState.updatePinnedState(isPinnedToEnd: isPinnedToEnd, messageCount: props.messages.count)
            return
        }

        if isPinnedToEnd, autoFollowState.shouldEnsureLayoutEnd(autoFollowEnabled: true) {
            logScroll("layout-check ensure-end count=\(props.messages.count)")
            snapToLastMessage()
            return
        }

        if isPinnedToEnd {
            logScroll("layout-check no-op count=\(props.messages.count) pinned=\(isPinnedToEnd) userScrolling=\(isUserScrolling)")
            autoFollowState.updatePinnedState(isPinnedToEnd: true, messageCount: props.messages.count)
            return
        }

        guard autoFollowState.shouldFollowLayoutChange(
            autoFollowEnabled: true,
            isPinnedToEnd: isPinnedToEnd
        ) else {
            logScroll("layout-check disarm count=\(props.messages.count)")
            autoFollowState.updatePinnedState(isPinnedToEnd: false, messageCount: props.messages.count)
            return
        }

        logScroll("layout-check follow count=\(props.messages.count)")
        snapToLastMessage()
    }

    private func followLayoutChangeIfNeededAfterLayoutSettles() {
        logScroll("layout-check scheduled count=\(props.messages.count)")
        Task { @MainActor in
            await Task.yield()
            await Task.yield()
            followLayoutChangeIfNeeded()
        }
    }

    private func scrollToLastMessage() {
        guard !props.messages.isEmpty else { return }
        logScroll("animate-to-end start count=\(props.messages.count)")
        scrollPositionID = nil
        Task { @MainActor in
            await Task.yield()
            withAnimation(.easeOut(duration: 0.2)) {
                scrollPositionID = sessionChatTimelineBottomAnchorID
            }
            await Task.yield()
            autoFollowState.markProgrammaticScrollSettled(isPinnedToEnd: isPinnedToEnd)
            logScroll("animate-to-end settled count=\(props.messages.count) pinned=\(isPinnedToEnd) position=\(scrollPositionID ?? "-")")
        }
    }

    private func snapToLastMessage() {
        guard !props.messages.isEmpty else { return }
        logScroll("layout-snap start count=\(props.messages.count)")
        scrollPositionID = nil
        Task { @MainActor in
            await Task.yield()
            var transaction = Transaction()
            transaction.disablesAnimations = true
            withTransaction(transaction) {
                scrollPositionID = sessionChatTimelineBottomAnchorID
            }
            await Task.yield()
            autoFollowState.markProgrammaticScrollSettled(isPinnedToEnd: isPinnedToEnd)
            logScroll("layout-snap settled count=\(props.messages.count) pinned=\(isPinnedToEnd) position=\(scrollPositionID ?? "-")")
        }
    }

    private func logScroll(_ message: String) {
        OpenCodeLog.sessionChat.debug("scroll \(message, privacy: .public)")
    }
}

private extension Array where Element == SessionChatRow {
    var layoutSignature: Int {
        reduce(count) { result, message in
            var hasher = Hasher()
            hasher.combine(result)
            hasher.combine(message.id)
            hasher.combine(message.text.count)
            hasher.combine(message.isStreaming)
            hasher.combine(message.renderedMarkdown?.sourceHash ?? 0)
            hasher.combine(message.renderedMarkdown?.blocks.count ?? 0)
            return hasher.finalize()
        }
    }
}

private struct SessionChatTimelineMessageStack: View {
    let messages: [SessionChatRow]
    let onRestoreComposerText: (String) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(Array(messages.enumerated()), id: \.element.id) { index, message in
                HStack {
                    if message.role == .assistant || message.role == .command || message.role == .tool || message.role == .unknown {
                        SessionChatMessageRow(
                            message: message,
                            onRestoreClick: { onRestoreComposerText(message.text) }
                        )
                        Spacer(minLength: 0)
                    } else {
                        Spacer(minLength: 0)
                        SessionChatMessageRow(
                            message: message,
                            onRestoreClick: { onRestoreComposerText(message.text) }
                        )
                    }
                }
                .id(message.id)
                .padding(.bottom, index == messages.count - 1 ? 0 : 8)
            }

            Color.clear
                .frame(height: 12)
                .id(sessionChatTimelineBottomAnchorID)
                .accessibilityHidden(true)
        }
        .padding(.horizontal, 16)
        .padding(.top, 12)
    }
}

private struct SessionChatMessageRow: View {
    let message: SessionChatRow
    let onRestoreClick: () -> Void

    var body: some View {
        switch message.role {
        case .user:
            OpenCodeOutgoingMessage(
                text: message.text,
                renderedMarkdown: message.renderedMarkdown,
                metadataLabel: message.metadataLabel,
                onRestoreClick: onRestoreClick
            )
        case .assistant:
            AssistantMessageBubble(
                text: message.text,
                renderedMarkdown: message.renderedMarkdown
            )
        case .command, .tool, .unknown:
            FallbackMessageBubble(
                roleLabel: message.role.displayLabel,
                text: message.text
            )
        }
    }
}

private struct AssistantMessageBubble: View {
    let text: String
    let renderedMarkdown: NativeMarkdownDocument?

    var body: some View {
        Group {
            if let renderedMarkdown {
                OpenCodeRenderedMarkdown(
                    document: renderedMarkdown,
                    variant: .assistantMessage,
                    measuresHeight: true
                )
            } else {
                Text(text.isEmpty ? "…" : text)
                    .font(OpenCodeTypography.body)
                    .foregroundStyle(OpenCodeColors.textStrong)
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(OpenCodeColors.surfaceBase, in: RoundedRectangle(cornerRadius: 8))
        .overlay(
            RoundedRectangle(cornerRadius: 8)
                .stroke(OpenCodeColors.borderWeakerBase, lineWidth: 1)
        )
    }
}

private struct FallbackMessageBubble: View {
    let roleLabel: String
    let text: String

    var body: some View {
        Text("\(roleLabel): \(text.isEmpty ? "…" : text)")
            .font(OpenCodeTypography.caption)
            .foregroundStyle(OpenCodeColors.textBase)
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(OpenCodeColors.surfaceRaisedBase, in: RoundedRectangle(cornerRadius: 12))
    }
}
