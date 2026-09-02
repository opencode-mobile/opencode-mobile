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

    fun activeEntryCount(): Int = entries.size

    fun clear() {
        val stale = entries.values.toList()
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
