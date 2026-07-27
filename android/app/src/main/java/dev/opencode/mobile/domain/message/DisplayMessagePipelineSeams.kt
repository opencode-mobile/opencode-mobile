package dev.opencode.mobile.domain.message

import dev.opencode.mobile.markdown.MarkdownDocument
import kotlinx.coroutines.flow.Flow

/**
 * Raw message snapshots that feed [DisplayMessagePipeline]. Production wires
 * [MessageSync]; tests use a controllable source so pacing and preparation can
 * be exercised without transport.
 */
fun interface DisplayMessageSnapshotSource {
    fun observeMessages(
        sessionId: String,
        directory: String?,
    ): Flow<MessageListUpdate>
}

/**
 * Markdown preparation used by the display pipeline. Production uses
 * [MessageContentPreparer]; tests can suspend completion and record mode.
 */
interface DisplayMessagePreparing {
    suspend fun prepareMessages(
        messages: List<Message>,
        previousById: Map<String, DisplayMessage> = emptyMap(),
        mode: MessagePreparationMode = MessagePreparationMode.Final,
    ): List<DisplayMessage>

    suspend fun prepareMessage(
        message: Message,
        previous: DisplayMessage? = null,
        mode: MessagePreparationMode = MessagePreparationMode.Final,
    ): DisplayMessage

    suspend fun prepareTextBlock(
        blockID: String,
        plain: String,
        isStreaming: Boolean,
        sessionKey: String = "",
        mode: MessagePreparationMode = MessagePreparationMode.Streaming,
    ): MarkdownDocument?
}

fun MessageSync.asDisplayMessageSnapshotSource(): DisplayMessageSnapshotSource =
    DisplayMessageSnapshotSource { sessionId, directory ->
        observeMessages(sessionId, directory)
    }

fun MessageContentPreparer.asDisplayMessagePreparing(): DisplayMessagePreparing =
    object : DisplayMessagePreparing {
        override suspend fun prepareMessages(
            messages: List<Message>,
            previousById: Map<String, DisplayMessage>,
            mode: MessagePreparationMode,
        ): List<DisplayMessage> =
            this@asDisplayMessagePreparing.prepareMessages(
                messages,
                previousById,
                mode,
            )

        override suspend fun prepareMessage(
            message: Message,
            previous: DisplayMessage?,
            mode: MessagePreparationMode,
        ): DisplayMessage =
            this@asDisplayMessagePreparing.prepareMessage(
                message,
                previous,
                mode,
            )

        override suspend fun prepareTextBlock(
            blockID: String,
            plain: String,
            isStreaming: Boolean,
            sessionKey: String,
            mode: MessagePreparationMode,
        ): MarkdownDocument? =
            this@asDisplayMessagePreparing.prepareTextBlock(
                blockID = blockID,
                plain = plain,
                isStreaming = isStreaming,
                sessionKey = sessionKey,
                mode = mode,
            )
    }
