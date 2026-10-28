package dev.opencode.mobile.domain.engine

import dev.opencode.mobile.domain.connection.ConnectionEngine
import dev.opencode.mobile.domain.project.DefaultProjectRepository
import dev.opencode.mobile.domain.project.Project
import dev.opencode.mobile.domain.project.ProjectRepository
import dev.opencode.mobile.domain.t3.T3ProjectRepository
import dev.opencode.mobile.network.ActiveConnectionManager
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EngineProjectRepository
    @Inject
    constructor(
        private val opencode: DefaultProjectRepository,
        private val t3: T3ProjectRepository,
        private val activeConnectionManager: ActiveConnectionManager,
    ) : ProjectRepository {
        private fun delegate(): ProjectRepository =
            if (activeConnectionManager.currentProfile()?.engine == ConnectionEngine.T3) t3 else opencode

        override suspend fun probeServer() = delegate().probeServer()

        override suspend fun listProjects(): List<Project> = delegate().listProjects()

        override suspend fun getCurrentProject(directory: String?): Project = delegate().getCurrentProject(directory)
    }
