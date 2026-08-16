package dev.opencode.mobile.domain.project

import dev.opencode.mobile.data.remote.ProjectsApi
import dev.opencode.mobile.domain.connection.ServerProtocol
import dev.opencode.mobile.network.ActiveConnectionManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

class DefaultProjectRepository
    @Inject
    constructor(
        private val projectsApi: ProjectsApi,
        private val adapter: ProjectDomainAdapter,
        private val activeConnectionManager: ActiveConnectionManager,
    ) : ProjectRepository {
        private val probeMutex = Mutex()

        override suspend fun probeServer() {
            probeMutex.withLock {
                runCatching {
                    activeConnectionManager.probeActive()
                }.getOrElse { throw adapter.mapProbeError(it) }
            }
        }

        override suspend fun listProjects(): List<Project> =
            runCatching { projectsApi.listProjects().map(adapter::toProject) }
                .getOrElse { throw adapter.mapProbeError(it) }

        override suspend fun getCurrentProject(directory: String?): Project =
            runCatching {
                val dto =
                    if (activeConnectionManager.currentProtocolOrProbe() == ServerProtocol.V2) {
                        projectsApi.getCurrentProjectV2(directory)
                    } else {
                        projectsApi.getCurrentProject(directory)
                    }
                adapter.toProject(dto)
            }.getOrElse { throw adapter.mapProbeError(it) }
    }
