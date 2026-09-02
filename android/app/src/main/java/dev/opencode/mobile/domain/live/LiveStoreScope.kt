package dev.opencode.mobile.domain.live

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.flatMapLatest
import java.util.concurrent.ConcurrentHashMap

class LiveStoreScope(
    val coroutineScope: CoroutineScope,
)

fun defaultLiveStoreScope(): LiveStoreScope = LiveStoreScope(CoroutineScope(SupervisorJob() + Dispatchers.Default))

/**
 * Per-directory cache of shared live upstreams.
 *
 * Two properties matter here, and neither came for free with a plain map of
 * `shareIn` flows:
 *
 * 1. **Deterministic teardown.** Every entry owns its own child scope of the live
 *    store scope, so [clear] stops the sharing coroutine (and the REST/SSE work
 *    underneath it) instead of merely dropping the map entry and leaving the old
 *    upstream polling a connection profile the app has already left.
 * 2. **Transparent re-subscription.** Observers attach through `flatMapLatest` on a
 *    generation counter that [clear] bumps, so a collector that is still running
 *    (Session Chat chrome, the ready store) re-attaches to the freshly built
 *    upstream rather than being stranded on the cancelled one. Without this a
 *    stale collector would pin the old flow and the next `observe` call would build
 *    a second upstream for the same directory.
 */
class LiveStoreFlowCache<T>(
    private val liveStoreScope: LiveStoreScope,
) {
    private class Entry<T>(
        val scope: CoroutineScope,
        val flow: SharedFlow<T>,
    )

    private val entries = ConcurrentHashMap<String, Entry<T>>()
    private val generation = MutableStateFlow(0)

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(
        key: String,
        build: (CoroutineScope) -> SharedFlow<T>,
    ): Flow<T> = generation.flatMapLatest { entry(key, build).flow }

    /** Number of live upstreams. Test seam for "a profile switch must not double upstreams". */
    fun activeEntryCount(): Int = entries.size

    fun clear() {
        val stale = entries.values.toList()
        // Drop first so a re-attach builds a fresh entry, then wake the observers,
        // then stop the old upstreams.
        entries.clear()
        generation.value += 1
        stale.forEach { it.scope.cancel() }
    }

    private fun entry(
        key: String,
        build: (CoroutineScope) -> SharedFlow<T>,
    ): Entry<T> =
        entries.computeIfAbsent(key) {
            val parentContext = liveStoreScope.coroutineScope.coroutineContext
            val scope = CoroutineScope(parentContext + SupervisorJob(parentContext[Job]))
            Entry(scope, build(scope))
        }
}
