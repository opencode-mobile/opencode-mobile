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
    val time: SessionTimeDto? = null,
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
    val archived: Long? = null,
)

@Serializable
data class CreateSessionRequestDto(
    @SerialName("parentID") val parentId: String? = null,
    val title: String? = null,
)

@Serializable
data class UpdateSessionTimeDto(
    val archived: Long? = null,
)

@Serializable
data class UpdateSessionRequestDto(
    val title: String? = null,
    val time: UpdateSessionTimeDto? = null,
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
data class QuestionReplyRequestDto(
    val answers: List<List<String>>,
)

@Serializable
data class QuestionRequestDto(
    val id: String,
    @SerialName("sessionID") val sessionId: String,
    val questions: List<QuestionInfoDto> = emptyList(),
    val tool: QuestionToolDto? = null,
)

@Serializable
data class QuestionInfoDto(
    val question: String,
    val header: String,
    val options: List<QuestionOptionDto> = emptyList(),
    val multiple: Boolean? = null,
    val custom: Boolean? = null,
)

@Serializable
data class QuestionOptionDto(
    val label: String,
    val description: String = "",
)

@Serializable
data class QuestionToolDto(
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

@Serializable
data class VcsInfoDto(
    val branch: String? = null,
    @SerialName("default_branch") val defaultBranch: String? = null,
)
