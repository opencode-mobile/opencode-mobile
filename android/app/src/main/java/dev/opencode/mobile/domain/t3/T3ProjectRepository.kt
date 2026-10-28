package dev.opencode.mobile.domain.t3

import dev.opencode.mobile.domain.connection.ConnectionProfileStore
import dev.opencode.mobile.domain.project.Project
import dev.opencode.mobile.domain.project.ProjectRepository
import dev.opencode.mobile.network.ActiveConnectionManager
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class T3ProjectRepository
    @Inject
    constructor(
        private val runtime: T3Runtime,
        private val activeConnectionManager: ActiveConnectionManager,
        private val connectionProfileStore: ConnectionProfileStore,
    ) : ProjectRepository {
        override suspend fun probeServer() {
            val snapshot = runtime.refreshShell()
            val profile = activeConnectionManager.currentProfile() ?: return
            val roots = snapshot.projects.map { it.workspaceRoot }.filter { it.isNotBlank() }
            if (roots.isEmpty()) return
            // Project roots join the user's own worktrees; they never replace them.
            val existing = connectionProfileStore.addedWorktrees(profile.baseUrl)
            val merged = (existing + roots).distinct()
            if (merged != existing) {
                connectionProfileStore.setAddedWorktrees(profile.baseUrl, merged)
            }
        }

        override suspend fun listProjects(): List<Project> {
            val snapshot = runtime.shell.value ?: runtime.refreshShell()
            return snapshot.projects.map { project ->
                Project(
                    id = project.id,
                    worktree = project.workspaceRoot,
                    displayName = project.title.ifBlank { project.workspaceRoot.substringAfterLast('/') },
                )
            }
        }

        override suspend fun getCurrentProject(directory: String?): Project {
            val projects = listProjects()
            if (directory.isNullOrBlank()) {
                return projects.firstOrNull() ?: Project(id = "t3", worktree = "/", displayName = "T3")
            }
            return projects.firstOrNull { it.worktree == directory }
                ?: Project(
                    id = directory,
                    worktree = directory,
                    displayName = directory.substringAfterLast('/').ifBlank { directory },
                )
        }
    }
