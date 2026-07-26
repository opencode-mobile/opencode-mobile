package dev.opencode.mobile.domain.message

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/** Quiet window before publishing the latest streaming snapshot. */
internal const val STREAMING_PREPARE_DEBOUNCE_MS = 16L

/** Minimum max-batch interval while deltas keep arriving. */
internal const val STREAMING_PREPARE_MAX_BATCH_MS = 48L

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
            var lastPublishedAt: TimeSource.Monotonic.ValueTimeMark? = null
            val timeSource = TimeSource.Monotonic

            suspend fun publishPrepared(
                messages: List<Message>,
                reuseStreamingDocumentAsFinalPlaceholder: Boolean = false,
            ) {
                val prepared =
                    contentPreparer.prepareMessages(
                        messages,
                        previousById,
                        reuseStreamingDocumentAsFinalPlaceholder,
                    )
                previousById = prepared.associateBy { it.id }
                lastPublishedAt = timeSource.markNow()
                send(DisplayMessageListUpdate.Messages(prepared))
            }

            fun scheduleStreamingPublish() {
                debounceJob?.cancel()
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
                            debounceJob?.cancel()
                            debounceJob = null
                            pendingMessages = null
                            send(DisplayMessageListUpdate.LoadFailed(update.error))
                        }
                        is MessageListUpdate.Messages -> {
                            if (hasStreamingDelta(update.messages)) {
                                // Keep the latest snapshot. Publish after a short quiet window,
                                // but also flush when the max batch age is exceeded so dense
                                // token bursts still stream instead of appearing only at the end.
                                pendingMessages = update.messages
                                val streamingChars =
                                    update.messages.maxOfOrNull { message ->
                                        if (message.parts.any { it is MessagePart.Text && it.isStreaming }) {
                                            message.text.length
                                        } else {
                                            0
                                        }
                                    } ?: 0
                                val maxBatchMs = streamingPrepareMaxBatchMs(streamingChars)
                                val dueForMaxBatch =
                                    lastPublishedAt
                                        ?.elapsedNow()
                                        ?.inWholeMilliseconds
                                        ?.let { it >= maxBatchMs }
                                        ?: true
                                if (dueForMaxBatch) {
                                    debounceJob?.cancel()
                                    debounceJob = null
                                    val latest = pendingMessages ?: update.messages
                                    pendingMessages = null
                                    publishPrepared(latest)
                                } else {
                                    scheduleStreamingPublish()
                                }
                            } else {
                                debounceJob?.cancel()
                                debounceJob = null
                                pendingMessages = null
                                val hadStreamingPrevious = previousById.values.any { it.isStreaming }
                                if (hadStreamingPrevious) {
                                    publishPrepared(
                                        update.messages,
                                        reuseStreamingDocumentAsFinalPlaceholder = true,
                                    )
                                }
                                publishPrepared(update.messages)
                            }
                        }
                    }
                }
            } finally {
                debounceJob?.cancel()
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

/** Widen the publish interval as the streaming message grows so UI can keep up. */
internal fun streamingPrepareMaxBatchMs(streamingChars: Int): Long =
    when {
        streamingChars < 5_000 -> STREAMING_PREPARE_MAX_BATCH_MS
        streamingChars < 15_000 -> 100L
        streamingChars < 25_000 -> 150L
        else -> 250L
    }
