package dev.opencode.mobile.domain.project

interface ProjectRepository {
  suspend fun probeServer()

  suspend fun listProjects(): List<Project>

  suspend fun getCurrentProject(directory: String? = null): Project
}
