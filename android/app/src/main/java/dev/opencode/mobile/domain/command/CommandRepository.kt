package dev.opencode.mobile.domain.command

interface CommandRepository {
    suspend fun listCommands(directory: String? = null): List<ServerCommand>

    suspend fun runCommand(
        sessionId: String,
        options: CommandRunOptions,
        directory: String? = null,
    )
}
