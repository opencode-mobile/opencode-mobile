package dev.opencode.mobile.domain.connection

import dev.opencode.mobile.data.remote.dto.HealthDto
import dev.opencode.mobile.domain.diagnostic.DiagnosticLog
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogArea
import dev.opencode.mobile.domain.project.ProjectDomainAdapter
import dev.opencode.mobile.domain.project.ServerProbeError

/**
 * Shared dual-health detection used by connection setup and home probe.
 */
class ServerProtocolDetector(
    private val adapter: ProjectDomainAdapter,
) {
    suspend fun detect(
        fetchGlobalHealth: suspend () -> HealthDto,
        fetchApiHealth: suspend () -> HealthDto,
    ): ServerProtocol {
        var lastError: Throwable? = null
        val globalHealth =
            runCatching { fetchGlobalHealth() }
                .onFailure { lastError = ServerProtocolProbeErrors.prefer(lastError, it) }
                .getOrNull()

        val apiHealth =
            if (globalHealth?.healthy == true) {
                null
            } else {
                runCatching { fetchApiHealth() }
                    .onFailure { lastError = ServerProtocolProbeErrors.prefer(lastError, it) }
                    .getOrNull()
            }

        val protocol =
            ServerProtocolClassifier.classify(globalHealth, apiHealth)
                ?: throw adapter.mapProbeError(
                    ServerProtocolProbeErrors.unresolved(globalHealth, apiHealth, lastError),
                )

        when {
            globalHealth?.healthy == true -> adapter.requireHealthy(globalHealth)
            apiHealth?.pid != null -> {
                if (apiHealth.healthy == false) {
                    throw ServerProbeError.Unhealthy(apiHealth.version)
                }
            }
            apiHealth != null -> adapter.requireHealthy(apiHealth)
        }

        DiagnosticLog.info(
            DiagnosticLogArea.Connection,
            "protocol=${protocol.wireValue}",
        )
        return protocol
    }
}
