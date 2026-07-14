import os
import SwiftUI

private let sessionChatTimelineBottomAnchorID = "session-chat-timeline-bottom-anchor"

struct SessionChatTimelineViewProps {
    let timeline: SessionChatTimelineState
    let messages: [SessionChatRow]
    let isRefreshing: Bool
    let errorMessage: String?
    let reviewChangesSummary: ReviewChangesSummary?
    let onRefresh: () async -> Void
    let onReviewChangesTap: () -> Void
    let onMessageActionsTap: (SessionChatMessageActionsTarget) -> Void
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

    @State private var timeline = MessageListTimelineState(
        bottomAnchorID: sessionChatTimelineBottomAnchorID
    )

    private var messageCount: Int { props.messages.count }

    private var showScrollToEnd: Bool {
        timeline.shouldShowScrollToEnd(messageCount: messageCount)
    }

    private var showReviewChanges: Bool {
        ReviewChangesPillVisibility.shouldShow(
            summary: props.reviewChangesSummary,
            isPinnedToEnd: timeline.isPinnedToEnd(messageCount: messageCount),
            showScrollToEnd: showScrollToEnd
        )
    }

    var body: some View {
        ZStack(alignment: .bottom) {
            ScrollView {
                MessageListReversedTimelineStack(
                    items: props.messages,
                    bottomAnchorID: sessionChatTimelineBottomAnchorID,
                    itemID: \.id,
                    onLastMessageContentSizeSettled: {
                        timeline.followLayoutChangeIfNeededAfterLayoutSettles(
                            messageCount: messageCount,
                            autoFollowEnabled: true,
                            scrollTargetID: props.messages.last?.id,
                            onLog: logScroll
                        )
                    }
                ) { message, isNewestMessage, onContentSizeSettled in
                    HStack {
                        if message.role == .assistant || message.role == .command || message.role == .tool || message.role == .unknown {
                            SessionChatMessageRow(
                                message: message,
                                onMessageActionsTap: props.onMessageActionsTap,
                                animateSizeChanges: isNewestMessage,
                                onContentSizeAnimationFinished: onContentSizeSettled
                            )
                            Spacer(minLength: 0)
                        } else {
                            Spacer(minLength: 0)
                            SessionChatMessageRow(
                                message: message,
                                onMessageActionsTap: props.onMessageActionsTap,
                                animateSizeChanges: isNewestMessage,
                                onContentSizeAnimationFinished: onContentSizeSettled
                            )
                        }
                    }
                }
                .scrollTargetLayout()
                .messageListTimelineContentHeight(timeline: timeline)
            }
            .defaultScrollAnchor(.bottom)
            .messageListTimelineScrollChrome(timeline: timeline)
            .refreshable {
                await props.onRefresh()
            }
            .overlay {
                if props.isRefreshing {
                    ProgressView()
                }
            }

            MessageListScrollToEndOverlay(
                visible: showScrollToEnd
            ) {
                logScroll("pill-click count=\(messageCount)")
                timeline.animateToEnd(
                    messageCount: messageCount,
                    scrollTargetID: props.messages.last?.id,
                    onLog: logScroll
                )
            }

            if showReviewChanges, let summary = props.reviewChangesSummary {
                OpenCodeReviewChangesPill(
                    variant: .latestTurn,
                    changedFileCount: summary.fileCount,
                    additions: summary.additions,
                    deletions: summary.deletions,
                    action: props.onReviewChangesTap
                )
                    .padding(.bottom, MessageListTimelineDefaults.scrollToEndPillBottomPadding)
                    .transition(.opacity.combined(with: .move(edge: .bottom)))
            }
        }
        .animation(.easeOut(duration: 0.2), value: showScrollToEnd)
        .animation(.easeOut(duration: 0.2), value: showReviewChanges)
        .messageListTimelineBehavior(
            timeline: timeline,
            messageCount: messageCount,
            firstMessageID: props.messages.first?.id,
            lastMessageID: props.messages.last?.id,
            autoFollowEnabled: true,
            layoutSignature: props.messages.layoutSignature,
            onLog: logScroll
        )
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

private struct SessionChatMessageRow: View {
    let message: SessionChatRow
    let onMessageActionsTap: (SessionChatMessageActionsTarget) -> Void
    let animateSizeChanges: Bool
    let onContentSizeAnimationFinished: (() -> Void)?

    private func openMessageActions(showsRevertMessage: Bool) {
        onMessageActionsTap(
            SessionChatMessageActionsTarget(
                text: message.text,
                showsRevertMessage: showsRevertMessage
            )
        )
    }

    var body: some View {
        Group {
            switch message.role {
            case .user:
                OpenCodeOutgoingMessage(
                    text: message.text,
                    renderedMarkdown: message.renderedMarkdown,
                    metadataLabel: message.metadataLabel,
                    onTap: message.renderedMarkdown == nil
                        ? { openMessageActions(showsRevertMessage: true) }
                        : nil,
                    onLongPress: message.renderedMarkdown != nil
                        ? { openMessageActions(showsRevertMessage: true) }
                        : nil
                )
                .frame(maxWidth: .infinity, alignment: .trailing)
            case .assistant:
                AssistantMessageBubble(
                    text: message.text,
                    renderedMarkdown: message.renderedMarkdown
                )
                .frame(maxWidth: .infinity, alignment: .leading)
                .modifier(
                    SessionChatMessageActionsGestureModifier(
                        renderedMarkdown: message.renderedMarkdown,
                        accessibilityLabel: "Assistant message",
                        onOpenMessageActions: { openMessageActions(showsRevertMessage: false) }
                    )
                )
            case .command, .tool, .unknown:
                FallbackMessageBubble(
                    roleLabel: message.role.displayLabel,
                    text: message.text
                )
            }
        }
        .messageListAnimateLastMessageSize(
            animateSizeChanges: animateSizeChanges,
            onContentSizeAnimationFinished: onContentSizeAnimationFinished
        )
    }
}

private struct SessionChatMessageActionsGestureModifier: ViewModifier {
    let renderedMarkdown: NativeMarkdownDocument?
    let accessibilityLabel: String
    let onOpenMessageActions: () -> Void

    func body(content: Content) -> some View {
        if renderedMarkdown != nil {
            content
                .onLongPressGesture(perform: onOpenMessageActions)
                .accessibilityLabel(accessibilityLabel)
                .accessibilityHint("Long press to show message actions")
        } else {
            content
                .onTapGesture(perform: onOpenMessageActions)
                .accessibilityLabel(accessibilityLabel)
                .accessibilityHint("Shows message actions")
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
