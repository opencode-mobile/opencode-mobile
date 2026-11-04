package dev.opencode.hexcode.app

import dev.opencode.hexcode.firstrun.ConnectResult
import dev.opencode.hexcode.firstrun.FirstRunEnvironment
import dev.opencode.hexcode.firstrun.ModelChoice
import dev.opencode.hexcode.firstrun.ModelTesting
import dev.opencode.hexcode.firstrun.PairingConnector
import dev.opencode.hexcode.firstrun.ProjectChoice
import dev.opencode.mobile.domain.filesystem.FilesystemRepository
import dev.opencode.mobile.domain.model.ModelRepository
import dev.opencode.mobile.domain.project.ProjectRepository
import kotlinx.coroutines.launch

/** First run on the app's real services. */
class HexFirstRunEnvironment(
    private val services: HexServices,
) : FirstRunEnvironment {
    override val pairing: PairingConnector get() = services.pairing
    override val projects: ProjectRepository get() = services.projects
    override val filesystem: FilesystemRepository get() = services.filesystem
    override val models: ModelRepository get() = services.models
    override val modelTester: ModelTesting get() = services.modelTester

    override suspend fun useServer(result: ConnectResult.Connected) = services.useServer(result.profile)

    override suspend fun saveProject(project: ProjectChoice) = services.settings.setProject(project)

    override suspend fun finish(model: ModelChoice) = services.settings.finishFirstRun(model)

    override fun getSpeechModel() {
        services.appScope.launch { services.voice.prepare() }
    }
}
