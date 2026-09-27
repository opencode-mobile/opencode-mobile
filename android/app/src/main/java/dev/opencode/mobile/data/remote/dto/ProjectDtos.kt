package dev.opencode.mobile.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class HealthDto(
    val healthy: Boolean? = null,
    val version: String? = null,
    val pid: Int? = null,
)

@Serializable
data class ProjectDto(
    val id: String,
    val worktree: String? = null,
    /** V2 location project uses `directory` instead of `worktree`. */
    val directory: String? = null,
    val vcs: String? = null,
) {
    val resolvedWorktree: String
        get() =
            worktree?.takeIf { it.isNotBlank() }
                ?: directory?.takeIf { it.isNotBlank() }
                ?: error("Project requires worktree or directory")
}
