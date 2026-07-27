package dev.opencode.mobile.domain.message

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
            val decorationBacklog = DecorationBacklog()

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

            fun buildPlainStreamingFrame(messages: List<Message>): List<DisplayMessage> =
                messages.map { message ->
                    val previous = previousById[message.id]
                    val streaming = messageHasStreamingText(message)
                    when {
                        streaming -> DisplayMessage(message = message, renderedMarkdown = null)
                        previous != null &&
                            previous.text == message.text &&
                            previous.renderedMarkdown != null ->
                            DisplayMessage(message = message, renderedMarkdown = previous.renderedMarkdown)
                        else -> DisplayMessage(message = message, renderedMarkdown = null)
                    }
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
                            send(DisplayMessageListUpdate.Messages(merged))
                        }
                        decorationJob = null
                    }
            }

            fun scheduleStreamingDecoration(
                generation: Long,
                messages: List<Message>,
            ) {
                val streamingMessage =
                    messages.firstOrNull(::messageHasStreamingText) ?: return
                val target = resolveStreamingDecorationTarget(streamingMessage) ?: return
                if (target.plain.length >=
                    MessageContentPreparer.STREAMING_PLAIN_TEXT_CHARACTER_THRESHOLD
                ) {
                    return
                }
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
                val frame = buildPlainStreamingFrame(messages)
                previousById = frame.associateBy { it.id }
                lastPublishedMessages = messages
                lastPublishedAt = timeSource.markNow()
                send(DisplayMessageListUpdate.Messages(frame))
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
                        send(DisplayMessageListUpdate.Messages(placeholder))

                        val finalized =
                            contentPreparer.prepareMessages(
                                messages,
                                previousById,
                                MessagePreparationMode.Final,
                            )
                        if (!isActive || !isCurrent(generation)) return@launch
                        if (!commitPrepared(generation, finalized, MessagePreparationMode.Final)) return@launch
                        lastPublishedMessages = messages
                        send(DisplayMessageListUpdate.Messages(finalized))
                    }
            }

            fun enrichHistoryInBackground(
                generation: Long,
                messages: List<Message>,
            ) {
                enrichmentJob?.cancel()
                enrichmentJob =
                    launch {
                        delayMillis(48)
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
                            send(DisplayMessageListUpdate.Messages(messages.map { byId.getValue(it.id) }))
                        }

                        // Newest first: open settles near the bottom, so enrich what the
                        // user is looking at before older off-screen history.
                        for (message in messages.asReversed()) {
                            ensureActive()
                            if (!isCurrent(generation)) return@launch
                            if (!OpenCodeMarkdownDetector.shouldRender(message.text)) continue
                            val cached = byId[message.id]
                            if (cached?.renderedMarkdown != null && cached.text == message.text) {
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
                        send(DisplayMessageListUpdate.Messages(prepared))
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
                                } else if (shouldPlainFirstHistoryPublish(update.messages, previousById)) {
                                    schedulePrepare(
                                        generation = generation,
                                        messages = update.messages,
                                        mode = MessagePreparationMode.HistoryPlainFirst,
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
 * Prefer [PresentationBlockId.mergedText] from streaming text part IDs;
 * fall back to [PresentationBlockId.messageFallback] when IDs are incomplete.
 */
internal fun resolveStreamingDecorationTarget(message: Message): StreamingDecorationTarget? {
    val streamingParts =
        message.parts.filterIsInstance<MessagePart.Text>().filter { it.isStreaming }
    if (streamingParts.isEmpty()) return null
    val pieces =
        streamingParts.mapNotNull { part ->
            val trimmed = part.text.trim()
            if (trimmed.isEmpty()) null else part to trimmed
        }
    if (pieces.isEmpty()) return null
    val partIds = pieces.mapNotNull { (part, _) -> part.id }
    val hasCompleteIds = partIds.size == pieces.size
    val blockID =
        if (hasCompleteIds) {
            PresentationBlockId.mergedText(partIds)
        } else {
            PresentationBlockId.messageFallback(message.id)
        }
    // Match SessionChatTimelineBlocks: structured merge uses trimmed joins;
    // incomplete IDs fall back to message.text as the sole block source.
    val plain =
        if (hasCompleteIds) {
            pieces.joinToString("\n") { it.second }
        } else {
            message.text
        }
    return StreamingDecorationTarget(
        blockID = blockID,
        plain = plain,
        textHash = stableMarkdownSourceHash(plain),
        textLength = plain.length,
        isStreaming = true,
    )
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
