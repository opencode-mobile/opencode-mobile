package dev.opencode.mobile.data.local

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.opencode.mobile.domain.composer.ComposerDraft
import dev.opencode.mobile.domain.model.ModelSelectionConnectionIdentity
import dev.opencode.mobile.domain.model.SessionModelSelection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileKeyedSelectionStoresTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val lan = "http://192.0.2.1:4096/"
    private val tailnet = "http://100.64.0.3:4096/"
    private val byLan = ModelSelectionConnectionIdentity(lan, "opencode")
    private val byTailnet = ModelSelectionConnectionIdentity(tailnet, "opencode")
    private val profile = ModelSelectionConnectionIdentity(lan, "opencode", profileId = "profile-a")
    private val selection = SessionModelSelection(providerId = "openai", modelId = "gpt-4", savedAtEpochMs = 2_000)

    @Test
    fun draftMigration_keepsEntriesFromEveryAddress_andIsIdempotent() =
        runTest {
            val store = DataStoreComposerDraftStore(dataStore(backgroundScope), json)
            store.save(byLan, "/repo", "ses-1", ComposerDraft(text = "from lan", savedAtEpochMs = 1_000))
            store.save(byTailnet, "/repo", "ses-2", ComposerDraft(text = "from tailnet", savedAtEpochMs = 1_000))

            repeat(2) { store.migrateConnection(profile, setOf(lan, tailnet)) }

            assertEquals("from lan", store.load(profile, "/repo", "ses-1")?.text)
            assertEquals("from tailnet", store.load(profile.copy(serverBaseUrl = tailnet), "/repo", "ses-2")?.text)
            assertNull(store.load(byLan, "/repo", "ses-1"))
        }

    @Test
    fun draftMigration_keepsTheNewerOfTwoCopies() =
        runTest {
            val store = DataStoreComposerDraftStore(dataStore(backgroundScope), json)
            store.save(profile, "/repo", "ses-1", ComposerDraft(text = "newer", savedAtEpochMs = 5_000))
            store.save(byLan, "/repo", "ses-1", ComposerDraft(text = "older", savedAtEpochMs = 1_000))

            store.migrateConnection(profile, setOf(lan))

            assertEquals("newer", store.load(profile, "/repo", "ses-1")?.text)
        }

    @Test
    fun modelMigration_movesSessionsPreferencesAndPlainEntries() =
        runTest {
            val data = dataStore(backgroundScope)
            val store = DataStoreSessionModelSelectionStore(data, json)
            store.saveSession(byTailnet, "/repo", "ses-1", selection)
            store.saveNewSessionPreference(byLan, "/repo", selection)
            data.edit {
                it[stringPreferencesKey("$lan|/repo|ses-plain")] =
                    """{"providerId":"openai","modelId":"plain","savedAtEpochMs":1000}"""
            }

            repeat(2) { store.migrateConnection(profile, setOf(lan, tailnet), exclusiveAddresses = setOf(lan, tailnet)) }

            assertEquals("gpt-4", store.loadSession(profile, "/repo", "ses-1")?.modelId)
            assertEquals("gpt-4", store.loadNewSessionPreference(profile, "/repo")?.modelId)
            assertEquals("plain", store.loadSession(profile.copy(serverBaseUrl = tailnet), "/repo", "ses-plain")?.modelId)
        }

    @Test
    fun modelMigration_leavesAPlainEntryOnASharedAddress() =
        runTest {
            val data = dataStore(backgroundScope)
            val store = DataStoreSessionModelSelectionStore(data, json)
            data.edit {
                it[stringPreferencesKey("$lan|/repo|ses-plain")] =
                    """{"providerId":"openai","modelId":"plain","savedAtEpochMs":1000}"""
            }

            store.migrateConnection(profile, setOf(lan), exclusiveAddresses = emptySet())

            assertEquals("plain", store.loadSession(byLan.copy(username = "other"), "/repo", "ses-plain")?.modelId)
        }

    @Test
    fun clearConnection_removesProfileAndUnmigratedEntries() =
        runTest {
            val store = DataStoreComposerDraftStore(dataStore(backgroundScope), json)
            store.save(profile, "/repo", "ses-1", ComposerDraft(text = "migrated", savedAtEpochMs = 1_000))
            store.save(byLan, "/repo", "ses-2", ComposerDraft(text = "unmigrated", savedAtEpochMs = 1_000))

            store.clearConnection(profile)

            assertNull(store.load(profile, "/repo", "ses-1"))
            assertNull(store.load(byLan, "/repo", "ses-2"))
        }

    @Test
    fun delete_dropsAQueuedDetachedSave() =
        runTest {
            val paused = StandardTestDispatcher()
            val store = DataStoreComposerDraftStore(dataStore(backgroundScope), json, CoroutineScope(SupervisorJob() + paused))
            store.saveDetached(byLan, "/repo", "ses-1", ComposerDraft(text = "queued", savedAtEpochMs = 1_000))

            store.delete(byLan, "/repo", "ses-1")
            assertNull(store.load(byLan, "/repo", "ses-1"))

            runQueued(paused)
            assertNull(store.load(byLan, "/repo", "ses-1"))
        }

    @Test
    fun deleteSessionEntries_dropsAQueuedDetachedSave() =
        runTest {
            val paused = StandardTestDispatcher()
            val store = DataStoreComposerDraftStore(dataStore(backgroundScope), json, CoroutineScope(SupervisorJob() + paused))
            store.saveDetached(byLan, "/repo", "ses-1", ComposerDraft(text = "queued", savedAtEpochMs = 1_000))

            store.deleteSessionEntries(byLan, "ses-1")
            assertNull(store.load(byLan, "/repo", "ses-1"))

            runQueued(paused)
            assertNull(store.load(byLan, "/repo", "ses-1"))
        }

    @Test
    fun save_replacesAQueuedDetachedSave() =
        runTest {
            val paused = StandardTestDispatcher()
            val store = DataStoreComposerDraftStore(dataStore(backgroundScope), json, CoroutineScope(SupervisorJob() + paused))
            store.saveDetached(byLan, "/repo", "ses-1", ComposerDraft(text = "queued", savedAtEpochMs = 5_000))

            store.save(byLan, "/repo", "ses-1", ComposerDraft(text = "typed later", savedAtEpochMs = 1_000))
            assertEquals("typed later", store.load(byLan, "/repo", "ses-1")?.text)

            runQueued(paused)
            assertEquals("typed later", store.load(byLan, "/repo", "ses-1")?.text)
        }

    /** Lets the held detached write run, alternating with the DataStore on the test scheduler. */
    private fun TestScope.runQueued(paused: TestDispatcher) {
        repeat(3) {
            paused.scheduler.advanceUntilIdle()
            testScheduler.advanceUntilIdle()
        }
    }

    private fun dataStore(scope: CoroutineScope) =
        PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { Files.createTempDirectory("selection").resolve("preferences.preferences_pb").toFile() },
        )
}
