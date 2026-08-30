package dev.opencode.mobile.domain.terminal

import dev.opencode.mobile.domain.connection.ServerProtocol
import dev.opencode.mobile.network.ActiveConnectionManager

internal class ProtocolSelectedTerminalRemote(
    private val v1: TerminalRemote,
    private val v2: TerminalRemote,
    private val activeConnectionManager: ActiveConnectionManager?,
) : TerminalRemote {
    private suspend fun selected(): TerminalRemote = if (activeConnectionManager?.currentProtocolOrProbe() == ServerProtocol.V2) v2 else v1

    override suspend fun list(directory: String): List<TerminalSession> = selected().list(directory)

    override suspend fun create(
        directory: String,
        cwd: String,
    ): TerminalSession = selected().create(directory, cwd)

    override suspend fun get(
        id: String,
        directory: String,
    ): TerminalSession = selected().get(id, directory)

    override suspend fun updateSize(
        id: String,
        directory: String,
        cols: Int,
        rows: Int,
    ): TerminalSession = selected().updateSize(id, directory, cols, rows)

    override suspend fun remove(
        id: String,
        directory: String,
    ) {
        selected().remove(id, directory)
    }
}
