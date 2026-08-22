package dev.opencode.mobile.domain.agent

import dev.opencode.mobile.data.remote.dto.AgentDto
import dev.opencode.mobile.data.remote.dto.V2AgentDto
import io.ktor.client.plugins.ResponseException
import io.ktor.serialization.JsonConvertException

class AgentDomainAdapter {
    fun toSelectableAgents(dtos: List<AgentDto>): List<SelectableAgent> =
        dtos.mapNotNull { dto ->
            selectableAgent(
                id = dto.name,
                mode = dto.mode,
                hidden = dto.hidden,
                description = dto.description,
            )
        }

    fun toSelectableAgentsFromV2(dtos: List<V2AgentDto>): List<SelectableAgent> =
        dtos.mapNotNull { dto ->
            selectableAgent(
                id = dto.id,
                mode = dto.mode,
                hidden = dto.hidden,
                description = dto.description,
            )
        }

    fun toMentionableAgents(dtos: List<AgentDto>): List<MentionableAgent> =
        dtos.mapNotNull { dto ->
            if (dto.mode.equals("primary", ignoreCase = true)) return@mapNotNull null
            if (dto.hidden == true) return@mapNotNull null
            MentionableAgent(
                name = dto.name,
                description = dto.description?.takeIf { it.isNotBlank() },
            )
        }

    fun toMentionableAgentsFromV2(dtos: List<V2AgentDto>): List<MentionableAgent> =
        dtos.mapNotNull { dto ->
            if (dto.mode.equals("primary", ignoreCase = true)) return@mapNotNull null
            if (dto.hidden == true) return@mapNotNull null
            MentionableAgent(
                name = dto.id,
                description = dto.description?.takeIf { it.isNotBlank() },
            )
        }

    private fun selectableAgent(
        id: String,
        mode: String,
        hidden: Boolean?,
        description: String?,
    ): SelectableAgent? {
        val normalized = mode.lowercase()
        if (normalized != "primary" && normalized != "all") return null
        if (hidden == true) return null
        return SelectableAgent(
            id = id,
            displayName = SelectableAgent.displayNameFor(id),
            description = description?.takeIf { it.isNotBlank() },
        )
    }

    fun mapError(throwable: Throwable): AgentError =
        when (throwable) {
            is AgentError -> throwable
            is ResponseException ->
                AgentError.Http(
                    statusCode = throwable.response.status.value,
                    message = throwable.message,
                )
            is JsonConvertException -> AgentError.Decoding(throwable)
            else -> AgentError.Transport(throwable)
        }
}
