package dev.opencode.mobile.domain.message

/**
 * Invalidation description for one reducer transaction.
 * Consumers read immutable store state at [sessionRevision]; this is not a
 * second mutable store.
 */
data class SessionChangeSet(
    val sessionRevision: Long,
    val insertedMessageIds: Set<String> = emptySet(),
    val changedMessageIds: Set<String> = emptySet(),
    val removedMessageIds: Set<String> = emptySet(),
    val changedPartIdsByMessage: Map<String, Set<String>> = emptyMap(),
    val streamingStateChangedIds: Set<String> = emptySet(),
    val structuralChange: Boolean = false,
)

sealed interface MessageStoreApplyResult {
    data object NoChange : MessageStoreApplyResult

    data class Changed(
        val changeSet: SessionChangeSet,
    ) : MessageStoreApplyResult
}