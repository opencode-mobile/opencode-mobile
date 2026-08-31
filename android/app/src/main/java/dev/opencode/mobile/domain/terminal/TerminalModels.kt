package dev.opencode.mobile.domain.terminal

enum class TerminalSessionStatus {
    Running,
    Exited,
}

data class TerminalSession(
    val id: String,
    val title: String,
    val command: String,
    val cwd: String,
    val status: TerminalSessionStatus,
)

data class RunningTerminal(
    val directory: String,
    val session: TerminalSession,
)

sealed class TerminalError : Exception() {
    data class Transport(
        override val cause: Throwable,
    ) : TerminalError()

    data class Http(
        val statusCode: Int,
        override val message: String?,
    ) : TerminalError()

    data class Decoding(
        override val cause: Throwable,
    ) : TerminalError()
}
