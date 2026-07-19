package dev.opencode.mobile.domain.command

import dev.opencode.mobile.data.remote.dto.CommandDto
import dev.opencode.mobile.data.remote.dto.CommandFilePartDto
import dev.opencode.mobile.data.remote.dto.RunCommandRequestDto
import io.ktor.client.plugins.ResponseException
import io.ktor.serialization.JsonConvertException

class CommandDomainAdapter {
    fun toServerCommands(dtos: List<CommandDto>): List<ServerCommand> =
        dtos.map { dto ->
            ServerCommand(
                name = dto.name,
                description = dto.description?.takeIf { it.isNotBlank() },
                source = toSource(dto.source),
            )
        }

    fun toRunRequest(options: CommandRunOptions): RunCommandRequestDto =
        RunCommandRequestDto(
            command = options.command,
            arguments = options.arguments,
            agent = options.mode,
            model =
                options.model?.let { selection ->
                    "${selection.providerId}/${selection.modelId}"
                },
            variant = options.variant,
            parts =
                options.attachments.map { attachment ->
                    CommandFilePartDto(
                        mime = attachment.mime,
                        url = attachment.dataUrl,
                        filename = attachment.filename,
                    )
                },
        )

    fun mapError(throwable: Throwable): CommandError =
        when (throwable) {
            is CommandError -> throwable
            is ResponseException ->
                CommandError.Http(
                    statusCode = throwable.response.status.value,
                    message = throwable.message,
                )
            is JsonConvertException -> CommandError.Decoding(throwable)
            else -> CommandError.Transport(throwable)
        }

    private fun toSource(raw: String?): ServerCommandSource =
        when (raw?.lowercase()) {
            "mcp" -> ServerCommandSource.Mcp
            "skill" -> ServerCommandSource.Skill
            else -> ServerCommandSource.Command
        }
}
