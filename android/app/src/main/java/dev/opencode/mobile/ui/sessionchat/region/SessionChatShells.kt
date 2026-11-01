package dev.opencode.mobile.ui.sessionchat.region

import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.shell.AgentShell
import dev.opencode.mobile.domain.shell.AgentShellOutput
import dev.opencode.mobile.domain.shell.AgentShellRepository
import dev.opencode.mobile.domain.shell.AgentShellSession
import dev.opencode.mobile.domain.shell.AgentShellStatus
import dev.opencode.mobile.ui.sessionchat.SessionChatBackgroundWork
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

object SessionChatShellsCopy {
    const val SECTION_TITLE = "Shells"
    const val NO_OUTPUT = "No output yet"
    const val TRUNCATED = "Showing the latest output"
    const val COPY = "Copy"
    const val STOP = "Stop shell"
    const val STOPPING = "Stopping…"
    const val STOP_FAILED = "Couldn't stop the shell. Try again."

    /** "Running · 1 min 12 s", "Exited with code 1 · 4 s"; just the state when the start is unknown. */
    fun status(
        shell: AgentShell,
        nowMs: Long,
    ): String {
        val state =
            when (val status = shell.status) {
                AgentShellStatus.Running -> "Running"
                is AgentShellStatus.Exited ->
                    if (status.code == null || status.code == 0) "Exited" else "Exited with code ${status.code}"
                AgentShellStatus.TimedOut -> "Timed out"
                AgentShellStatus.Stopped -> "Stopped"
                AgentShellStatus.Failed -> "Failed"
            }
        val start = shell.startedAtMs ?: return state
        val end = if (shell.isRunning) nowMs else shell.finishedAtMs ?: return state
        return "$state · ${duration(end - start)}"
    }

    /** "12 s", "1 min 12 s", "2 h 5 min". */
    fun duration(elapsedMs: Long): String {
        val total = (elapsedMs / 1000).coerceAtLeast(0)
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val seconds = total % 60
        return when {
            hours > 0 -> "$hours h $minutes min"
            minutes > 0 -> "$minutes min $seconds s"
            else -> "$seconds s"
        }
    }
}

/** The shell output sheet: one shell, its latest output, and the Stop action's progress. */
data class SessionChatShellOutputUiState(
    val shell: AgentShell,
    /** Null until the first read answers. */
    val output: AgentShellOutput? = null,
    val isStopping: Boolean = false,
    val stopError: String? = null,
) {
    /** Stop shows only where the server can stop this shell, and only while it runs. */
    val showsStop: Boolean
        get() = shell.canStop && shell.isRunning
}

data class SessionChatShellsRegionState(
    /** Running shells of the open session, in panel order. */
    val shells: List<AgentShell> = emptyList(),
    /** The output sheet's content; null while it is closed. */
    val output: SessionChatShellOutputUiState? = null,
)

/**
 * Agent-run shells of the open session for the background work panel, and the output sheet.
 * Output is read only while the sheet is open and the screen is in the foreground
 * ([setOutputObserved]); an ended shell's sheet keeps its final state and last output.
 */
class SessionChatShells(
    private val repository: AgentShellRepository,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private val _state = MutableStateFlow(SessionChatShellsRegionState())
    val state: StateFlow<SessionChatShellsRegionState> = _state.asStateFlow()

    private var session: AgentShellSession? = null
    private var listJob: Job? = null
    private var outputJob: Job? = null

    /** What the output job reads: the shell id and whether it still ran when the job started. */
    private var outputKey: Pair<String, Boolean>? = null
    private var observed = false

    fun bind(
        sessionId: String,
        directory: String,
        messages: Flow<List<Message>>,
    ) {
        val next = AgentShellSession(sessionId = sessionId, directory = directory, messages = messages)
        if (session?.sessionId == sessionId && session?.directory == directory) return
        reset()
        session = next
        listJob = scope.launch { repository.shells(next).collect(::onShells) }
    }

    fun reset() {
        listJob?.cancel()
        listJob = null
        stopOutput()
        session = null
        _state.value = SessionChatShellsRegionState()
    }

    fun open(id: String) {
        val shell = _state.value.shells.firstOrNull { it.id == id } ?: return
        _state.update { it.copy(output = SessionChatShellOutputUiState(shell)) }
        syncOutput()
    }

    fun close() {
        _state.update { it.copy(output = null) }
        syncOutput()
    }

    /** The output sheet is on screen and the app is in the foreground. */
    fun setOutputObserved(observed: Boolean) {
        if (this.observed == observed) return
        this.observed = observed
        syncOutput()
    }

    fun stop(id: String) {
        val current = _state.value.output ?: return
        val session = session ?: return
        if (current.shell.id != id || !current.showsStop || current.isStopping) return
        _state.update { it.copy(output = it.output?.copy(isStopping = true, stopError = null)) }
        scope.launch {
            try {
                repository.stop(current.shell, session)
                // Stays Stopping… until the server reports the shell ended.
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                _state.update { state ->
                    val output = state.output?.takeIf { it.shell.id == id } ?: return@update state
                    state.copy(output = output.copy(isStopping = false, stopError = SessionChatShellsCopy.STOP_FAILED))
                }
            }
        }
    }

    private fun onShells(shells: List<AgentShell>) {
        _state.update { state ->
            val open = state.output
            val updated =
                if (open == null) {
                    null
                } else {
                    val latest = shells.firstOrNull { it.id == open.shell.id }
                    val shell =
                        when {
                            latest != null -> latest
                            // Removed right after a stop, before its end reached the app.
                            open.isStopping && open.shell.isRunning ->
                                open.shell.copy(status = AgentShellStatus.Stopped, finishedAtMs = nowMs(), canStop = false)
                            else -> open.shell
                        }
                    open.copy(shell = shell, isStopping = open.isStopping && shell.isRunning)
                }
            state.copy(shells = SessionChatBackgroundWork.runningShells(shells), output = updated)
        }
        syncOutput()
    }

    private fun syncOutput() {
        val open = _state.value.output?.shell
        val session = session
        val wanted = if (open != null && session != null && observed) open.id to open.isRunning else null
        if (wanted == outputKey) return
        stopOutput()
        if (open == null || session == null || wanted == null) return
        outputKey = wanted
        outputJob =
            scope.launch {
                repository.output(open, session).collect { output ->
                    _state.update { state ->
                        val sheet = state.output?.takeIf { it.shell.id == open.id } ?: return@update state
                        state.copy(output = sheet.copy(output = output))
                    }
                }
            }
    }

    private fun stopOutput() {
        outputJob?.cancel()
        outputJob = null
        outputKey = null
    }
}
