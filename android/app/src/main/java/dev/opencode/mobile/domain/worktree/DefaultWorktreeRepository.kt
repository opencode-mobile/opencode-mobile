package dev.opencode.mobile.domain.worktree

import dev.opencode.mobile.data.remote.V1WorkspaceHttp
import dev.opencode.mobile.data.remote.WorktreeRemote
import dev.opencode.mobile.domain.connection.ServerProtocol
import dev.opencode.mobile.network.ActiveConnectionManager
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

class DefaultWorktreeRepository(
    private val remote: WorktreeRemote,
    private val protocol: suspend () -> ServerProtocol,
) : WorktreeRepository {
    @Inject
    constructor(
        remote: WorktreeRemote,
        activeConnectionManager: ActiveConnectionManager,
    ) : this(
        remote = remote,
        protocol = { activeConnectionManager.currentProtocolOrProbe() },
    )

    private var connectionKey: String? = null
    private var v1StoppedKey: String? = null

    override suspend fun load(
        addedPath: String,
        projectId: String?,
    ): WorktreeLoad =
        when (protocol()) {
            ServerProtocol.V2 -> loadV2(projectId)
            ServerProtocol.V1 -> loadV1(addedPath)
        }

    override suspend fun loadRefreshed(
        addedPath: String,
        projectId: String?,
    ): WorktreeLoad {
        val listed = load(addedPath, projectId)
        if (listed !is WorktreeLoad.Loaded || listed.listsSessions || projectId.isNullOrBlank()) return listed
        if (!refresh(projectId)) return listed
        return when (val refreshed = refetch(projectId)) {
            is WorktreeLoad.Loaded, is WorktreeLoad.Failed -> refreshed
            WorktreeLoad.Unloaded, WorktreeLoad.Stopped -> listed
        }
    }

    override suspend fun refresh(projectId: String): Boolean {
        if (protocol() != ServerProtocol.V2 || projectId.isBlank()) return false
        return try {
            remote.refreshV2(projectId)
            true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun refetch(projectId: String): WorktreeLoad {
        if (protocol() != ServerProtocol.V2) return WorktreeLoad.Unloaded
        return loadV2(projectId)
    }

    override fun onDisconnected() {
        connectionKey = null
        v1StoppedKey = null
    }

    override fun onConnection(key: String) {
        if (connectionKey != key) {
            v1StoppedKey = null
            connectionKey = key
        }
    }

    private suspend fun loadV2(projectId: String?): WorktreeLoad {
        if (projectId.isNullOrBlank()) return WorktreeLoad.Unloaded
        return try {
            WorktreeLoad.Loaded(remote.listV2(projectId), listsSessions = false)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            WorktreeLoad.Failed(error)
        }
    }

    private suspend fun loadV1(addedPath: String): WorktreeLoad {
        if (v1StoppedKey != null && v1StoppedKey == connectionKey) return WorktreeLoad.Stopped
        return when (val probe = runV1(addedPath)) {
            is V1WorkspaceHttp.NotFound -> {
                v1StoppedKey = connectionKey
                WorktreeLoad.Stopped
            }
            is V1WorkspaceHttp.Failed ->
                WorktreeLoad.Failed(IllegalStateException("workspace probe failed with status ${probe.statusCode}"))
            is V1WorkspaceHttp.Ok -> {
                val directories = WorkspaceDirectoryParser.parse(probe.body)
                    ?: return WorktreeLoad.Failed(IllegalStateException("unrecognized workspace body"))
                WorktreeLoad.Loaded(directories, listsSessions = true)
            }
        }
    }

    private suspend fun runV1(addedPath: String): V1WorkspaceHttp =
        try {
            remote.probeV1(addedPath)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            V1WorkspaceHttp.Failed(-1)
        }
}
