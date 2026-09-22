package dev.opencode.mobile.data.remote

import dev.opencode.mobile.data.remote.dto.ApiListEnvelope
import dev.opencode.mobile.data.remote.dto.McpStatusDto
import dev.opencode.mobile.data.remote.dto.V2McpServerDto
import dev.opencode.mobile.network.OpencodeHttpClient
import dev.opencode.mobile.network.applyDirectory
import dev.opencode.mobile.network.applyLocationDirectory
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get

class McpApi private constructor(
    private val httpClient: ClientProvider,
) {
    constructor(httpClient: OpencodeHttpClient) : this(ClientProvider(httpClient::get))

    internal constructor(httpClient: HttpClient) : this(ClientProvider { httpClient })

    suspend fun listServers(directory: String? = null): Map<String, McpStatusDto> =
        httpClient
            .get()
            .get("mcp") {
                applyDirectory(directory)
            }.body()

    suspend fun listServersV2(directory: String? = null): List<V2McpServerDto> =
        httpClient
            .get()
            .get("api/mcp") {
                applyLocationDirectory(directory)
            }.body<ApiListEnvelope<V2McpServerDto>>()
            .data

    private fun interface ClientProvider {
        fun get(): HttpClient
    }
}
