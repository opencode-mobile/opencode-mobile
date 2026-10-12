package dev.opencode.mobile.domain.connection

import dev.opencode.mobile.data.remote.dto.HealthDto
import dev.opencode.mobile.domain.diagnostic.DiagnosticLog
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogArea
import dev.opencode.mobile.domain.project.ProjectDomainAdapter
import dev.opencode.mobile.domain.project.ServerProbeError
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Shared health detection used by connection setup and home probe.
 * Probes /global/health, /api/info, and /api/health in parallel.
 */
class ServerProtocolDetector(
    private val adapter: ProjectDomainAdapter,
) {
    suspend fun detect(
        fetchGlobalHealth: suspend () -> HealthDto,
        fetchApiInfo: suspend () -> HealthDto,
        fetchApiHealth: suspend () -> HealthDto,
    ): ServerProtocol {
        val (globalHealth, apiInfo, apiHealth, lastError) =
            coroutineScope {
                val global =
                    async {
                        probe("/global/health", fetchGlobalHealth)
                    }
                val info =
                    async {
                        probe("/api/info", fetchApiInfo)
                    }
                val health =
                    async {
                        probe("/api/health", fetchApiHealth)
                    }
                val globalResult = global.await()
                val infoResult = info.await()
                val healthResult = health.await()
                ProbeRound(
                    globalResult.getOrNull(),
                    infoResult.getOrNull(),
                    healthResult.getOrNull(),
                    ServerProtocolProbeErrors.prefer(
                        ServerProtocolProbeErrors.prefer(
                            globalResult.exceptionOrNull(),
                            infoResult.exceptionOrNull(),
                        ),
                        healthResult.exceptionOrNull(),
                    ),
                )
            }

        val protocol =
            ServerProtocolClassifier.classify(globalHealth, apiInfo, apiHealth)
                ?: throw adapter.mapProbeError(
                    ServerProtocolProbeErrors.unresolved(globalHealth, lastError),
                )

        when {
            globalHealth?.healthy == true -> adapter.requireHealthy(globalHealth)
            else -> {
                val payload = ServerProtocolClassifier.v2Health(apiInfo, apiHealth)
                if (payload?.pid != null && payload.healthy == false) {
                    throw ServerProbeError.Unhealthy(payload.version)
                }
            }
        }

        DiagnosticLog.info(
            DiagnosticLogArea.Connection,
            "protocol=${protocol.wireValue}",
        )
        return protocol
    }

    private suspend fun probe(
        path: String,
        fetch: suspend () -> HealthDto,
    ): Result<HealthDto> {
        val result =
            runCatching { fetch() }
                .recoverCatching { throw adapter.mapProbeError(it) }
        result.exceptionOrNull()?.let { error ->
            DiagnosticLog.warning(
                DiagnosticLogArea.Connection,
                "probe $path failed ${describeProbeFailure(error)}",
            )
        }
        return result
    }

    private fun describeProbeFailure(error: Throwable): String =
        when (val probe = error as? ServerProbeError) {
            is ServerProbeError.Http -> "status=${probe.statusCode}"
            is ServerProbeError.Unhealthy -> "unhealthy"
            is ServerProbeError.Transport -> {
                val code = probe.cause?.javaClass?.simpleName ?: "transport"
                "code=$code"
            }
            null -> "code=${error.javaClass.simpleName}"
        }

    private data class ProbeRound(
        val globalHealth: HealthDto?,
        val apiInfo: HealthDto?,
        val apiHealth: HealthDto?,
        val lastError: Throwable?,
    )
}
