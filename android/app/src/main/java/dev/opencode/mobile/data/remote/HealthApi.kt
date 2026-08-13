package dev.opencode.mobile.data.remote

import dev.opencode.mobile.data.remote.dto.HealthDto
import dev.opencode.mobile.domain.connection.ServerProtocol
import dev.opencode.mobile.domain.connection.ServerProtocolDetector
import dev.opencode.mobile.domain.project.ProjectDomainAdapter
import dev.opencode.mobile.domain.project.ServerProbeError
import dev.opencode.mobile.network.OpencodeHttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.isSuccess

class HealthApi(
    private val httpClient: OpencodeHttpClient,
    private val adapter: ProjectDomainAdapter = ProjectDomainAdapter(),
) {
    private val detector = ServerProtocolDetector(adapter)

    suspend fun getGlobalHealth(): HealthDto = fetchHealth("global/health")

    suspend fun getApiHealth(): HealthDto = fetchHealth("api/health")

    /** @deprecated Use [detectProtocol] for dual-stack probes. */
    suspend fun getHealth(): HealthDto = getGlobalHealth()

    /**
     * Dual-probe health endpoints and return the detected protocol.
     * Throws [ServerProbeError] when neither endpoint is usable.
     */
    suspend fun detectProtocol(): ServerProtocol =
        try {
            detector.detect(
                fetchGlobalHealth = ::getGlobalHealth,
                fetchApiHealth = ::getApiHealth,
            )
        } catch (error: Throwable) {
            throw if (error is ServerProbeError) error else adapter.mapProbeError(error)
        }

    private suspend fun fetchHealth(path: String): HealthDto {
        val response: HttpResponse = httpClient.get().get(path)
        if (!response.status.isSuccess()) {
            throw ServerProbeError.Http(
                statusCode = response.status.value,
                message = response.status.description,
            )
        }
        return response.body()
    }
}
