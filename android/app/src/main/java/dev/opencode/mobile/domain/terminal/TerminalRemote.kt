package dev.opencode.mobile.domain.terminal

internal interface TerminalRemote {
    suspend fun list(directory: String): List<TerminalSession>

    suspend fun create(
        directory: String,
        cwd: String,
    ): TerminalSession

    suspend fun get(
        id: String,
        directory: String,
    ): TerminalSession

    suspend fun updateSize(
        id: String,
        directory: String,
        cols: Int,
        rows: Int,
    ): TerminalSession

    suspend fun remove(
        id: String,
        directory: String,
    )
}
