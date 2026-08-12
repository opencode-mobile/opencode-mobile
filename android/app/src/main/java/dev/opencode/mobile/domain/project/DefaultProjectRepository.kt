package dev.opencode.mobile.domain.project

import dev.opencode.mobile.data.remote.HealthApi
import dev.opencode.mobile.data.remote.ProjectsApi
import dev.opencode.mobile.domain.connection.ServerProtocol
import dev.opencode.mobile.network.ActiveConnectionManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

class DefaultProjectRepository
    @Inject
    constructor(
        private val healthApi: HealthApi,
        private val projectsApi: ProjectsApi,
        private val adapter: ProjectDomainAdapter,
        private val activeConnectionManager: ActiveConnectionManager,
    ) : ProjectRepository {
        private val protocolMutex = Mutex()

        private suspend fun isV2(): Boolean {
            protocolMutex.withLock {
                activeConnectionManager.currentProtocol()?.let { return it == ServerProtocol.V2 }
                runCatching { probeServerLocked() }
                return activeConnectionManager.currentProtocol() == ServerProtocol.V2
            }
        }

        override suspend fun probeServer() {
            protocolMutex.withLock { probeServerLocked() }
        }

        private suspend fun probeServerLocked() {
            runCatching {
                val protocol = healthApi.detectProtocol()
                activeConnectionManager.updateProtocol(protocol)
            }.getOrElse { throw adapter.mapProbeError(it) }
        }

        override suspend fun listProjects(): List<Project> =
            runCatching { projectsApi.listProjects().map(adapter::toProject) }
                .getOrElse { throw adapter.mapProbeError(it) }

        override suspend fun getCurrentProject(directory: String?): Project =
            runCatching {
                val dto =
                    if (isV2()) {
                        projectsApi.getCurrentProjectV2(directory)
                    } else {
                        projectsApi.getCurrentProject(directory)
                    }
                adapter.toProject(dto)
            }.getOrElse { throw adapter.mapProbeError(it) }
    }
