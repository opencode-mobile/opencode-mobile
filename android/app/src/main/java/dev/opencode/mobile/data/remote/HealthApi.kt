package dev.opencode.mobile.data.remote

import dev.opencode.mobile.data.remote.dto.HealthDto
import dev.opencode.mobile.network.OpencodeHttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get

class HealthApi(
    private val httpClient: OpencodeHttpClient,
) {
    suspend fun getHealth(): HealthDto = httpClient.get().get("global/health").body()
}
