package dev.opencode.mobile.domain.terminal

import dev.opencode.mobile.data.remote.PtyApi
import dev.opencode.mobile.domain.connection.ServerProtocol
import dev.opencode.mobile.network.ActiveConnectionManager
import javax.inject.Inject

class DefaultTerminalRepository
    @Inject
    constructor(
        api: PtyApi,
        private val adapter: TerminalDomainAdapter,
        private val activeConnectionManager: ActiveConnectionManager? = null,
    ) : TerminalRepository {
        private val remote: TerminalRemote =
            ProtocolSelectedTerminalRemote(
                v1 = V1TerminalRemote(api, adapter),
                v2 = V2TerminalRemote(api, adapter),
                activeConnectionManager = activeConnectionManager,
            )

        override suspend fun list(directory: String): List<TerminalSession> =
            runCatching { remote.list(directory) }
                .getOrElse { throw adapter.mapError(it) }

        override suspend fun create(
            directory: String,
            cwd: String,
        ): TerminalSession =
            runCatching { remote.create(directory, cwd) }
                .getOrElse { throw adapter.mapError(it) }

        override suspend fun get(
            id: String,
            directory: String,
        ): TerminalSession =
            runCatching { remote.get(id, directory) }
                .getOrElse { throw adapter.mapError(it) }

        override suspend fun updateSize(
            id: String,
            directory: String,
            cols: Int,
            rows: Int,
        ): TerminalSession =
            runCatching { remote.updateSize(id, directory, cols, rows) }
                .getOrElse { throw adapter.mapError(it) }

        override suspend fun ensureRunning(directory: String): TerminalSession {
            val running = list(directory).firstOrNull { it.status == TerminalSessionStatus.Running }
            return running ?: create(directory, cwd = directory)
        }

        override suspend fun remove(
            id: String,
            directory: String,
        ) {
            runCatching { remote.remove(id, directory) }
                .getOrElse { throw adapter.mapError(it) }
        }

        override suspend fun listRunning(directories: List<String>): List<RunningTerminal> {
            val unscoped = runCatching { remote.list(null) }.getOrNull()
            if (unscoped != null) {
                val running = unscoped.running()
                if (running.isNotEmpty()) {
                    return running.map { session ->
                        RunningTerminal(
                            directory = session.cwd.ifBlank { "" },
                            session = session,
                        )
                    }
                }
                if (directories.isEmpty()) return emptyList()
            }
            val result = mutableListOf<RunningTerminal>()
            for (directory in directories) {
                val sessions = runCatching { remote.list(directory) }.getOrNull() ?: continue
                sessions.running().forEach { session ->
                    result += RunningTerminal(directory = directory, session = session)
                }
            }
            return result
        }

        private fun List<TerminalSession>.running(): List<TerminalSession> = filter { it.status == TerminalSessionStatus.Running }

        override fun connectRequest(
            id: String,
            directory: String,
            protocol: ServerProtocol,
            baseUrl: String,
            cursor: String,
            ticket: String?,
            username: String,
            password: String,
        ): TerminalConnectRequest =
            TerminalConnectRequest.build(
                id = id,
                directory = directory,
                protocol = protocol,
                baseUrl = baseUrl,
                cursor = cursor,
                ticket = ticket,
                username = username,
                password = password,
            )
    }
