package dev.opencode.mobile.domain.project

import dev.opencode.mobile.data.remote.dto.HealthDto
import dev.opencode.mobile.data.remote.dto.ProjectDto
import dev.opencode.mobile.domain.filesystem.ServerPathPolicy
import io.ktor.client.plugins.ResponseException

class ProjectDomainAdapter {
    fun toProject(dto: ProjectDto): Project {
        val worktree = dto.resolvedWorktree
        return Project(
            id = dto.id,
            worktree = worktree,
            displayName = displayNameFromWorktree(worktree),
            activityAtEpochMs = dto.time?.active ?: dto.time?.updated,
            isRepository = !dto.vcs.isNullOrBlank(),
            isArchived = dto.time?.archived != null,
            name = dto.name?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    fun mapProbeError(throwable: Throwable): ServerProbeError =
        when (throwable) {
            is ServerProbeError -> throwable
            is ResponseException ->
                ServerProbeError.Http(
                    statusCode = throwable.response.status.value,
                    message = throwable.message,
                )
            else -> ServerProbeError.Transport(throwable)
        }

    fun requireHealthy(dto: HealthDto) {
        if (dto.healthy != true) {
            throw ServerProbeError.Unhealthy(dto.version)
        }
    }

    companion object {
        fun displayNameFromWorktree(worktree: String): String = ServerPathPolicy.name(worktree).ifBlank { worktree }
    }
}
