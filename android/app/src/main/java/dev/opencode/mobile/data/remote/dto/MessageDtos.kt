package dev.opencode.mobile.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
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
    val tool: String? = null,
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
)

@Serializable
data class SendPromptModelDto(
    @SerialName("providerID") val providerId: String,
    @SerialName("modelID") val modelId: String,
)

@Serializable
data class SendMessagePartDto(
    val type: String,
    val text: String,
)
