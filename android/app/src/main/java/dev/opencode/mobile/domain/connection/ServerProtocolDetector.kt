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
 * Probes /global/health, /api/health, and /api/info in parallel.
 */
class ServerProtocolDetector(
    private val adapter: ProjectDomainAdapter,
) {
    suspend fun detect(
        fetchGlobalHealth: suspend () -> HealthDto,
        fetchApiHealth: suspend () -> HealthDto,
        fetchApiInfo: suspend () -> HealthDto,
    ): ServerProtocol {
        val (globalHealth, apiHealth, apiInfo, lastError) =
            coroutineScope {
                val global =
                    async {
                        probe("/global/health", fetchGlobalHealth)
                    }
                val api =
                    async {
                        probe("/api/health", fetchApiHealth)
                    }
                val info =
                    async {
                        probe("/api/info", fetchApiInfo)
                    }
                val globalResult = global.await()
                val apiResult = api.await()
                val infoResult = info.await()
                ProbeRound(
                    globalResult.getOrNull(),
                    apiResult.getOrNull(),
                    infoResult.getOrNull(),
                    ServerProtocolProbeErrors.prefer(
                        ServerProtocolProbeErrors.prefer(
                            globalResult.exceptionOrNull(),
                            apiResult.exceptionOrNull(),
                        ),
                        infoResult.exceptionOrNull(),
                    ),
                )
            }

        val protocol =
            ServerProtocolClassifier.classify(globalHealth, apiHealth, apiInfo)
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
            apiHealth?.healthy == true -> adapter.requireHealthy(apiHealth)
            apiInfo?.pid != null -> {
                if (apiInfo.healthy == false) {
                    throw ServerProbeError.Unhealthy(apiInfo.version)
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
        val apiHealth: HealthDto?,
        val apiInfo: HealthDto?,
        val lastError: Throwable?,
    )
}
