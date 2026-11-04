package dev.opencode.mobile.domain.model

import dev.opencode.mobile.data.remote.ModelsApi
import dev.opencode.mobile.domain.connection.ServerProtocol
import dev.opencode.mobile.network.ActiveConnectionManager
import javax.inject.Inject

class DefaultModelRepository
    @Inject
    constructor(
        private val api: ModelsApi,
        private val adapter: ModelDomainAdapter,
        private val activeConnectionManager: ActiveConnectionManager? = null,
    ) : ModelRepository {
        override suspend fun listModelOptions(directory: String?): ModelSelectionCatalog =
            runCatching {
                if (activeConnectionManager?.currentProtocolOrProbe() == ServerProtocol.V2) {
                    adapter.toCatalog(
                        api.listProvidersV2(directory),
                        api.listModelsV2(directory),
                        runCatching { api.getDefaultModelV2(directory) }.getOrNull(),
                    )
                } else {
                    adapter.toCatalog(api.listProviders(directory))
                }
            }.getOrElse { throw adapter.mapError(it) }
    }
