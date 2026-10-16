package dev.opencode.mobile.domain.project

data class Project(
    val id: String,
    val worktree: String,
    val displayName: String,
    /** Last activity on the server (v2 `time.active`, else `time.updated`), epoch ms. */
    val activityAtEpochMs: Long? = null,
)

sealed class ServerProbeError : Exception() {
    data class Transport(
        override val cause: Throwable,
    ) : ServerProbeError()

    data class Http(
        val statusCode: Int,
        override val message: String?,
    ) : ServerProbeError()

    data class Unhealthy(
        val version: String?,
    ) : ServerProbeError()
}
