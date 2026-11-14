package dev.opencode.mobile.data.remote.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** v2 `ShellInfo`: one agent-run shell command (`/api/shell`, `shell.created`). */
@Serializable
data class ShellInfoDto(
    val id: String,
    val status: String = "",
    val command: String = "",
    val cwd: String? = null,
    val exit: Double? = null,
    val metadata: JsonObject? = null,
    val time: ShellTimeDto? = null,
)

@Serializable
data class ShellTimeDto(
    val started: Double? = null,
    val completed: Double? = null,
)

/** One page of v2 `GET /api/shell/{id}/output`; [cursor] and [size] are absolute byte offsets. */
@Serializable
data class ShellOutputDto(
    val output: String = "",
    val cursor: Long = 0,
    val size: Long = 0,
    val truncated: Boolean = false,
)
