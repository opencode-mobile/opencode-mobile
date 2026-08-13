package dev.opencode.mobile.data.remote

import dev.opencode.mobile.data.remote.dto.AgentDto
import dev.opencode.mobile.data.remote.dto.ApiListEnvelope
import dev.opencode.mobile.data.remote.dto.V2AgentDto
import dev.opencode.mobile.network.OpencodeHttpClient
import dev.opencode.mobile.network.applyDirectory
import dev.opencode.mobile.network.applyLocationDirectory
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get

class AgentsApi private constructor(
    private val httpClient: ClientProvider,
) {
    constructor(httpClient: OpencodeHttpClient) : this(ClientProvider(httpClient::get))

    internal constructor(httpClient: HttpClient) : this(ClientProvider { httpClient })

    suspend fun listAgents(directory: String? = null): List<AgentDto> =
        httpClient
            .get()
            .get("agent") {
                applyDirectory(directory)
            }.body()

    suspend fun listAgentsV2(directory: String? = null): List<V2AgentDto> =
        httpClient
            .get()
            .get("api/agent") {
                applyLocationDirectory(directory)
            }.body<ApiListEnvelope<V2AgentDto>>()
            .data

    private fun interface ClientProvider {
        fun get(): HttpClient
    }
}
