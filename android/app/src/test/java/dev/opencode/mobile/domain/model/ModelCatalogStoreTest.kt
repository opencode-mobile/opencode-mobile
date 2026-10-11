package dev.opencode.mobile.domain.model

import dev.opencode.mobile.domain.agent.AgentRepository
import dev.opencode.mobile.domain.agent.MentionableAgent
import dev.opencode.mobile.domain.agent.SelectableAgent
import dev.opencode.mobile.domain.connection.ConnectionProfile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogStoreTest {
    private val sonnet = ModelSelectionOption("anthropic", "Anthropic", "claude-sonnet", "Claude Sonnet")
    private val gpt4 = ModelSelectionOption("openai", "OpenAI", "gpt-4", "GPT-4")
    private val catalog = ModelSelectionCatalog(listOf(sonnet, gpt4), sonnet)
    private val empty = ModelSelectionCatalog(emptyList(), null)
    private var profile: ConnectionProfile? = ConnectionProfile(baseUrl = "http://127.0.0.1:4096/", username = "opencode", id = "a")

    @Test
    fun concurrentRefreshes_shareOneFetch() =
        runTest {
            val models = GatedModelRepository()
            val store = store(models)

            val first = async { store.refreshCatalog("/repo") }
            val second = async { store.refreshCatalog("/repo") }
            runCurrent()
            models.answers.complete(catalog)
            advanceUntilIdle()

            assertEquals(1, models.calls)
            assertEquals(catalog, first.await())
            assertEquals(catalog, second.await())
            assertEquals(catalog, store.peek("/repo")?.catalog)
        }

    @Test
    fun forcedRefresh_doesNotJoinAnUnforcedFetchButJoinsAnotherForcedOne() =
        runTest {
            val models = GatedModelRepository()
            val store = store(models)

            async { store.refreshCatalog("/repo") }
            runCurrent()
            async { store.refreshCatalog("/repo", force = true) }
            async { store.refreshCatalog("/repo", force = true) }
            runCurrent()
            models.answers.complete(catalog)
            advanceUntilIdle()

            assertEquals(2, models.calls)
        }

    @Test
    fun failedOrEmptyRefresh_keepsTheLastNonEmptyCatalog() =
        runTest {
            val models =
                QueuedModelRepository(
                    mutableListOf(
                        Result.success(catalog),
                        Result.failure(IllegalStateException("offline")),
                        Result.success(empty),
                    ),
                )
            val store = store(models)

            store.refreshCatalog("/repo")
            val failure = runCatching { store.refreshCatalog("/repo") }
            val emptyAnswer = store.refreshCatalog("/repo")

            assertTrue(failure.isFailure)
            assertEquals(empty, emptyAnswer)
            assertEquals(catalog, store.peek("/repo")?.catalog)
        }

    @Test
    fun emptyFirstAnswer_isNotCached() =
        runTest {
            val store = store(QueuedModelRepository(mutableListOf(Result.success(empty))))

            store.refreshCatalog("/repo")

            assertNull(store.peek("/repo")?.catalog)
        }

    @Test
    fun entriesAreKeyedByWorktreeAndProfile() =
        runTest {
            val store = store(QueuedModelRepository(mutableListOf(Result.success(catalog))))

            store.refreshCatalog("/repo")

            assertEquals(catalog, store.peek("/repo")?.catalog)
            assertNull(store.peek("/other"))
            profile = profile?.copy(id = "b")
            assertNull(store.peek("/repo"))
        }

    @Test
    fun clear_dropsCatalogsAndSelections() =
        runTest {
            val store = store(QueuedModelRepository(mutableListOf(Result.success(catalog))))
            store.refreshCatalog("/repo")
            store.refreshSelectableAgents("/repo")
            store.rememberSelection("/repo", "ses-1", SessionModelSelection("openai", "gpt-4"))
            store.rememberNewSessionPreference("/repo", SessionModelSelection("openai", "gpt-4"))

            store.clear()

            assertNull(store.peek("/repo"))
            assertNull(store.lastSelection("/repo", "ses-1"))
            assertNull(store.newSessionPreference("/repo"))
        }

    @Test
    fun resultLandingAfterProfileChange_isDiscarded() =
        runTest {
            val models = GatedModelRepository()
            val store = store(models)

            val pending = async { store.refreshCatalog("/repo") }
            runCurrent()
            store.clear()
            models.answers.complete(catalog)
            advanceUntilIdle()

            assertEquals(catalog, pending.await())
            assertNull(store.peek("/repo"))
        }

    @Test
    fun selectableAgents_areCachedWithTheCatalog() =
        runTest {
            val agents = listOf(SelectableAgent(id = "build", displayName = "Build"))
            val store = store(QueuedModelRepository(mutableListOf(Result.success(catalog))), agents)

            store.refreshCatalog("/repo")
            store.refreshSelectableAgents("/repo")

            assertEquals(catalog, store.peek("/repo")?.catalog)
            assertEquals(agents, store.peek("/repo")?.selectableAgents)
        }

    private fun TestScope.store(
        models: ModelRepository,
        agents: List<SelectableAgent> = SelectableAgent.Fallback,
    ): ModelCatalogStore =
        ModelCatalogStore(
            modelRepository = models,
            agentRepository = FixedAgentRepository(agents),
            currentProfile = { profile },
            scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
        )

    private class GatedModelRepository : ModelRepository {
        val answers = CompletableDeferred<ModelSelectionCatalog>()
        var calls = 0

        override suspend fun listModelOptions(directory: String?): ModelSelectionCatalog {
            calls += 1
            return answers.await()
        }
    }

    private class QueuedModelRepository(
        private val answers: MutableList<Result<ModelSelectionCatalog>>,
    ) : ModelRepository {
        override suspend fun listModelOptions(directory: String?): ModelSelectionCatalog = answers.removeAt(0).getOrThrow()
    }

    private class FixedAgentRepository(
        private val agents: List<SelectableAgent>,
    ) : AgentRepository {
        override suspend fun listSelectableAgents(directory: String?): List<SelectableAgent> = agents

        override suspend fun listMentionableAgents(directory: String?): List<MentionableAgent> = emptyList()
    }
}
