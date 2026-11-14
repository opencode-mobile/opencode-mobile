package dev.opencode.mobile.domain.project

import dev.opencode.mobile.data.remote.ProjectsApi
import dev.opencode.mobile.data.remote.dto.ProjectDto
import dev.opencode.mobile.domain.connection.ServerProtocol
import dev.opencode.mobile.network.ActiveConnectionManager
import kotlinx.coroutines.CancellationException
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
            runCatching {
                val dtos =
                    if (activeConnectionManager.currentProtocolOrProbe() == ServerProtocol.V2) {
                        listProjectsV2OrV1()
                    } else {
                        projectsApi.listProjects()
                    }
                dtos.map(adapter::toProject)
            }.getOrElse { throw adapter.mapProbeError(it) }

        // Servers detected as v2 may not serve `/api/project` (the web UI answers with HTML);
        // the unprefixed `/project` list is still there.
        private suspend fun listProjectsV2OrV1(): List<ProjectDto> =
            try {
                projectsApi.listProjectsV2()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                projectsApi.listProjects()
            }

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
