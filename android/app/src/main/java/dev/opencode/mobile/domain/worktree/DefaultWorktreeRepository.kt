package dev.opencode.mobile.domain.worktree

import dev.opencode.mobile.data.remote.V1WorktreeHttp
import dev.opencode.mobile.data.remote.WorktreeHttpException
import dev.opencode.mobile.data.remote.WorktreeRemote
import dev.opencode.mobile.domain.connection.ServerProtocol
import dev.opencode.mobile.domain.filesystem.ServerPathPolicy
import dev.opencode.mobile.domain.session.ServerErrorMessage
import dev.opencode.mobile.network.ActiveConnectionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val _canCreate = MutableStateFlow(true)
    override val canCreate: StateFlow<Boolean> = _canCreate.asStateFlow()

    override suspend fun load(
        addedPath: String,
        projectId: String?,
        mainWorktree: String?,
    ): WorktreeLoad =
        when (protocol()) {
            ServerProtocol.V2 -> loadV2(projectId)
            ServerProtocol.V1 -> loadV1(addedPath, mainWorktree)
        }

    override suspend fun loadRefreshed(
        addedPath: String,
        projectId: String?,
        mainWorktree: String?,
    ): WorktreeLoad {
        val listed = load(addedPath, projectId, mainWorktree)
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
        _canCreate.value = true
    }

    override fun onConnection(key: String) {
        if (connectionKey != key) {
            v1StoppedKey = null
            connectionKey = key
            _canCreate.value = true
        }
    }

    override suspend fun create(request: CreateWorktreeRequest): CreatedWorktree =
        when (protocol()) {
            ServerProtocol.V2 -> createV2(request)
            ServerProtocol.V1 -> createV1(request)
        }

    override suspend fun baseBranches(directory: String): List<String> =
        when (protocol()) {
            ServerProtocol.V2 -> remote.branchesV2(directory)
            ServerProtocol.V1 -> emptyList()
        }

    private suspend fun createV2(request: CreateWorktreeRequest): CreatedWorktree {
        val projectId = request.projectId?.trim()?.takeIf { it.isNotEmpty() } ?: throw WorktreeCreateError(WorktreeCreateCopy.NOT_GIT)
        val directory =
            createCall(notFoundMeansUnsupported = false) {
                remote.createV2(
                    projectId = projectId,
                    from = request.sourceDirectory,
                    name = request.name,
                    branch = request.baseRef?.takeIf { it.isNotBlank() },
                )
            }
        val canonical = ServerPathPolicy.canonical(directory.trim())
        return CreatedWorktree(
            directory = canonical,
            name = ServerPathPolicy.name(canonical).ifBlank { request.name },
            branch = null,
        )
    }

    private suspend fun createV1(request: CreateWorktreeRequest): CreatedWorktree {
        val created =
            try {
                createCall(notFoundMeansUnsupported = true) { remote.createV1(request.sourceDirectory, request.name) }
            } catch (_: V1CreateNotFound) {
                _canCreate.value = false
                throw WorktreeCreateError(WorktreeCreateCopy.UNSUPPORTED)
            }
        val canonical = ServerPathPolicy.canonical(created.directory.trim())
        return CreatedWorktree(
            directory = canonical,
            name = created.name.trim().ifEmpty { ServerPathPolicy.name(canonical).ifBlank { request.name } },
            branch = created.branch?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    /** Runs a create call and turns its failure into [WorktreeCreateError] (a v1 404 into [V1CreateNotFound]). */
    private suspend fun <T> createCall(
        notFoundMeansUnsupported: Boolean,
        call: suspend () -> T,
    ): T =
        try {
            call()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: WorktreeCreateError) {
            throw error
        } catch (error: WorktreeHttpException) {
            if (error.statusCode == 404 && notFoundMeansUnsupported) throw V1CreateNotFound()
            throw WorktreeCreateError(httpErrorMessage(error.statusCode, error.body))
        } catch (error: Exception) {
            throw WorktreeCreateError(error.message?.trim()?.takeIf { it.isNotEmpty() } ?: "Unknown error")
        }

    private class V1CreateNotFound : Exception()

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

    private suspend fun loadV1(
        addedPath: String,
        mainWorktree: String?,
    ): WorktreeLoad {
        val main = listOfNotNull(mainWorktree?.trim()?.takeIf { it.isNotEmpty() })
        val mainOnly = if (main.isEmpty()) WorktreeLoad.Stopped else WorktreeLoad.Loaded(main, listsSessions = true)
        if (v1StoppedKey != null && v1StoppedKey == connectionKey) return mainOnly
        return when (val listed = runV1(addedPath)) {
            is V1WorktreeHttp.NotFound -> {
                // Older servers: no worktree route, so no extra worktrees.
                v1StoppedKey = connectionKey
                mainOnly
            }
            is V1WorktreeHttp.Failed ->
                WorktreeLoad.Failed(IllegalStateException("worktree list failed with status ${listed.statusCode}"))
            is V1WorktreeHttp.Ok -> {
                val directories =
                    WorktreeDirectoryParser.parse(listed.body)
                        ?: return WorktreeLoad.Failed(IllegalStateException("unrecognized worktree body"))
                WorktreeLoad.Loaded((main + directories).distinct(), listsSessions = true)
            }
        }
    }

    private suspend fun runV1(addedPath: String): V1WorktreeHttp =
        try {
            remote.listV1(addedPath)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            V1WorktreeHttp.Failed(-1)
        }
}

/** The server's own message, else the trimmed raw body, else the HTTP status. */
internal fun httpErrorMessage(
    statusCode: Int,
    body: String,
): String =
    ServerErrorMessage.userFacing(body)
        ?: body.trim().takeIf { it.isNotEmpty() && !it.startsWith("{") && !it.startsWith("[") && it.length <= 200 && !it.contains('\n') }
        ?: WorktreeCreateCopy.requestFailed(statusCode)
