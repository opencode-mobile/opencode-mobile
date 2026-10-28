package dev.opencode.mobile.domain.t3

import dev.opencode.mobile.domain.model.ModelProviderTraits
import dev.opencode.mobile.domain.model.ModelRepository
import dev.opencode.mobile.domain.model.ModelSelectionCatalog
import dev.opencode.mobile.domain.model.ModelSelectionError
import dev.opencode.mobile.domain.model.ModelSelectionOption
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The T3 provider catalogue as the composer's model catalogue: each usable harness
 * is a provider and its models sit beneath it. The server default is the
 * [T3ModelSelectionResolver] answer for the project that owns the directory.
 */
@Singleton
class T3ModelRepository
    @Inject
    constructor(
        private val runtime: T3Runtime,
    ) : ModelRepository {
        override suspend fun listModelOptions(directory: String?): ModelSelectionCatalog {
            val config =
                try {
                    // Every catalogue request is an explicit refresh; creation reuses the cache.
                    runtime.serverConfig(forceRefresh = true)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    throw ModelSelectionError.Transport(error)
                }
            val shell = runtime.shell.value ?: runCatching { runtime.refreshShell() }.getOrNull()
            val project = shell?.let { projectFor(it, directory) }
            return catalog(config, project, directory)
        }

        companion object {
            fun catalog(
                config: T3ServerConfig,
                project: T3Project?,
                directory: String? = null,
            ): ModelSelectionCatalog {
                val cwd = directory?.takeIf { it.isNotBlank() } ?: project?.workspaceRoot
                val usable = config.harnesses.filter { it.isUsable }
                val options = usable.flatMap { harness -> harness.models.map { model -> option(harness, model) } }
                val projectDefault =
                    project?.defaultInstanceId?.takeIf { it.isNotBlank() }?.let {
                        T3ModelSelection(instanceId = it, model = project.defaultModel)
                    }
                val resolved = T3ModelSelectionResolver.resolve(config.harnesses, projectDefault, config.defaultModelSelection)
                val serverDefault =
                    resolved?.let { selection ->
                        options.firstOrNull { it.providerId == selection.instanceId && it.modelId == selection.model }
                            ?: options.firstOrNull { it.providerId == selection.instanceId }
                    }
                return ModelSelectionCatalog(
                    options = options,
                    serverDefault = serverDefault,
                    providerTraits =
                        config.harnesses.associate { harness ->
                            harness.instanceId to
                                ModelProviderTraits(
                                    supportedRuntimeModes = harness.supportedRuntimeModes?.let { harness.runtimeModes },
                                    requiresNewSessionForModelChange = harness.requiresNewThreadForModelChange,
                                    slashSkills = T3SlashSkills.visible(harness.skillsFor(cwd)),
                                )
                        },
                )
            }

            fun option(
                harness: T3Harness,
                model: T3HarnessModel,
            ): ModelSelectionOption =
                ModelSelectionOption(
                    providerId = harness.instanceId,
                    providerName = harness.displayName,
                    modelId = model.slug,
                    modelName = model.label,
                    modelNameIsDisplayReady = true,
                )

            /** The project rooted at [directory] or owning a thread worktree there; else none. */
            fun projectFor(
                shell: T3ShellSnapshot,
                directory: String?,
            ): T3Project? {
                if (directory.isNullOrBlank()) return shell.projects.firstOrNull()
                return shell.projects.firstOrNull { it.workspaceRoot == directory }
                    ?: shell.allThreads
                        .firstOrNull { it.worktreePath == directory }
                        ?.let { thread -> shell.projects.firstOrNull { it.id == thread.projectId } }
            }
        }
    }
