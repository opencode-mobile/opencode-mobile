package dev.opencode.mobile.ui.sessionchat.region

import dev.opencode.mobile.domain.connection.ConnectionProfileStore
import dev.opencode.mobile.domain.project.ProjectRepository
import dev.opencode.mobile.domain.worktree.WorktreeInventory
import dev.opencode.mobile.domain.worktree.WorktreeInventoryCache
import dev.opencode.mobile.domain.worktree.WorktreeLoad
import dev.opencode.mobile.domain.worktree.WorktreeRepository
import dev.opencode.mobile.ui.sessionchat.SessionChatProjectIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

data class SessionChatProjectHeaderRegionState(
    val identity: SessionChatProjectIdentity = SessionChatProjectIdentity.None,
)

/**
 * Session Chat region for the header's project identity (label, letter, accent, and whether the
 * directory is a worktree checkout): seeded synchronously from the inventories Home already
 * cached, then refined from the server, and refined again when a `worktree.updated` event names
 * one of the checkout's projects.
 */
class SessionChatProjectHeader(
    private val worktreeInventoryCache: WorktreeInventoryCache,
    private val connectionProfileStore: ConnectionProfileStore,
    private val projectRepository: ProjectRepository,
    private val worktreeRepository: WorktreeRepository,
    /** The connected profile's base URL (the connection coordinator's current profile). */
    private val currentProfileBaseUrl: () -> String?,
    /** Connection key Home records worktree inventories under. */
    private val inventoryServerKey: () -> String,
    /** The directory Session Chat runs in now; a refine for another one is dropped. */
    private val boundWorktree: () -> String?,
    private val scope: CoroutineScope,
    private val onChange: () -> Unit,
) {
    var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    private val _state = MutableStateFlow(SessionChatProjectHeaderRegionState())
    val state: StateFlow<SessionChatProjectHeaderRegionState> = _state.asStateFlow()

    val identity: SessionChatProjectIdentity
        get() = _state.value.identity

    private var refineJob: Job? = null
    private val checkoutProjectIds = mutableSetOf<String>()

    /**
     * What Home already loaded decides the identity before the first frame; the refine only
     * changes it when the server now answers differently. Does not notify.
     */
    fun seed(worktree: String) {
        refineJob?.cancel()
        checkoutProjectIds.clear()
        _state.value =
            SessionChatProjectHeaderRegionState(
                if (worktree.isBlank()) {
                    SessionChatProjectIdentity.None
                } else {
                    cachedIdentity(worktree) ?: SessionChatProjectIdentity.folder(worktree)
                },
            )
    }

    /**
     * Re-resolves the identity from the server. Keeps the identity on screen until the answer
     * lands, so an agreeing answer changes nothing.
     */
    fun refine(worktree: String) {
        if (worktree.isBlank()) return
        refineJob?.cancel()
        cachedIdentity(worktree)?.let(::apply)
        refineJob =
            scope.launch {
                val peers =
                    withContext(ioDispatcher) {
                        val baseUrl =
                            currentProfileBaseUrl()
                                ?: connectionProfileStore.activeProfile()?.baseUrl
                        if (baseUrl.isNullOrBlank()) {
                            emptyList()
                        } else {
                            connectionProfileStore.addedWorktrees(baseUrl)
                        }
                    }
                coroutineContext.ensureActive()
                if (boundWorktree() != worktree) return@launch
                val inventories = loadInventories(peers)
                coroutineContext.ensureActive()
                if (boundWorktree() != worktree) return@launch
                apply(
                    SessionChatProjectIdentity.resolve(worktree, peers) { added ->
                        inventories[added] ?: WorktreeInventory.Unloaded
                    },
                )
            }
    }

    /** A `worktree.updated` naming [projectId]: refines when it is one of the checkout's projects. */
    fun onWorktreeUpdated(projectId: String) {
        if (projectId !in checkoutProjectIds) return
        boundWorktree()?.let { refine(it) }
    }

    /** Identity from the inventories Home (or an earlier Session Chat) loaded; null when unknown. */
    private fun cachedIdentity(worktree: String): SessionChatProjectIdentity? {
        val snapshot = worktreeInventoryCache.snapshot(inventoryServerKey()) ?: return null
        return SessionChatProjectIdentity.resolve(worktree, snapshot.addedPaths, snapshot::inventoryFor)
    }

    private fun apply(identity: SessionChatProjectIdentity) {
        if (identity == _state.value.identity) return
        _state.value = SessionChatProjectHeaderRegionState(identity)
        onChange()
    }

    /**
     * Fresh inventory of every added path. A load that did not succeed falls back to the last
     * known one, so a transient failure does not turn a checkout back into a project letter.
     */
    private suspend fun loadInventories(peers: List<String>): Map<String, WorktreeInventory> {
        val server = inventoryServerKey()
        val cached = worktreeInventoryCache.snapshot(server)
        worktreeInventoryCache.setAddedPaths(peers, server)
        if (peers.isEmpty()) return emptyMap()
        val baseUrl =
            currentProfileBaseUrl()
                ?: connectionProfileStore.activeProfile()?.baseUrl
        if (!baseUrl.isNullOrBlank()) worktreeRepository.onConnection(baseUrl)
        val inventories = mutableMapOf<String, WorktreeInventory>()
        checkoutProjectIds.clear()
        for (added in peers) {
            coroutineContext.ensureActive()
            val project =
                try {
                    projectRepository.getCurrentProject(added)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    null
                }
            val projectId = project?.id?.takeIf { it.isNotBlank() }
            if (projectId != null) checkoutProjectIds += projectId
            val load = worktreeRepository.loadRefreshed(added, projectId, project?.worktree?.takeIf { it != "/" })
            inventories[added] =
                when (load) {
                    is WorktreeLoad.Loaded ->
                        WorktreeInventory.loaded(load.directories).also { inventory ->
                            worktreeInventoryCache.record(inventory, added, server)
                        }
                    is WorktreeLoad.Failed -> cached?.inventories?.get(added) ?: WorktreeInventory.Failed
                    WorktreeLoad.Unloaded, WorktreeLoad.Stopped -> cached?.inventories?.get(added) ?: WorktreeInventory.Unloaded
                }
        }
        return inventories
    }
}
