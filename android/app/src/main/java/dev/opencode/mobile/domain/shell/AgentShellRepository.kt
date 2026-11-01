package dev.opencode.mobile.domain.shell

import dev.opencode.mobile.domain.message.Message
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf

/** The open session whose shells are read. */
data class AgentShellSession(
    val sessionId: String,
    /** The session's directory; v2 shell routes are scoped by it. */
    val directory: String,
    /**
     * The session's loaded messages, live. Servers without shell routes (v1) read shells from
     * the `bash` tool parts in them; others ignore it.
     */
    val messages: Flow<List<Message>>,
)

/**
 * Agent-run shells of the open session, behind the protocol. Feature code sees only
 * [AgentShell] and [AgentShellOutput].
 */
interface AgentShellRepository {
    /**
     * The session's shells, live: running ones, plus ended ones the source still knows about so
     * an open output sheet can show how a shell ended. Restarts its server reads on reconnect.
     */
    fun shells(session: AgentShellSession): Flow<List<AgentShell>>

    /**
     * Output of [shell] while collected. A running shell is read until it ends or the collector
     * goes away; an ended one is read once. Emits only changes; ends when the shell is removed.
     */
    fun output(
        shell: AgentShell,
        session: AgentShellSession,
    ): Flow<AgentShellOutput>

    /** Stops a running shell. Only for shells with [AgentShell.canStop]; throws on failure. */
    suspend fun stop(
        shell: AgentShell,
        session: AgentShellSession,
    )
}

/** No shells. View-model tests and previews use this when they do not pass a repository. */
object EmptyAgentShellRepository : AgentShellRepository {
    override fun shells(session: AgentShellSession): Flow<List<AgentShell>> = flowOf(emptyList())

    override fun output(
        shell: AgentShell,
        session: AgentShellSession,
    ): Flow<AgentShellOutput> = emptyFlow()

    override suspend fun stop(
        shell: AgentShell,
        session: AgentShellSession,
    ) = Unit
}
