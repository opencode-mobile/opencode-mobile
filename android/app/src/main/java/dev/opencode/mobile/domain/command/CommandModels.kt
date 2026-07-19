package dev.opencode.mobile.domain.command

import dev.opencode.mobile.domain.message.Attachment
import dev.opencode.mobile.domain.message.PromptModelSelection

enum class ServerCommandSource {
    Command,
    Mcp,
    Skill,
}

data class ServerCommand(
    val name: String,
    val description: String? = null,
    val source: ServerCommandSource = ServerCommandSource.Command,
)

data class CommandRunOptions(
    val command: String,
    val arguments: String,
    val attachments: List<Attachment> = emptyList(),
    val model: PromptModelSelection? = null,
    val variant: String? = null,
    val mode: String? = null,
)

sealed class ComposerSubmitIntent {
    data class Prompt(
        val text: String,
    ) : ComposerSubmitIntent()

    data class ServerCommand(
        val command: String,
        val arguments: String,
    ) : ComposerSubmitIntent()
}

sealed class CommandError : Exception() {
    data class Transport(
        override val cause: Throwable,
    ) : CommandError()

    data class Http(
        val statusCode: Int,
        override val message: String?,
    ) : CommandError()

    data class Decoding(
        override val cause: Throwable,
    ) : CommandError()
}
