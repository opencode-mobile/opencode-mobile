package dev.opencode.mobile.domain.connection

import dev.opencode.mobile.data.remote.dto.HealthDto
import dev.opencode.mobile.domain.project.ServerProbeError

/**
 * Prefers the most actionable dual-probe failure when classification cannot
 * pick a protocol (auth over unreachable, unhealthy bodies over generic miss).
 */
object ServerProtocolProbeErrors {
    fun prefer(
        previous: Throwable?,
        next: Throwable?,
    ): Throwable? {
        if (next == null) return previous
        if (previous == null) return next
        return if (rank(next) > rank(previous)) next else previous
    }

    fun unresolved(
        globalHealth: HealthDto?,
        apiHealth: HealthDto?,
        fetchError: Throwable?,
    ): Throwable {
        var error = fetchError
        if (globalHealth != null && globalHealth.healthy != true) {
            error = prefer(error, ServerProbeError.Unhealthy(globalHealth.version))
        }
        if (apiHealth != null && apiHealth.healthy != true && apiHealth.pid == null) {
            error = prefer(error, ServerProbeError.Unhealthy(apiHealth.version))
        }
        return error ?: ServerProbeError.Transport(IllegalStateException("No health endpoint"))
    }

    private fun rank(error: Throwable): Int {
        val probe =
            error as? ServerProbeError
                ?: return 0
        return when (probe) {
            is ServerProbeError.Http ->
                when (probe.statusCode) {
                    401, 403 -> 40
                    else -> 5
                }
            is ServerProbeError.Unhealthy -> 30
            is ServerProbeError.Transport -> 5
        }
    }
}
