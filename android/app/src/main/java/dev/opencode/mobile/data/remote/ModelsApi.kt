package dev.opencode.mobile.data.remote

import dev.opencode.mobile.data.remote.dto.ApiListEnvelope
import dev.opencode.mobile.data.remote.dto.ConfigProvidersResponseDto
import dev.opencode.mobile.data.remote.dto.V2ModelInfoDto
import dev.opencode.mobile.data.remote.dto.V2ProviderInfoDto
import dev.opencode.mobile.network.OpencodeHttpClient
import dev.opencode.mobile.network.applyDirectory
import dev.opencode.mobile.network.applyLocationDirectory
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.serialization.json.Json

class ModelsApi private constructor(
    private val httpClient: ClientProvider,
    @Suppress("unused") private val json: Json,
) {
    constructor(httpClient: OpencodeHttpClient, json: Json) : this(ClientProvider(httpClient::get), json)

    internal constructor(httpClient: HttpClient, json: Json) : this(ClientProvider { httpClient }, json)

    suspend fun listProviders(directory: String? = null): ConfigProvidersResponseDto =
        httpClient
            .get()
            .get("config/providers") {
                applyDirectory(directory)
            }.body()

    suspend fun listProvidersV2(directory: String? = null): List<V2ProviderInfoDto> =
        httpClient
            .get()
            .get("api/provider") {
                applyLocationDirectory(directory)
            }.body<ApiListEnvelope<V2ProviderInfoDto>>()
            .data

    suspend fun listModelsV2(directory: String? = null): List<V2ModelInfoDto> =
        httpClient
            .get()
            .get("api/model") {
                applyLocationDirectory(directory)
            }.body<ApiListEnvelope<V2ModelInfoDto>>()
            .data

    private fun interface ClientProvider {
        fun get(): HttpClient
    }
}
