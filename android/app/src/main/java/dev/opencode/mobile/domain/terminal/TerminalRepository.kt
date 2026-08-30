package dev.opencode.mobile.domain.terminal

import dev.opencode.mobile.domain.connection.ServerProtocol

interface TerminalRepository {
    suspend fun list(directory: String): List<TerminalSession>

    suspend fun create(
        directory: String,
        cwd: String = directory,
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

    suspend fun ensureRunning(directory: String): TerminalSession

    suspend fun remove(
        id: String,
        directory: String,
    )

    suspend fun listRunning(directories: List<String>): List<RunningTerminal>

    fun connectRequest(
        id: String,
        directory: String,
        protocol: ServerProtocol,
        baseUrl: String,
        cursor: String,
        ticket: String? = null,
        username: String,
        password: String,
    ): TerminalConnectRequest
}
