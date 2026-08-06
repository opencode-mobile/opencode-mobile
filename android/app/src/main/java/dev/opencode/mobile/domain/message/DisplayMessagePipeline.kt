package dev.opencode.mobile.domain.message

import dev.opencode.mobile.domain.preferences.MarkdownHighlightPreferences
import dev.opencode.mobile.markdown.OpenCodeMarkdownDetector
import dev.opencode.mobile.markdown.stableMarkdownSourceHash
import dev.opencode.mobile.ui.sessionchat.PresentationBlockId
import dev.opencode.mobile.ui.sessionchat.SessionChatTimelineBlock
import dev.opencode.mobile.ui.sessionchat.SessionChatTimelineBlocks
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeSource

/** Quiet window before publishing the latest streaming snapshot. */
internal const val STREAMING_PREPARE_DEBOUNCE_MS = 16L

/** Cap how long dense SSE bursts can delay a UI publish. Keep flat so long replies
 * do not intentionally slow down as they grow. */
internal const val STREAMING_PREPARE_MAX_BATCH_MS = 48L

class DisplayMessagePipeline(
    private val snapshotSource: DisplayMessageSnapshotSource,
    private val contentPreparer: DisplayMessagePreparing,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val delayMillis: suspend (Long) -> Unit = { delay(it) },
    private val messageSync: MessageSync? = null,
) {
    constructor(
        messageSync: MessageSync,
        contentPreparer: MessageContentPreparer,
        timeSource: TimeSource = TimeSource.Monotonic,
        delayMillis: suspend (Long) -> Unit = { delay(it) },
    ) : this(
        snapshotSource = messageSync.asDisplayMessageSnapshotSource(),
        contentPreparer = contentPreparer.asDisplayMessagePreparing(),
        timeSource = timeSource,
        delayMillis = delayMillis,
        messageSync = messageSync,
    )

    fun observeDisplayMessages(
        sessionId: String,
        directory: String?,
    ): Flow<DisplayMessageListUpdate> =
        channelFlow {
            var previousById = emptyMap<String, DisplayMessage>()
            var pendingMessages: List<Message>? = null
            var lastPublishedMessages: List<Message>? = null
            var currentGeneration = 0L
            var debounceJob: Job? = null
            var prepareJob: Job? = null
            var decorationJob: Job? = null
            var enrichmentJob: Job? = null
            var lastPublishedAt: kotlin.time.TimeMark? = null
            // True while coalescing a live streaming reply (including in-flight prepare).
            var isStreamingSession = false
            var sourceIsFromCache = false
            val decorationBacklog = DecorationBacklog()

            suspend fun emitDisplayMessages(messages: List<DisplayMessage>) {
                send(
                    DisplayMessageListUpdate.Messages(
                        messages = messages,
                        isFromCache = sourceIsFromCache,
                    ),
                )
            }

            fun invalidateInFlightWork() {
                debounceJob?.cancel()
                debounceJob = null
                prepareJob?.cancel()
                prepareJob = null
                decorationJob?.cancel()
                decorationJob = null
                enrichmentJob?.cancel()
                enrichmentJob = null
                decorationBacklog.clear()
            }

            fun nextGeneration(): Long {
                currentGeneration += 1
                return currentGeneration
            }

            fun isCurrent(generation: Long): Boolean = generation == currentGeneration

            fun commitPrepared(
                generation: Long,
                prepared: List<DisplayMessage>,
                mode: MessagePreparationMode,
            ): Boolean {
                if (!isCurrent(generation)) return false
                if (mode.replacesPreparedCache) {
                    previousById = prepared.associateBy { it.id }
                } else {
                    for (display in prepared) {
                        if (display.renderedMarkdown != null) {
                            previousById = previousById + (display.id to display)
                        }
                    }
                }
                lastPublishedAt = timeSource.markNow()
                return true
            }

            suspend fun buildStructureFirstStreamingFrame(messages: List<Message>): List<DisplayMessage> {
                val out = ArrayList<DisplayMessage>(messages.size)
                for (message in messages) {
                    val previous = previousById[message.id]
                    if (!messageHasStreamingText(message)) {
                        out +=
                            when {
                                previous != null &&
                                    previous.text == message.text &&
                                    previous.renderedMarkdown != null ->
                                    // Preserve upgrade metadata so concurrent streams do not
                                    // clear HistoryPlainFirst / enrichment pending flags.
                                    previous.copy(message = message)
                                else -> DisplayMessage(message = message, renderedMarkdown = null)
                            }
                        continue
                    }
                    val target = resolveStreamingDecorationTarget(message)
                    val structure =
                        if (target != null && OpenCodeMarkdownDetector.shouldRender(target.plain)) {
                            contentPreparer.prepareStructureOnlyTextBlock(
                                blockID = target.blockID,
                                plain = target.plain,
                                sessionKey = sessionId,
                            )
                        } else {
                            null
                        }
                    if (structure != null && target != null) {
                        BlockDecorationCache.put(
                            sessionKey = sessionId,
                            blockID = target.blockID,
                            textHash = target.textHash,
                            textLength = target.textLength,
                            isStreaming = true,
                            document = structure,
                        )
                    }
                    out +=
                        DisplayMessage(
                            message = message,
                            renderedMarkdown = structure.takeIf { messageHasSoleTextBlock(message) },
                        )
                }
                return out
            }

            fun decorationIdentityMatches(job: DecorationJob): Boolean {
                val messages = lastPublishedMessages ?: return false
                return messages.any { message ->
                    val target = resolveStreamingDecorationTarget(message) ?: return@any false
                    target.blockID == job.blockID &&
                        target.textHash == job.textHash &&
                        target.textLength == job.textLength &&
                        target.isStreaming == job.isStreaming
                }
            }

            fun drainDecorationBacklog() {
                if (decorationJob?.isActive == true) return
                decorationJob =
                    launch {
                        while (isActive) {
                            val job = decorationBacklog.poll() ?: break
                            if (!isCurrent(job.generation) || !decorationIdentityMatches(job)) {
                                continue
                            }
                            val targetMessage =
                                job.messages.firstOrNull { message ->
                                    resolveStreamingDecorationTarget(message)?.blockID == job.blockID
                                } ?: continue
                            val target =
                                resolveStreamingDecorationTarget(targetMessage) ?: continue
                            val document =
                                try {
                                    contentPreparer.prepareTextBlock(
                                        blockID = job.blockID,
                                        plain = target.plain,
                                        isStreaming = job.isStreaming,
                                        sessionKey = job.sessionKey,
                                        mode = MessagePreparationMode.Streaming,
                                    )
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    // Keep current plain text; disposable decoration may retry later.
                                    continue
                                }
                            if (document == null) continue
                            if (!isActive || !isCurrent(job.generation)) continue
                            if (!decorationIdentityMatches(job)) continue
                            // Generation + identity reject completed before cache mutation.
                            BlockDecorationCache.put(
                                sessionKey = job.sessionKey,
                                blockID = job.blockID,
                                textHash = job.textHash,
                                textLength = job.textLength,
                                isStreaming = job.isStreaming,
                                document = document,
                            )
                            val soleTextBridge = messageHasSoleTextBlock(targetMessage)
                            var changed = false
                            val merged =
                                job.messages.map { message ->
                                    val previous = previousById[message.id]
                                    when {
                                        message.id == targetMessage.id &&
                                            (previous == null || previous.text == message.text) -> {
                                            changed = true
                                            DisplayMessage(
                                                message = message,
                                                renderedMarkdown =
                                                    document.takeIf { soleTextBridge },
                                            )
                                        }
                                        previous != null && previous.text == message.text -> previous
                                        else -> DisplayMessage(message = message, renderedMarkdown = null)
                                    }
                                }
                            if (!changed) continue
                            if (!commitPrepared(job.generation, merged, MessagePreparationMode.Streaming)) {
                                continue
                            }
                            emitDisplayMessages(merged)
                        }
                        decorationJob = null
                    }
            }

            fun scheduleStreamingDecoration(
                generation: Long,
                messages: List<Message>,
            ) {
                // TextMate on: keep structure-only until Final colors. Skip
                // streaming semantic decoration so colors do not flash early.
                if (MarkdownHighlightPreferences.textMateEnabled) return
                val streamingMessage =
                    messages.firstOrNull(::messageHasStreamingText) ?: return
                val target = resolveStreamingDecorationTarget(streamingMessage) ?: return
                if (!OpenCodeMarkdownDetector.shouldRender(target.plain)) return
                val job =
                    DecorationJob(
                        generation = generation,
                        sessionKey = sessionId,
                        blockID = target.blockID,
                        textHash = target.textHash,
                        textLength = target.textLength,
                        isStreaming = true,
                        messages = messages,
                        priority = 2,
                    )
                if (!decorationBacklog.replaceStreamingJob(job)) return
                drainDecorationBacklog()
            }

            suspend fun publishPlainStreamingFrame(
                generation: Long,
                messages: List<Message>,
            ) {
                if (!isCurrent(generation)) return
                val frame = buildStructureFirstStreamingFrame(messages)
                previousById = frame.associateBy { it.id }
                lastPublishedMessages = messages
                lastPublishedAt = timeSource.markNow()
                emitDisplayMessages(frame)
                scheduleStreamingDecoration(generation, messages)
            }

            fun scheduleStreamingPublish(generation: Long) {
                debounceJob?.cancel()
                debounceJob =
                    launch {
                        delayMillis(STREAMING_PREPARE_DEBOUNCE_MS)
                        if (!isActive || !isCurrent(generation)) return@launch
                        val latest = pendingMessages ?: return@launch
                        pendingMessages = null
                        publishPlainStreamingFrame(generation, latest)
                        // Catch up if denser tokens arrived during this plain publish.
                        val catchUp = pendingMessages
                        if (catchUp != null && hasStreamingDelta(catchUp)) {
                            val dueForMaxBatch =
                                lastPublishedAt
                                    ?.elapsedNow()
                                    ?.inWholeMilliseconds
                                    ?.let { it >= STREAMING_PREPARE_MAX_BATCH_MS }
                                    ?: true
                            if (dueForMaxBatch) {
                                pendingMessages = null
                                publishPlainStreamingFrame(currentGeneration, catchUp)
                            } else {
                                scheduleStreamingPublish(currentGeneration)
                            }
                        }
                    }
            }

            fun scheduleFinalization(
                generation: Long,
                messages: List<Message>,
            ) {
                prepareJob?.cancel()
                prepareJob =
                    launch {
                        val snapshot = previousById
                        val placeholder =
                            contentPreparer.prepareMessages(
                                messages,
                                snapshot,
                                MessagePreparationMode.FinalPlaceholder,
                            )
                        if (!isActive || !isCurrent(generation)) return@launch
                        if (!commitPrepared(generation, placeholder, MessagePreparationMode.FinalPlaceholder)) {
                            return@launch
                        }
                        lastPublishedMessages = messages
                        emitDisplayMessages(placeholder)

                        val finalized =
                            contentPreparer.prepareMessages(
                                messages,
                                // Use pre-placeholder snapshot so Final does not
                                // settle-reuse streaming-quality spans via the
                                // placeholder DisplayMessage (isStreaming=false).
                                snapshot,
                                MessagePreparationMode.Final,
                            )
                        if (!isActive || !isCurrent(generation)) return@launch
                        if (!commitPrepared(generation, finalized, MessagePreparationMode.Final)) return@launch
                        lastPublishedMessages = messages
                        emitDisplayMessages(finalized)
                    }
            }

            fun enrichHistoryInBackground(
                generation: Long,
                messages: List<Message>,
            ) {
                enrichmentJob?.cancel()
                enrichmentJob =
                    launch {
                        val holdReady = MessageContentPreparer.HOLD_READY_UNTIL_NEWEST_MARKDOWN
                        // Spinner is already up when holding Ready — start TextMate ASAP.
                        if (!holdReady) {
                            delayMillis(48)
                        }
                        if (!isActive || !isCurrent(generation)) return@launch

                        val byId =
                            messages
                                .associate { message ->
                                    message.id to
                                        (
                                            previousById[message.id]?.takeIf { it.text == message.text }
                                                ?: DisplayMessage(message = message, renderedMarkdown = null)
                                        )
                                }.toMutableMap()
                        var enrichedCount = 0
                        var viewportEnrichedCount = 0
                        var viewportCharactersRemaining =
                            MessageContentPreparer.HISTORY_ENRICH_VIEWPORT_CHARACTER_BUDGET
                        var publishedViewport = false

                        suspend fun publishSnapshot() {
                            if (!isCurrent(generation)) return
                            previousById = byId.toMap()
                            lastPublishedMessages = messages
                            lastPublishedAt = timeSource.markNow()
                            emitDisplayMessages(messages.map { byId.getValue(it.id) })
                        }

                        // Newest first: open settles near the bottom, so enrich what the
                        // user is looking at before older off-screen history.
                        for (message in messages.asReversed()) {
                            ensureActive()
                            if (!isCurrent(generation)) return@launch
                            if (!OpenCodeMarkdownDetector.shouldRender(message.text)) continue
                            val cached = byId[message.id]
                            if (cached?.renderedMarkdown != null &&
                                cached.text == message.text &&
                                !cached.awaitingTextMateUpgrade
                            ) {
                                continue
                            }
                            val prepared =
                                contentPreparer.prepareMessage(
                                    message,
                                    previous = byId[message.id],
                                    mode = MessagePreparationMode.HistoryEnrichment,
                                )
                            if (!isActive || !isCurrent(generation)) return@launch
                            byId[message.id] = prepared
                            previousById = previousById + (message.id to prepared)
                            enrichedCount += 1

                            if (!publishedViewport) {
                                viewportEnrichedCount += 1
                                viewportCharactersRemaining -= message.text.length
                                val viewportReady =
                                    holdReady ||
                                        viewportEnrichedCount >=
                                        MessageContentPreparer.HISTORY_ENRICH_VIEWPORT_MESSAGE_COUNT ||
                                        viewportCharactersRemaining <= 0
                                if (viewportReady) {
                                    publishSnapshot()
                                    publishedViewport = true
                                }
                            }
                            // Cooperative cancel point while preparing the remaining batch.
                            yield()
                        }
                        if (!isActive || !isCurrent(generation)) return@launch
                        // Escape hatch: hold path must not leave the UI on Loading forever.
                        if (holdReady && !publishedViewport) {
                            publishSnapshot()
                            return@launch
                        }
                        if (enrichedCount == 0) return@launch
                        if (!publishedViewport || enrichedCount > viewportEnrichedCount) {
                            publishSnapshot()
                        }
                    }
            }

            fun schedulePrepare(
                generation: Long,
                messages: List<Message>,
                mode: MessagePreparationMode,
                onCommitted: (() -> Unit)? = null,
            ) {
                prepareJob?.cancel()
                prepareJob =
                    launch {
                        val snapshot = previousById
                        val prepared =
                            contentPreparer.prepareMessages(
                                messages,
                                snapshot,
                                mode,
                            )
                        if (!isActive || !isCurrent(generation)) return@launch
                        if (!commitPrepared(generation, prepared, mode)) return@launch
                        lastPublishedMessages = messages
                        // Format-first history is publishable; do not hold Ready on
                        // unprocessed plain while TextMate enrichment catches up.
                        emitDisplayMessages(prepared)
                        onCommitted?.invoke()
                    }
            }

            /**
             * HistoryPlainFirst newest-first: format the bottom message first and
             * publish immediately, then fill the rest of history.
             */
            fun schedulePlainFirstNewestFirst(
                generation: Long,
                messages: List<Message>,
                onCommitted: (() -> Unit)? = null,
            ) {
                prepareJob?.cancel()
                prepareJob =
                    launch {
                        val byId =
                            messages
                                .associate { message ->
                                    val previous = previousById[message.id]?.takeIf { it.text == message.text }
                                    message.id to (
                                        previous
                                            ?: DisplayMessage(message = message, renderedMarkdown = null)
                                    )
                                }.toMutableMap()

                        suspend fun publish(label: String) {
                            if (!isCurrent(generation)) return
                            val frame = messages.map { byId.getValue(it.id) }
                            if (!commitPrepared(generation, frame, MessagePreparationMode.HistoryPlainFirst)) {
                                return
                            }
                            lastPublishedMessages = messages
                            lastPublishedAt = timeSource.markNow()
                            emitDisplayMessages(frame)
                        }

                        // Phase 1: newest markdown message only — what the open
                        // viewport shows — then publish before older history.
                        val newestMarkdown =
                            messages.asReversed().firstOrNull {
                                OpenCodeMarkdownDetector.shouldRender(it.text)
                            }
                        if (newestMarkdown != null) {
                            val prepared =
                                contentPreparer.prepareMessage(
                                    message = newestMarkdown,
                                    previous = byId[newestMarkdown.id],
                                    mode = MessagePreparationMode.HistoryPlainFirst,
                                )
                            if (!isActive || !isCurrent(generation)) return@launch
                            byId[newestMarkdown.id] = prepared
                            previousById = previousById + (newestMarkdown.id to prepared)
                            publish("plain-first-newest")
                        }

                        // Phase 2: remaining HistoryPlainFirst (reuses newest).
                        val phase2 =
                            contentPreparer.prepareMessages(
                                messages,
                                byId,
                                MessagePreparationMode.HistoryPlainFirst,
                            )
                        if (!isActive || !isCurrent(generation)) return@launch
                        for (display in phase2) {
                            byId[display.id] = display
                        }
                        previousById = byId.toMap()
                        publish("plain-first-rest")
                        onCommitted?.invoke()
                    }
            }

            try {
                snapshotSource.observeMessages(sessionId, directory).collect { update ->
                    when (update) {
                        is MessageListUpdate.LoadFailed -> {
                            nextGeneration()
                            invalidateInFlightWork()
                            pendingMessages = null
                            lastPublishedMessages = null
                            isStreamingSession = false
                            BlockDecorationCache.clear()
                            send(DisplayMessageListUpdate.LoadFailed(update.error))
                        }
                        is MessageListUpdate.Messages -> {
                            sourceIsFromCache = update.isFromCache
                            if (hasStreamingDelta(update.messages)) {
                                // Coalesce streaming tokens into pendingMessages. Do not bump
                                // generation on every delta — decoration stays generation-gated
                                // and rejects on text-identity mismatch within the session.
                                if (!isStreamingSession) {
                                    isStreamingSession = true
                                    nextGeneration()
                                    invalidateInFlightWork()
                                } else {
                                    enrichmentJob?.cancel()
                                    enrichmentJob = null
                                    debounceJob?.cancel()
                                    debounceJob = null
                                }
                                pendingMessages = update.messages
                                val dueForMaxBatch =
                                    lastPublishedAt
                                        ?.elapsedNow()
                                        ?.inWholeMilliseconds
                                        ?.let { it >= STREAMING_PREPARE_MAX_BATCH_MS }
                                        ?: true
                                if (dueForMaxBatch) {
                                    debounceJob = null
                                    val latest = pendingMessages ?: update.messages
                                    pendingMessages = null
                                    publishPlainStreamingFrame(currentGeneration, latest)
                                } else {
                                    scheduleStreamingPublish(currentGeneration)
                                }
                            } else {
                                val generation = nextGeneration()
                                invalidateInFlightWork()
                                pendingMessages = null
                                isStreamingSession = false
                                val hadStreamingPrevious = previousById.values.any { it.isStreaming }
                                if (hadStreamingPrevious) {
                                    scheduleFinalization(generation, update.messages)
                                } else if (sourceIsFromCache ||
                                    shouldPlainFirstHistoryPublish(update.messages, previousById)
                                ) {
                                    // Newest-first HistoryPlainFirst: format the open
                                    // viewport (last message) before older history.
                                    // Skip null-markdown flash. REST fingerprint-unchanged
                                    // keeps TextMate enrich from being cancelled.
                                    schedulePlainFirstNewestFirst(
                                        generation = generation,
                                        messages = update.messages,
                                        onCommitted = {
                                            if (isCurrent(generation)) {
                                                enrichHistoryInBackground(generation, update.messages)
                                            }
                                        },
                                    )
                                } else {
                                    schedulePrepare(generation, update.messages, MessagePreparationMode.Final)
                                }
                            }
                        }
                    }
                }
            } finally {
                // Teardown cancels and drops unpublished work. No flush contract.
                invalidateInFlightWork()
                pendingMessages = null
            }
        }

    suspend fun refreshDisplayMessages(
        sessionId: String,
        directory: String?,
    ): List<DisplayMessage> {
        val sync =
            messageSync
                ?: error("refreshDisplayMessages requires a MessageSync-backed pipeline")
        val messages = sync.refreshMessages(sessionId, directory)
        return contentPreparer.prepareMessages(messages, mode = MessagePreparationMode.Final)
    }

    private fun hasStreamingDelta(messages: List<Message>): Boolean =
        messages.any(::messageHasStreamingText)
}

private fun messageHasStreamingText(message: Message): Boolean =
    message.parts.any { part -> part is MessagePart.Text && part.isStreaming }

internal data class StreamingDecorationTarget(
    val blockID: String,
    val plain: String,
    val textHash: Int,
    val textLength: Int,
    val isStreaming: Boolean,
)

/**
 * Resolve the streaming text presentation block for decoration.
 *
 * Matches [SessionChatTimelineBlocks] text-run boundaries: visible tools and
 * file parts flush a run; invisible tools (e.g. todowrite) do not. Prefer the
 * last run that still contains streaming text — the active block the user is
 * watching — then fall back to [PresentationBlockId.messageFallback].
 */
internal fun resolveStreamingDecorationTarget(message: Message): StreamingDecorationTarget? {
    if (!messageHasStreamingText(message)) return null
    val allowToolCards = message.role == MessageRole.Assistant
    val runs = mutableListOf<List<Pair<MessagePart.Text, String>>>()
    var current = mutableListOf<Pair<MessagePart.Text, String>>()

    fun flush() {
        if (current.isEmpty()) return
        runs += current.toList()
        current = mutableListOf()
    }

    for (part in message.parts) {
        when (part) {
            is MessagePart.Text -> {
                val trimmed = part.text.trim()
                if (trimmed.isEmpty()) continue
                if (part.id == null) {
                    flush()
                    continue
                }
                current += part to trimmed
            }
            is MessagePart.Tool -> {
                if (!allowToolCards || !isVisibleToolForStreamingDecoration(part)) continue
                flush()
            }
            is MessagePart.File -> flush()
        }
    }
    flush()

    val selected =
        runs.lastOrNull { run -> run.any { (textPart, _) -> textPart.isStreaming } }
    if (selected == null) {
        return StreamingDecorationTarget(
            blockID = PresentationBlockId.messageFallback(message.id),
            plain = message.text,
            textHash = stableMarkdownSourceHash(message.text),
            textLength = message.text.length,
            isStreaming = true,
        )
    }
    val partIds = selected.mapNotNull { (textPart, _) -> textPart.id }
    val plain = selected.joinToString("\n") { it.second }
    return StreamingDecorationTarget(
        blockID = PresentationBlockId.mergedText(partIds),
        plain = plain,
        textHash = stableMarkdownSourceHash(plain),
        textLength = plain.length,
        isStreaming = true,
    )
}

/** Mirrors [SessionChatTimelineBlocks] visibility used when flushing text runs. */
private fun isVisibleToolForStreamingDecoration(tool: MessagePart.Tool): Boolean {
    val name = tool.toolName.lowercase()
    if (name == "todowrite") return false
    if (name == "question" &&
        (tool.status == ToolStatus.Pending || tool.status == ToolStatus.Running)
    ) {
        return false
    }
    return true
}

private fun messageHasSoleTextBlock(message: Message): Boolean {
    val blocks =
        SessionChatTimelineBlocks.buildOrFallback(
            role = message.role,
            parts = message.parts,
            messageId = message.id,
            fallbackText = message.text,
        )
    return blocks.count { it is SessionChatTimelineBlock.Text } == 1
}

internal fun shouldPlainFirstHistoryPublish(
    messages: List<Message>,
    previousById: Map<String, DisplayMessage>,
): Boolean {
    if (previousById.values.any { it.renderedMarkdown != null }) return false
    var markdownChars = 0
    for (message in messages) {
        if (!OpenCodeMarkdownDetector.shouldRender(message.text)) continue
        markdownChars += message.text.length
        if (markdownChars >= MessageContentPreparer.HISTORY_PLAIN_FIRST_MARKDOWN_CHARACTER_THRESHOLD) {
            return true
        }
    }
    return false
}

/** Newest markdown-bearing message ids for the open viewport (bottom of chat). */
internal fun newestMarkdownViewportIds(
    messages: List<Message>,
    maxCount: Int = MessageContentPreparer.HISTORY_ENRICH_VIEWPORT_MESSAGE_COUNT,
    charBudget: Int = MessageContentPreparer.HISTORY_ENRICH_VIEWPORT_CHARACTER_BUDGET,
): Set<String> {
    if (messages.isEmpty() || maxCount <= 0) return emptySet()
    val ids = LinkedHashSet<String>()
    var remainingChars = charBudget
    for (message in messages.asReversed()) {
        if (!OpenCodeMarkdownDetector.shouldRender(message.text)) continue
        ids += message.id
        remainingChars -= message.text.length
        if (ids.size >= maxCount || remainingChars <= 0) break
    }
    return ids
}
