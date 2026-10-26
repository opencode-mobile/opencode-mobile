package dev.opencode.mobile.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonObject

@Serializable
data class MessageDto(
    val info: MessageInfoDto,
    val parts: List<MessagePartDto> = emptyList(),
)

@Serializable
data class MessageInfoDto(
    val id: String,
    @SerialName("sessionID") val sessionId: String? = null,
    val role: String,
    val agent: String? = null,
    val mode: String? = null,
    val model: MessageModelRefDto? = null,
    @SerialName("modelID") val modelId: String? = null,
    @SerialName("providerID") val providerId: String? = null,
    val variant: String? = null,
    val time: MessageTimeDto? = null,
    val error: MessageErrorInfoDto? = null,
    val tokens: MessageTokensDto? = null,
    val cost: Double? = null,
    val waiting: Boolean? = null,
    val delivery: String? = null,
    val finish: String? = null,
)

@Serializable
data class MessageTokensDto(
    val input: Long? = null,
    val output: Long? = null,
    val reasoning: Long? = null,
    val cache: MessageTokenCacheDto? = null,
)

@Serializable
data class MessageTokenCacheDto(
    val read: Long? = null,
    val write: Long? = null,
)

@Serializable
data class MessageErrorInfoDto(
    val name: String? = null,
    val data: MessageErrorDataDto? = null,
)

@Serializable
data class MessageErrorDataDto(
    val message: String? = null,
    val statusCode: Int? = null,
    val responseBody: String? = null,
    val isRetryable: Boolean? = null,
    val providerID: String? = null,
    val retries: Int? = null,
    /** v2 `retryAfterMs`, carried here by the live event translation; v1 servers never send it. */
    val retryAfterMs: Long? = null,
)

@Serializable
data class MessageModelRefDto(
    @SerialName("modelID") val modelId: String? = null,
    @SerialName("providerID") val providerId: String? = null,
)

@Serializable
data class MessageTimeDto(
    val created: Long? = null,
    val completed: Long? = null,
)

@Serializable
data class MessagePartDto(
    val id: String? = null,
    @SerialName("sessionID") val sessionId: String? = null,
    @SerialName("messageID") val messageId: String? = null,
    val type: String,
    val text: String? = null,
    val mime: String? = null,
    val url: String? = null,
    val filename: String? = null,
    /** Absolute server path of a file the client uploaded (from a v2 prompt's `metadata.attachments`). */
    val serverPath: String? = null,
    val system: String? = null,
    val tool: String? = null,
    val source: JsonObject? = null,
    @SerialName("callID") val callId: String? = null,
    val state: JsonObject? = null,
    val snapshot: String? = null,
    val reason: String? = null,
    val name: String? = null,
    val prompt: String? = null,
    val description: String? = null,
    val agent: String? = null,
    val attempt: Int? = null,
    val error: MessageErrorInfoDto? = null,
    val time: MessagePartTimeDto? = null,
    val synthetic: Boolean? = null,
    val ignored: Boolean? = null,
    val hash: String? = null,
    val files: List<String>? = null,
    val auto: Boolean? = null,
    val completed: Boolean? = null,
    val summary: String? = null,
    /** Client-only `notice` part from the v2 live projection (a timeline notice). */
    val notice: JsonObject? = null,
)

@Serializable
data class MessagePartTimeDto(
    val start: Long? = null,
    val end: Long? = null,
)

@Serializable
data class MessageToolStateDto(
    val status: String,
    val input: JsonObject? = null,
    val raw: String? = null,
    val title: String? = null,
    val output: String? = null,
    val error: String? = null,
    val time: MessageToolTimeDto? = null,
    val metadata: JsonObject? = null,
    /** Set by the v2 live projection when `tool.failed` reports `error.type: "aborted"`. */
    val interrupted: Boolean? = null,
    /** Set when the server closed the call without running it (`executed: false`). */
    val malformed: Boolean? = null,
)

@Serializable
data class MessageToolTimeDto(
    val start: Long? = null,
    val end: Long? = null,
    val compacted: Long? = null,
    val created: Long? = null,
)

@Serializable
data class SendMessageRequestDto(
    val parts: List<SendMessagePartDto>,
    val model: SendPromptModelDto? = null,
    val variant: String? = null,
    val agent: String? = null,
    @SerialName("messageID") val messageId: String? = null,
)

@Serializable
data class SendPromptModelDto(
    @SerialName("providerID") val providerId: String,
    @SerialName("modelID") val modelId: String,
)

/** v1 `POST /session/{id}/shell`. The server records the run under [agent] and [model]. */
@Serializable
data class V1ShellRequestDto(
    val agent: String,
    val command: String,
    val model: SendPromptModelDto? = null,
)

@Serializable
data class SendMessagePartDto(
    val type: String,
    val text: String? = null,
    val mime: String? = null,
    val url: String? = null,
    val filename: String? = null,
    val name: String? = null,
    val source: AgentPartSourceDto? = null,
    /** Project-file mention. Written by the prompt body writer; not a device attachment. */
    @Transient val fileSource: FileReferenceSourceDto? = null,
)

data class FileReferenceSourceDto(
    val path: String,
    val value: String,
    val start: Int,
    val end: Int,
)

@Serializable
data class AgentPartSourceDto(
    val value: String,
    val start: Int,
    val end: Int,
)

@Serializable
data class V2PromptRequestDto(
    val id: String? = null,
    val text: String,
    val files: List<V2PromptFileDto>? = null,
    val agents: List<V2PromptAgentDto>? = null,
    val skills: List<V2PromptSkillDto>? = null,
    val delivery: String,
    /** Project-file mentions. Written into `files` as literal URIs, after device attachments. */
    @Transient val fileMentions: List<V2PromptFileMentionDto>? = null,
)

data class V2PromptFileMentionDto(
    val uri: String,
    val name: String,
    val mention: V2PromptSourceDto,
)

@Serializable
data class V2PromptSkillDto(
    val id: String,
    val mention: V2PromptSourceDto? = null,
)

@Serializable
data class V2PromptFileDto(
    val uri: String,
    val name: String? = null,
    /** Used to build a data URL from a local file. Not a wire field. */
    @Transient val mime: String? = null,
)

@Serializable
data class V2PromptAgentDto(
    val name: String,
    val mention: V2PromptSourceDto? = null,
)

/** v2 `POST /api/session/{id}/shell`; output streams back as `session.shell.*` events. */
@Serializable
data class V2ShellRequestDto(
    val command: String,
)

@Serializable
data class V2SwitchAgentRequestDto(
    val agent: String,
)

@Serializable
data class V2SwitchModelRequestDto(
    val model: V2ModelRefDto,
)
