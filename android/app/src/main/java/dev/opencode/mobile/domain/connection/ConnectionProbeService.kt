package dev.opencode.mobile.domain.connection

import dev.opencode.mobile.data.remote.dto.HealthDto
import dev.opencode.mobile.domain.diagnostic.DiagnosticLog
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogArea
import dev.opencode.mobile.domain.project.ProjectDomainAdapter
import dev.opencode.mobile.domain.project.ServerProbeError
import dev.opencode.mobile.network.KtorClientFactory
import dev.opencode.mobile.network.ServerConnection
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json

class ConnectionProbeService(
    private val json: Json,
    private val adapter: ProjectDomainAdapter,
    private val clientFactory: (ServerConnection) -> HttpClient = { connection ->
        KtorClientFactory.create(connection, json)
    },
) {
    /**
     * Dual-probe health and return the detected [ServerProtocol].
     *
     * Throws [ServerProbeError] when neither health endpoint is usable.
     */
    suspend fun probe(connection: ServerConnection): ServerProtocol {
        val client = clientFactory(connection)
        try {
            var lastError: Throwable? = null
            val globalHealth =
                runCatching { client.fetchHealth("global/health") }
                    .onFailure { lastError = it }
                    .getOrNull()

            val apiHealth =
                if (globalHealth?.healthy == true) {
                    null
                } else {
                    runCatching { client.fetchHealth("api/health") }
                        .onFailure { lastError = it }
                        .getOrNull()
                }

            val protocol =
                ServerProtocolClassifier.classify(globalHealth, apiHealth)
                    ?: throw adapter.mapProbeError(
                        lastError ?: ServerProbeError.Transport(IllegalStateException("No health endpoint")),
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
        } catch (error: Throwable) {
            throw if (error is ServerProbeError) error else adapter.mapProbeError(error)
        } finally {
            client.close()
        }
    }

    private suspend fun HttpClient.fetchHealth(path: String): HealthDto {
        val response: HttpResponse = get(path)
        if (!response.status.isSuccess()) {
            throw ServerProbeError.Http(
                statusCode = response.status.value,
                message = response.status.description,
            )
        }
        return response.body()
    }
}

enum class ProbeStatus {
    Reachable,
    AuthFailed,
    Unreachable,
}

fun ServerProbeError.toProbeStatus(): ProbeStatus =
    when (this) {
        is ServerProbeError.Http ->
            if (statusCode == 401 || statusCode == 403) {
                ProbeStatus.AuthFailed
            } else {
                ProbeStatus.Unreachable
            }
        is ServerProbeError.Transport, is ServerProbeError.Unhealthy -> ProbeStatus.Unreachable
    }

fun ServerProbeError.userMessage(): String =
    when (this) {
        is ServerProbeError.Transport -> "Unable to reach the server"
        is ServerProbeError.Http -> message ?: "Authentication failed"
        is ServerProbeError.Unhealthy -> "Server reported unhealthy"
    }
