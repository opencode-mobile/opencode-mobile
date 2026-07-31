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
    /**
     * Newest streaming row grew in text length without a structural layout key change.
     * Armed follow may pin; unarmed visible-anchor restore must ignore this set.
     */
    val grewBottomRowIDs: Set<String> = emptySet(),
    /**
     * Markdown-only subset of [layoutChangedRowIDs] (streaming flag and block structure
     * unchanged). Still layout-affecting for scroll in Phase 2; Phase 3 may coalesce.
     */
    val decorateRowIDs: Set<String> = emptySet(),
    /**
     * Region-owned: load / refresh / post-send applies that must never be coalesced away.
     * Not inferred from ID sets.
     */
    val isRequired: Boolean = false,
) {
    /** Row IDs whose apply can change list geometry (inserts ∪ layout-affecting restyles). */
    fun layoutAffectingRowIDs(): Set<String> = insertedRowIDs + layoutChangedRowIDs

    /**
     * Host auto-follow key for layout-affecting frames.
     * Content-only frames return `0` so the live path does not rediscover layout via scans.
     */
    fun layoutAffectingRevision(): Long =
        if (layoutAffectingRowIDs().isEmpty()) 0L else frameRevision

    /** Armed-only pin key for streaming bottom growth (not unarmed restore). */
    fun growBottomRevision(): Long =
        if (grewBottomRowIDs.isEmpty()) 0L else frameRevision

    fun withRequired(isRequired: Boolean): TimelineFrame = copy(isRequired = isRequired)
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

    /**
     * Build display message items with iOS-parity row reuse: unchanged non-pending
     * rows keep the previous [SessionChatMessageItem] instance (same object identity).
     * Still returns a full list every frame — not a patch protocol.
     */
    fun mergeMessageItems(
        frame: TimelineFrame,
        previousById: Map<String, SessionChatMessageItem>,
    ): List<SessionChatMessageItem> =
        frame.rows.map { row ->
            val previous = previousById[row.id]
            val reusePrevious =
                previous != null &&
                    row.id !in frame.changedRowIDs &&
                    row.id !in frame.insertedRowIDs &&
                    !row.isPending
            if (reusePrevious) {
                previous
            } else {
                toMessageItem(row)
            }
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
            // Text length is content-only while streaming; GrowBottom declares height growth.
            is SessionChatTimelineBlock.Text ->
                "t:${block.blockID}:${block.renderedMarkdown != null}"
            is SessionChatTimelineBlock.Attachment ->
                "a:${block.blockID}:${block.attachment.filename}"
            is SessionChatTimelineBlock.ToolCard ->
                "c:${block.blockID}:${block.title}:${block.subtitle}:${block.status}:" +
                    "${block.errorText}:${block.additions}:${block.deletions}"
            is SessionChatTimelineBlock.ToolGroup ->
                "g:${block.blockID}:${block.count}:${block.title}:${block.subtitle}:${block.status}"
        }

    private data class LayoutKey(
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
        val newestId = rows.lastOrNull()?.id

        val inserted = linkedSetOf<String>()
        val changed = linkedSetOf<String>()
        val layoutChanged = linkedSetOf<String>()
        val grewBottom = linkedSetOf<String>()
        val decorate = linkedSetOf<String>()

        for (row in rows) {
            val prior = previousById[row.id]
            when {
                prior == null -> inserted += row.id
                prior !== row -> {
                    changed += row.id
                    if (row.layoutRevision != prior.layoutRevision) {
                        layoutChanged += row.id
                        if (isMarkdownOnlyLayoutChange(prior, row)) {
                            decorate += row.id
                        }
                    } else if (
                        row.id == newestId &&
                        row.isStreaming &&
                        row.text.length > prior.text.length
                    ) {
                        grewBottom += row.id
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
            grewBottomRowIDs = grewBottom,
            decorateRowIDs = decorate,
        )
    }

    /**
     * Layout bump caused only by markdown presence/sourceHash while streaming flag and
     * non-markdown structure stay the same. Streaming end is Structure, not Decorate.
     */
    internal fun isMarkdownOnlyLayoutChange(
        prior: TimelineRow,
        row: TimelineRow,
    ): Boolean {
        if (prior.isStreaming != row.isStreaming) return false
        if (structureFingerprint(prior) != structureFingerprint(row)) return false
        return markdownFingerprint(prior) != markdownFingerprint(row)
    }

    private fun structureFingerprint(row: TimelineRow): String =
        buildString {
            append(row.blocks.size)
            append('\u0000')
            row.attachments.forEach { attachment ->
                append(attachment.id)
                append('\u0000')
                append(attachment.signature)
                append('\u0000')
            }
            row.blocks.forEach { block ->
                append(structureBlockFingerprint(block))
                append('|')
            }
        }

    private fun structureBlockFingerprint(block: SessionChatTimelineBlock): String =
        when (block) {
            is SessionChatTimelineBlock.Text -> "t:${block.blockID}"
            is SessionChatTimelineBlock.Attachment ->
                "a:${block.blockID}:${block.attachment.filename}"
            is SessionChatTimelineBlock.ToolCard ->
                "c:${block.blockID}:${block.title}:${block.subtitle}:${block.status}:" +
                    "${block.errorText}:${block.additions}:${block.deletions}"
            is SessionChatTimelineBlock.ToolGroup ->
                "g:${block.blockID}:${block.count}:${block.title}:${block.subtitle}:${block.status}"
        }

    private fun markdownFingerprint(row: TimelineRow): String =
        buildString {
            row.blocks.forEach { block ->
                if (block is SessionChatTimelineBlock.Text) {
                    append(block.blockID)
                    append(':')
                    append(block.renderedMarkdown != null)
                    append(':')
                    append(block.renderedMarkdown?.sourceHash ?: 0)
                    append('|')
                }
            }
        }

    private fun formatSentTime(epochMs: Long): String =
        Instant
            .ofEpochMilli(epochMs)
            .atZone(ZoneId.systemDefault())
            .format(sentTimeFormatter)
}
