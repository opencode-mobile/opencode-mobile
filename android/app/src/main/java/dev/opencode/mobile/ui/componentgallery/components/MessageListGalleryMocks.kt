package dev.opencode.mobile.ui.componentgallery.components

enum class MessageListGalleryRole {
    User,
    Assistant,
}

data class MessageListGalleryMessage(
    val id: String,
    val role: MessageListGalleryRole,
    val text: String,
    val metadataLabel: String? = null,
)

object MessageListGalleryMocks {
    private const val USER_METADATA = "Build · DeepSeek V4 Flash · 9:05 PM"

    val messages: List<MessageListGalleryMessage> =
        listOf(
            MessageListGalleryMessage(
                id = "gallery-user-1",
                role = MessageListGalleryRole.User,
                text = "Walk me through the session chat list UX we want on mobile.",
                metadataLabel = USER_METADATA,
            ),
            MessageListGalleryMessage(
                id = "gallery-assistant-1",
                role = MessageListGalleryRole.Assistant,
                text =
                    """
                    The timeline should alternate outgoing prompts and incoming replies with comfortable spacing.
                    When the latest messages are off-screen, a floating scroll-to-end pill appears above the composer area.
                    """.trimIndent(),
            ),
            MessageListGalleryMessage(
                id = "gallery-user-2",
                role = MessageListGalleryRole.User,
                text = "How should the pill behave when I tap it?",
                metadataLabel = USER_METADATA,
            ),
            MessageListGalleryMessage(
                id = "gallery-assistant-2",
                role = MessageListGalleryRole.Assistant,
                text =
                    "It should animate to the true bottom of the conversation, including bottom padding, " +
                    "without leaving a small manual scroll gap.",
            ),
            MessageListGalleryMessage(
                id = "gallery-user-3",
                role = MessageListGalleryRole.User,
                text = "Add a few more medium-length messages so we can stress-test scrolling in the gallery.",
                metadataLabel = USER_METADATA,
            ),
            MessageListGalleryMessage(
                id = "gallery-assistant-3",
                role = MessageListGalleryRole.Assistant,
                text =
                    """
                    Use plain text only in this harness so height changes come from copy length rather than markdown layout.
                    Mix short acknowledgements with longer paragraphs to mimic a real coding session.
                    """.trimIndent(),
            ),
            MessageListGalleryMessage(
                id = "gallery-user-4",
                role = MessageListGalleryRole.User,
                text = "Good. Keep the outgoing metadata line visible on user messages.",
                metadataLabel = USER_METADATA,
            ),
            MessageListGalleryMessage(
                id = "gallery-assistant-4",
                role = MessageListGalleryRole.Assistant,
                text = "Done. Metadata stays on outgoing cards only.",
            ),
            MessageListGalleryMessage(
                id = "gallery-user-5",
                role = MessageListGalleryRole.User,
                text = "Now simulate a longer assistant answer with multiple sentences that wraps across several lines on phone width.",
                metadataLabel = USER_METADATA,
            ),
            MessageListGalleryMessage(
                id = "gallery-assistant-5",
                role = MessageListGalleryRole.Assistant,
                text =
                    """
                    On Android we rely on LazyColumn visibility of a bottom anchor item.
                    On iOS we track scroll distance from the bottom on iOS 18 and fall back to scroll position on iOS 17.
                    This gallery page exists so we can tune that behavior without loading a live session.
                    """.trimIndent(),
            ),
            MessageListGalleryMessage(
                id = "gallery-user-6",
                role = MessageListGalleryRole.User,
                text = "What happens when I scroll up?",
                metadataLabel = USER_METADATA,
            ),
            MessageListGalleryMessage(
                id = "gallery-assistant-6",
                role = MessageListGalleryRole.Assistant,
                text = "The scroll-to-end pill should appear once the latest messages are no longer visible.",
            ),
            MessageListGalleryMessage(
                id = "gallery-user-7",
                role = MessageListGalleryRole.User,
                text = "Perfect. One more outgoing message near the end.",
                metadataLabel = USER_METADATA,
            ),
            MessageListGalleryMessage(
                id = "gallery-assistant-7",
                role = MessageListGalleryRole.Assistant,
                text = "Scroll up now and the pill should appear centered above the bottom edge of the screen.",
            ),
            MessageListGalleryMessage(
                id = "gallery-user-8",
                role = MessageListGalleryRole.User,
                text = "Tap the pill to return here.",
                metadataLabel = USER_METADATA,
            ),
            MessageListGalleryMessage(
                id = "gallery-assistant-8",
                role = MessageListGalleryRole.Assistant,
                text = "You should land on this final assistant reply with no extra manual scroll needed.",
            ),
        )
}
