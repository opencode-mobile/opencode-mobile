package dev.opencode.mobile.data.remote

import dev.opencode.mobile.data.remote.dto.ProjectDto
import dev.opencode.mobile.data.remote.dto.V2LocationInfoDto
import dev.opencode.mobile.data.remote.dto.V2ProjectDto
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

    suspend fun listProjectsV2(): List<ProjectDto> =
        httpClient
            .get()
            .get("api/project")
            .body<List<V2ProjectDto>>()
            .map { ProjectDto(id = it.id, directory = it.canonical) }

    suspend fun getCurrentProject(directory: String? = null): ProjectDto =
        httpClient
            .get()
            .get("project/current") {
                applyDirectory(directory)
            }.body()

    suspend fun getCurrentProjectV2(directory: String? = null): ProjectDto {
        val location =
            httpClient
                .get()
                .get("api/location") {
                    applyLocationDirectory(directory)
                }.body<V2LocationInfoDto>()
        val project = requireNotNull(location.project)
        return ProjectDto(
            id = requireNotNull(project.id),
            directory = requireNotNull(project.directory),
        )
    }

    private fun interface ClientProvider {
        fun get(): HttpClient
    }
}
