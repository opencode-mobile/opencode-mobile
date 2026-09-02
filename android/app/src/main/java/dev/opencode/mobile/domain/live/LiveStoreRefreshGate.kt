package dev.opencode.mobile.domain.live

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal enum class LiveStoreRefreshOutcome {
    COMMITTED,
    DISCARDED,
    FAILED,
}

internal class LiveStoreRefreshGate {
    private val mutex = Mutex()
    private val revisionLock = Any()
    private var revision = 0L

    fun bump() {
        synchronized(revisionLock) { revision += 1 }
    }

    fun revision(): Long = synchronized(revisionLock) { revision }

    suspend fun <T> refresh(
        load: suspend () -> T?,
        commit: suspend (T) -> Unit,
    ): LiveStoreRefreshOutcome =
        mutex.withLock {
            val expected = revision()
            val loaded = load() ?: return@withLock LiveStoreRefreshOutcome.FAILED
            if (revision() != expected) {
                return@withLock LiveStoreRefreshOutcome.DISCARDED
            }
            commit(loaded)
            bump()
            LiveStoreRefreshOutcome.COMMITTED
        }
}
