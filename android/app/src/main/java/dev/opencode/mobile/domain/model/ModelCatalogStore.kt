package dev.opencode.mobile.domain.model

import dev.opencode.mobile.domain.agent.AgentRepository
import dev.opencode.mobile.domain.agent.SelectableAgent
import dev.opencode.mobile.domain.connection.ConnectionProfile
import dev.opencode.mobile.domain.live.LiveStoreScope
import dev.opencode.mobile.network.ActiveConnectionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** One model catalog per Connection Profile and worktree (project config can change providers). */
data class ModelCatalogKey(
    val profileId: String,
    val serverBaseUrl: String,
    val username: String,
    val worktree: String?,
)

data class ModelCatalogEntry(
    /** Last non-empty catalog; an empty or failed refresh never replaces it. */
    val catalog: ModelSelectionCatalog? = null,
    val selectableAgents: List<SelectableAgent>? = null,
    val fetchedAtEpochMs: Long? = null,
)

/**
 * App-wide model catalog cache so every Session Chat for the same Connection Profile and
 * worktree starts from the last loaded catalog and selection instead of "Loading model…".
 *
 * Reads are synchronous ([peek], [lastSelection]); refreshes are shared per key while in flight
 * and keep the last non-empty catalog on failure (stale-while-revalidate). [clear] runs on every
 * profile change and Clear cache, and drops results of fetches started before it.
 */
@Singleton
class ModelCatalogStore(
    private val modelRepository: ModelRepository,
    private val agentRepository: AgentRepository,
    private val currentProfile: () -> ConnectionProfile?,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    @Inject
    constructor(
        modelRepository: ModelRepository,
        agentRepository: AgentRepository,
        activeConnectionManager: ActiveConnectionManager,
        liveStoreScope: LiveStoreScope,
    ) : this(
        modelRepository = modelRepository,
        agentRepository = agentRepository,
        currentProfile = activeConnectionManager::currentProfile,
        scope = CoroutineScope(liveStoreScope.coroutineScope.coroutineContext + Dispatchers.IO),
    )

    private data class SelectionKey(
        val catalogKey: ModelCatalogKey,
        val sessionId: String,
    )

    private class InFlight<T>(
        val deferred: Deferred<T>,
        val generation: Long,
        val forced: Boolean,
    )

    private val lock = Any()
    private var generation = 0L
    private var nextFetchId = 0L
    private val entries = MutableStateFlow<Map<ModelCatalogKey, ModelCatalogEntry>>(emptyMap())
    private val selections = mutableMapOf<SelectionKey, SessionModelSelection>()
    private val catalogFetches = mutableMapOf<ModelCatalogKey, InFlight<ModelSelectionCatalog>>()
    private val agentFetches = mutableMapOf<ModelCatalogKey, InFlight<List<SelectableAgent>>>()
    /** Highest fetch id stored per key, so an older answer never overwrites a newer one. */
    private val storedCatalogFetchIds = mutableMapOf<ModelCatalogKey, Long>()
    private val storedAgentFetchIds = mutableMapOf<ModelCatalogKey, Long>()

    fun peek(worktree: String?): ModelCatalogEntry? {
        val key = keyFor(worktree) ?: return null
        return entries.value[key]
    }

    fun observe(worktree: String?): Flow<ModelCatalogEntry?> {
        val key = keyFor(worktree)
        return entries.map { key?.let(it::get) }.distinctUntilChanged()
    }

    /** The cached catalog when there is one, otherwise a shared refresh. */
    suspend fun catalog(worktree: String?): ModelSelectionCatalog = peek(worktree)?.catalog ?: refreshCatalog(worktree)

    /**
     * Fetches the catalog and returns the server's answer, which may be empty; throws on failure.
     * Joins a fetch already in flight for the same key unless [force] asks for an answer newer
     * than this call (server catalog events), in which case only another forced fetch is joined.
     */
    suspend fun refreshCatalog(
        worktree: String?,
        force: Boolean = false,
    ): ModelSelectionCatalog {
        val key = keyFor(worktree) ?: return modelRepository.listModelOptions(worktree)
        return shared(
            fetches = catalogFetches,
            storedIds = storedCatalogFetchIds,
            key = key,
            force = force,
            fetch = { modelRepository.listModelOptions(worktree) },
            // An empty answer (a server still loading providers) keeps the last usable catalog.
            stores = { it.options.isNotEmpty() },
        ) { entry, catalog ->
            entry.copy(catalog = catalog, fetchedAtEpochMs = nowMs())
        }
    }

    suspend fun refreshSelectableAgents(
        worktree: String?,
        force: Boolean = false,
    ): List<SelectableAgent> {
        val key = keyFor(worktree) ?: return agentRepository.listSelectableAgents(worktree)
        return shared(
            fetches = agentFetches,
            storedIds = storedAgentFetchIds,
            key = key,
            force = force,
            fetch = { agentRepository.listSelectableAgents(worktree) },
            stores = { _ -> true },
        ) { entry, agents ->
            entry.copy(selectableAgents = agents)
        }
    }

    /** Last selection the composer resolved or the user picked for a session, kept in memory. */
    fun lastSelection(
        worktree: String?,
        sessionId: String,
    ): SessionModelSelection? {
        val key = keyFor(worktree) ?: return null
        return synchronized(lock) { selections[SelectionKey(key, sessionId)] }
    }

    fun rememberSelection(
        worktree: String?,
        sessionId: String,
        selection: SessionModelSelection,
    ) {
        val key = keyFor(worktree) ?: return
        synchronized(lock) {
            val selectionKey = SelectionKey(key, sessionId)
            val existing = selections[selectionKey]?.savedAtEpochMs
            val incoming = selection.savedAtEpochMs
            // An older persist must not hide a newer in-memory pick.
            if (existing != null && incoming != null && existing > incoming) return
            selections[selectionKey] = selection
        }
    }

    fun clear() {
        synchronized(lock) {
            generation += 1
            entries.value = emptyMap()
            selections.clear()
            catalogFetches.clear()
            agentFetches.clear()
            storedCatalogFetchIds.clear()
            storedAgentFetchIds.clear()
        }
    }

    private suspend fun <T> shared(
        fetches: MutableMap<ModelCatalogKey, InFlight<T>>,
        storedIds: MutableMap<ModelCatalogKey, Long>,
        key: ModelCatalogKey,
        force: Boolean,
        fetch: suspend () -> T,
        stores: (T) -> Boolean,
        merge: (ModelCatalogEntry, T) -> ModelCatalogEntry,
    ): T {
        val inFlight =
            synchronized(lock) {
                fetches[key]
                    ?.takeIf { it.generation == generation && it.deferred.isActive && (!force || it.forced) }
                    ?.let { return@synchronized it }
                lateinit var started: InFlight<T>
                val fetchGeneration = generation
                val fetchId = ++nextFetchId
                val deferred =
                    scope.async(start = CoroutineStart.LAZY) {
                        try {
                            fetch().also { result ->
                                synchronized(lock) {
                                    // A profile change, or a newer fetch for this key, makes this answer stale.
                                    if (generation == fetchGeneration &&
                                        fetchId > (storedIds[key] ?: 0L) &&
                                        stores(result)
                                    ) {
                                        storedIds[key] = fetchId
                                        val current = entries.value
                                        this@ModelCatalogStore.entries.value =
                                            current + (key to merge(current[key] ?: ModelCatalogEntry(), result))
                                    }
                                }
                            }
                        } finally {
                            synchronized(lock) {
                                if (fetches[key] === started) fetches.remove(key)
                            }
                        }
                    }
                started = InFlight(deferred, fetchGeneration, force)
                fetches[key] = started
                started
            }
        inFlight.deferred.start()
        return inFlight.deferred.await()
    }

    private fun keyFor(worktree: String?): ModelCatalogKey? {
        val profile = currentProfile() ?: return null
        return ModelCatalogKey(
            profileId = profile.id,
            serverBaseUrl = profile.baseUrl,
            username = profile.username,
            worktree = worktree,
        )
    }
}
