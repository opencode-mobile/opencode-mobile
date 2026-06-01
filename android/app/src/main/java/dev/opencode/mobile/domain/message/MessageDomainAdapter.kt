package dev.opencode.mobile.domain.message

import dev.opencode.mobile.data.remote.dto.MessageDto
import dev.opencode.mobile.data.remote.dto.MessageErrorInfoDto
import dev.opencode.mobile.data.remote.dto.MessagePartDto
import dev.opencode.mobile.data.remote.dto.SendMessagePartDto
import dev.opencode.mobile.data.remote.dto.SendMessageRequestDto
import dev.opencode.mobile.data.remote.dto.SendPromptModelDto
import io.ktor.client.plugins.ResponseException
import io.ktor.serialization.JsonConvertException

class MessageDomainAdapter {
    fun toMessage(dto: MessageDto): Message {
        val info = dto.info
        return Message(
            id = info.id,
            role = toRole(info.role),
            text = displayText(dto.parts).ifBlank { errorText(info.error) },
            model = info.model?.modelId ?: info.modelId,
            modelProviderId = info.model?.providerId ?: info.providerId,
            modelId = info.model?.modelId ?: info.modelId,
            variant = info.variant,
            mode = info.mode ?: info.agent,
            sentAtEpochMs = info.time?.created,
            contextInputTokens = info.tokens?.input,
        )
    }

    fun toSendRequest(text: String): SendMessageRequestDto =
        SendMessageRequestDto(parts = listOf(SendMessagePartDto(type = "text", text = text)))

    fun toSendRequest(options: PromptSendOptions): SendMessageRequestDto =
        SendMessageRequestDto(
            parts = listOf(SendMessagePartDto(type = "text", text = options.text)),
            model = options.model?.let { SendPromptModelDto(providerId = it.providerId, modelId = it.modelId) },
            variant = options.variant,
        )

    fun mapError(throwable: Throwable): MessageError =
        when (throwable) {
            is MessageError -> throwable
            is ResponseException ->
                MessageError.Http(
                    statusCode = throwable.response.status.value,
                    message = throwable.message,
                )
            is JsonConvertException -> MessageError.Decoding(throwable)
            else -> MessageError.Transport(throwable)
        }

    private fun toRole(raw: String): MessageRole =
        when (raw.lowercase()) {
            "user" -> MessageRole.User
            "assistant" -> MessageRole.Assistant
            "command" -> MessageRole.Command
            "tool" -> MessageRole.Tool
            else -> MessageRole.Unknown
        }

    private fun errorText(error: MessageErrorInfoDto?): String {
        error ?: return ""
        return error.data
            ?.message
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: error.data
                ?.responseBody
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            ?: error.name?.trim()?.takeIf { it.isNotEmpty() }
            ?: ""
    }

    private fun displayText(parts: List<MessagePartDto>): String {
        val textParts = parts.filter { it.type == "text" }.mapNotNull { it.text?.trim() }.filter { it.isNotEmpty() }
        if (textParts.isNotEmpty()) {
            return textParts.joinToString("\n")
        }
        val toolParts = parts.filter { it.type == "tool" }
        if (toolParts.isNotEmpty()) {
            val labels = toolParts.mapNotNull { part -> part.tool?.let { tool -> "Tool: $tool" } }
            if (labels.isNotEmpty()) {
                return labels.joinToString("\n")
            }
        }
        return ""
    }
}
