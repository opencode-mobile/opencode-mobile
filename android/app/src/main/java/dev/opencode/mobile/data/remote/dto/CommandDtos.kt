package dev.opencode.mobile.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class CommandDto(
    val name: String,
    val description: String? = null,
    val agent: String? = null,
    val model: String? = null,
    val source: String? = null,
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
)

@Serializable
data class CommandFilePartDto(
    val type: String = "file",
    val mime: String,
    val url: String,
    val filename: String? = null,
    val id: String? = null,
)
