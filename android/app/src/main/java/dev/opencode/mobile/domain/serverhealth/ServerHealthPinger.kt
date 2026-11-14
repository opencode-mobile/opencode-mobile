package dev.opencode.mobile.domain.serverhealth

import dev.opencode.mobile.data.remote.dto.HealthDto
import dev.opencode.mobile.domain.connection.ServerProtocol
import dev.opencode.mobile.network.KtorClientFactory
import dev.opencode.mobile.network.ServerConnection
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

/** What one health check found. */
sealed interface ServerHealthPingResult {
    data class Success(
        val latencyMs: Int,
        val pid: Int?,
        val version: String?,
    ) : ServerHealthPingResult

    data class Failure(
        val category: ServerHealthFailureCategory,
    ) : ServerHealthPingResult
}

/** Sends one health request to the active server. */
fun interface ServerHealthPinger {
    suspend fun ping(
        connection: ServerConnection,
        protocol: ServerProtocol,
    ): ServerHealthPingResult
}

/**
 * GET `global/health` (v1) or `api/info` (v2) on a short-timeout probe client. The client is
 * kept until the connection changes, so checks reuse one connection pool.
 */
class KtorServerHealthPinger(
    private val json: Json,
    private val clock: () -> Long = System::nanoTime,
    private val clientFactory: (ServerConnection) -> HttpClient = { connection ->
        KtorClientFactory.create(connection, json, probe = true)
    },
) : ServerHealthPinger {
    private val lock = Any()
    private var cached: Pair<ServerConnection, HttpClient>? = null

    override suspend fun ping(
        connection: ServerConnection,
        protocol: ServerProtocol,
    ): ServerHealthPingResult {
        val client = clientFor(connection)
        val path = if (protocol == ServerProtocol.V2) "api/info" else "global/health"
        val started = clock()
        return try {
            val response = client.get(path)
            val status = response.status.value
            if (!response.status.isSuccess()) {
                return ServerHealthPingResult.Failure(ServerHealthFailureClassifier.classifyStatus(status))
            }
            val health =
                try {
                    response.body<HealthDto>()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    return ServerHealthPingResult.Failure(ServerHealthFailureCategory.Other)
                }
            val latencyMs = ((clock() - started) / 1_000_000L).toInt().coerceAtLeast(0)
            if (health.healthy == false) {
                ServerHealthPingResult.Failure(ServerHealthFailureCategory.Server)
            } else {
                ServerHealthPingResult.Success(latencyMs = latencyMs, pid = health.pid, version = health.version)
            }
        } catch (cancelled: CancellationException) {
            if (ServerHealthFailureClassifier.isTimeout(cancelled)) {
                ServerHealthPingResult.Failure(ServerHealthFailureCategory.Timeout)
            } else {
                throw cancelled
            }
        } catch (error: Throwable) {
            ServerHealthPingResult.Failure(ServerHealthFailureClassifier.classifyTransport(error))
        }
    }

    private fun clientFor(connection: ServerConnection): HttpClient =
        synchronized(lock) {
            val existing = cached
            if (existing != null && existing.first == connection) return existing.second
            existing?.second?.close()
            val client = clientFactory(connection)
            cached = connection to client
            client
        }
}
