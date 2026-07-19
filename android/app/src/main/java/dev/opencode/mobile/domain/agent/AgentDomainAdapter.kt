package dev.opencode.mobile.domain.agent

import dev.opencode.mobile.data.remote.dto.AgentDto
import io.ktor.client.plugins.ResponseException
import io.ktor.serialization.JsonConvertException

class AgentDomainAdapter {
    fun toMentionableAgents(dtos: List<AgentDto>): List<MentionableAgent> =
        dtos.mapNotNull { dto ->
            if (dto.mode.equals("primary", ignoreCase = true)) return@mapNotNull null
            if (dto.hidden == true) return@mapNotNull null
            MentionableAgent(
                name = dto.name,
                description = dto.description?.takeIf { it.isNotBlank() },
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
