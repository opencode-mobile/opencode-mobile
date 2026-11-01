package dev.opencode.mobile.domain.shell

import dev.opencode.mobile.data.remote.ShellApi
import dev.opencode.mobile.domain.connection.ServerProtocol
import dev.opencode.mobile.domain.event.EventStreamConnectionState
import dev.opencode.mobile.domain.event.ForegroundEventStream
import dev.opencode.mobile.domain.message.MessagePart
import dev.opencode.mobile.network.ActiveConnectionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal interface AgentShellRemote {
    fun shells(session: AgentShellSession): Flow<List<AgentShell>>

    fun output(
        shell: AgentShell,
        session: AgentShellSession,
    ): Flow<AgentShellOutput>

    suspend fun stop(
        shell: AgentShell,
        session: AgentShellSession,
    )
}

internal class ProtocolSelectedAgentShellRemote(
    private val v1: AgentShellRemote,
    private val v2: AgentShellRemote,
    private val activeConnectionManager: ActiveConnectionManager?,
) : AgentShellRemote {
    private suspend fun selected(): AgentShellRemote = if (activeConnectionManager?.currentProtocolOrProbe() == ServerProtocol.V2) v2 else v1

    override fun shells(session: AgentShellSession): Flow<List<AgentShell>> = flow { emitAll(selected().shells(session)) }

    override fun output(
        shell: AgentShell,
        session: AgentShellSession,
    ): Flow<AgentShellOutput> = flow { emitAll(selected().output(shell, session)) }

    override suspend fun stop(
        shell: AgentShell,
        session: AgentShellSession,
    ) {
        selected().stop(shell, session)
    }
}

/**
 * v1 has no shell routes. A running `bash` tool part is a running shell; its live output is
 * `metadata.output`, which `message.part.updated` already streams with every chunk.
 */
internal class V1AgentShellRemote(
    private val adapter: AgentShellDomainAdapter,
) : AgentShellRemote {
    override fun shells(session: AgentShellSession): Flow<List<AgentShell>> =
        session.messages
            .map { adapter.shellsFromMessages(it, session.sessionId) }
            .distinctUntilChanged()

    override fun output(
        shell: AgentShell,
        session: AgentShellSession,
    ): Flow<AgentShellOutput> =
        session.messages
            .mapNotNull { messages ->
                messages
                    .asSequence()
                    .flatMap { it.parts.asSequence() }
                    .filterIsInstance<MessagePart.Tool>()
                    .firstOrNull { it.id == shell.id }
            }.map(adapter::outputFromBashPart)
            .distinctUntilChanged()

    override suspend fun stop(
        shell: AgentShell,
        session: AgentShellSession,
    ): Unit = throw UnsupportedOperationException("v1 servers cannot stop one shell")
}

/**
 * v2 `/api/shell`: the list on start and on every reconnect, `shell.created` / `shell.exited`
 * / `shell.deleted` on `/api/event` in between. There is no output event, so output is polled
 * while someone collects it. Servers that do not declare the routes have no shells.
 */
internal class V2AgentShellRemote(
    private val api: ShellApi,
    private val adapter: AgentShellDomainAdapter,
    private val eventStream: ForegroundEventStream,
    private val supported: () -> Boolean,
    private val pollIntervalMs: Long = OUTPUT_POLL_INTERVAL_MS,
) : AgentShellRemote {
    override fun shells(session: AgentShellSession): Flow<List<AgentShell>> {
        if (!supported()) return flowOf(emptyList())
        return channelFlow {
            val known = MutableStateFlow(V2ShellList())
            // Events first, so a shell created while the list loads is not lost.
            launch(start = CoroutineStart.UNDISPATCHED) {
                eventStream.events.collect { event ->
                    val shellEvent = adapter.shellEvent(event) ?: return@collect
                    known.update { it.apply(shellEvent, session.sessionId, adapter) }
                }
            }
            launch(start = CoroutineStart.UNDISPATCHED) {
                var wasConnected = eventStream.connectionState.value == EventStreamConnectionState.Connected
                eventStream.connectionState.collect { state ->
                    val connected = state == EventStreamConnectionState.Connected
                    if (connected && !wasConnected) refresh(known, session)
                    wasConnected = connected
                }
            }
            launch { refresh(known, session) }
            known.collect { send(it.shells) }
        }.distinctUntilChanged()
    }

    private suspend fun refresh(
        known: MutableStateFlow<V2ShellList>,
        session: AgentShellSession,
    ) {
        val listed =
            try {
                api
                    .listV2(session.directory)
                    .filter { adapter.sessionIdOf(it) == session.sessionId }
                    .map { adapter.fromInfo(it, canStop = true) }
                    .filter { it.isRunning }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                return
            }
        known.update { it.replaceRunning(listed) }
    }

    override fun output(
        shell: AgentShell,
        session: AgentShellSession,
    ): Flow<AgentShellOutput> =
        flow {
            val reader = V2ShellOutputReader { cursor -> api.outputV2(shell.id, session.directory, cursor) }
            while (true) {
                val changed =
                    try {
                        reader.poll()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        false
                    }
                if (changed) emit(reader.output)
                if (reader.removed || !shell.isRunning) return@flow
                delay(pollIntervalMs)
            }
        }

    override suspend fun stop(
        shell: AgentShell,
        session: AgentShellSession,
    ) {
        api.deleteV2(shell.id, session.directory)
    }

    companion object {
        const val OUTPUT_POLL_INTERVAL_MS = 1_000L
    }
}

/**
 * What the v2 source knows about the session's shells, in the order it learned them. Ended
 * shells stay (with their end state) until `shell.deleted` or the next list drops them.
 */
internal data class V2ShellList(
    val byId: Map<String, AgentShell> = emptyMap(),
    /** Shells `shell.created` added since the last list was applied; that list may predate them. */
    val createdDuringRefresh: Set<String> = emptySet(),
) {
    val shells: List<AgentShell>
        get() = byId.values.toList()

    fun apply(
        event: AgentShellEvent,
        sessionId: String,
        adapter: AgentShellDomainAdapter,
    ): V2ShellList =
        when (event) {
            is AgentShellEvent.Created -> {
                if (adapter.sessionIdOf(event.info) != sessionId) {
                    this
                } else {
                    val shell = adapter.fromInfo(event.info, canStop = true)
                    copy(byId = byId + (shell.id to shell), createdDuringRefresh = createdDuringRefresh + shell.id)
                }
            }
            is AgentShellEvent.Exited -> {
                val shell = byId[event.id]
                if (shell == null) {
                    this
                } else {
                    val ended =
                        shell.copy(
                            status = event.status,
                            finishedAtMs = event.atMs ?: shell.finishedAtMs,
                            canStop = false,
                        )
                    copy(byId = byId + (shell.id to ended))
                }
            }
            is AgentShellEvent.Deleted -> copy(byId = byId - event.id, createdDuringRefresh = createdDuringRefresh - event.id)
        }

    /**
     * The list holds every running shell. A running shell missing from it ended or went away
     * while the app was not listening; ended shells and ones created meanwhile stay.
     */
    fun replaceRunning(listed: List<AgentShell>): V2ShellList {
        val listedIds = listed.mapTo(mutableSetOf()) { it.id }
        val kept =
            byId.filterValues { shell ->
                !shell.isRunning || shell.id in listedIds || shell.id in createdDuringRefresh
            }
        val merged = LinkedHashMap(kept)
        for (shell in listed) {
            if (merged[shell.id]?.isRunning == false) continue
            merged[shell.id] = shell
        }
        return copy(byId = merged, createdDuringRefresh = emptySet())
    }
}
