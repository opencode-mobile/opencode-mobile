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

/** First run on the app's real services. In Settings ([forSettings]) the model page saves the model and nothing else. */
class HexFirstRunEnvironment(
    private val services: HexServices,
    private val forSettings: Boolean = false,
) : FirstRunEnvironment {
    override val pairing: PairingConnector get() = services.pairing
    override val projects: ProjectRepository get() = services.projects
    override val filesystem: FilesystemRepository get() = services.filesystem
    override val models: ModelRepository get() = services.models
    override val modelTester: ModelTesting get() = services.modelTester

    override suspend fun useServer(result: ConnectResult.Connected) {
        services.useServer(result.profile)
        val home = runCatching { services.filesystem.getPaths().home }.getOrNull()
        services.settings.setServer(home = home, version = result.version)
    }

    override suspend fun saveProject(project: ProjectChoice) = services.settings.setProject(project)

    override suspend fun finish(
        model: ModelChoice,
        name: String,
    ) = if (forSettings) services.settings.setModel(model, name) else services.settings.finishFirstRun(model, name)

    override suspend fun savedModel(): ModelChoice? = services.settings.current().model

    override fun getSpeechModel() {
        services.loadSpeech()
    }
}
