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
