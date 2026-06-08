package dev.opencode.mobile.domain.message

import dev.opencode.mobile.data.remote.dto.MessageDto
import dev.opencode.mobile.data.remote.dto.MessageErrorInfoDto
import dev.opencode.mobile.data.remote.dto.MessageInfoDto
import dev.opencode.mobile.data.remote.dto.MessagePartDto
import dev.opencode.mobile.data.remote.dto.MessageToolStateDto
import dev.opencode.mobile.data.remote.dto.SendMessagePartDto
import dev.opencode.mobile.data.remote.dto.SendMessageRequestDto
import dev.opencode.mobile.data.remote.dto.SendPromptModelDto
import io.ktor.client.plugins.ResponseException
import io.ktor.serialization.JsonConvertException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

class MessageDomainAdapter {
    private val json = Json { ignoreUnknownKeys = true }
    fun toMessage(dto: MessageDto): Message {
        val info = dto.info
        val domainParts = parseParts(dto.parts)
        val flatText = displayText(domainParts)
        return Message(
            id = info.id,
            role = toRole(info.role),
            text = flatText.ifBlank { errorText(info.error) },
            parts = domainParts,
            model = info.model?.modelId ?: info.modelId,
            modelProviderId = info.model?.providerId ?: info.providerId,
            modelId = info.model?.modelId ?: info.modelId,
            variant = info.variant,
            mode = info.mode ?: info.agent,
            sentAtEpochMs = info.time?.created,
            contextInputTokens = info.tokens?.input,
        )
    }

    fun toMessage(
        info: MessageInfoDto,
        domainParts: List<MessagePart>,
    ): Message {
        val flatText = displayText(domainParts)
        return Message(
            id = info.id,
            role = toRole(info.role),
            text = flatText.ifBlank { errorText(info.error) },
            parts = domainParts,
            model = info.model?.modelId ?: info.modelId,
            modelProviderId = info.model?.providerId ?: info.providerId,
            modelId = info.model?.modelId ?: info.modelId,
            variant = info.variant,
            mode = info.mode ?: info.agent,
            sentAtEpochMs = info.time?.created,
            contextInputTokens = info.tokens?.input,
        )
    }

    fun toMessagePart(dto: MessagePartDto): MessagePart? {
        return when (dto.type) {
            "text" -> {
                val text = dto.text ?: return null
                MessagePart.Text(text = text, isStreaming = false)
            }
            "tool" -> {
                val toolName = dto.tool ?: return null
                val stateDto = parseToolState(dto.state)
                val statusText = toolStatusLabel(toolName, stateDto)
                MessagePart.Tool(toolName = toolName, statusText = statusText)
            }
            else -> null
        }
    }

    private fun parseToolState(stateJson: JsonObject?): MessageToolStateDto? {
        if (stateJson == null) return null
        return runCatching { json.decodeFromJsonElement<MessageToolStateDto>(stateJson) }.getOrNull()
    }

    fun toolStatusLabel(
        toolName: String,
        state: MessageToolStateDto?,
    ): String {
        if (state == null) return "\u231B $toolName"
        return when (state.status) {
            "pending" -> "\u231B $toolName"
            "running" -> {
                val title = state.title?.takeIf { it.isNotEmpty() }
                if (title != null) "\u23F3 $title" else "\u23F3 $toolName"
            }
            "completed" -> "\u2713 $toolName"
            "error" -> {
                val message = state.error ?: "failed"
                "\u2715 $toolName: $message"
            }
            else -> toolName
        }
    }

    fun flattenedText(parts: List<MessagePart>): String = displayText(parts)

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

    // MARK: Part parsing

    fun parseParts(dtos: List<MessagePartDto>): List<MessagePart> =
        dtos.mapNotNull { toMessagePart(it) }

    // MARK: Private

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

    private fun displayText(parts: List<MessagePart>): String {
        val segments = parts.map { part ->
            when (part) {
                is MessagePart.Text -> part.text
                is MessagePart.Tool -> part.statusText
            }
        }
        val nonEmpty = segments.map { it.trim() }.filter { it.isNotEmpty() }
        return nonEmpty.joinToString("\n")
    }
}
