package dev.opencode.mobile.domain.t3

import dev.opencode.mobile.domain.model.ModelChangeLock
import dev.opencode.mobile.domain.model.ModelProviderTraits
import dev.opencode.mobile.domain.model.ModelSelectionOption
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Provider catalogue parsing, the new-thread model resolver chain, and the picker catalogue. */
class T3ModelSelectionTest {
    private fun harness(
        instanceId: String,
        enabled: Boolean = true,
        available: Boolean = true,
        status: String = "ready",
        models: List<T3HarnessModel> = listOf(T3HarnessModel(slug = "$instanceId-default", name = "Default", isDefault = true)),
        requiresNewThreadForModelChange: Boolean = false,
        supportedRuntimeModes: List<String>? = null,
    ): T3Harness =
        T3Harness(
            instanceId = instanceId,
            displayName = T3ProviderLabels.displayName(instanceId)!!,
            driver = instanceId.substringBefore('-'),
            enabled = enabled,
            available = available,
            status = status,
            supportedRuntimeModes = supportedRuntimeModes,
            requiresNewThreadForModelChange = requiresNewThreadForModelChange,
            models = models,
        )

    // region resolver

    @Test
    fun resolve_prefersUsableProjectOverride() {
        val resolved =
            T3ModelSelectionResolver.resolve(
                harnesses = listOf(harness("codex-1"), harness("claude-1")),
                projectDefault = T3ModelSelection("claude-1", "opus"),
                globalDefault = T3ModelSelection("codex-1", "gpt-5"),
            )

        assertEquals(T3ModelSelection("claude-1", "opus"), resolved)
    }

    @Test
    fun resolve_skipsUnusableProjectOverrideForGlobalDefault() {
        val resolved =
            T3ModelSelectionResolver.resolve(
                harnesses = listOf(harness("codex-1"), harness("claude-1", available = false)),
                projectDefault = T3ModelSelection("claude-1", "opus"),
                globalDefault = T3ModelSelection("codex-1", "gpt-5"),
            )

        assertEquals(T3ModelSelection("codex-1", "gpt-5"), resolved)
    }

    @Test
    fun resolve_withoutDefaults_takesFirstReadyHarnessAndItsDefaultModel() {
        val resolved =
            T3ModelSelectionResolver.resolve(
                harnesses =
                    listOf(
                        harness("codex-1", enabled = false),
                        harness("cursor-1", status = "warning"),
                        harness(
                            "claude-1",
                            models =
                                listOf(
                                    T3HarnessModel(slug = "custom", name = "Custom", isDefault = true, isCustom = true),
                                    T3HarnessModel(slug = "sonnet", name = "Sonnet"),
                                    T3HarnessModel(slug = "opus", name = "Opus", isDefault = true),
                                ),
                        ),
                    ),
                projectDefault = null,
                globalDefault = null,
            )

        assertEquals(T3ModelSelection("claude-1", "opus"), resolved)
    }

    @Test
    fun resolve_fallsBackToFirstBuiltInModelWhenNoDefaultIsDeclared() {
        val resolved =
            T3ModelSelectionResolver.resolve(
                harnesses =
                    listOf(
                        harness(
                            "codex-1",
                            models =
                                listOf(
                                    T3HarnessModel(slug = "mine", name = "Mine", isCustom = true),
                                    T3HarnessModel(slug = "gpt-5", name = "GPT-5"),
                                ),
                        ),
                    ),
                projectDefault = null,
                globalDefault = null,
            )

        assertEquals(T3ModelSelection("codex-1", "gpt-5"), resolved)
    }

    @Test
    fun resolve_withoutUsableHarness_returnsNull() {
        val resolved =
            T3ModelSelectionResolver.resolve(
                harnesses = listOf(harness("codex-1", available = false), harness("claude-1", enabled = false)),
                projectDefault = T3ModelSelection("codex-1", "gpt-5"),
                globalDefault = null,
            )

        assertNull(resolved)
    }

    @Test
    fun resolveExplicit_keepsUsableChoiceAndReplacesAStaleModel() {
        val harnesses = listOf(harness("codex-1"), harness("claude-1"))

        assertEquals(
            T3ModelSelection("claude-1", "claude-1-default"),
            T3ModelSelectionResolver.resolveExplicit(harnesses, T3ModelSelection("claude-1", "claude-1-default"), null, null),
        )
        assertEquals(
            T3ModelSelection("claude-1", "claude-1-default"),
            T3ModelSelectionResolver.resolveExplicit(harnesses, T3ModelSelection("claude-1", "retired"), null, null),
        )
        assertEquals(
            T3ModelSelection("codex-1", "codex-1-default"),
            T3ModelSelectionResolver.resolveExplicit(harnesses, T3ModelSelection("gone-1", "x"), null, null),
        )
    }

    // endregion

    // region parsing

    @Test
    fun serverConfig_parsesHarnessesModelsAndGlobalDefault() {
        val config =
            T3Json.serverConfig(
                serverConfigJson(
                    providers =
                        listOf(
                            harnessJson(
                                "claude-1",
                                driver = "claudeAgent",
                                supportedRuntimeModes = listOf("approval-required", "full-access", "future-mode"),
                                requiresNewThreadForModelChange = true,
                                models =
                                    listOf(
                                        harnessModelJson("claude-opus-4", name = "Claude Opus 4", shortName = "Opus", isDefault = true),
                                        harnessModelJson("claude-sonnet-4", name = "Claude Sonnet 4"),
                                    ),
                            ),
                            harnessJson("codex-1", displayName = "Work Codex", authStatus = "unauthenticated"),
                            harnessJson("cursor-1", installed = false),
                            harnessJson("gemini-1", status = "error"),
                        ),
                    defaultInstanceId = "claude-1",
                    defaultModel = "claude-sonnet-4",
                ),
            )

        val claude = config.harness("claude-1")!!
        assertEquals("Claude Agent", claude.displayName)
        assertEquals("claudeAgent", claude.driver)
        assertTrue(claude.isUsable)
        assertTrue(claude.requiresNewThreadForModelChange)
        assertEquals(listOf("approval-required", "full-access"), claude.supportedRuntimeModes)
        assertEquals(listOf("approval-required", "full-access"), claude.runtimeModes)
        assertEquals(listOf("Opus", "Claude Sonnet 4"), claude.models.map { it.label })
        assertEquals("claude-opus-4", claude.defaultModel?.slug)

        val codex = config.harness("codex-1")!!
        assertEquals("Work Codex", codex.displayName)
        assertFalse(codex.available)
        assertNull(codex.supportedRuntimeModes)
        assertEquals(T3RuntimeModeLabels.allModes, codex.runtimeModes)
        assertFalse(config.harness("cursor-1")!!.available)
        assertFalse(config.harness("gemini-1")!!.available)

        assertEquals(T3ModelSelection("claude-1", "claude-sonnet-4"), config.defaultModelSelection)
    }

    @Test
    fun serverConfig_nullGlobalDefaultAndMissingInstanceIdsAreTolerated() {
        val config =
            T3Json.serverConfig(
                serverConfigJson(
                    providers = listOf(harnessJson("codex-1"), JsonObject(harnessJson("") - "instanceId")),
                ),
            )

        assertEquals(listOf("codex-1"), config.harnesses.map { it.instanceId })
        assertNull(config.defaultModelSelection)
    }

    // endregion

    // region picker catalogue

    @Test
    fun catalog_listsUsableHarnessModelsWithResolvedDefaultAndTraits() {
        val config =
            T3ServerConfig(
                harnesses =
                    listOf(
                        harness("codex-1", available = false),
                        harness(
                            "claude-1",
                            models =
                                listOf(
                                    T3HarnessModel(slug = "claude-opus-4", name = "Claude Opus 4", shortName = "Opus"),
                                    T3HarnessModel(slug = "claude-sonnet-4", name = "Claude Sonnet 4", isDefault = true),
                                ),
                            requiresNewThreadForModelChange = true,
                            supportedRuntimeModes = listOf("approval-required"),
                        ),
                    ),
                defaultModelSelection = T3ModelSelection("codex-1", "gpt-5"),
            )

        val catalog = T3ModelRepository.catalog(config, project = null)

        assertEquals(listOf("claude-opus-4", "claude-sonnet-4"), catalog.options.map { it.modelId })
        assertEquals(listOf("Opus", "Claude Sonnet 4"), catalog.options.map { it.modelLabel })
        assertEquals("Claude", catalog.providers.single().label)
        assertEquals("claude-sonnet-4", catalog.serverDefault?.modelId)
        assertEquals(
            ModelProviderTraits(supportedRuntimeModes = listOf("approval-required"), requiresNewSessionForModelChange = true),
            catalog.providerTraits["claude-1"],
        )
    }

    @Test
    fun catalog_projectOverrideBeatsGlobalDefault() {
        val config =
            T3ServerConfig(
                harnesses = listOf(harness("codex-1"), harness("claude-1")),
                defaultModelSelection = T3ModelSelection("codex-1", "codex-1-default"),
            )
        val project =
            T3Project(
                id = "proj-a",
                title = "A",
                workspaceRoot = "/repos/a",
                defaultInstanceId = "claude-1",
                defaultModel = "claude-1-default",
            )

        assertEquals("claude-1", T3ModelRepository.catalog(config, project).serverDefault?.providerId)
    }

    // endregion

    // region model change lock

    private fun option(
        providerId: String,
        modelId: String,
    ) = ModelSelectionOption(providerId = providerId, providerName = providerId, modelId = modelId, modelName = modelId)

    @Test
    fun modelChangeLock_isAbsentBeforeTheSessionStarts() {
        val traits = mapOf("claude-1" to ModelProviderTraits(requiresNewSessionForModelChange = true))

        assertNull(ModelChangeLock.of(hasStarted = false, providerId = "claude-1", modelId = "opus", traits = traits))
    }

    @Test
    fun modelChangeLock_requiresNewThreadBlocksOtherModelsOfTheProvider() {
        val traits = mapOf("claude-1" to ModelProviderTraits(requiresNewSessionForModelChange = true))
        val lock = ModelChangeLock.of(hasStarted = true, providerId = "claude-1", modelId = "opus", traits = traits)!!

        assertEquals(ModelChangeLock.CHANGE_MODEL_NOTICE, lock.notice)
        assertNull(lock.blockReason(option("claude-1", "opus")))
        assertEquals(ModelChangeLock.CHANGE_MODEL_NOTICE, lock.blockReason(option("claude-1", "sonnet")))
        assertEquals(ModelChangeLock.SWITCH_PROVIDER_NOTICE, lock.blockReason(option("codex-1", "gpt-5")))
    }

    @Test
    fun modelChangeLock_startedSessionMayChangeModelsWithinAFlexibleProvider() {
        val lock = ModelChangeLock.of(hasStarted = true, providerId = "codex-1", modelId = "gpt-5", traits = emptyMap())!!

        assertNull(lock.blockReason(option("codex-1", "gpt-5-mini")))
        assertEquals(ModelChangeLock.SWITCH_PROVIDER_NOTICE, lock.blockReason(option("claude-1", "opus")))
    }

    // endregion
}
