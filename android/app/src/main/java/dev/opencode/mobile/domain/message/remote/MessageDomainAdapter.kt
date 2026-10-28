package dev.opencode.mobile.domain.message

import dev.opencode.mobile.data.remote.dto.AgentPartSourceDto
import dev.opencode.mobile.data.remote.dto.FileReferenceSourceDto
import dev.opencode.mobile.data.remote.dto.MessageDto
import dev.opencode.mobile.data.remote.dto.MessageErrorInfoDto
import dev.opencode.mobile.data.remote.dto.MessageInfoDto
import dev.opencode.mobile.data.remote.dto.MessagePartDto
import dev.opencode.mobile.data.remote.dto.MessageTokensDto
import dev.opencode.mobile.data.remote.dto.MessageToolStateDto
import dev.opencode.mobile.data.remote.dto.SendMessagePartDto
import dev.opencode.mobile.data.remote.dto.SendMessageRequestDto
import dev.opencode.mobile.data.remote.dto.SendPromptModelDto
import dev.opencode.mobile.data.remote.dto.V1ShellRequestDto
import dev.opencode.mobile.data.remote.dto.V2AssistantContentDto
import dev.opencode.mobile.data.remote.dto.V2FileAttachmentDto
import dev.opencode.mobile.data.remote.dto.V2InboxItemDto
import dev.opencode.mobile.data.remote.dto.V2ModelRefDto
import dev.opencode.mobile.data.remote.dto.V2PromptAgentDto
import dev.opencode.mobile.data.remote.dto.V2PromptFileDto
import dev.opencode.mobile.data.remote.dto.V2PromptFileMentionDto
import dev.opencode.mobile.data.remote.dto.V2PromptRequestDto
import dev.opencode.mobile.data.remote.dto.V2PromptSkillDto
import dev.opencode.mobile.data.remote.dto.V2PromptSourceDto
import dev.opencode.mobile.data.remote.dto.V2SessionMessageDto
import dev.opencode.mobile.data.remote.dto.V2ShellRequestDto
import dev.opencode.mobile.data.remote.dto.V2SwitchModelRequestDto
import dev.opencode.mobile.data.remote.HttpBodyResponseException
import dev.opencode.mobile.data.remote.dto.V2ToolStateDto
import dev.opencode.mobile.data.remote.dto.V2UnknownErrorDto
import dev.opencode.mobile.domain.agent.ComposerMentionRouting
import dev.opencode.mobile.domain.agent.SkillMention
import dev.opencode.mobile.domain.filesystem.ProjectFileReference
import dev.opencode.mobile.domain.session.ServerErrorMessage
import io.ktor.client.plugins.ResponseException
import io.ktor.serialization.JsonConvertException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class MessageDomainAdapter(
    /** Receive time for a failure whose message has no completed time yet. */
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun toMessage(dto: MessageDto): Message {
        val info = dto.info
        val domainParts = parseParts(dto.parts)
        val flatText = displayText(domainParts)
        val stopReason = stopReason(info)
        return Message(
            id = info.id,
            role = toRole(info.role),
            text = flatText,
            parts = domainParts,
            cost = info.cost,
            tokens = toTokenUsage(info.tokens),
            model = info.model?.modelId ?: info.modelId,
            modelProviderId = info.model?.providerId ?: info.providerId,
            modelId = info.model?.modelId ?: info.modelId,
            variant = info.variant,
            mode = info.mode ?: info.agent,
            sentAtEpochMs = info.time?.created,
            completedAtEpochMs = info.time?.completed,
            contextInputTokens = info.tokens?.input,
            contextEstimate = toContextEstimate(info.role, dto.parts),
            followUpDelivery = FollowUpDelivery.fromWire(info.delivery),
            isWaitingForPickup = info.waiting == true,
            stopReason = stopReason,
            failure = sessionFailure(info.error, stopReason, completedAtEpochMs = info.time?.completed),
        )
    }

    fun toMessage(
        info: MessageInfoDto,
        domainParts: List<MessagePart>,
    ): Message {
        val flatText = displayText(domainParts)
        val stopReason = stopReason(info)
        return Message(
            id = info.id,
            role = toRole(info.role),
            text = flatText,
            parts = domainParts,
            cost = info.cost,
            tokens = toTokenUsage(info.tokens),
            model = info.model?.modelId ?: info.modelId,
            modelProviderId = info.model?.providerId ?: info.providerId,
            modelId = info.model?.modelId ?: info.modelId,
            variant = info.variant,
            mode = info.mode ?: info.agent,
            sentAtEpochMs = info.time?.created,
            completedAtEpochMs = info.time?.completed,
            contextInputTokens = info.tokens?.input,
            contextEstimate = MessageContextEstimate(),
            followUpDelivery = FollowUpDelivery.fromWire(info.delivery),
            isWaitingForPickup = info.waiting == true,
            stopReason = stopReason,
            failure = sessionFailure(info.error, stopReason, completedAtEpochMs = info.time?.completed),
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
                val stateDto = parseToolState(dto.state)
                val toolName = ToolPartIdentity.resolvedName(dto.tool ?: dto.name, stateDto?.input)
                toToolPart(toolName = toolName, callId = dto.callId, state = stateDto, id = dto.id)
            }
            "file" -> toFilePart(dto)
            "compaction" ->
                MessagePart.Compaction(
                    id = dto.id,
                    auto = dto.auto == true,
                    completed = dto.completed == true,
                    summary = dto.summary?.takeIf { it.isNotBlank() },
                )
            "reasoning" ->
                MessagePart.Reasoning(
                    text = dto.text.orEmpty(),
                    isStreaming = false,
                    id = dto.id,
                    timeStartEpochMs = dto.time?.start,
                    timeEndEpochMs = dto.time?.end,
                )
            // Client-only part of the v2 live notice projection; no v1 server sends it.
            "notice" -> {
                val id = dto.id ?: dto.messageId ?: return null
                TimelineNoticeCodec.decode(dto.notice)?.let { MessagePart.Notice(id = id, notice = it) }
            }
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
            isInterrupted =
                status == ToolStatus.Error &&
                    (state?.interrupted == true || ToolInterruption.isUserInterruption(state?.error)),
            isMalformed = state?.malformed == true,
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
                    fileReferenceParts(options).forEach { add(it) }
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
            messageId = options.messageId,
        )

    fun toV1ShellRequest(options: ShellCommandOptions): V1ShellRequestDto =
        V1ShellRequestDto(
            agent = options.agent,
            command = options.command,
            model = options.model?.let { SendPromptModelDto(providerId = it.providerId, modelId = it.modelId) },
        )

    fun toV2ShellRequest(options: ShellCommandOptions): V2ShellRequestDto = V2ShellRequestDto(command = options.command)

    fun toV2PromptRequest(options: PromptSendOptions): V2PromptRequestDto {
        val uploaded = options.attachments.mapNotNull { attachment -> attachment.uploadedServerPath?.let { attachment to it } }
        val files =
            options.attachments
                .inlineAttachments()
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
        val skills =
            ComposerMentionRouting
                .skillParts(options.text, options.skillMentions)
                .distinctBy { it.skillId }
                .map { part ->
                    V2PromptSkillDto(
                        id = part.skillId,
                        mention =
                            V2PromptSourceDto(
                                start = part.start,
                                end = part.end,
                                text = part.sourceValue,
                            ),
                    )
                }.takeIf { it.isNotEmpty() }
        val fileMentions = v2FileMentions(options)
        return V2PromptRequestDto(
            id = options.messageId,
            text = UploadedAttachments.promptText(options.text, uploaded.map { it.second }),
            files = files,
            metadata = uploadedAttachmentsMetadata(uploaded),
            agents = agents,
            skills = skills,
            delivery = options.delivery.wireValue,
            fileMentions = fileMentions,
        )
    }

    /** `metadata.attachments` lets the timeline draw a chip for each uploaded file. */
    private fun uploadedAttachmentsMetadata(uploaded: List<Pair<Attachment, String>>): JsonObject? {
        if (uploaded.isEmpty()) return null
        return buildJsonObject {
            put(
                "attachments",
                buildJsonArray {
                    uploaded.forEach { (attachment, path) ->
                        add(
                            buildJsonObject {
                                put("name", attachment.filename)
                                put("mime", attachment.mime)
                                put("path", path)
                            },
                        )
                    }
                },
            )
        }
    }

    private fun fileReferenceParts(options: PromptSendOptions): List<SendMessagePartDto> {
        val worktree = options.worktree ?: return emptyList()
        return ComposerMentionRouting.fileParts(options.text, options.fileMentions).map { part ->
            SendMessagePartDto(
                type = "file",
                mime = "text/plain",
                filename = part.filename,
                url = ProjectFileReference.url(worktree, part.path),
                fileSource =
                    FileReferenceSourceDto(
                        path = part.path,
                        value = part.sourceValue,
                        start = part.start,
                        end = part.end,
                    ),
            )
        }
    }

    private fun v2FileMentions(options: PromptSendOptions): List<V2PromptFileMentionDto>? {
        val worktree = options.worktree ?: return null
        return ComposerMentionRouting
            .fileParts(options.text, options.fileMentions)
            .map { part ->
                V2PromptFileMentionDto(
                    uri = ProjectFileReference.url(worktree, part.path),
                    name = part.filename,
                    mention =
                        V2PromptSourceDto(
                            start = part.start,
                            end = part.end,
                            text = part.sourceValue,
                        ),
                )
            }.takeIf { it.isNotEmpty() }
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
            is HttpBodyResponseException ->
                MessageError.Http(
                    statusCode = throwable.response.status.value,
                    message = httpMessage(throwable.bodyText),
                )
            is ResponseException ->
                MessageError.Http(
                    statusCode = throwable.response.status.value,
                    message = httpMessage(throwable.message),
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
                -> {
                    agent = dto.agent ?: agent
                    projectV2Notice(dto)?.let { messages += it }
                }
                "model-switched" -> {
                    model = dto.model
                    projectV2Notice(dto)?.let { messages += it }
                }
                "system" -> projectV2Notice(dto)?.let { messages += it }
                "user" -> {
                    messages += projectV2User(dto, agent, model)
                    agent = dto.agent ?: agent
                    model = dto.model ?: model
                }
                "synthetic" ->
                    (projectV2Notice(dto) ?: projectV2Synthetic(dto, agent, model))?.let { messages += it }
                "skill" -> messages += projectV2Skill(dto, agent, model)
                "shell" -> messages += projectV2Shell(dto, agent, model)
                "assistant" -> {
                    agent = dto.agent ?: agent
                    model = dto.model ?: model
                    messages += projectV2Assistant(dto, agent, model)
                }
                "compaction" -> messages += projectV2Compaction(dto)
                "idle" -> projectV2Idle(dto, messages)
                else -> Unit
            }
        }
        return messages
    }

    fun mergeInbox(
        messages: List<Message>,
        inbox: List<V2InboxItemDto>,
    ): List<Message> {
        if (inbox.isEmpty()) return messages
        val userItems =
            inbox
                .filter { it.type == "user" }
                .sortedBy { it.createdAtEpochMs }
        if (userItems.isEmpty()) return messages
        val existingIds = messages.map { it.id }.toSet()
        val itemsById = userItems.associateBy { it.id }
        val merged =
            messages.map { message ->
                val item = itemsById[message.id] ?: return@map message
                message.copy(
                    isWaitingForPickup = true,
                    followUpDelivery = FollowUpDelivery.fromWire(item.delivery),
                )
            }
        // Inbox items carry no agent or model; the server records the session's current
        // ones on delivery, so inherit them from the newest message that has them.
        val context = messages.lastOrNull { it.model != null && it.mode != "compaction" }
        val materialized =
            userItems
                .filter { it.id !in existingIds }
                .map { item ->
                    val uploads = uploadedFileParts(item.id, item.payload?.metadata)
                    val text = withoutUploadLines(item.payload?.text.orEmpty(), uploads)
                    Message(
                        id = item.id,
                        role = MessageRole.User,
                        text = text,
                        parts = listOf(MessagePart.Text(text = text, id = "${item.id}:text:0")) + uploads,
                        model = context?.model,
                        modelProviderId = context?.modelProviderId,
                        modelId = context?.modelId,
                        variant = context?.variant,
                        mode = context?.mode,
                        sentAtEpochMs = item.createdAtEpochMs,
                        followUpDelivery = FollowUpDelivery.fromWire(item.delivery),
                        isWaitingForPickup = true,
                    )
                }
        return if (materialized.isEmpty()) merged else merged + materialized
    }

    // MARK: Private

    private fun projectV2User(
        dto: V2SessionMessageDto,
        agent: String,
        model: V2ModelRefDto?,
    ): Message {
        val parts = mutableListOf<MessagePart>()
        val uploads = uploadedFileParts(dto)
        // The prompt text names each uploaded file; the chips say it, so the bubble drops the lines.
        val text = withoutUploadLines(dto.text.orEmpty(), uploads)
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
        parts += uploads
        return messageFromV2(
            id = dto.id,
            role = MessageRole.User,
            parts = parts,
            promptSkills = promptSkills(dto),
            mode = dto.agent ?: agent.ifBlank { null },
            model = dto.model ?: model,
            sentAtEpochMs = dto.time?.created,
            completedAtEpochMs = dto.time?.completed,
            contextEstimate =
                MessageContextEstimate(
                    userCharacters =
                        text.length +
                            dto.files.sumOf { it.mention?.text?.length ?: it.source?.text?.length ?: 0 } +
                            dto.agents.sumOf { it.mention?.text?.length ?: it.source?.text?.length ?: 0 } +
                            dto.skills.sumOf { it.mention?.text?.length ?: 0 },
                ),
        )
    }

    /**
     * A v2 timeline notice is its own row: no agent, model, text or context
     * estimate, so it never changes a turn, the running context, or the read state.
     */
    private fun projectV2Notice(dto: V2SessionMessageDto): Message? {
        val notice = V2TimelineNotices.fromMessage(dto) ?: return null
        return messageFromV2(
            id = dto.id,
            role = MessageRole.Notice,
            parts = listOf(MessagePart.Notice(id = dto.id, notice = notice)),
            sentAtEpochMs = dto.time?.created,
            completedAtEpochMs = dto.time?.completed,
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
            completedAtEpochMs = dto.time?.completed,
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
            completedAtEpochMs = dto.time?.completed,
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
                completedAtEpochMs = dto.time?.completed,
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
                            input = command.takeIf { it.isNotEmpty() }?.let { buildJsonObject { put("command", it) } },
                            output = v2ShellOutput(dto.output),
                            metadata = dto.exit?.let { exit -> buildJsonObject { put("exit", exit) } },
                            id = "${dto.id}:tool",
                        ),
                    ),
                mode = agent.ifBlank { null },
                model = model,
                sentAtEpochMs = dto.time?.created,
                completedAtEpochMs = dto.time?.completed,
            )
        return listOf(user, assistant)
    }

    private fun projectV2Assistant(
        dto: V2SessionMessageDto,
        agent: String,
        model: V2ModelRefDto?,
    ): Message {
        val ordinals = mutableMapOf<String, Int>()
        val parts =
            dto.content.mapIndexedNotNull { index, content ->
                val fallbackId =
                    V2ContentPartId.kind(content.type)?.let { kind ->
                        val ordinal = ordinals.getOrDefault(kind, 0)
                        ordinals[kind] = ordinal + 1
                        V2ContentPartId.of(dto.id, kind, ordinal.toString())
                    } ?: "${dto.id}:${content.type}:$index"
                toV2AssistantPart(content, fallbackId = fallbackId)
            } + uploadedFileParts(dto)
        val stopReason = MessageStopReason.from(finish = dto.finish, errorKind = dto.error?.type)
        val failure =
            structuredFailure(dto.error, outcome = null, completedAtEpochMs = dto.time?.completed)
                ?: dto.retry
                    ?.error
                    ?.let { structuredFailure(it, outcome = null, completedAtEpochMs = dto.time?.completed) }
                    ?.takeIf { dto.finish.equals("error", ignoreCase = true) }
        return messageFromV2(
            id = dto.id,
            role = MessageRole.Assistant,
            parts = parts,
            stopReason = stopReason,
            failure = failure,
            cost = dto.cost,
            tokens = toTokenUsage(dto.tokens),
            mode = dto.agent ?: agent.ifBlank { null },
            model = dto.model ?: model,
            sentAtEpochMs = dto.time?.created,
            completedAtEpochMs = dto.time?.completed,
            contextInputTokens = dto.tokens?.input,
            contextEstimate = v2AssistantContextEstimate(dto.content),
        )
    }

    private fun projectV2Compaction(dto: V2SessionMessageDto): Message {
        val summary = dto.summary.orEmpty()
        // The summary is model context, not a reply: it rides on the compaction
        // part so the timeline shows a compaction row instead of reply text.
        val parts =
            listOf<MessagePart>(
                MessagePart.Compaction(
                    id = dto.id,
                    auto = dto.reason == "auto",
                    completed = dto.status.equals("completed", ignoreCase = true),
                    summary = summary.takeIf { it.isNotBlank() },
                ),
            )
        return messageFromV2(
            id = dto.id,
            role = MessageRole.Assistant,
            parts = parts,
            mode = "compaction",
            sentAtEpochMs = dto.time?.created,
            completedAtEpochMs = dto.time?.completed,
            contextEstimate = MessageContextEstimate(assistantCharacters = summary.length),
            failure =
                structuredFailure(
                    dto.error,
                    outcome = null,
                    source = FailureSource.Compaction,
                    completedAtEpochMs = dto.time?.completed,
                ),
        )
    }

    /** A failed idle item is its own row when the assistant message did not already carry the same failure. */
    private fun projectV2Idle(
        dto: V2SessionMessageDto,
        messages: MutableList<Message>,
    ) {
        val failure =
            structuredFailure(
                dto.error,
                outcome = dto.outcome,
                source = FailureSource.Execution,
                completedAtEpochMs = dto.time?.completed,
            ) ?: return
        if (messages.lastOrNull()?.failure?.line() == failure.line()) return
        messages +=
            messageFromV2(
                id = dto.id,
                role = MessageRole.Assistant,
                parts = emptyList(),
                failure = failure,
                sentAtEpochMs = dto.time?.created,
                completedAtEpochMs = dto.time?.completed,
            )
    }

    private fun messageFromV2(
        id: String,
        role: MessageRole,
        parts: List<MessagePart>,
        fallbackText: String = "",
        promptSkills: List<PromptSkill> = emptyList(),
        cost: Double? = null,
        tokens: MessageTokenUsage? = null,
        mode: String? = null,
        model: V2ModelRefDto? = null,
        sentAtEpochMs: Long? = null,
        completedAtEpochMs: Long? = null,
        contextInputTokens: Long? = null,
        contextEstimate: MessageContextEstimate = MessageContextEstimate(),
        stopReason: MessageStopReason? = null,
        failure: SessionFailure? = null,
    ): Message {
        val flatText = displayText(parts)
        return Message(
            id = id,
            role = role,
            stopReason = stopReason,
            failure = failure,
            text = flatText.ifBlank { fallbackText },
            parts = parts,
            promptSkills = promptSkills,
            cost = cost,
            tokens = tokens,
            model = model?.id,
            modelProviderId = model?.providerId,
            modelId = model?.id,
            variant = model?.variant,
            mode = mode,
            sentAtEpochMs = sentAtEpochMs,
            completedAtEpochMs = completedAtEpochMs,
            contextInputTokens = contextInputTokens,
            contextEstimate = contextEstimate,
        )
    }

    /**
     * Skills ride on the user message. Keep id and name only — `text` is the
     * model-facing skill body and must not become a second chat bubble.
     */
    private fun promptSkills(dto: V2SessionMessageDto): List<PromptSkill> =
        dto.skills.mapNotNull { skill ->
            val id = skill.id?.trim().orEmpty().ifEmpty { skill.name?.trim().orEmpty() }
            val name = skill.name?.trim().orEmpty().ifEmpty { id }
            if (name.isEmpty()) return@mapNotNull null
            PromptSkill(
                id = id.ifEmpty { name },
                name = name,
                mentionText = skill.mention?.text?.trim()?.takeIf { it.isNotEmpty() },
                mentionStart = skill.mention?.start,
                mentionEnd = skill.mention?.end,
            )
        }

    private fun toV2AssistantPart(
        content: V2AssistantContentDto,
        fallbackId: String,
    ): MessagePart? =
        when (content.type) {
            "text" -> {
                val text = content.text ?: return null
                MessagePart.Text(text = text, id = content.id ?: fallbackId)
            }
            "reasoning",
            "thinking",
            ->
                MessagePart.Reasoning(
                    text = content.text.orEmpty(),
                    id = content.id ?: fallbackId,
                    timeStartEpochMs = content.time?.created,
                    timeEndEpochMs = content.time?.completed,
                )
            "tool" -> {
                val name = content.name ?: return null
                val state = content.state
                val status = toToolStatus(state?.status)
                val metadata = state?.metadata ?: state?.structured
                MessagePart.Tool(
                    toolName = name,
                    status = status,
                    // v2 tools report their progress title in metadata (v1: state.title).
                    title = (metadata?.get("title") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() },
                    error = state?.error?.message?.let(SessionFailure::unwrap)?.takeIf { it.isNotEmpty() },
                    callId = content.id,
                    input = jsonObjectFromInput(state?.input) ?: streamingInputPreview(state),
                    output = toolOutput(state),
                    metadata = metadata,
                    id = content.id ?: fallbackId,
                    timeStartEpochMs = content.time?.created,
                    isInterrupted = status == ToolStatus.Error && ToolInterruption.isAbortedType(state?.error?.type),
                    isMalformed =
                        SessionFailure.isMalformedTool(
                            type = state?.error?.type,
                            executed = state?.executed,
                        ),
                )
            }
            else -> null
        }

    /** `metadata.attachments` of a v2 prompt: files the client uploaded and named in the text. */
    private fun uploadedFileParts(dto: V2SessionMessageDto): List<MessagePart.File> = uploadedFileParts(dto.id, dto.metadata)

    private fun uploadedFileParts(
        messageId: String,
        metadata: JsonObject?,
    ): List<MessagePart.File> =
        UploadedAttachments.parse(metadata).mapIndexed { index, upload ->
            MessagePart.File(
                id = "$messageId:upload:$index",
                filename = upload.name,
                mime = upload.mime,
                kind = upload.kind,
                dataUrl = "",
                serverPath = upload.path,
            )
        }

    private fun withoutUploadLines(
        text: String,
        uploads: List<MessagePart.File>,
    ): String =
        UploadedAttachments.stripLines(
            text,
            uploads.mapNotNull { file -> file.serverPath?.let { UploadedAttachment(file.filename, file.mime, it) } },
        )

    private fun toV2FilePart(
        file: V2FileAttachmentDto,
        fallbackId: String,
    ): MessagePart.File? {
        val dataUrl = v2FileDataUrl(file).orEmpty()
        val mime =
            (
                file.mime
                    ?: dataUrl.takeIf { it.isNotEmpty() }?.let(AttachmentDataUrl::mimeFromDataUrl)
                    ?: file.name?.let(AttachmentDataUrl::mimeFromFilename)
            )?.lowercase() ?: return null
        val kind = AttachmentDataUrl.kind(mime) ?: return null
        if (dataUrl.isNotEmpty() &&
            (!dataUrl.startsWith("data:", ignoreCase = true) || !AttachmentDataUrl.validates(dataUrl, mime))
        ) {
            return null
        }
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
        file.source
            ?.uri
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
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

    private fun jsonString(element: JsonElement?): String? = (element as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun jsonObjectFromInput(input: JsonElement?): JsonObject? =
        when (input) {
            is JsonObject -> input
            is JsonPrimitive ->
                runCatching { json.parseToJsonElement(input.content) as? JsonObject }.getOrNull()
            else -> null
        }

    /** A tool whose arguments are still streaming keeps the partial text as its preview. */
    private fun streamingInputPreview(state: V2ToolStateDto?): JsonObject? {
        if (state?.status != "streaming" && state?.status != "pending") return null
        val raw = state.input as? JsonPrimitive ?: return null
        if (!raw.isString) return null
        return buildJsonObject { put(ToolArgumentsPreview.INPUT_KEY, raw.content) }
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
            // Client-only role of the v2 live notice projection; no v1 server sends it.
            "notice" -> MessageRole.Notice
            else -> MessageRole.Unknown
        }

    private fun stopReason(info: MessageInfoDto): MessageStopReason? =
        if (info.role.equals("assistant", ignoreCase = true)) {
            MessageStopReason.from(finish = info.finish, errorKind = info.error?.name)
        } else {
            null
        }

    private fun sessionFailure(
        error: MessageErrorInfoDto?,
        stopReason: MessageStopReason?,
        completedAtEpochMs: Long?,
    ): SessionFailure? {
        if (stopReason == MessageStopReason.Interrupted) return null
        return SessionFailure.fromV1(
            name = error?.name,
            message = error?.data?.message,
            statusCode = error?.data?.statusCode,
            responseBody = error?.data?.responseBody,
            isRetryable = error?.data?.isRetryable,
            providerId = error?.data?.providerID,
            retries = error?.data?.retries,
            retryAfterMs = error?.data?.retryAfterMs,
            recordedAtEpochMs = recordedAt(error?.data?.retryAfterMs, completedAtEpochMs),
        )
    }

    private fun structuredFailure(
        error: V2UnknownErrorDto?,
        outcome: String?,
        source: FailureSource = FailureSource.Step,
        completedAtEpochMs: Long?,
    ): SessionFailure? =
        SessionFailure.fromV2(
            type = error?.type,
            message = error?.message,
            status = error?.status,
            providerBody = error?.response?.body,
            outcome = outcome,
            source = source,
            retryAfterMs = error?.retryAfterMs,
            recordedAtEpochMs = recordedAt(error?.retryAfterMs, completedAtEpochMs),
        )

    /** The failed message's completion time, else when the app received it. Only a retry hint needs it. */
    private fun recordedAt(
        retryAfterMs: Long?,
        completedAtEpochMs: Long?,
    ): Long? = if (retryAfterMs == null) null else completedAtEpochMs ?: nowEpochMs()

    private fun httpMessage(body: String?): String {
        val friendly = ServerErrorMessage.userFacing(body)
        if (friendly != null) return friendly
        val plain = body?.trim().orEmpty()
        if (plain.isEmpty() || plain.startsWith("{") || plain.length > 200) return "Request failed"
        return plain
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
                    is MessagePart.Reasoning,
                    is MessagePart.Notice,
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
        dto.serverPath?.takeIf { it.isNotBlank() }?.let { path ->
            val mime = dto.mime?.lowercase() ?: AttachmentMime.BINARY
            return MessagePart.File(
                id = dto.id ?: path,
                filename = dto.filename?.takeIf { it.isNotBlank() } ?: "Attachment",
                mime = mime,
                kind = AttachmentDataUrl.kindOrFile(mime),
                dataUrl = "",
                serverPath = path,
            )
        }
        val dataUrl = dto.url?.takeIf { it.isNotBlank() }.orEmpty()
        val mime =
            (
                dto.mime
                    ?: dataUrl.takeIf { it.isNotEmpty() }?.let(AttachmentDataUrl::mimeFromDataUrl)
                    ?: dto.filename?.let(AttachmentDataUrl::mimeFromFilename)
            )?.lowercase() ?: return null
        val kind = AttachmentDataUrl.kind(mime) ?: return null
        if (dataUrl.isNotEmpty() &&
            (!dataUrl.startsWith("data:", ignoreCase = true) || !AttachmentDataUrl.validates(dataUrl, mime))
        ) {
            return null
        }
        return MessagePart.File(
            id = dto.id ?: dataUrl.takeIf { it.isNotEmpty() } ?: dto.filename ?: "file",
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

/**
 * Part ids for v2 assistant text and reasoning, which the server stores without
 * ids. Live events carry a per-message `ordinal` per kind; REST content keeps the
 * same per-kind order, so both sides derive `<messageID>:<kind>:<ordinal>` and a
 * reload replaces live parts in place.
 */
internal object V2ContentPartId {
    fun of(
        messageId: String,
        kind: String,
        ordinal: String,
    ): String = "$messageId:$kind:$ordinal"

    fun kind(contentType: String): String? =
        when (contentType) {
            "text" -> "text"
            "reasoning", "thinking" -> "reasoning"
            else -> null
        }
}
