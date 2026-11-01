package dev.opencode.mobile.domain.shell

/** Where an agent-run shell command is. */
sealed interface AgentShellStatus {
    data object Running : AgentShellStatus

    /** The command ended on its own; [code] is its exit code when the server reports one. */
    data class Exited(
        val code: Int?,
    ) : AgentShellStatus

    data object TimedOut : AgentShellStatus

    /** Killed: stopped from the app, or the run it belonged to was stopped. */
    data object Stopped : AgentShellStatus

    data object Failed : AgentShellStatus
}

/**
 * A shell command an agent runs for the open session, foreground or background. v2 reads
 * these from `/api/shell`; v1 from the session's `bash` tool parts.
 */
data class AgentShell(
    /** v2 shell id (`sh_…`); v1 the bash tool part id. */
    val id: String,
    val sessionId: String,
    val command: String,
    /** What the agent said the command does (tool input `description`), when known. */
    val description: String?,
    val status: AgentShellStatus,
    val startedAtMs: Long?,
    val finishedAtMs: Long?,
    /** The app can stop it: true while running on a server that supports stopping. */
    val canStop: Boolean,
) {
    val isRunning: Boolean
        get() = status == AgentShellStatus.Running
}

/** The latest output of one shell, as the output sheet shows it. */
data class AgentShellOutput(
    /** At most the last [MAX_CHARS] characters. */
    val text: String = "",
    /** Older output was dropped, by the app's cap or by the server. */
    val isTruncated: Boolean = false,
) {
    companion object {
        const val MAX_CHARS = 30_000

        /** [text] cut to its last [MAX_CHARS] characters. */
        fun capped(
            text: String,
            truncated: Boolean = false,
        ): AgentShellOutput =
            if (text.length > MAX_CHARS) {
                AgentShellOutput(text.takeLast(MAX_CHARS), isTruncated = true)
            } else {
                AgentShellOutput(text, isTruncated = truncated)
            }
    }
}
