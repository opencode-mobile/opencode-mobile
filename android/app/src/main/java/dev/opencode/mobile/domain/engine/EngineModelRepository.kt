package dev.opencode.mobile.domain.engine

import dev.opencode.mobile.domain.connection.ConnectionEngine
import dev.opencode.mobile.domain.model.DefaultModelRepository
import dev.opencode.mobile.domain.model.ModelRepository
import dev.opencode.mobile.domain.model.ModelSelectionCatalog
import dev.opencode.mobile.domain.t3.T3ModelRepository
import dev.opencode.mobile.network.ActiveConnectionManager
import javax.inject.Inject
import javax.inject.Singleton

/** Serves the model catalogue of the active profile's engine. */
@Singleton
class EngineModelRepository private constructor(
    private val opencode: ModelRepository,
    private val t3: ModelRepository,
    private val activeConnectionManager: ActiveConnectionManager,
) : ModelRepository {
    @Inject
    constructor(
        opencode: DefaultModelRepository,
        t3: T3ModelRepository,
        activeConnectionManager: ActiveConnectionManager,
    ) : this(opencode as ModelRepository, t3 as ModelRepository, activeConnectionManager)

    override suspend fun listModelOptions(directory: String?): ModelSelectionCatalog = delegate().listModelOptions(directory)

    private fun delegate(): ModelRepository = if (activeConnectionManager.currentProfile()?.engine == ConnectionEngine.T3) t3 else opencode

    companion object {
        fun forTest(
            opencode: ModelRepository,
            t3: ModelRepository,
            activeConnectionManager: ActiveConnectionManager,
        ): EngineModelRepository = EngineModelRepository(opencode, t3, activeConnectionManager)
    }
}
