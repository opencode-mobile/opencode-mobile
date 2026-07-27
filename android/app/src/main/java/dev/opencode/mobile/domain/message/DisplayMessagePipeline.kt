package dev.opencode.mobile.domain.message

import dev.opencode.mobile.markdown.OpenCodeMarkdownDetector
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
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
            var currentGeneration = 0L
            var debounceJob: Job? = null
            var prepareJob: Job? = null
            var enrichmentJob: Job? = null
            var lastPublishedAt: kotlin.time.TimeMark? = null

            fun invalidateInFlightWork() {
                debounceJob?.cancel()
                debounceJob = null
                prepareJob?.cancel()
                prepareJob = null
                enrichmentJob?.cancel()
                enrichmentJob = null
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
                        send(DisplayMessageListUpdate.Messages(prepared))
                        onCommitted?.invoke()
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
                        send(DisplayMessageListUpdate.Messages(placeholder))

                        val finalized =
                            contentPreparer.prepareMessages(
                                messages,
                                previousById,
                                MessagePreparationMode.Final,
                            )
                        if (!isActive || !isCurrent(generation)) return@launch
                        if (!commitPrepared(generation, finalized, MessagePreparationMode.Final)) return@launch
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

            fun scheduleStreamingPublish(generation: Long) {
                debounceJob?.cancel()
                debounceJob =
                    launch {
                        delayMillis(STREAMING_PREPARE_DEBOUNCE_MS)
                        if (!isActive || !isCurrent(generation)) return@launch
                        val latest = pendingMessages ?: return@launch
                        pendingMessages = null
                        schedulePrepare(generation, latest, MessagePreparationMode.Streaming)
                    }
            }

            try {
                snapshotSource.observeMessages(sessionId, directory).collect { update ->
                    when (update) {
                        is MessageListUpdate.LoadFailed -> {
                            nextGeneration()
                            invalidateInFlightWork()
                            pendingMessages = null
                            send(DisplayMessageListUpdate.LoadFailed(update.error))
                        }
                        is MessageListUpdate.Messages -> {
                            val generation = nextGeneration()
                            invalidateInFlightWork()
                            if (hasStreamingDelta(update.messages)) {
                                // Keep the latest snapshot. Publish after a short quiet window,
                                // but also flush when the max batch age is exceeded so dense
                                // token bursts still stream instead of appearing only at the end.
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
                                    schedulePrepare(generation, latest, MessagePreparationMode.Streaming)
                                } else {
                                    scheduleStreamingPublish(generation)
                                }
                            } else {
                                pendingMessages = null
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
        messages.any { message ->
            message.parts.any { part -> part is MessagePart.Text && part.isStreaming }
        }
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
