package dev.opencode.mobile.domain.message

import dev.opencode.mobile.markdown.OpenCodeMarkdownDetector
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/** Quiet window before publishing the latest streaming snapshot. */
internal const val STREAMING_PREPARE_DEBOUNCE_MS = 16L

/** Cap how long dense SSE bursts can delay a UI publish. Keep flat so long replies
 * do not intentionally slow down as they grow. */
internal const val STREAMING_PREPARE_MAX_BATCH_MS = 48L

/** Pause between history markdown enrich publishes so scrolling stays responsive. */
internal const val HISTORY_ENRICH_YIELD_MS = 24L

class DisplayMessagePipeline(
    private val messageSync: MessageSync,
    private val contentPreparer: MessageContentPreparer,
) {
    fun observeDisplayMessages(
        sessionId: String,
        directory: String?,
    ): Flow<DisplayMessageListUpdate> =
        channelFlow {
            var previousById = emptyMap<String, DisplayMessage>()
            var pendingMessages: List<Message>? = null
            var debounceJob: Job? = null
            var enrichmentJob: Job? = null
            var lastPublishedAt: TimeSource.Monotonic.ValueTimeMark? = null
            val timeSource = TimeSource.Monotonic

            suspend fun publishPrepared(
                messages: List<Message>,
                reuseStreamingDocumentAsFinalPlaceholder: Boolean = false,
                skipMarkdown: Boolean = false,
            ) {
                val prepared =
                    contentPreparer.prepareMessages(
                        messages,
                        previousById,
                        reuseStreamingDocumentAsFinalPlaceholder,
                        skipMarkdown,
                    )
                if (!skipMarkdown) {
                    previousById = prepared.associateBy { it.id }
                }
                lastPublishedAt = timeSource.markNow()
                send(DisplayMessageListUpdate.Messages(prepared))
            }

            suspend fun enrichHistoryIncrementally(messages: List<Message>) {
                val byId =
                    messages
                        .associate { message ->
                            message.id to DisplayMessage(message = message, renderedMarkdown = null)
                        }.toMutableMap()
                // Newest first: open settles near the bottom, so enrich what the
                // user is looking at before older off-screen history.
                for (message in messages.asReversed()) {
                    ensureActive()
                    if (!OpenCodeMarkdownDetector.shouldRender(message.text)) continue
                    val prepared = contentPreparer.prepareMessage(message)
                    byId[message.id] = prepared
                    previousById = previousById + (message.id to prepared)
                    lastPublishedAt = timeSource.markNow()
                    send(DisplayMessageListUpdate.Messages(messages.map { byId.getValue(it.id) }))
                    yield()
                    delay(HISTORY_ENRICH_YIELD_MS.milliseconds)
                }
            }

            suspend fun scheduleStreamingPublish() {
                debounceJob?.cancelAndJoin()
                debounceJob =
                    launch {
                        delay(STREAMING_PREPARE_DEBOUNCE_MS.milliseconds)
                        if (!isActive) return@launch
                        val latest = pendingMessages ?: return@launch
                        pendingMessages = null
                        publishPrepared(latest)
                    }
            }

            try {
                messageSync.observeMessages(sessionId, directory).collect { update ->
                    when (update) {
                        is MessageListUpdate.LoadFailed -> {
                            debounceJob?.cancelAndJoin()
                            debounceJob = null
                            enrichmentJob?.cancelAndJoin()
                            enrichmentJob = null
                            pendingMessages = null
                            send(DisplayMessageListUpdate.LoadFailed(update.error))
                        }
                        is MessageListUpdate.Messages -> {
                            enrichmentJob?.cancelAndJoin()
                            enrichmentJob = null
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
                                    debounceJob?.cancelAndJoin()
                                    debounceJob = null
                                    val latest = pendingMessages ?: update.messages
                                    pendingMessages = null
                                    publishPrepared(latest)
                                } else {
                                    scheduleStreamingPublish()
                                }
                            } else {
                                debounceJob?.cancelAndJoin()
                                debounceJob = null
                                pendingMessages = null
                                val hadStreamingPrevious = previousById.values.any { it.isStreaming }
                                if (hadStreamingPrevious) {
                                    publishPrepared(
                                        update.messages,
                                        reuseStreamingDocumentAsFinalPlaceholder = true,
                                    )
                                    publishPrepared(update.messages)
                                } else if (shouldPlainFirstHistoryPublish(update.messages, previousById)) {
                                    publishPrepared(update.messages, skipMarkdown = true)
                                    enrichmentJob =
                                        launch {
                                            delay(48.milliseconds)
                                            enrichHistoryIncrementally(update.messages)
                                        }
                                } else {
                                    publishPrepared(update.messages)
                                }
                            }
                        }
                    }
                }
            } finally {
                debounceJob?.cancel()
                enrichmentJob?.cancel()
                pendingMessages?.let { publishPrepared(it) }
            }
        }

    suspend fun refreshDisplayMessages(
        sessionId: String,
        directory: String?,
    ): List<DisplayMessage> {
        val messages = messageSync.refreshMessages(sessionId, directory)
        return contentPreparer.prepareMessages(messages)
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
