package dev.opencode.mobile.domain.command

import dev.opencode.mobile.domain.message.Attachment
import dev.opencode.mobile.domain.message.FollowUpDelivery
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
    /** Expanded prompt template from `GET /command`; used to collapse chat display. */
    val template: String? = null,
    /**
     * v2 skill id used to admit the skill on a prompt. Absent for real commands,
     * including v1 entries whose source happens to be a skill.
     */
    val skillId: String? = null,
)

data class CommandRunOptions(
    val command: String,
    val arguments: String,
    val attachments: List<Attachment> = emptyList(),
    val model: PromptModelSelection? = null,
    val variant: String? = null,
    val mode: String? = null,
    val delivery: FollowUpDelivery = FollowUpDelivery.Steer,
)

sealed class ComposerSubmitIntent {
    data class Prompt(
        val text: String,
    ) : ComposerSubmitIntent()

    data class ServerCommand(
        val command: String,
        val arguments: String,
    ) : ComposerSubmitIntent()

    /** A slash skill. Admitted on the prompt, not the command route. */
    data class SkillPrompt(
        val skillId: String,
        val text: String,
    ) : ComposerSubmitIntent()

    /** Slash text on an engine that has no command catalogue. Nothing is sent. */
    data object Ignored : ComposerSubmitIntent()
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
