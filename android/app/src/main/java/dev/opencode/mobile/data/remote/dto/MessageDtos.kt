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
    val type: String,
    val text: String? = null,
    val tool: String? = null,
    val state: JsonObject? = null,
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
