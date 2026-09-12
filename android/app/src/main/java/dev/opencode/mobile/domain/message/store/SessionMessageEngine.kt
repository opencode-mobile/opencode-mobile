package dev.opencode.mobile.domain.message

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Named single-writer reducer facade. MessageSync owns transport/recovery;
 * this type owns apply/revision and live-frame splicing.
 */
@Singleton
class SessionMessageEngine
    @Inject
    constructor(
        private val store: InMemoryMessageStore,
    ) {
        fun apply(
            sessionId: String,
            patch: MessagePatch,
        ): MessageStoreApplyResult = store.apply(sessionId, patch)

        fun replaceAll(
            sessionId: String,
            messages: List<Message>,
        ) = store.replaceAll(sessionId, messages)

        fun replaceAllIfRevision(
            sessionId: String,
            messages: List<Message>,
            expectedRevision: Long,
        ): Boolean = store.replaceAllIfRevision(sessionId, messages, expectedRevision)

        fun mergeAuthoritativeIfRevision(
            sessionId: String,
            messages: List<Message>,
            expectedRevision: Long,
        ): Boolean = store.mergeAuthoritativeIfRevision(sessionId, messages, expectedRevision)

        fun applyTailIfRevision(
            sessionId: String,
            tail: List<Message>,
            expectedRevision: Long,
        ): Boolean = store.applyTailIfRevision(sessionId, tail, expectedRevision)

        fun mergeTailAuthoritativeIfRevision(
            sessionId: String,
            tail: List<Message>,
            expectedRevision: Long,
        ): Boolean = store.mergeTailAuthoritativeIfRevision(sessionId, tail, expectedRevision)

        fun prependOlderIfRevision(
            sessionId: String,
            older: List<Message>,
            expectedRevision: Long,
        ): Boolean = store.prependOlderIfRevision(sessionId, older, expectedRevision)

        fun messages(sessionId: String): List<Message> = store.messages(sessionId)

        fun message(
            sessionId: String,
            messageId: String,
        ): Message? = store.message(sessionId, messageId)

        fun revision(sessionId: String): Long = store.revision(sessionId)

        fun isRestSeededMessage(
            sessionId: String,
            messageId: String,
        ): Boolean = store.isRestSeededMessage(sessionId, messageId)

        fun hasStreamingText(sessionId: String): Boolean = store.hasStreamingText(sessionId)

        fun finishStreamingText(sessionId: String) = store.finishStreamingText(sessionId)

        fun clear() = store.clear()

        /** Live SSE publishability without scanning the full message list. */
        fun shouldPublishLive(changeSet: SessionChangeSet): Boolean =
            changeSet.changedPartIdsByMessage.isNotEmpty() ||
                changeSet.removedMessageIds.isNotEmpty() ||
                changeSet.streamingStateChangedIds.isNotEmpty()

        /**
         * Build the next published frame by splicing changed message IDs into
         * [previous], or fully rebuilding on structural inserts/removals.
         */
        fun frameAfterChange(
            sessionId: String,
            changeSet: SessionChangeSet,
            previous: List<Message>?,
        ): List<Message> {
            if (previous == null ||
                changeSet.structuralChange ||
                changeSet.insertedMessageIds.isNotEmpty() ||
                changeSet.removedMessageIds.isNotEmpty()
            ) {
                return store.messages(sessionId)
            }
            val indexById = previous.withIndex().associate { it.value.id to it.index }
            if (changeSet.changedMessageIds.any { it !in indexById }) {
                return store.messages(sessionId)
            }
            val next = previous.toMutableList()
            for (id in changeSet.changedMessageIds) {
                val updated = store.message(sessionId, id) ?: continue
                val index = indexById[id]
                if (index != null) {
                    next[index] = updated
                }
            }
            return next
        }
    }
