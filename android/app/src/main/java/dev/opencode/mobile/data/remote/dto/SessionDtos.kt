package dev.opencode.mobile.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class SessionDto(
    val id: String,
    val title: String,
    val slug: String? = null,
    @SerialName("projectID") val projectId: String? = null,
    val directory: String? = null,
    @SerialName("parentID") val parentId: String? = null,
    val version: String? = null,
    val summary: SessionSummaryDto? = null,
    val cost: Double? = null,
    val tokens: MessageTokensDto? = null,
    val model: SessionModelDto? = null,
    val time: SessionTimeDto? = null,
)

@Serializable
data class SessionModelDto(
    val id: String? = null,
    @SerialName("providerID") val providerId: String? = null,
    val variant: String? = null,
)

@Serializable
data class SessionSummaryDto(
    val additions: Int? = null,
    val deletions: Int? = null,
    val files: Int? = null,
)

@Serializable
data class SessionTimeDto(
    val created: Long? = null,
    val updated: Long? = null,
)

@Serializable
data class CreateSessionRequestDto(
    @SerialName("parentID") val parentId: String? = null,
    val title: String? = null,
)

@Serializable
data class UpdateSessionRequestDto(
    val title: String? = null,
)

@Serializable
data class ForkSessionRequestDto(
    @SerialName("messageID") val messageId: String? = null,
)

@Serializable
data class InitSessionRequestDto(
    @SerialName("messageID") val messageId: String,
    @SerialName("providerID") val providerId: String,
    @SerialName("modelID") val modelId: String,
)

@Serializable
data class SummarizeSessionRequestDto(
    @SerialName("providerID") val providerId: String,
    @SerialName("modelID") val modelId: String,
)

@Serializable
data class RevertSessionRequestDto(
    @SerialName("messageID") val messageId: String,
    @SerialName("partID") val partId: String? = null,
)

@Serializable
data class PermissionResponseRequestDto(
    val response: String,
)

@Serializable
data class PermissionRequestDto(
    val id: String,
    @SerialName("sessionID") val sessionId: String,
    val permission: String,
    val patterns: List<String> = emptyList(),
    val metadata: JsonObject = JsonObject(emptyMap()),
    val always: List<String> = emptyList(),
    val tool: PermissionToolDto? = null,
)

@Serializable
data class PermissionToolDto(
    @SerialName("messageID") val messageId: String,
    @SerialName("callID") val callId: String,
)

@Serializable
data class TodoDto(
    val value: JsonElement? = null,
)

@Serializable
data class FileDiffDto(
    @SerialName("file") val file: String? = null,
    val path: String? = null,
    val patch: String? = null,
    val before: String? = null,
    val after: String? = null,
    val additions: Int = 0,
    val deletions: Int = 0,
    val status: String? = null,
) {
    val resolvedPath: String
        get() = file ?: requireNotNull(path) { "File diff requires file or path" }
}
