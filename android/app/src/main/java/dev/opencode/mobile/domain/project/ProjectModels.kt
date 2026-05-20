package dev.opencode.mobile.domain.project

data class Project(
  val id: String,
  val worktree: String,
  val displayName: String,
)

sealed class ServerProbeError : Exception() {
  data class Transport(override val cause: Throwable) : ServerProbeError()

  data class Http(val statusCode: Int, override val message: String?) : ServerProbeError()

  data class Unhealthy(val version: String?) : ServerProbeError()
}
