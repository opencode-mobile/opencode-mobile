package dev.opencode.mobile.domain.command

import dev.opencode.mobile.data.remote.dto.CommandDto
import dev.opencode.mobile.data.remote.dto.CommandFilePartDto
import dev.opencode.mobile.data.remote.dto.RunCommandRequestDto
import dev.opencode.mobile.data.remote.dto.V2CommandDto
import dev.opencode.mobile.data.remote.dto.V2ModelRefDto
import dev.opencode.mobile.data.remote.dto.V2PromptFileDto
import dev.opencode.mobile.data.remote.dto.V2RunCommandRequestDto
import dev.opencode.mobile.data.remote.dto.V2SkillDto
import dev.opencode.mobile.data.remote.dto.V2SwitchModelRequestDto
import io.ktor.client.plugins.ResponseException
import io.ktor.serialization.JsonConvertException

class CommandDomainAdapter {
    fun toServerCommands(dtos: List<CommandDto>): List<ServerCommand> =
        dtos.map { dto ->
            ServerCommand(
                name = dto.name,
                description = dto.description?.takeIf { it.isNotBlank() },
                source = toSource(dto.source),
                template = dto.template?.takeIf { it.isNotBlank() },
            )
        }

    fun toServerCommandsFromV2(dtos: List<V2CommandDto>): List<ServerCommand> =
        dtos.map { dto ->
            ServerCommand(
                name = dto.name,
                description = dto.description?.takeIf { it.isNotBlank() },
                source = toSource(dto.source),
                template = dto.template?.takeIf { it.isNotBlank() },
            )
        }

    fun mergeV2SlashCatalog(
        commands: List<ServerCommand>,
        skills: List<V2SkillDto>,
    ): List<ServerCommand> {
        val names = commands.map { it.name }.toSet()
        return commands +
            skills.mapNotNull(::toSkillCommand).filter { it.name !in names }
    }

    fun toSkillCommand(dto: V2SkillDto): ServerCommand? {
        if (dto.slash == false) return null
        val skillId = dto.id?.takeIf { it.isNotBlank() } ?: dto.name
        return ServerCommand(
            name = dto.name,
            description = dto.description?.takeIf { it.isNotBlank() },
            source = ServerCommandSource.Skill,
            template = skillTemplate(dto.content, skillLocation(dto)),
            skillId = skillId,
        )
    }

    private fun skillLocation(dto: V2SkillDto): String? {
        dto.location?.takeIf { it.isNotBlank() }?.let { return it }
        val path = dto.path?.takeIf { it.isNotBlank() } ?: return null
        if (path == BUILT_IN_SKILL_LOCATION || path.startsWith("/builtin")) return BUILT_IN_SKILL_LOCATION
        return path
    }

    internal fun skillTemplate(
        content: String?,
        location: String?,
    ): String? {
        val body = content?.takeIf { it.isNotBlank() } ?: return null
        val directory =
            location
                ?.takeIf { it.isNotBlank() && it != BUILT_IN_SKILL_LOCATION }
                ?.substringBeforeLast('/', missingDelimiterValue = "")
                ?.takeIf { it.isNotBlank() }
        if (directory == null) return body
        return "$body\n\nBase directory for this skill: $directory\n" +
            "Relative paths in this skill (e.g., scripts/, references/) are relative to this base directory."
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

    fun toV2RunRequest(options: CommandRunOptions): V2RunCommandRequestDto =
        V2RunCommandRequestDto(
            name = options.command,
            text = options.arguments,
            files =
                options.attachments
                    .map { attachment ->
                        V2PromptFileDto(
                            uri = attachment.dataUrl,
                            name = attachment.filename,
                            mime = attachment.mime,
                        )
                    }.takeIf { it.isNotEmpty() },
            delivery = options.delivery.wireValue,
        )

    fun toV2SwitchModelRequest(options: CommandRunOptions): V2SwitchModelRequestDto? {
        val model = options.model ?: return null
        return V2SwitchModelRequestDto(
            model =
                V2ModelRefDto(
                    id = model.modelId,
                    providerId = model.providerId,
                    variant = options.variant,
                ),
        )
    }

    fun mapError(throwable: Throwable): CommandError =
        when (throwable) {
            is CommandError -> throwable
            is ResponseException ->
                CommandError.Http(
                    statusCode = throwable.response.status.value,
                    message = responseExceptionBody(throwable),
                )
            is JsonConvertException -> CommandError.Decoding(throwable)
            else -> CommandError.Transport(throwable)
        }

    private fun responseExceptionBody(throwable: ResponseException): String? {
        val raw = throwable.message ?: return null
        val marker = ". Text: \""
        val start = raw.indexOf(marker)
        if (start < 0) return raw
        return raw.substring(start + marker.length).removeSuffix("\"").takeIf { it.isNotBlank() }
    }

    private fun toSource(raw: String?): ServerCommandSource =
        when (raw?.lowercase()) {
            "mcp" -> ServerCommandSource.Mcp
            "skill" -> ServerCommandSource.Skill
            else -> ServerCommandSource.Command
        }

    private companion object {
        const val BUILT_IN_SKILL_LOCATION = "<built-in>"
    }
}
