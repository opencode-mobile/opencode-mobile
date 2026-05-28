package dev.opencode.mobile.domain.connection

import dev.opencode.mobile.data.remote.dto.HealthDto
import dev.opencode.mobile.domain.project.ProjectDomainAdapter
import dev.opencode.mobile.domain.project.ServerProbeError
import dev.opencode.mobile.network.KtorClientFactory
import dev.opencode.mobile.network.ServerConnection
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.serialization.json.Json
import javax.inject.Inject

class ConnectionProbeService
    @Inject
    constructor(
        private val json: Json,
        private val adapter: ProjectDomainAdapter,
    ) {
        suspend fun probe(connection: ServerConnection) {
            val client = KtorClientFactory.create(connection, json)
            try {
                runCatching {
                    adapter.requireHealthy(client.get("global/health").body<HealthDto>())
                }.getOrElse { throw adapter.mapProbeError(it) }
            } finally {
                client.close()
            }
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
