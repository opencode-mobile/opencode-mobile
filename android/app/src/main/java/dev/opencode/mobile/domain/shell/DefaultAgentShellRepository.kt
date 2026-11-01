package dev.opencode.mobile.domain.shell

import dev.opencode.mobile.data.remote.ShellApi
import dev.opencode.mobile.domain.event.ForegroundEventStream
import dev.opencode.mobile.network.ActiveConnectionManager
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class DefaultAgentShellRepository internal constructor(
    private val remote: AgentShellRemote,
) : AgentShellRepository {
    @Inject
    constructor(
        api: ShellApi,
        adapter: AgentShellDomainAdapter,
        eventStream: ForegroundEventStream,
        activeConnectionManager: ActiveConnectionManager,
    ) : this(
        ProtocolSelectedAgentShellRemote(
            v1 = V1AgentShellRemote(adapter),
            v2 =
                V2AgentShellRemote(
                    api = api,
                    adapter = adapter,
                    eventStream = eventStream,
                    supported = { activeConnectionManager.currentCapabilities().supportsAgentShells },
                ),
            activeConnectionManager = activeConnectionManager,
        ),
    )

    override fun shells(session: AgentShellSession): Flow<List<AgentShell>> = remote.shells(session)

    override fun output(
        shell: AgentShell,
        session: AgentShellSession,
    ): Flow<AgentShellOutput> = remote.output(shell, session)

    override suspend fun stop(
        shell: AgentShell,
        session: AgentShellSession,
    ) {
        check(shell.canStop) { "This shell cannot be stopped" }
        remote.stop(shell, session)
    }
}
