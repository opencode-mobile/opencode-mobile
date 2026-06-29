import SwiftUI

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
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 0) {
                        ForEach(Array(props.messages.enumerated()), id: \.element.id) { index, message in
                            HStack {
                                if message.role == .assistant || message.role == .command || message.role == .tool || message.role == .unknown {
                                    SessionChatMessageRow(
                                        message: message,
                                        onRestoreClick: { props.onRestoreComposerText(message.text) }
                                    )
                                    Spacer(minLength: 0)
                                } else {
                                    Spacer(minLength: 0)
                                    SessionChatMessageRow(
                                        message: message,
                                        onRestoreClick: { props.onRestoreComposerText(message.text) }
                                    )
                                }
                            }
                            .padding(.bottom, index == props.messages.count - 1 ? 0 : 8)
                        }
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 12)
                }
                .refreshable {
                    await props.onRefresh()
                }
                .overlay {
                    if props.isRefreshing {
                        ProgressView()
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
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
