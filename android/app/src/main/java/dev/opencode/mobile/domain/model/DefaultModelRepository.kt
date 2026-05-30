package dev.opencode.mobile.domain.model

import dev.opencode.mobile.data.remote.ModelsApi
import javax.inject.Inject

class DefaultModelRepository
    @Inject
    constructor(
        private val api: ModelsApi,
        private val adapter: ModelDomainAdapter,
    ) : ModelRepository {
        override suspend fun listModelOptions(directory: String?): ModelSelectionCatalog =
            runCatching { adapter.toCatalog(api.listProviders(directory)) }
                .getOrElse { throw adapter.mapError(it) }
    }
