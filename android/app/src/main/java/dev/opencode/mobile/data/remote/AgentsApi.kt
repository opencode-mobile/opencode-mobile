package dev.opencode.mobile.data.remote

import dev.opencode.mobile.data.remote.dto.AgentDto
import dev.opencode.mobile.network.OpencodeHttpClient
import dev.opencode.mobile.network.applyDirectory
import io.ktor.client.call.body
import io.ktor.client.request.get

class AgentsApi(
    private val httpClient: OpencodeHttpClient,
) {
    suspend fun listAgents(directory: String? = null): List<AgentDto> =
        httpClient
            .get()
            .get("agent") {
                applyDirectory(directory)
            }.body()
}
