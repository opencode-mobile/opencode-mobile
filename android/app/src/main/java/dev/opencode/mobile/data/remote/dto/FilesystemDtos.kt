package dev.opencode.mobile.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class PathDto(
    val home: String,
    val state: String? = null,
    val config: String? = null,
    val worktree: String,
    val directory: String,
)

@Serializable
data class FileNodeDto(
    val name: String,
    val path: String,
    val absolute: String,
    val type: String,
    val ignored: Boolean = false,
)

@Serializable
data class V2FsEntryDto(
    val path: String,
    val type: String,
)

/** v1 `GET /file/content` body. `encoding` is `"base64"` for binary payloads. */
@Serializable
data class FileContentDto(
    val type: String = "text",
    val content: String = "",
    val encoding: String? = null,
    val mimeType: String? = null,
)
