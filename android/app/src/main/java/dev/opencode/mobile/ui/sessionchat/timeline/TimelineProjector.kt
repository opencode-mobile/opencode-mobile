package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.domain.message.ClientMessageId
import dev.opencode.mobile.domain.message.FollowUpDelivery
import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessagePart
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.text.TitleCaseIdentifier
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class TimelineRow(
    val id: String,
    val role: MessageRole,
    val text: String,
    val metadataLabel: String,
    val pendingDelivery: FollowUpDelivery? = null,
    val isPending: Boolean = false,
    val isStreaming: Boolean = false,
    val blocks: List<SessionChatTimelineBlock> = emptyList(),
    val attachments: List<SessionChatAttachmentItem> = emptyList(),
    val modelProviderId: String? = null,
    val modelId: String? = null,
    val contextInputTokens: Long? = null,
    val sentAtEpochMs: Long? = null,
    /** Set on a prompt steered into a running turn, pending or delivered. */
    val steer: SessionChatSteer? = null,
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
    /**
     * Server row id → list key for server echoes that replaced an optimistic send row.
     * The list keys the echo under the optimistic id, so its item slot is reused instead
     * of removed and inserted. Carried across frames while the row stays.
     */
    val listIdAliases: Map<String, String> = emptyMap(),
) {
    /** LazyColumn key for [rowId]. */
    fun listKey(rowId: String): String = listIdAliases[rowId] ?: rowId

    /** Row IDs whose apply can change list geometry (inserts ∪ layout-affecting restyles). */
    fun layoutAffectingRowIDs(): Set<String> = insertedRowIDs + layoutChangedRowIDs

    /**
     * Host auto-follow key for layout-affecting frames.
     * Content-only frames return `0` so the live path does not rediscover layout via scans.
     */
    fun layoutAffectingRevision(): Long = if (layoutAffectingRowIDs().isEmpty()) 0L else frameRevision

    /**
     * Armed auto-follow key for inserts / non-decorate layout changes.
     * Decorate-only frames return `0` so code-color / slash collapse paint does not re-scroll.
     */
    fun structureAffectingRevision(): Long {
        val structureIds = insertedRowIDs + (layoutChangedRowIDs - decorateRowIDs)
        return if (structureIds.isEmpty()) 0L else frameRevision
    }

    /** Armed-only pin key for streaming bottom growth (not unarmed restore). */
    fun growBottomRevision(): Long = if (grewBottomRowIDs.isEmpty()) 0L else frameRevision

    fun withRequired(isRequired: Boolean): TimelineFrame = copy(isRequired = isRequired)
}

/**
 * Explicit optimistic reconciliation inputs for the pure projector.
 * [SessionChatTimeline] owns lifecycle and supplies these; merge policy lives here.
 */
data class OptimisticInputs(
    val pendingRows: List<TimelineRow> = emptyList(),
    /** Server row ids loaded when the send began. None of them is the send's echo. */
    val baselineIds: Set<String> = emptySet(),
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
        val visibleRows = merged.rows.filterNot { isQueuedPrompt(it) || it.id in merged.hiddenEchoIds }
        val previousIds = previousById.keys
        val currentIds = visibleRows.mapTo(HashSet()) { it.id }
        // Alias only an echo that replaces its optimistic row in this frame. A server
        // row the list already shows under its own id keeps that key.
        val listIdAliases =
            previous?.listIdAliases.orEmpty().filterKeys { it in currentIds } +
                merged.echoes.filter { (serverId, pendingId) ->
                    pendingId in previousIds && serverId !in previousIds && serverId in currentIds
                }
        val rows =
            markSteers(
                rows = visibleRows,
                previousById = previousById,
                listIdAliases = listIdAliases,
                runIsActive = runIsActive(frame),
            )
        return diffFrame(
            frameRevision = frame.frameRevision,
            rows = rows,
            previous = previous,
        ).copy(listIdAliases = listIdAliases)
    }

    /** Project server bodies without hiding inbox rows. Used for send-time baselines. */
    fun projectServerRows(bodies: List<DisplayMessageBody>): List<TimelineRow> =
        bodies.mapNotNull { body -> projectRow(body, previous = null) }

    fun isHiddenPendingPrompt(item: SessionChatMessageItem): Boolean = item.role == MessageRole.User && item.pendingDelivery != null

    /**
     * Optimistic send rows the timeline shows: everything but a queued prompt, which only
     * the queued strip shows. A steer shows as a pending bubble.
     */
    fun displayedOptimisticSendItems(items: List<SessionChatMessageItem>): List<SessionChatMessageItem> =
        items.filterNot { it.role == MessageRole.User && it.pendingDelivery == FollowUpDelivery.Queue }

    fun shouldMarkTimelineReadyAfterOptimisticSend(
        displayedItems: List<SessionChatMessageItem>,
        hadPriorDisplayedRows: Boolean,
    ): Boolean = displayedItems.isNotEmpty() || hadPriorDisplayedRows

    /**
     * Pending rows that no new server echo accounts for yet. Same match
     * [mergeWithPending] and [SessionChatTimeline] reconciliation use.
     *
     * A shown optimistic row is not retired by a waiting steer echo. v2 enqueues every
     * prompt, idle sends included, as a steer delivered at the next step boundary;
     * retiring on that hidden copy left a frame with neither bubble. A queued echo still
     * retires it, since the pending strip owns a real queue.
     */
    fun unmatchedPending(
        pendingRows: List<TimelineRow>,
        serverRows: List<TimelineRow>,
        baselineIds: Set<String>,
    ): List<TimelineRow> =
        pendingRows.filter { pending ->
            serverRows.none { server ->
                retiresPending(server, pending) && isNewServerEchoOf(server, pending, baselineIds)
            }
        }

    private fun retiresPending(
        server: TimelineRow,
        pending: TimelineRow,
    ): Boolean = isHiddenPendingPrompt(pending) || server.pendingDelivery != FollowUpDelivery.Steer

    /**
     * A pending row whose send carried a [ClientMessageId] is echoed only by the user row
     * the server created under that id, whatever its text or filenames.
     *
     * A v2 command takes no id, and its echo is the expanded template, so the first user
     * row that was not loaded when the send began stands for it.
     */
    fun isNewServerEchoOf(
        server: TimelineRow,
        pending: TimelineRow,
        baselineIds: Set<String>,
    ): Boolean {
        if (server.role != MessageRole.User) return false
        if (ClientMessageId.isClientId(pending.id)) return server.id == pending.id
        return server.id !in baselineIds
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
                pendingDelivery = item.pendingDelivery,
                isPending = item.isPending,
                isStreaming = item.isStreaming,
                blocks = item.blocks,
                attachments = item.attachments,
                modelProviderId = item.modelProviderId,
                modelId = item.modelId,
                contextInputTokens = item.contextInputTokens,
                sentAtEpochMs = item.sentAtEpochMs,
                steer = item.steer,
                contentRevision = contentRevision,
                layoutRevision = 0L,
            )
        return finalizeRow(draft, previous)
    }

    fun toMessageItem(row: TimelineRow): SessionChatMessageItem {
        val soleDecoratedText =
            row.blocks
                .filter { it.markdownText != null }
                .singleOrNull()
                ?.decoratedMarkdown
        return SessionChatMessageItem(
            id = row.id,
            role = row.role,
            text = row.text,
            metadataLabel = row.metadataLabel,
            pendingDelivery = row.pendingDelivery,
            isPending = row.isPending,
            isStreaming = row.isStreaming,
            renderedMarkdown = soleDecoratedText,
            blocks = row.blocks,
            attachments = row.attachments,
            modelProviderId = row.modelProviderId,
            modelId = row.modelId,
            contextInputTokens = row.contextInputTokens,
            sentAtEpochMs = row.sentAtEpochMs,
            steer = row.steer,
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
        return listOfNotNull(
            message.mode?.let(TitleCaseIdentifier::displayName),
            message.model?.let(TitleCaseIdentifier::displayName),
            message.variant?.let(TitleCaseIdentifier::displayName),
            timeLabel,
        ).joinToString(" · ")
    }

    fun isStreaming(message: Message): Boolean = message.parts.any { part -> part is MessagePart.Text && part.isStreaming }

    private fun projectRow(
        body: DisplayMessageBody,
        previous: TimelineRow?,
    ): TimelineRow? {
        val message = body.message
        if (body.blocks.isEmpty()) return null
        val attachments =
            body.blocks.mapNotNull { (it as? SessionChatTimelineBlock.Attachment)?.attachment }
        // Prefer sole narration copy so slash-command collapse is what chat shows
        // and copies, not the server-expanded template still on Message.text.
        // A chip-merged run spans messages; Message.text only covers the first.
        val displayText =
            run {
                val texts = body.blocks.mapNotNull { it.markdownText }
                if (texts.size == 1) {
                    texts[0]
                } else {
                    val paragraphTexts =
                        body.blocks.mapNotNull { block ->
                            (block as? SessionChatTimelineBlock.StepParagraph)?.text
                        }
                    if (paragraphTexts.size > 1) paragraphTexts.joinToString("\n\n") else message.text
                }
            }
        val draft =
            TimelineRow(
                id = message.id,
                role = message.role,
                text = displayText,
                metadataLabel = metadataLabel(message),
                pendingDelivery = message.followUpDelivery.takeIf { message.isWaitingForPickup },
                isPending = false,
                isStreaming = isStreaming(message),
                blocks = body.blocks,
                attachments = attachments,
                modelProviderId = message.modelProviderId,
                modelId = message.modelId,
                contextInputTokens = message.contextInputTokens,
                sentAtEpochMs = message.sentAtEpochMs,
                // Carried so an unchanged row stays the same instance; [markSteers] decides.
                steer = previous?.steer,
                contentRevision = ContentRevision.of(displayText),
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
            left.pendingDelivery == right.pendingDelivery &&
            left.isPending == right.isPending &&
            left.isStreaming == right.isStreaming &&
            left.blocks == right.blocks &&
            left.attachments == right.attachments &&
            left.modelProviderId == right.modelProviderId &&
            left.modelId == right.modelId &&
            left.contextInputTokens == right.contextInputTokens &&
            left.sentAtEpochMs == right.sentAtEpochMs &&
            left.steer == right.steer &&
            left.contentRevision == right.contentRevision

    private fun layoutKey(row: TimelineRow): LayoutKey =
        LayoutKey(
            blockCount = row.blocks.size,
            hasMarkdown = row.blocks.any { it.decoratedMarkdown != null },
            isStreaming = row.isStreaming,
            hasMetadata = row.metadataLabel.isNotEmpty(),
            hasWaiting = row.pendingDelivery != null,
            steer = row.steer,
            attachmentSignatures = row.attachments.map { "${it.id}\u0000${it.signature}" },
            blockLayoutFingerprints = row.blocks.map(::blockLayoutFingerprint),
        )

    private fun blockLayoutFingerprint(block: SessionChatTimelineBlock): String =
        when (block) {
            // Presence only. Streaming text/markdown growth is GrowBottom or Content.
            is SessionChatTimelineBlock.Text ->
                "t:${block.blockID}:${block.renderedMarkdown != null}"
            is SessionChatTimelineBlock.Attachment ->
                "a:${block.blockID}:${block.attachment.filename}"
            is SessionChatTimelineBlock.ToolCard ->
                "c:${block.blockID}:${block.errorText != null}:${block.additions}:${block.deletions}"
            is SessionChatTimelineBlock.ToolGroup ->
                "g:${block.blockID}:${block.count}:${block.errorText != null}"
            is SessionChatTimelineBlock.ChildSessionRow ->
                "r:${block.blockID}:${block.childSessionId != null}"
            is SessionChatTimelineBlock.Reasoning ->
                "n:${block.blockID}"
            is SessionChatTimelineBlock.StepParagraph ->
                "s:${block.blockID}:${block.renderedMarkdown != null}:${stepParagraphChipFingerprint(block)}"
            is SessionChatTimelineBlock.StepRail ->
                "sr:${block.blockID}:${stepRailFingerprint(block)}:${block.thinking}"
            is SessionChatTimelineBlock.ActivityCard ->
                "ac:${block.blockID}:${activityCardFingerprint(block)}:${block.isLive}:${block.thinking}"
            is SessionChatTimelineBlock.StopNote -> "x:${block.blockID}:${block.reason}"
            is SessionChatTimelineBlock.FailureLine ->
                "f:${block.blockID}:${block.providerBody != null}:${block.isNeutral}:${block.action != null}:" +
                    "${block.retryAtEpochMs != null}"
            is SessionChatTimelineBlock.Notice -> "o:${block.blockID}:${block.notice.hashCode()}"
        }

    /** Chip status / counts change chip width and error captions, so they are layout. */
    private fun stepParagraphChipFingerprint(block: SessionChatTimelineBlock.StepParagraph): String =
        block.chips.joinToString(",") { chip ->
            "${chip.status.ordinal}:${chip.additions}:${chip.deletions}:${chip.trailingText != null}"
        } + ":${block.errorLines.size}"

    /** Step count, status, branch and detail presence change rail rows and their height. */
    private fun stepRailFingerprint(block: SessionChatTimelineBlock.StepRail): String =
        block.steps.joinToString(",") { step ->
            "${step.kind.ordinal}:${step.status.ordinal}:${step.isBranch}:${step.detail != null}"
        }

    /** Step count and status change the card's running lines, footer and settled line. */
    private fun activityCardFingerprint(block: SessionChatTimelineBlock.ActivityCard): String =
        block.steps.joinToString(",") { step -> "${step.kind.ordinal}:${step.status.ordinal}" }

    private data class LayoutKey(
        val blockCount: Int,
        val hasMarkdown: Boolean,
        val isStreaming: Boolean,
        val hasMetadata: Boolean,
        val hasWaiting: Boolean,
        val steer: SessionChatSteer?,
        val attachmentSignatures: List<String>,
        val blockLayoutFingerprints: List<String>,
    )

    /**
     * A user row with a pending delivery (steer or queue), including optimistic
     * `isPending` rows. Any server echo retires such an optimistic row.
     */
    fun isHiddenPendingPrompt(row: TimelineRow): Boolean = row.role == MessageRole.User && row.pendingDelivery != null

    /** A queued prompt: the queued strip is its only surface until it is delivered. */
    fun isQueuedPrompt(row: TimelineRow): Boolean = row.role == MessageRole.User && row.pendingDelivery == FollowUpDelivery.Queue

    /**
     * The newest assistant message has not finished, so the session is mid-run.
     * v2 also enqueues an idle prompt as a steer; this keeps that one unlabelled.
     */
    fun runIsActive(frame: DisplayFrame): Boolean {
        val assistant = frame.messages.lastOrNull { it.message.role == MessageRole.Assistant }?.message ?: return false
        return assistant.completedAtEpochMs == null && assistant.failure == null
    }

    /**
     * Marks prompts steered into a running turn. A waiting steer is pending; a row that
     * was a steer in the previous frame keeps its mark once delivered, so the server's
     * copy replaces the pending bubble in place and keeps the label. Consecutive steers
     * share one label, on the first.
     */
    fun markSteers(
        rows: List<TimelineRow>,
        previousById: Map<String, TimelineRow>,
        listIdAliases: Map<String, String>,
        runIsActive: Boolean,
    ): List<TimelineRow> {
        var previousWasSteer = false
        return rows.map { row ->
            // A notice line (agent or model change) between steers does not split them.
            if (row.role == MessageRole.Notice) return@map row
            val isUser = row.role == MessageRole.User
            val isPending = isUser && row.pendingDelivery == FollowUpDelivery.Steer
            val wasSteer =
                previousById[row.id]?.steer != null ||
                    listIdAliases[row.id]?.let { previousById[it]?.steer } != null
            // `isPending` optimistic rows only carry a steer delivery for a busy send.
            val isSteer = isUser && (wasSteer || (isPending && (row.isPending || runIsActive)))
            val steer = if (isSteer) SessionChatSteer(isPending = isPending, showsLabel = !previousWasSteer) else null
            previousWasSteer = isSteer
            if (row.steer == steer) row else row.copy(steer = steer, layoutRevision = row.layoutRevision + 1)
        }
    }

    /** Marks optimistic send items placed after [after]'s rows, as [markSteers] would. */
    fun markOptimisticSteers(
        items: List<SessionChatMessageItem>,
        after: TimelineFrame?,
    ): List<SessionChatMessageItem> {
        if (items.isEmpty()) return items
        val previousRows = after?.rows.orEmpty()
        val marked =
            markSteers(
                rows = previousRows + items.map(::rowFromMessageItem),
                previousById = previousRows.associateBy { it.id },
                listIdAliases = after?.listIdAliases.orEmpty(),
                runIsActive = false,
            ).takeLast(items.size)
        return items.zip(marked) { item, row -> if (item.steer == row.steer) item else item.copy(steer = row.steer) }
    }

    /**
     * Merged rows; for each dropped optimistic row matched to its echo, `serverId → pendingId`;
     * and waiting-steer echoes of a still-shown optimistic row, hidden so the prompt never
     * shows twice.
     */
    private data class PendingMerge(
        val rows: List<TimelineRow>,
        val echoes: Map<String, String> = emptyMap(),
        val hiddenEchoIds: Set<String> = emptySet(),
    )

    private fun mergeWithPending(
        serverRows: List<TimelineRow>,
        optimisticInputs: OptimisticInputs,
    ): PendingMerge {
        val pendingRows = optimisticInputs.pendingRows
        if (pendingRows.isEmpty()) return PendingMerge(serverRows)
        val baselineIds = optimisticInputs.baselineIds
        val pendingToKeep =
            unmatchedPending(
                pendingRows = pendingRows,
                serverRows = serverRows,
                baselineIds = baselineIds,
            )
        val dropped = pendingRows.filter { pending -> pendingToKeep.none { it.id == pending.id } }
        val withMetadata = applyPendingMetadata(serverRows, dropped, baselineIds)
        val keptShown = pendingToKeep.filterNot(::isHiddenPendingPrompt)
        val hiddenEchoIds =
            serverRows
                .filter { server ->
                    server.pendingDelivery == FollowUpDelivery.Steer &&
                        keptShown.any { isNewServerEchoOf(server, it, baselineIds) }
                }.mapTo(HashSet()) { it.id }
        val keptIds = pendingToKeep.mapTo(HashSet()) { it.id }
        // A same-id waiting echo is dropped here so the optimistic row stays.
        return PendingMerge(
            rows = splicePending(withMetadata.rows.filterNot { it.id in hiddenEchoIds }, pendingToKeep),
            echoes = withMetadata.echoes,
            hiddenEchoIds = hiddenEchoIds - keptIds,
        )
    }

    private fun splicePending(
        serverRows: List<TimelineRow>,
        pendingToKeep: List<TimelineRow>,
    ): List<TimelineRow> {
        if (pendingToKeep.isEmpty()) return serverRows
        val index = serverRows.indexOfFirst { it.role == MessageRole.Assistant && it.isStreaming }
        if (index >= 0) {
            val precededByUser = index > 0 && serverRows[index - 1].role == MessageRole.User
            if (!precededByUser) {
                return serverRows.take(index) + pendingToKeep + serverRows.drop(index)
            }
        }
        return serverRows + pendingToKeep
    }

    private fun applyPendingMetadata(
        serverRows: List<TimelineRow>,
        dropped: List<TimelineRow>,
        baselineIds: Set<String>,
    ): PendingMerge {
        if (dropped.isEmpty()) return PendingMerge(serverRows)
        val echoes = mutableMapOf<String, String>()
        val rows =
            serverRows.map { row ->
                val pending =
                    dropped.firstOrNull { candidate -> isNewServerEchoOf(row, candidate, baselineIds) }
                        ?: return@map row
                if (!isHiddenPendingPrompt(row) && pending.id !in echoes.values) {
                    echoes[row.id] = pending.id
                }
                val metadata =
                    if (row.metadataLabel.isEmpty() && pending.metadataLabel.isNotEmpty()) {
                        pending.metadataLabel
                    } else {
                        row.metadataLabel
                    }
                val copyAttachments = row.attachments.isEmpty() && pending.attachments.isNotEmpty()
                val attachments = if (copyAttachments) pending.attachments else row.attachments
                val blocks =
                    if (copyAttachments) {
                        row.blocks + pending.blocks.filterIsInstance<SessionChatTimelineBlock.Attachment>()
                    } else {
                        row.blocks
                    }
                if (metadata == row.metadataLabel && attachments === row.attachments && blocks === row.blocks) {
                    return@map row
                }
                row.copy(
                    metadataLabel = metadata,
                    attachments = attachments,
                    blocks = blocks,
                    layoutRevision = row.layoutRevision + 1,
                )
            }
        return PendingMerge(rows, echoes)
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
     * Layout bump caused only by markdown presence/sourceHash/renderTreeVersion
     * while streaming flag and non-markdown structure stay the same. Streaming
     * end is Structure, not Decorate.
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
                "c:${block.blockID}:${block.errorText != null}:${block.additions}:${block.deletions}"
            is SessionChatTimelineBlock.ToolGroup ->
                "g:${block.blockID}:${block.count}:${block.errorText != null}"
            is SessionChatTimelineBlock.ChildSessionRow ->
                "r:${block.blockID}:${block.childSessionId != null}"
            is SessionChatTimelineBlock.Reasoning ->
                "n:${block.blockID}"
            is SessionChatTimelineBlock.StepParagraph ->
                "s:${block.blockID}:${stepParagraphChipFingerprint(block)}"
            is SessionChatTimelineBlock.StepRail -> "sr:${block.blockID}:${stepRailFingerprint(block)}"
            is SessionChatTimelineBlock.ActivityCard -> "ac:${block.blockID}:${activityCardFingerprint(block)}"
            is SessionChatTimelineBlock.StopNote -> "x:${block.blockID}:${block.reason}"
            is SessionChatTimelineBlock.FailureLine -> "f:${block.blockID}:${block.providerBody != null}"
            is SessionChatTimelineBlock.Notice -> "o:${block.blockID}:${block.notice.hashCode()}"
        }

    private fun markdownFingerprint(row: TimelineRow): String =
        buildString {
            row.blocks.forEach { block ->
                if (block.markdownText != null) {
                    val document = block.decoratedMarkdown
                    append(block.blockID)
                    append(':')
                    append(document != null)
                    append(':')
                    append(document?.sourceHash ?: 0)
                    append(':')
                    append(document?.renderTreeVersion ?: 0)
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
