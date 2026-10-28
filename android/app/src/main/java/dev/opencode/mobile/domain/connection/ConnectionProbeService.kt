package dev.opencode.mobile.domain.connection

import dev.opencode.mobile.data.remote.dto.HealthDto
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
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject

class ConnectionProbeService(
    private val json: Json,
    private val adapter: ProjectDomainAdapter,
    private val clientFactory: (ServerConnection) -> HttpClient = { connection ->
        KtorClientFactory.create(connection, json, probe = true)
    },
) : ConnectionProbing {
    private val detector = ServerProtocolDetector(adapter)

    /**
     * Probe health endpoints and return the detected [ServerProtocol].
     *
     * Throws [ServerProbeError] when no health endpoint is usable.
     */
    override suspend fun probe(connection: ServerConnection): ServerProtocol = probeServer(connection).protocol

    override suspend fun probeServer(connection: ServerConnection): DetectedServer {
        val client = clientFactory(connection)
        try {
            return detector.detectServer(
                fetchGlobalHealth = { client.fetchHealth("global/health") },
                fetchApiInfo = { client.fetchHealth("api/info") },
                fetchApiHealth = { client.fetchHealth("api/health") },
            )
        } catch (error: Throwable) {
            throw if (error is ServerProbeError) error else adapter.mapProbeError(error)
        } finally {
            client.close()
        }
    }

    override suspend fun probeV2Features(connection: ServerConnection): V2ServerFeatures {
        val client = clientFactory(connection)
        try {
            val response: HttpResponse = client.get(V2ServerContract.OPENAPI_PATH)
            if (!response.status.isSuccess()) {
                throw ServerProbeError.Http(
                    statusCode = response.status.value,
                    message = response.status.description,
                )
            }
            val document = response.body<JsonObject>()
            val features = V2ServerContract.features(document)
            if (!V2ServerContract.declaresAttachmentUpload(document)) return features
            // The route alone is not enough: the upload path is built under the server's tmp directory.
            val tmp =
                try {
                    fetchTmpDirectory(client)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    return features.copy(tmpDirectoryUnread = true)
                }
            return features.copy(attachmentUploadTmp = tmp)
        } finally {
            client.close()
        }
    }

    /** Null when `/api/info` answers without `paths.tmp`; throws when it cannot be read. */
    private suspend fun fetchTmpDirectory(client: HttpClient): String? {
        val response: HttpResponse = client.get(V2ServerContract.INFO_PATH)
        if (!response.status.isSuccess()) {
            throw ServerProbeError.Http(
                statusCode = response.status.value,
                message = response.status.description,
            )
        }
        return V2ServerContract.tmpDirectory(response.body<JsonObject>())
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
        is ServerProbeError.Http ->
            if (statusCode == 401 || statusCode == 403) {
                "Authentication failed"
            } else {
                "Unable to reach the server"
            }
        is ServerProbeError.Unhealthy -> "Server reported unhealthy"
    }
