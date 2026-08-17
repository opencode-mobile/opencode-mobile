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
import dev.opencode.mobile.data.remote.dto.V2AssistantContentDto
import dev.opencode.mobile.data.remote.dto.V2FileAttachmentDto
import dev.opencode.mobile.data.remote.dto.V2ModelRefDto
import dev.opencode.mobile.data.remote.dto.V2PromptAgentDto
import dev.opencode.mobile.data.remote.dto.V2PromptFileDto
import dev.opencode.mobile.data.remote.dto.V2PromptRequestDto
import dev.opencode.mobile.data.remote.dto.V2PromptSourceDto
import dev.opencode.mobile.data.remote.dto.V2SessionMessageDto
import dev.opencode.mobile.data.remote.dto.V2SwitchModelRequestDto
import dev.opencode.mobile.data.remote.dto.V2ToolStateDto
import dev.opencode.mobile.domain.agent.ComposerMentionRouting
import io.ktor.client.plugins.ResponseException
import io.ktor.serialization.JsonConvertException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
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
            "compaction" ->
                MessagePart.Compaction(
                    id = dto.id,
                    auto = dto.auto == true,
                    completed = dto.completed == true,
                )
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
            timeStartEpochMs = state?.time?.start,
        )
    }

    fun toToolStatus(raw: String?): ToolStatus =
        when (raw) {
            "pending", "streaming" -> ToolStatus.Pending
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

    fun toV2PromptRequest(options: PromptSendOptions): V2PromptRequestDto {
        val files =
            options.attachments
                .map { attachment ->
                    V2PromptFileDto(
                        uri = attachment.dataUrl,
                        name = attachment.filename,
                        mime = attachment.mime,
                    )
                }.takeIf { it.isNotEmpty() }
        val agents =
            ComposerMentionRouting
                .agentParts(options.text, options.agentMentions)
                .map { part ->
                    V2PromptAgentDto(
                        name = part.name,
                        mention =
                            V2PromptSourceDto(
                                start = part.start,
                                end = part.end,
                                text = part.sourceValue,
                            ),
                    )
                }.takeIf { it.isNotEmpty() }
        return V2PromptRequestDto(
            text = options.text,
            files = files,
            agents = agents,
            delivery = "steer",
        )
    }

    fun toV2SwitchModelRequest(options: PromptSendOptions): V2SwitchModelRequestDto? {
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

    fun projectV2(dtos: List<V2SessionMessageDto>): List<Message> {
        val messages = mutableListOf<Message>()
        var agent = ""
        var model: V2ModelRefDto? = null
        dtos.forEach { dto ->
            when (dto.type) {
                "agent-switched",
                "location-switched",
                -> agent = dto.agent ?: agent
                "model-switched" -> model = dto.model
                "user" -> {
                    messages += projectV2User(dto, agent, model)
                    agent = dto.agent ?: agent
                    model = dto.model ?: model
                }
                "synthetic" -> projectV2Synthetic(dto, agent, model)?.let { messages += it }
                "skill" -> messages += projectV2Skill(dto, agent, model)
                "shell" -> messages += projectV2Shell(dto, agent, model)
                "assistant" -> {
                    agent = dto.agent ?: agent
                    model = dto.model ?: model
                    messages += projectV2Assistant(dto, agent, model)
                }
                "compaction" -> messages += projectV2Compaction(dto)
                else -> Unit
            }
        }
        return messages
    }

    // MARK: Private

    private fun projectV2User(
        dto: V2SessionMessageDto,
        agent: String,
        model: V2ModelRefDto?,
    ): Message {
        val parts = mutableListOf<MessagePart>()
        val text = dto.text.orEmpty()
        if (text.isNotEmpty()) {
            parts +=
                MessagePart.Text(
                    text = text,
                    id = "${dto.id}:text:0",
                )
        }
        dto.files.forEachIndexed { index, file ->
            toV2FilePart(file, fallbackId = "${dto.id}:file:$index")?.let { parts += it }
        }
        return messageFromV2(
            id = dto.id,
            role = MessageRole.User,
            parts = parts,
            mode = dto.agent ?: agent.ifBlank { null },
            model = dto.model ?: model,
            sentAtEpochMs = dto.time?.created,
            contextEstimate =
                MessageContextEstimate(
                    userCharacters =
                        text.length +
                            dto.files.sumOf { it.mention?.text?.length ?: it.source?.text?.length ?: 0 } +
                            dto.agents.sumOf { it.mention?.text?.length ?: it.source?.text?.length ?: 0 },
                ),
        )
    }

    private fun projectV2Synthetic(
        dto: V2SessionMessageDto,
        agent: String,
        model: V2ModelRefDto?,
    ): Message? {
        val text =
            dto.description?.trim()?.takeIf { it.isNotEmpty() }
                ?: dto.text?.trim()?.takeIf { it.isNotEmpty() }
                ?: return null
        return messageFromV2(
            id = dto.id,
            role = MessageRole.User,
            parts =
                listOf(
                    MessagePart.Text(
                        text = text,
                        id = "${dto.id}:text:0",
                        isSynthetic = true,
                    ),
                ),
            mode = agent.ifBlank { null },
            model = model,
            sentAtEpochMs = dto.time?.created,
            contextEstimate = MessageContextEstimate(userCharacters = text.length),
        )
    }

    private fun projectV2Skill(
        dto: V2SessionMessageDto,
        agent: String,
        model: V2ModelRefDto?,
    ): Message {
        val text = dto.text.orEmpty()
        return messageFromV2(
            id = dto.id,
            role = MessageRole.User,
            parts =
                listOfNotNull(
                    text.takeIf { it.isNotEmpty() }?.let {
                        MessagePart.Text(text = it, id = "${dto.id}:text:0")
                    },
                ),
            mode = dto.name ?: agent.ifBlank { null },
            model = model,
            sentAtEpochMs = dto.time?.created,
            contextEstimate = MessageContextEstimate(userCharacters = text.length),
        )
    }

    private fun projectV2Shell(
        dto: V2SessionMessageDto,
        agent: String,
        model: V2ModelRefDto?,
    ): List<Message> {
        val command = dto.command.orEmpty()
        val completed = dto.time?.completed != null
        val user =
            messageFromV2(
                id = dto.id,
                role = MessageRole.User,
                parts =
                    listOfNotNull(
                        command.takeIf { it.isNotEmpty() }?.let {
                            MessagePart.Text(text = it, id = "${dto.id}:text:0")
                        },
                    ),
                mode = agent.ifBlank { null },
                model = model,
                sentAtEpochMs = dto.time?.created,
                contextEstimate = MessageContextEstimate(userCharacters = command.length),
            )
        val assistant =
            messageFromV2(
                id = "${dto.id}:assistant",
                role = MessageRole.Assistant,
                parts =
                    listOf(
                        MessagePart.Tool(
                            toolName = "bash",
                            status = if (completed) ToolStatus.Completed else ToolStatus.Running,
                            title = "Shell",
                            callId = dto.callId,
                            output = v2ShellOutput(dto.output),
                            id = "${dto.id}:tool",
                        ),
                    ),
                mode = agent.ifBlank { null },
                model = model,
                sentAtEpochMs = dto.time?.created,
            )
        return listOf(user, assistant)
    }

    private fun projectV2Assistant(
        dto: V2SessionMessageDto,
        agent: String,
        model: V2ModelRefDto?,
    ): Message {
        val parts = dto.content.mapNotNull(::toV2AssistantPart)
        val errorText = dto.error?.message?.trim().orEmpty()
        return messageFromV2(
            id = dto.id,
            role = MessageRole.Assistant,
            parts = parts,
            fallbackText = errorText,
            cost = dto.cost,
            tokens = toTokenUsage(dto.tokens),
            mode = dto.agent ?: agent.ifBlank { null },
            model = dto.model ?: model,
            sentAtEpochMs = dto.time?.created,
            contextInputTokens = dto.tokens?.input,
            contextEstimate = v2AssistantContextEstimate(dto.content),
        )
    }

    private fun projectV2Compaction(dto: V2SessionMessageDto): Message {
        val summary = dto.summary.orEmpty()
        val parts =
            mutableListOf<MessagePart>(
                MessagePart.Compaction(
                    id = dto.id,
                    auto = dto.reason == "auto",
                    completed = dto.status.equals("completed", ignoreCase = true),
                ),
            )
        if (summary.isNotBlank()) {
            parts += MessagePart.Text(text = summary, id = "${dto.id}:text:0")
        }
        return messageFromV2(
            id = dto.id,
            role = MessageRole.Assistant,
            parts = parts,
            mode = "compaction",
            sentAtEpochMs = dto.time?.created,
            contextEstimate = MessageContextEstimate(assistantCharacters = summary.length),
        )
    }

    private fun messageFromV2(
        id: String,
        role: MessageRole,
        parts: List<MessagePart>,
        fallbackText: String = "",
        cost: Double? = null,
        tokens: MessageTokenUsage? = null,
        mode: String? = null,
        model: V2ModelRefDto? = null,
        sentAtEpochMs: Long? = null,
        contextInputTokens: Long? = null,
        contextEstimate: MessageContextEstimate = MessageContextEstimate(),
    ): Message {
        val flatText = displayText(parts)
        return Message(
            id = id,
            role = role,
            text = flatText.ifBlank { fallbackText },
            parts = parts,
            cost = cost,
            tokens = tokens,
            model = model?.id,
            modelProviderId = model?.providerId,
            modelId = model?.id,
            variant = model?.variant,
            mode = mode,
            sentAtEpochMs = sentAtEpochMs,
            contextInputTokens = contextInputTokens,
            contextEstimate = contextEstimate,
        )
    }

    private fun toV2AssistantPart(content: V2AssistantContentDto): MessagePart? =
        when (content.type) {
            "text" -> {
                val text = content.text ?: return null
                MessagePart.Text(text = text, id = content.id)
            }
            "tool" -> {
                val name = content.name ?: return null
                val state = content.state
                val status = toToolStatus(state?.status)
                MessagePart.Tool(
                    toolName = name,
                    status = status,
                    error = state?.error?.message?.takeIf { it.isNotEmpty() },
                    callId = content.id,
                    input = jsonObjectFromInput(state?.input),
                    output = toolOutput(state),
                    metadata = state?.metadata ?: state?.structured,
                    id = content.id,
                    timeStartEpochMs = content.time?.created,
                )
            }
            else -> null
        }

    private fun toV2FilePart(
        file: V2FileAttachmentDto,
        fallbackId: String,
    ): MessagePart.File? {
        val dataUrl = v2FileDataUrl(file) ?: return null
        val mime = (file.mime ?: AttachmentDataUrl.mimeFromDataUrl(dataUrl))?.lowercase() ?: return null
        if (!AttachmentDataUrl.validates(dataUrl, mime)) return null
        val kind = AttachmentDataUrl.kind(mime) ?: return null
        return MessagePart.File(
            id = fallbackId,
            filename = file.name?.takeIf { it.isNotBlank() } ?: "Attachment",
            mime = mime,
            kind = kind,
            dataUrl = dataUrl,
        )
    }

    private fun v2FileDataUrl(file: V2FileAttachmentDto): String? {
        file.uri?.takeIf { it.isNotBlank() }?.let { return it }
        file.source?.uri?.takeIf { it.isNotBlank() }?.let { return it }
        val mime = file.mime ?: return null
        val base64 = jsonString(file.data) ?: return null
        return AttachmentDataUrl.fromBase64(base64, mime)
    }

    private fun v2ShellOutput(output: JsonElement?): String? =
        when (output) {
            is JsonPrimitive -> output.contentOrNull
            is JsonObject -> jsonString(output["output"])
            else -> null
        }

    private fun jsonString(element: JsonElement?): String? =
        (element as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun jsonObjectFromInput(input: JsonElement?): JsonObject? =
        when (input) {
            is JsonObject -> input
            is JsonPrimitive ->
                runCatching { json.parseToJsonElement(input.content) as? JsonObject }.getOrNull()
            else -> null
        }

    private fun toolOutput(state: V2ToolStateDto?): String? {
        val texts =
            state
                ?.content
                ?.mapNotNull { item -> item.text?.takeIf { item.type == "text" && it.isNotEmpty() } }
                .orEmpty()
        return texts.joinToString("\n").takeIf { it.isNotEmpty() }
    }

    private fun v2AssistantContextEstimate(content: List<V2AssistantContentDto>): MessageContextEstimate {
        val assistantCharacters =
            content.sumOf { item ->
                when (item.type) {
                    "text", "reasoning" -> item.text?.length ?: 0
                    else -> 0
                }
            }
        val toolCharacters =
            content.sumOf { item ->
                if (item.type != "tool") return@sumOf 0
                val state = item.state ?: return@sumOf 0
                val inputOverhead = jsonObjectFromInput(state.input)?.keys?.size.orZero() * 16
                val payload =
                    when (state.status) {
                        "pending", "streaming" -> (state.input as? JsonPrimitive)?.content?.length ?: 0
                        "completed" -> toolOutput(state)?.length ?: 0
                        "error" -> state.error?.message?.length ?: 0
                        else -> 0
                    }
                inputOverhead + payload
            }
        return MessageContextEstimate(assistantCharacters = assistantCharacters, toolCharacters = toolCharacters)
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
        val mime = dto.mime?.lowercase() ?: AttachmentDataUrl.mimeFromDataUrl(dataUrl) ?: return null
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
