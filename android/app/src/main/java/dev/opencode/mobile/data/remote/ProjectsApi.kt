package dev.opencode.mobile.data.remote

import dev.opencode.mobile.data.remote.dto.ProjectDto
import dev.opencode.mobile.network.OpencodeHttpClient
import dev.opencode.mobile.network.applyDirectory
import dev.opencode.mobile.network.applyLocationDirectory
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get

class ProjectsApi private constructor(
    private val httpClient: ClientProvider,
) {
    constructor(httpClient: OpencodeHttpClient) : this(ClientProvider(httpClient::get))

    internal constructor(httpClient: HttpClient) : this(ClientProvider { httpClient })

    suspend fun listProjects(): List<ProjectDto> = httpClient.get().get("project").body()

    suspend fun getCurrentProject(directory: String? = null): ProjectDto =
        httpClient
            .get()
            .get("project/current") {
                applyDirectory(directory)
            }.body()

    suspend fun getCurrentProjectV2(directory: String? = null): ProjectDto =
        httpClient
            .get()
            .get("api/project/current") {
                applyLocationDirectory(directory)
            }.body()

    private fun interface ClientProvider {
        fun get(): HttpClient
    }
}
