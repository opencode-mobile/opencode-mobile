package dev.opencode.mobile.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class HealthDto(
    val healthy: Boolean,
    val version: String? = null,
)

@Serializable
data class ProjectDto(
    val id: String,
    val worktree: String,
    val vcs: String? = null,
)
