package dev.opencode.mobile.domain.message

import dev.opencode.mobile.markdown.MarkdownDocument
import kotlinx.coroutines.flow.Flow

/**
 * A streaming fence pass. [partial] means the engine deadline stopped the call;
 * the spans are a colored prefix and the next pass continues.
 */
data class StreamingHighlight(
    val document: MarkdownDocument,
    val partial: Boolean,
)

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

    /**
     * Format-first streaming parse with empty code spans. Does not touch the
     * preparer LRU or the highlighting engine. Returns null only when markdown
     * is not detected (or parse fails).
     */
    suspend fun prepareStructureOnlyTextBlock(
        blockID: String,
        plain: String,
        sessionKey: String = "",
    ): MarkdownDocument?

    /**
     * Colors the fences in a streaming block after the structure frame has
     * published. Does not touch the preparer LRU. A newer token drops the
     * result; the engine re-lexes from the last changed line and stops on its
     * deadline. Returns null when markdown is not detected (or parse fails).
     */
    suspend fun highlightStreamingTextBlock(
        blockID: String,
        plain: String,
        sessionKey: String = "",
    ): StreamingHighlight?
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

        override suspend fun prepareStructureOnlyTextBlock(
            blockID: String,
            plain: String,
            sessionKey: String,
        ): MarkdownDocument? =
            this@asDisplayMessagePreparing.prepareStructureOnlyTextBlock(
                blockID = blockID,
                plain = plain,
                sessionKey = sessionKey,
            )

        override suspend fun highlightStreamingTextBlock(
            blockID: String,
            plain: String,
            sessionKey: String,
        ): StreamingHighlight? =
            this@asDisplayMessagePreparing.highlightStreamingTextBlock(
                blockID = blockID,
                plain = plain,
                sessionKey = sessionKey,
            )
    }
