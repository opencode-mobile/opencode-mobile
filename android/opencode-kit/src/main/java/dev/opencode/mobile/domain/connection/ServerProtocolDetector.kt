package dev.opencode.mobile.domain.connection

import dev.opencode.mobile.data.remote.dto.HealthDto
import dev.opencode.mobile.domain.diagnostic.DiagnosticLog
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogArea
import dev.opencode.mobile.domain.project.ProjectDomainAdapter
import dev.opencode.mobile.domain.project.ServerProbeError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

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
    ): ServerProtocol = detectServer(fetchGlobalHealth, fetchApiInfo, fetchApiHealth).protocol

    /** [detect] plus the health payload's server version, when the server reports one. */
    suspend fun detectServer(
        fetchGlobalHealth: suspend () -> HealthDto,
        fetchApiInfo: suspend () -> HealthDto,
        fetchApiHealth: suspend () -> HealthDto,
        /** The saved protocol: its own answer ends detection without the slowest endpoint. */
        expected: ServerProtocol? = null,
    ): DetectedServer {
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
                savedProtocolAnswer(expected, global, info, health)?.let { answer ->
                    coroutineContext.cancelChildren()
                    return@coroutineScope answer
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

        val version =
            when {
                globalHealth?.healthy == true -> {
                    adapter.requireHealthy(globalHealth)
                    globalHealth.version
                }
                else -> {
                    val payload = ServerProtocolClassifier.v2Health(apiInfo, apiHealth)
                    if (payload?.pid != null && payload.healthy == false) {
                        throw ServerProbeError.Unhealthy(payload.version)
                    }
                    payload?.version
                }
            }

        DiagnosticLog.info(
            DiagnosticLogArea.Connection,
            "protocol=${protocol.wireValue}",
        )
        return DetectedServer(protocol = protocol, version = version?.trim()?.takeIf { it.isNotEmpty() })
    }

    /**
     * The answer that settles a saved protocol early, or null to wait for all three. A healthy
     * `/global/health` always classifies as v1, so it settles alone. A v2 answer settles once
     * `/global/health` had [SAVED_V2_GLOBAL_GRACE_MS] to say the server is v1 now.
     */
    private suspend fun savedProtocolAnswer(
        expected: ServerProtocol?,
        global: Deferred<Result<HealthDto>>,
        info: Deferred<Result<HealthDto>>,
        health: Deferred<Result<HealthDto>>,
    ): ProbeRound? =
        when (expected) {
            ServerProtocol.V1 ->
                global
                    .await()
                    .getOrNull()
                    ?.takeIf { it.healthy == true }
                    ?.let { ProbeRound(it, null, null, null) }
            ServerProtocol.V2 -> {
                val usable = firstUsableV2(info, health)
                if (usable == null) {
                    null
                } else {
                    val globalAnswer = withTimeoutOrNull(SAVED_V2_GLOBAL_GRACE_MS) { global.await() }?.getOrNull()
                    ProbeRound(globalAnswer, usable, null, null)
                }
            }
            null -> null
        }

    /** The first of the two v2 endpoints to answer with a usable payload; null when neither does. */
    private suspend fun firstUsableV2(
        info: Deferred<Result<HealthDto>>,
        health: Deferred<Result<HealthDto>>,
    ): HealthDto? {
        val remaining = mutableListOf(info, health)
        while (remaining.isNotEmpty()) {
            val (finished, answer) =
                select {
                    remaining.forEach { candidate ->
                        candidate.onAwait { result -> candidate to result }
                    }
                }
            remaining.remove(finished)
            answer.getOrNull()?.takeIf { it.pid != null && it.healthy != false }?.let { return it }
        }
        return null
    }

    private suspend fun probe(
        path: String,
        fetch: suspend () -> HealthDto,
    ): Result<HealthDto> {
        val result =
            try {
                Result.success(fetch())
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                Result.failure(adapter.mapProbeError(error))
            }
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

    companion object {
        /** How long a saved v2 server's answer waits for `/global/health` to report v1 instead. */
        const val SAVED_V2_GLOBAL_GRACE_MS = 750L
    }
}

/** A probed server: its protocol and the version its health endpoint reported. */
data class DetectedServer(
    val protocol: ServerProtocol,
    val version: String? = null,
)
