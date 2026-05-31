package dev.opencode.mobile.data.remote

import dev.opencode.mobile.data.remote.dto.ConfigProvidersResponseDto
import dev.opencode.mobile.network.OpencodeHttpClient
import dev.opencode.mobile.network.applyDirectory
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.serialization.json.Json

class ModelsApi(
    private val httpClient: OpencodeHttpClient,
    @Suppress("unused") private val json: Json,
) {
    suspend fun listProviders(directory: String? = null): ConfigProvidersResponseDto =
        httpClient
            .get()
            .get("config/providers") {
                applyDirectory(directory)
            }.body()
}
