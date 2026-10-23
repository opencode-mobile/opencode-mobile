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
) {
    /** A v2 skill: admitted on the prompt as an inline mention, never run as a command. */
    val isV2Skill: Boolean
        get() = source == ServerCommandSource.Skill && !skillId.isNullOrBlank()
}

data class CommandRunOptions(
    val command: String,
    val arguments: String,
    val attachments: List<Attachment> = emptyList(),
    val model: PromptModelSelection? = null,
    val variant: String? = null,
    val mode: String? = null,
    val delivery: FollowUpDelivery = FollowUpDelivery.Steer,
    /** v2 skills mentioned in the draft; the command route carries them as `skills: [{id}]`. */
    val skillIds: List<String> = emptyList(),
    /** [ClientMessageId] for the user message. v1 keeps it; v2 commands take no id. */
    val messageId: String? = null,
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
