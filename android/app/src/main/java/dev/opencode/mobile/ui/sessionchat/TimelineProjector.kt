package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessagePart
import dev.opencode.mobile.domain.message.MessageRole
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class TimelineRow(
    val id: String,
    val role: MessageRole,
    val text: String,
    val metadataLabel: String,
    val isPending: Boolean = false,
    val isStreaming: Boolean = false,
    val blocks: List<SessionChatTimelineBlock> = emptyList(),
    val attachments: List<SessionChatAttachmentItem> = emptyList(),
    val modelProviderId: String? = null,
    val modelId: String? = null,
    val contextInputTokens: Long? = null,
    val contentRevision: ContentRevision,
    val layoutRevision: Long,
)

data class TimelineFrame(
    val frameRevision: Long,
    val rows: List<TimelineRow>,
    val insertedRowIDs: Set<String>,
    val changedRowIDs: Set<String>,
    val removedRowIDs: Set<String>,
    val layoutChangedRowIDs: Set<String>,
) {
    /** Row IDs whose apply can change list geometry (inserts ∪ layout-affecting restyles). */
    fun layoutAffectingRowIDs(): Set<String> = insertedRowIDs + layoutChangedRowIDs

    /**
     * Host auto-follow key for layout-affecting frames.
     * Content-only frames return `0` so the live path does not rediscover layout via scans.
     */
    fun layoutAffectingRevision(): Long =
        if (layoutAffectingRowIDs().isEmpty()) 0L else frameRevision
}

/**
 * Explicit optimistic reconciliation inputs for the pure projector.
 * [SessionChatTimeline] owns lifecycle and supplies these; merge policy lives here.
 */
data class OptimisticInputs(
    val pendingRows: List<TimelineRow> = emptyList(),
    val baselineSignatures: Map<String, String> = emptyMap(),
)

object TimelineProjector {
    private val sentTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

    fun project(
        frame: DisplayFrame,
        optimisticInputs: OptimisticInputs = OptimisticInputs(),
        previous: TimelineFrame? = null,
    ): TimelineFrame {
        val previousById = previous?.rows?.associateBy { it.id }.orEmpty()
        val authoritative =
            frame.messages.mapNotNull { body ->
                projectRow(body, previousById[body.message.id])
            }
        val merged = mergeWithPending(authoritative, optimisticInputs)
        return diffFrame(
            frameRevision = frame.frameRevision,
            rows = merged,
            previous = previous,
        )
    }

    fun rowFromMessageItem(
        item: SessionChatMessageItem,
        previous: TimelineRow? = null,
    ): TimelineRow {
        val contentRevision = ContentRevision.of(item.text)
        val draft =
            TimelineRow(
                id = item.id,
                role = item.role,
                text = item.text,
                metadataLabel = item.metadataLabel,
                isPending = item.isPending,
                isStreaming = item.isStreaming,
                blocks = item.blocks,
                attachments = item.attachments,
                modelProviderId = item.modelProviderId,
                modelId = item.modelId,
                contextInputTokens = item.contextInputTokens,
                contentRevision = contentRevision,
                layoutRevision = 0L,
            )
        return finalizeRow(draft, previous)
    }

    fun toMessageItem(row: TimelineRow): SessionChatMessageItem {
        val soleDecoratedText =
            row.blocks
                .filterIsInstance<SessionChatTimelineBlock.Text>()
                .singleOrNull()
                ?.renderedMarkdown
        return SessionChatMessageItem(
            id = row.id,
            role = row.role,
            text = row.text,
            metadataLabel = row.metadataLabel,
            isPending = row.isPending,
            isStreaming = row.isStreaming,
            renderedMarkdown = soleDecoratedText,
            blocks = row.blocks,
            attachments = row.attachments,
            modelProviderId = row.modelProviderId,
            modelId = row.modelId,
            contextInputTokens = row.contextInputTokens,
        )
    }

    fun metadataLabel(message: Message): String {
        val timeLabel = message.sentAtEpochMs?.let(::formatSentTime)
        return listOfNotNull(message.mode, message.model, message.variant, timeLabel).joinToString(" · ")
    }

    fun isStreaming(message: Message): Boolean =
        message.parts.any { part -> part is MessagePart.Text && part.isStreaming }

    fun reconciliationSignature(row: TimelineRow): String =
        buildString {
            append(row.role.name)
            append('\u0000')
            append(row.text)
            row.attachments.forEach { attachment ->
                append('\u0000')
                append(attachment.id)
                append('\u0000')
                append(attachment.signature)
            }
        }

    private fun projectRow(
        body: DisplayMessageBody,
        previous: TimelineRow?,
    ): TimelineRow? {
        val message = body.message
        if (body.blocks.isEmpty()) return null
        val attachments =
            body.blocks.mapNotNull { (it as? SessionChatTimelineBlock.Attachment)?.attachment }
        val draft =
            TimelineRow(
                id = message.id,
                role = message.role,
                text = message.text,
                metadataLabel = metadataLabel(message),
                isPending = false,
                isStreaming = isStreaming(message),
                blocks = body.blocks,
                attachments = attachments,
                modelProviderId = message.modelProviderId,
                modelId = message.modelId,
                contextInputTokens = message.contextInputTokens,
                contentRevision = ContentRevision.of(message.text),
                layoutRevision = 0L,
            )
        return finalizeRow(draft, previous)
    }

    private fun finalizeRow(
        draft: TimelineRow,
        previous: TimelineRow?,
    ): TimelineRow {
        if (previous != null && structurallyEqual(previous, draft)) {
            return previous
        }
        val layoutRevision =
            when {
                previous == null -> 0L
                layoutKey(previous) == layoutKey(draft) -> previous.layoutRevision
                else -> previous.layoutRevision + 1L
            }
        return draft.copy(layoutRevision = layoutRevision)
    }

    private fun structurallyEqual(
        left: TimelineRow,
        right: TimelineRow,
    ): Boolean =
        left.id == right.id &&
            left.role == right.role &&
            left.text == right.text &&
            left.metadataLabel == right.metadataLabel &&
            left.isPending == right.isPending &&
            left.isStreaming == right.isStreaming &&
            left.blocks == right.blocks &&
            left.attachments == right.attachments &&
            left.modelProviderId == right.modelProviderId &&
            left.modelId == right.modelId &&
            left.contextInputTokens == right.contextInputTokens &&
            left.contentRevision == right.contentRevision

    private fun layoutKey(row: TimelineRow): LayoutKey =
        LayoutKey(
            textLength = row.text.length,
            blockCount = row.blocks.size,
            hasMarkdown =
                row.blocks.any {
                    it is SessionChatTimelineBlock.Text && it.renderedMarkdown != null
                },
            isStreaming = row.isStreaming,
            attachmentSignatures = row.attachments.map { "${it.id}\u0000${it.signature}" },
            blockLayoutFingerprints = row.blocks.map(::blockLayoutFingerprint),
        )

    private fun blockLayoutFingerprint(block: SessionChatTimelineBlock): String =
        when (block) {
            is SessionChatTimelineBlock.Text ->
                "t:${block.blockID}:${block.text.length}:${block.renderedMarkdown != null}"
            is SessionChatTimelineBlock.Attachment ->
                "a:${block.blockID}:${block.attachment.filename}"
            is SessionChatTimelineBlock.ToolCard ->
                "c:${block.blockID}:${block.title}:${block.subtitle}:${block.status}:" +
                    "${block.errorText}:${block.additions}:${block.deletions}"
            is SessionChatTimelineBlock.ToolGroup ->
                "g:${block.blockID}:${block.count}:${block.title}:${block.subtitle}:${block.status}"
        }

    private data class LayoutKey(
        val textLength: Int,
        val blockCount: Int,
        val hasMarkdown: Boolean,
        val isStreaming: Boolean,
        val attachmentSignatures: List<String>,
        val blockLayoutFingerprints: List<String>,
    )

    private fun mergeWithPending(
        serverRows: List<TimelineRow>,
        optimisticInputs: OptimisticInputs,
    ): List<TimelineRow> {
        val pendingRows = optimisticInputs.pendingRows
        if (pendingRows.isEmpty()) return serverRows
        val baselineSignatures = optimisticInputs.baselineSignatures
        val pendingToKeep =
            pendingRows.filter { pending ->
                !serverRows.any { server ->
                    baselineSignatures[server.id] != reconciliationSignature(server) &&
                        matchesOptimistic(server, pending)
                }
            }
        return serverRows + pendingToKeep
    }

    private fun matchesOptimistic(
        server: TimelineRow,
        pending: TimelineRow,
    ): Boolean =
        server.role == MessageRole.User &&
            server.text == pending.text &&
            server.attachments.size == pending.attachments.size &&
            server.attachments.zip(pending.attachments).all { (left, right) ->
                left.id == right.id || left.signature == right.signature
            }

    private fun diffFrame(
        frameRevision: Long,
        rows: List<TimelineRow>,
        previous: TimelineFrame?,
    ): TimelineFrame {
        val previousRows = previous?.rows.orEmpty()
        val previousById = previousRows.associateBy { it.id }
        val currentIds = rows.map { it.id }.toSet()
        val previousIds = previousById.keys

        val inserted = linkedSetOf<String>()
        val changed = linkedSetOf<String>()
        val layoutChanged = linkedSetOf<String>()

        for (row in rows) {
            val prior = previousById[row.id]
            when {
                prior == null -> inserted += row.id
                prior !== row -> {
                    changed += row.id
                    if (row.layoutRevision != prior.layoutRevision) {
                        layoutChanged += row.id
                    }
                }
            }
        }

        return TimelineFrame(
            frameRevision = frameRevision,
            rows = rows,
            insertedRowIDs = inserted,
            changedRowIDs = changed,
            removedRowIDs = previousIds - currentIds,
            layoutChangedRowIDs = layoutChanged,
        )
    }

    private fun formatSentTime(epochMs: Long): String =
        Instant
            .ofEpochMilli(epochMs)
            .atZone(ZoneId.systemDefault())
            .format(sentTimeFormatter)
}
