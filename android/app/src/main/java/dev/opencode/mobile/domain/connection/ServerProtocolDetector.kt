package dev.opencode.mobile.domain.connection

import dev.opencode.mobile.data.remote.dto.HealthDto
import dev.opencode.mobile.domain.diagnostic.DiagnosticLog
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogArea
import dev.opencode.mobile.domain.project.ProjectDomainAdapter
import dev.opencode.mobile.domain.project.ServerProbeError
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

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
        val (globalHealth, apiHealth, lastError) =
            coroutineScope {
                val global =
                    async {
                        runCatching { fetchGlobalHealth() }
                    }
                val api =
                    async {
                        runCatching { fetchApiHealth() }
                    }
                val globalResult = global.await()
                val apiResult = api.await()
                Triple(
                    globalResult.getOrNull(),
                    apiResult.getOrNull(),
                    ServerProtocolProbeErrors.prefer(
                        globalResult.exceptionOrNull(),
                        apiResult.exceptionOrNull(),
                    ),
                )
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
