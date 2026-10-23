package dev.opencode.mobile.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class V2SessionMessageDto(
    val id: String,
    val type: String,
    val text: String? = null,
    val files: List<V2FileAttachmentDto> = emptyList(),
    val agents: List<V2AgentAttachmentDto> = emptyList(),
    val skills: List<V2SkillAttachmentDto> = emptyList(),
    val agent: String? = null,
    val model: V2ModelRefDto? = null,
    val content: List<V2AssistantContentDto> = emptyList(),
    val cost: Double? = null,
    val tokens: MessageTokensDto? = null,
    val error: V2UnknownErrorDto? = null,
    val outcome: String? = null,
    val retry: V2RetryDto? = null,
    val finish: String? = null,
    val time: V2MessageTimeDto? = null,
    val reason: String? = null,
    val summary: String? = null,
    val status: String? = null,
    val command: String? = null,
    val output: JsonElement? = null,
    /** `shell` items: the command's exit code once it ended. */
    val exit: Int? = null,
    @SerialName("callID") val callId: String? = null,
    val name: String? = null,
    val description: String? = null,
    /** `location-switched`: the new location, `{directory}`. */
    val location: JsonObject? = null,
    /** Switch items: a previous agent id, model ref, or `{location}`, by item type. */
    val previous: JsonElement? = null,
    val metadata: JsonObject? = null,
)

@Serializable
data class V2MessageTimeDto(
    val created: Long? = null,
    val completed: Long? = null,
)

@Serializable
data class V2ModelRefDto(
    val id: String? = null,
    @SerialName("providerID") val providerId: String? = null,
    val variant: String? = null,
)

@Serializable
data class V2UnknownErrorDto(
    val type: String? = null,
    val message: String? = null,
    val status: Int? = null,
    val response: V2ErrorResponseDto? = null,
)

@Serializable
data class V2ErrorResponseDto(
    val body: String? = null,
)

/** In-progress retry on an assistant message. The working label reads the live event, not this field. */
@Serializable
data class V2RetryDto(
    val attempt: Int? = null,
    val at: Long? = null,
    val error: V2UnknownErrorDto? = null,
)

@Serializable
data class V2FileAttachmentDto(
    val uri: String? = null,
    val mime: String? = null,
    val name: String? = null,
    val description: String? = null,
    val data: JsonElement? = null,
    val source: V2FileSourceDto? = null,
    val mention: V2PromptSourceDto? = null,
)

@Serializable
data class V2FileSourceDto(
    val type: String? = null,
    val uri: String? = null,
    val start: Int? = null,
    val end: Int? = null,
    val text: String? = null,
)

@Serializable
data class V2AgentAttachmentDto(
    val name: String? = null,
    val source: V2PromptSourceDto? = null,
    val mention: V2PromptSourceDto? = null,
)

/** A skill admitted on a user message: `{id, name?, mention?}`. */
@Serializable
data class V2SkillAttachmentDto(
    val id: String,
    val name: String? = null,
    val mention: V2PromptSourceDto? = null,
)

@Serializable
data class V2PromptSourceDto(
    val start: Int? = null,
    val end: Int? = null,
    val text: String? = null,
)

@Serializable
data class V2AssistantContentDto(
    val type: String,
    val id: String? = null,
    val text: String? = null,
    val name: String? = null,
    val state: V2ToolStateDto? = null,
    val time: V2MessageTimeDto? = null,
)

@Serializable
data class V2ToolStateDto(
    val status: String? = null,
    val input: JsonElement? = null,
    val content: List<V2ToolContentDto> = emptyList(),
    val error: V2UnknownErrorDto? = null,
    val executed: Boolean? = null,
    val structured: JsonObject? = null,
    val metadata: JsonObject? = null,
)

@Serializable
data class V2ToolContentDto(
    val type: String,
    val text: String? = null,
    val uri: String? = null,
    val mime: String? = null,
    val name: String? = null,
)
