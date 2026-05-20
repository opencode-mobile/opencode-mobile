package dev.opencode.mobile.domain.project

import dev.opencode.mobile.data.remote.HealthApi
import dev.opencode.mobile.data.remote.ProjectsApi
import javax.inject.Inject

class DefaultProjectRepository
@Inject
constructor(
  private val healthApi: HealthApi,
  private val projectsApi: ProjectsApi,
  private val adapter: ProjectDomainAdapter,
) : ProjectRepository {
  override suspend fun probeServer() {
    runCatching {
      adapter.requireHealthy(healthApi.getHealth())
    }.getOrElse { throw adapter.mapProbeError(it) }
  }

  override suspend fun listProjects(): List<Project> =
    runCatching { projectsApi.listProjects().map(adapter::toProject) }
      .getOrElse { throw adapter.mapProbeError(it) }

  override suspend fun getCurrentProject(directory: String?): Project =
    runCatching { adapter.toProject(projectsApi.getCurrentProject(directory)) }
      .getOrElse { throw adapter.mapProbeError(it) }
}
