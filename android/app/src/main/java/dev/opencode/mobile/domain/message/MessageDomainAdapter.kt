package dev.opencode.mobile.domain.message

import dev.opencode.mobile.data.remote.dto.AgentPartSourceDto
import dev.opencode.mobile.data.remote.dto.MessageDto
import dev.opencode.mobile.data.remote.dto.MessageErrorInfoDto
import dev.opencode.mobile.data.remote.dto.MessageInfoDto
import dev.opencode.mobile.data.remote.dto.MessagePartDto
import dev.opencode.mobile.data.remote.dto.MessageTokensDto
import dev.opencode.mobile.data.remote.dto.MessageToolStateDto
import dev.opencode.mobile.data.remote.dto.SendMessagePartDto
import dev.opencode.mobile.data.remote.dto.SendMessageRequestDto
import dev.opencode.mobile.data.remote.dto.SendPromptModelDto
import dev.opencode.mobile.domain.agent.ComposerMentionRouting
import io.ktor.client.plugins.ResponseException
import io.ktor.serialization.JsonConvertException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
            cost = info.cost,
            tokens = toTokenUsage(info.tokens),
            model = info.model?.modelId ?: info.modelId,
            modelProviderId = info.model?.providerId ?: info.providerId,
            modelId = info.model?.modelId ?: info.modelId,
            variant = info.variant,
            mode = info.mode ?: info.agent,
            sentAtEpochMs = info.time?.created,
            contextInputTokens = info.tokens?.input,
            contextEstimate = toContextEstimate(info.role, dto.parts),
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
            cost = info.cost,
            tokens = toTokenUsage(info.tokens),
            model = info.model?.modelId ?: info.modelId,
            modelProviderId = info.model?.providerId ?: info.providerId,
            modelId = info.model?.modelId ?: info.modelId,
            variant = info.variant,
            mode = info.mode ?: info.agent,
            sentAtEpochMs = info.time?.created,
            contextInputTokens = info.tokens?.input,
            contextEstimate = MessageContextEstimate(),
        )
    }

    fun toMessagePart(dto: MessagePartDto): MessagePart? {
        return when (dto.type) {
            "text" -> {
                val text = dto.text ?: return null
                MessagePart.Text(
                    text = text,
                    isStreaming = false,
                    id = dto.id,
                    isSynthetic = dto.synthetic == true,
                    isIgnored = dto.ignored == true,
                )
            }
            "tool" -> {
                val toolName = dto.tool ?: return null
                val stateDto = parseToolState(dto.state)
                toToolPart(toolName = toolName, callId = dto.callId, state = stateDto, id = dto.id)
            }
            "file" -> toFilePart(dto)
            "compaction" -> MessagePart.Compaction(id = dto.id, auto = dto.auto == true)
            else -> null
        }
    }

    private fun parseToolState(stateJson: JsonObject?): MessageToolStateDto? {
        if (stateJson == null) return null
        return runCatching { json.decodeFromJsonElement<MessageToolStateDto>(stateJson) }.getOrNull()
    }

    fun toToolPart(
        toolName: String,
        callId: String? = null,
        state: MessageToolStateDto?,
        id: String? = null,
    ): MessagePart.Tool {
        val status = toToolStatus(state?.status)
        return MessagePart.Tool(
            toolName = toolName,
            status = status,
            title = state?.title?.takeIf { it.isNotEmpty() },
            error = state?.error?.takeIf { it.isNotEmpty() },
            callId = callId,
            input = state?.input,
            output = state?.output,
            metadata = state?.metadata,
            id = id,
        )
    }

    fun toToolStatus(raw: String?): ToolStatus =
        when (raw) {
            "pending" -> ToolStatus.Pending
            "running" -> ToolStatus.Running
            "completed" -> ToolStatus.Completed
            "error" -> ToolStatus.Error
            else -> ToolStatus.Pending
        }

    fun compactToolLabel(tool: MessagePart.Tool): String =
        when (tool.status) {
            ToolStatus.Pending -> "\u231B ${tool.toolName}"
            ToolStatus.Running -> {
                val title = tool.title?.takeIf { it.isNotEmpty() }
                if (title != null) "\u23F3 $title" else "\u23F3 ${tool.toolName}"
            }
            ToolStatus.Completed -> "\u2713 ${tool.toolName}"
            ToolStatus.Error -> {
                val message = tool.error?.takeIf { it.isNotEmpty() } ?: "failed"
                "\u2715 ${tool.toolName}: $message"
            }
        }

    fun flattenedText(parts: List<MessagePart>): String = displayText(parts)

    fun toSendRequest(text: String): SendMessageRequestDto =
        SendMessageRequestDto(parts = listOf(SendMessagePartDto(type = "text", text = text)))

    fun toSendRequest(options: PromptSendOptions): SendMessageRequestDto =
        SendMessageRequestDto(
            parts =
                buildList {
                    if (options.text.isNotBlank()) {
                        add(SendMessagePartDto(type = "text", text = options.text))
                    }
                    ComposerMentionRouting.agentParts(options.text, options.agentMentions).forEach { part ->
                        add(
                            SendMessagePartDto(
                                type = "agent",
                                name = part.name,
                                source =
                                    AgentPartSourceDto(
                                        value = part.sourceValue,
                                        start = part.start,
                                        end = part.end,
                                    ),
                            ),
                        )
                    }
                    options.attachments.forEach { attachment ->
                        add(
                            SendMessagePartDto(
                                type = "file",
                                mime = attachment.mime,
                                url = attachment.dataUrl,
                                filename = attachment.filename,
                            ),
                        )
                    }
                },
            model = options.model?.let { SendPromptModelDto(providerId = it.providerId, modelId = it.modelId) },
            variant = options.variant,
            agent = options.mode,
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

    fun parseParts(dtos: List<MessagePartDto>): List<MessagePart> = dtos.mapNotNull { toMessagePart(it) }

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
        val segments =
            parts.mapNotNull { part ->
                when (part) {
                    is MessagePart.Text ->
                        if (part.isDisplayable) part.text.trim().takeIf { it.isNotEmpty() } else null
                    is MessagePart.Tool,
                    is MessagePart.File,
                    is MessagePart.Compaction,
                    -> null
                }
            }
        return segments.joinToString("\n")
    }

    private fun toTokenUsage(dto: MessageTokensDto?): MessageTokenUsage? {
        dto ?: return null
        return MessageTokenUsage(
            input = dto.input ?: 0,
            output = dto.output ?: 0,
            reasoning = dto.reasoning ?: 0,
            cacheRead = dto.cache?.read ?: 0,
            cacheWrite = dto.cache?.write ?: 0,
        )
    }

    private fun toContextEstimate(
        role: String,
        parts: List<MessagePartDto>,
    ): MessageContextEstimate =
        when (role.lowercase()) {
            "user" ->
                MessageContextEstimate(
                    systemCharacters = parts.sumOf { it.system?.length ?: 0 },
                    userCharacters = parts.sumOf(::userContextCharacters),
                )
            "assistant" ->
                MessageContextEstimate(
                    assistantCharacters =
                        parts.sumOf { part ->
                            when (part.type) {
                                "text", "reasoning" -> part.text?.length ?: 0
                                else -> 0
                            }
                        },
                    toolCharacters = parts.sumOf(::toolContextCharacters),
                )
            else -> MessageContextEstimate()
        }

    private fun userContextCharacters(part: MessagePartDto): Int =
        when (part.type) {
            "text" -> part.text?.length ?: 0
            "file" -> part.source.stringAt("text", "value")?.length ?: 0
            "agent" -> part.source.stringAt("value")?.length ?: 0
            else -> 0
        }

    private fun toolContextCharacters(part: MessagePartDto): Int {
        if (part.type != "tool") return 0
        val state = parseToolState(part.state) ?: return 0
        val inputOverhead =
            state.input
                ?.keys
                ?.size
                .orZero() * 16
        return when (state.status) {
            "pending" -> inputOverhead + (state.raw?.length ?: 0)
            "completed" -> inputOverhead + (state.output?.length ?: 0)
            "error" -> inputOverhead + (state.error?.length ?: 0)
            else -> inputOverhead
        }
    }

    private fun Int?.orZero(): Int = this ?: 0

    private fun toFilePart(dto: MessagePartDto): MessagePart.File? {
        val dataUrl = dto.url ?: return null
        val mime = dto.mime?.lowercase() ?: return null
        if (!AttachmentDataUrl.validates(dataUrl, mime)) return null
        val kind = AttachmentDataUrl.kind(mime) ?: return null
        return MessagePart.File(
            id = dto.id ?: dataUrl,
            filename = dto.filename?.takeIf { it.isNotBlank() } ?: "Attachment",
            mime = mime,
            kind = kind,
            dataUrl = dataUrl,
        )
    }

    private fun JsonObject?.stringAt(vararg path: String): String? {
        var current: kotlinx.serialization.json.JsonElement = this ?: return null
        for (segment in path) {
            current = current.jsonObject[segment] ?: return null
        }
        return (current as? JsonPrimitive)?.jsonPrimitive?.content
    }
}
