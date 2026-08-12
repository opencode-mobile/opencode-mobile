package dev.opencode.mobile.domain.model

import dev.opencode.mobile.data.remote.ModelsApi
import dev.opencode.mobile.domain.connection.ServerProtocol
import dev.opencode.mobile.domain.project.ProjectRepository
import dev.opencode.mobile.network.ActiveConnectionManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

class DefaultModelRepository
    @Inject
    constructor(
        private val api: ModelsApi,
        private val adapter: ModelDomainAdapter,
        private val activeConnectionManager: ActiveConnectionManager? = null,
        private val projectRepository: ProjectRepository? = null,
    ) : ModelRepository {
        private val protocolMutex = Mutex()

        private suspend fun isV2(): Boolean {
            protocolMutex.withLock {
                activeConnectionManager?.currentProtocol()?.let { return it == ServerProtocol.V2 }
                val projectRepository = projectRepository ?: return false
                runCatching { projectRepository.probeServer() }
                return activeConnectionManager?.currentProtocol() == ServerProtocol.V2
            }
        }

        override suspend fun listModelOptions(directory: String?): ModelSelectionCatalog =
            runCatching {
                if (isV2()) {
                    adapter.toCatalog(api.listProvidersV2(directory), api.listModelsV2(directory))
                } else {
                    adapter.toCatalog(api.listProviders(directory))
                }
            }.getOrElse { throw adapter.mapError(it) }
    }
