package dev.opencode.mobile.ui.sessionchat

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Session-scoped in-memory Last Turn summary. Changes publishes counts after a
 * successful load; Session Chat reads them so the pill can appear on return.
 * Patch text is never stored.
 */
@Singleton
class LastTurnReviewChangesStore
    @Inject
    constructor() {
        data class Snapshot(
            val sessionId: String,
            val messageId: String,
            val summary: ReviewChangesSummary?,
        )

        private val snapshots = MutableStateFlow<Map<String, Snapshot>>(emptyMap())

        fun observe(sessionId: String): Flow<Snapshot?> =
            snapshots.map { it[sessionId] }.distinctUntilChanged()

        fun snapshot(sessionId: String): Snapshot? = snapshots.value[sessionId]

        fun publish(
            sessionId: String,
            messageId: String,
            summary: ReviewChangesSummary?,
        ) {
            snapshots.update { current ->
                current + (sessionId to Snapshot(sessionId, messageId, summary))
            }
        }
    }
