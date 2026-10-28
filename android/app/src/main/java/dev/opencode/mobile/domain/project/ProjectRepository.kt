package dev.opencode.mobile.domain.project

interface ProjectRepository {
    suspend fun probeServer()

    suspend fun listProjects(): List<Project>

    suspend fun getCurrentProject(directory: String? = null): Project
}

/** Session Chat tests that do not load a worktree inventory. */
object UnavailableProjectRepository : ProjectRepository {
    override suspend fun probeServer() = Unit

    override suspend fun listProjects(): List<Project> = emptyList()

    override suspend fun getCurrentProject(directory: String?): Project =
        Project(id = "", worktree = directory.orEmpty(), displayName = "")
}
