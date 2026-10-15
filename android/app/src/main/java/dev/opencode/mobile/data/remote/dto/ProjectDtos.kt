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
    val time: ProjectTimeDto? = null,
) {
    val resolvedWorktree: String
        get() =
            worktree?.takeIf { it.isNotBlank() }
                ?: directory?.takeIf { it.isNotBlank() }
                ?: error("Project requires worktree or directory")
}

/** Project timestamps in epoch ms; v1 sends `updated`, v2 also `active`. */
@Serializable
data class ProjectTimeDto(
    val created: Long? = null,
    val updated: Long? = null,
    val initialized: Long? = null,
    val active: Long? = null,
    val archived: Long? = null,
)

/** v2 `GET /api/project` item: the project root is `canonical`. */
@Serializable
data class V2ProjectDto(
    val id: String,
    val canonical: String,
    val name: String? = null,
    val vcs: String? = null,
    val time: ProjectTimeDto? = null,
)
