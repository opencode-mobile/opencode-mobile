package dev.opencode.mobile.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CommandDto(
    val name: String,
    val description: String? = null,
    val agent: String? = null,
    val model: String? = null,
    val source: String? = null,
    @Serializable(with = LenientOptionalStringSerializer::class)
    val template: String? = null,
    val subtask: Boolean? = null,
    val hints: List<String> = emptyList(),
)

@Serializable
data class RunCommandRequestDto(
    val command: String,
    val arguments: String,
    val agent: String? = null,
    val model: String? = null,
    val variant: String? = null,
    val parts: List<CommandFilePartDto> = emptyList(),
    @SerialName("messageID") val messageId: String? = null,
)

@Serializable
data class CommandFilePartDto(
    val type: String = "file",
    val mime: String,
    val url: String,
    val filename: String? = null,
    val id: String? = null,
)

/**
 * `POST /api/session/{id}/command` with a command's [name] and its [text] (the field is `text`: `arguments` gets a
 * `400`), plus the prompt's [files]. Null fields are left out; without a [delivery], a goal's kickoff steers.
 */
@Serializable
data class V2RunCommandRequestDto(
    val name: String,
    val text: String,
    val files: List<V2PromptFileDto>? = null,
    val delivery: String? = null,
    /** v2 skills mentioned in the draft, `[{id}]`; mention offsets are meaningless after the token is cut. */
    val skills: List<V2PromptSkillDto>? = null,
)
