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
    let onToolDetailTap: (SessionChatToolDetailTarget) -> Void
    let onChildSessionTap: (String, String) -> Void
    let onChangesReviewTap: (String, String?) -> Void

    init(
        timeline: SessionChatTimelineState,
        messages: [SessionChatRow],
        isRefreshing: Bool,
        errorMessage: String?,
        reviewChangesSummary: ReviewChangesSummary?,
        onRefresh: @escaping () async -> Void,
        onReviewChangesTap: @escaping () -> Void,
        onMessageActionsTap: @escaping (SessionChatMessageActionsTarget) -> Void,
        onToolDetailTap: @escaping (SessionChatToolDetailTarget) -> Void = { _ in },
        onChildSessionTap: @escaping (String, String) -> Void = { _, _ in },
        onChangesReviewTap: @escaping (String, String?) -> Void = { _, _ in }
    ) {
        self.timeline = timeline
        self.messages = messages
        self.isRefreshing = isRefreshing
        self.errorMessage = errorMessage
        self.reviewChangesSummary = reviewChangesSummary
        self.onRefresh = onRefresh
        self.onReviewChangesTap = onReviewChangesTap
        self.onMessageActionsTap = onMessageActionsTap
        self.onToolDetailTap = onToolDetailTap
        self.onChildSessionTap = onChildSessionTap
        self.onChangesReviewTap = onChangesReviewTap
    }
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
                                onToolDetailTap: props.onToolDetailTap,
                                onChildSessionTap: props.onChildSessionTap,
                                onChangesReviewTap: props.onChangesReviewTap,
                                animateSizeChanges: isNewestMessage,
                                onContentSizeAnimationFinished: onContentSizeSettled
                            )
                            Spacer(minLength: 0)
                        } else {
                            Spacer(minLength: 0)
                            SessionChatMessageRow(
                                message: message,
                                onMessageActionsTap: props.onMessageActionsTap,
                                onToolDetailTap: props.onToolDetailTap,
                                onChildSessionTap: props.onChildSessionTap,
                                onChangesReviewTap: props.onChangesReviewTap,
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
            hasher.combine(message.blocks.count)
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
    let onToolDetailTap: (SessionChatToolDetailTarget) -> Void
    let onChildSessionTap: (String, String) -> Void
    let onChangesReviewTap: (String, String?) -> Void
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
                    content: message.outgoingContent,
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
                AssistantMessageContent(
                    message: message,
                    onOpenMessageActions: { openMessageActions(showsRevertMessage: false) },
                    onToolDetailTap: onToolDetailTap,
                    onChildSessionTap: onChildSessionTap,
                    onChangesReviewTap: onChangesReviewTap
                )
                .frame(maxWidth: .infinity, alignment: .leading)
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

private extension SessionChatRow {
    var outgoingContent: [OpenCodeOutgoingMessageContent] {
        let content = blocks.enumerated().compactMap { index, block -> OpenCodeOutgoingMessageContent? in
            switch block {
            case .text(let text):
                .text(id: String(index), text: text)
            case .attachment(let file):
                .attachment(file.attachmentPresentation)
            case .toolCard, .toolGroup:
                nil
            }
        }
        return content.isEmpty ? [.text(id: "message", text: text)] : content
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

private struct AssistantMessageContent: View {
    let message: SessionChatRow
    let onOpenMessageActions: () -> Void
    let onToolDetailTap: (SessionChatToolDetailTarget) -> Void
    let onChildSessionTap: (String, String) -> Void
    let onChangesReviewTap: (String, String?) -> Void

    private var blocks: [SessionChatTimelineBlock] {
        if message.blocks.isEmpty {
            return [.text(message.text)]
        }
        return message.blocks
    }

    private var singleTextBlock: Bool {
        blocks.filter {
            if case .text = $0 { return true }
            return false
        }.count == 1
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ForEach(Array(blocks.enumerated()), id: \.offset) { _, block in
                switch block {
                case .text(let text):
                    AssistantMessageBubble(
                        text: text,
                        renderedMarkdown: singleTextBlock ? message.renderedMarkdown : nil
                    )
                    .modifier(
                        SessionChatMessageActionsGestureModifier(
                            renderedMarkdown: singleTextBlock ? message.renderedMarkdown : nil,
                            accessibilityLabel: "Assistant message",
                            onOpenMessageActions: onOpenMessageActions
                        )
                    )
                case .attachment(let file):
                    OpenCodeMessageAttachment(attachment: file.attachmentPresentation)
                case .toolCard(let card):
                    OpenCodeToolCallCard(
                        status: card.status.toolCallCardStatus,
                        title: card.title,
                        subtitle: card.subtitle,
                        additions: card.additions,
                        deletions: card.deletions,
                        errorText: card.errorText,
                        action: toolCardAction(for: card)
                    )
                case .toolGroup(let group):
                    OpenCodeToolCallCard(
                        status: group.status.toolCallCardStatus,
                        title: group.title,
                        subtitle: group.subtitle,
                        errorText: group.errorText
                    )
                }
            }
        }
    }

    private func toolCardAction(for card: SessionChatToolCardBlock) -> (() -> Void)? {
        if let childSessionId = card.childSessionId {
            return { onChildSessionTap(childSessionId, card.title) }
        }
        if card.opensChangesReview {
            return { onChangesReviewTap(message.id, card.changesInitialFilePath) }
        }
        if let detail = card.detailText {
            return {
                onToolDetailTap(
                    SessionChatToolDetailTarget(
                        title: card.title,
                        body: detail
                    )
                )
            }
        }
        return nil
    }
}

private extension FilePart {
    var attachmentPresentation: OpenCodeAttachmentPresentation {
        AttachmentPresentationMapper.presentation(from: self)
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

private extension ToolStatus {
    var toolCallCardStatus: OpenCodeToolCallCardStatus {
        switch self {
        case .pending: .pending
        case .running: .running
        case .completed: .completed
        case .error: .error
        }
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
