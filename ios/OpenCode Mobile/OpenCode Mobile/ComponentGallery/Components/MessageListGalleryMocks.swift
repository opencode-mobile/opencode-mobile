import Foundation

enum MessageListGalleryRole {
    case user
    case assistant
}

struct MessageListGalleryMessage: Identifiable {
    let id: String
    let role: MessageListGalleryRole
    let text: String
    let metadataLabel: String?
}

enum MessageListGalleryMocks {
    private static let userMetadata = "Build · DeepSeek V4 Flash · 9:05 PM"

    static let messages: [MessageListGalleryMessage] = [
        MessageListGalleryMessage(
            id: "gallery-user-1",
            role: .user,
            text: "Walk me through the session chat list UX we want on mobile.",
            metadataLabel: userMetadata
        ),
        MessageListGalleryMessage(
            id: "gallery-assistant-1",
            role: .assistant,
            text: """
            The timeline should alternate outgoing prompts and incoming replies with comfortable spacing.
            When the latest messages are off-screen, a floating scroll-to-end pill appears above the composer area.
            """,
            metadataLabel: nil
        ),
        MessageListGalleryMessage(
            id: "gallery-user-2",
            role: .user,
            text: "How should the pill behave when I tap it?",
            metadataLabel: userMetadata
        ),
        MessageListGalleryMessage(
            id: "gallery-assistant-2",
            role: .assistant,
            text: "It should animate to the true bottom of the conversation, including bottom padding, without leaving a small manual scroll gap.",
            metadataLabel: nil
        ),
        MessageListGalleryMessage(
            id: "gallery-user-3",
            role: .user,
            text: "Add a few more medium-length messages so we can stress-test scrolling in the gallery.",
            metadataLabel: userMetadata
        ),
        MessageListGalleryMessage(
            id: "gallery-assistant-3",
            role: .assistant,
            text: """
            Use plain text only in this harness so height changes come from copy length rather than markdown layout.
            Mix short acknowledgements with longer paragraphs to mimic a real coding session.
            """,
            metadataLabel: nil
        ),
        MessageListGalleryMessage(
            id: "gallery-user-4",
            role: .user,
            text: "Good. Keep the outgoing metadata line visible on user messages.",
            metadataLabel: userMetadata
        ),
        MessageListGalleryMessage(
            id: "gallery-assistant-4",
            role: .assistant,
            text: "Done. Metadata stays on outgoing cards only.",
            metadataLabel: nil
        ),
        MessageListGalleryMessage(
            id: "gallery-user-5",
            role: .user,
            text: "Now simulate a longer assistant answer with multiple sentences that wraps across several lines on phone width.",
            metadataLabel: userMetadata
        ),
        MessageListGalleryMessage(
            id: "gallery-assistant-5",
            role: .assistant,
            text: """
            On Android we rely on LazyColumn visibility of a bottom anchor item.
            On iOS we track scroll distance from the bottom on iOS 18 and fall back to scroll position on iOS 17.
            This gallery page exists so we can tune that behavior without loading a live session.
            """,
            metadataLabel: nil
        ),
        MessageListGalleryMessage(
            id: "gallery-user-6",
            role: .user,
            text: "What happens when I scroll up?",
            metadataLabel: userMetadata
        ),
        MessageListGalleryMessage(
            id: "gallery-assistant-6",
            role: .assistant,
            text: "The scroll-to-end pill should appear once the latest messages are no longer visible.",
            metadataLabel: nil
        ),
        MessageListGalleryMessage(
            id: "gallery-user-7",
            role: .user,
            text: "Perfect. One more outgoing message near the end.",
            metadataLabel: userMetadata
        ),
        MessageListGalleryMessage(
            id: "gallery-assistant-7",
            role: .assistant,
            text: "Scroll up now and the pill should appear centered above the bottom edge of the screen.",
            metadataLabel: nil
        ),
        MessageListGalleryMessage(
            id: "gallery-user-8",
            role: .user,
            text: "Tap the pill to return here.",
            metadataLabel: userMetadata
        ),
        MessageListGalleryMessage(
            id: "gallery-assistant-8",
            role: .assistant,
            text: "You should land on this final assistant reply with no extra manual scroll needed.",
            metadataLabel: nil
        ),
    ]

    static func nextLiveMessage(sequence: Int, lastRole: MessageListGalleryRole?) -> MessageListGalleryMessage {
        let nextRole: MessageListGalleryRole =
            switch lastRole {
            case .none, .assistant: .user
            case .user: .assistant
            }

        switch nextRole {
        case .user:
            return MessageListGalleryMessage(
                id: "gallery-live-user-\(sequence)",
                role: .user,
                text: "Live message \(sequence): keep streaming updates coming every five seconds.",
                metadataLabel: userMetadata
            )
        case .assistant:
            return MessageListGalleryMessage(
                id: "gallery-live-assistant-\(sequence)",
                role: .assistant,
                text: "Live reply \(sequence): this simulated assistant message was appended while live messages are enabled.",
                metadataLabel: nil
            )
        }
    }
}
