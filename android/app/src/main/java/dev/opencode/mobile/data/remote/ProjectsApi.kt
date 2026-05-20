package dev.opencode.mobile.data.remote

import dev.opencode.mobile.data.remote.dto.ProjectDto
import dev.opencode.mobile.network.applyDirectory
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get

class ProjectsApi(
  private val httpClient: HttpClient,
) {
  suspend fun listProjects(): List<ProjectDto> = httpClient.get("project").body()

  suspend fun getCurrentProject(directory: String? = null): ProjectDto =
    httpClient.get("project/current") {
      applyDirectory(directory)
    }.body()
}
