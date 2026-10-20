package dev.opencode.mobile.data.local

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.opencode.mobile.domain.model.ModelSelectionConnectionIdentity
import dev.opencode.mobile.domain.model.SessionModelSelection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

class DataStoreSessionModelSelectionStoreTest {
    @Test
    fun preferenceIsUsernameScopedAndSurvivesSessionDeletion() =
        runTest {
            val store = store(backgroundScope)
            val alice = identity("alice")
            val bob = identity("bob")
            val selection = selection("gpt-4", timestamp = 2_000)

            store.saveSession(alice, "/repo", "ses-1", selection)
            store.saveSession(alice, "/other", "ses-1", selection)
            store.saveNewSessionPreference(alice, "/repo", selection)
            store.deleteSessionEntries(alice, "ses-1")

            assertNull(store.loadSession(alice, "/repo", "ses-1"))
            assertNull(store.loadSession(alice, "/other", "ses-1"))
            assertEquals("gpt-4", store.loadNewSessionPreference(alice, "/repo")?.modelId)
            assertNull(store.loadNewSessionPreference(bob, "/repo"))
            assertNull(store.loadNewSessionPreference(alice, "/other"))
        }

    @Test
    fun saveAndLoad_roundTripsAgentId() =
        runTest {
            val store = store(backgroundScope)
            val identity = identity("opencode")
            store.saveSession(
                identity,
                "/repo",
                "ses-1",
                SessionModelSelection("openai", "gpt-4", agentId = "plan", savedAtEpochMs = 2_000),
            )

            assertEquals("plan", store.loadSession(identity, "/repo", "ses-1")?.agentId)
        }

    @Test
    fun olderWriteCannotReplaceNewerSelection() =
        runTest {
            val store = store(backgroundScope)
            val identity = identity("opencode")

            store.saveSession(identity, "/repo", "ses-1", selection("new", timestamp = 2_000))
            store.saveSession(identity, "/repo", "ses-1", selection("old", timestamp = 1_000))

            assertEquals("new", store.loadSession(identity, "/repo", "ses-1")?.modelId)
        }

    @Test
    fun legacyEntryLoadsAndMigratesWhenSaved() =
        runTest {
            val dataStore = dataStore(backgroundScope)
            val store = DataStoreSessionModelSelectionStore(dataStore, Json { ignoreUnknownKeys = true })
            val legacyKey = stringPreferencesKey("http://127.0.0.1:4096/|/repo|ses-1")
            dataStore.edit {
                it[legacyKey] = """{"providerId":"openai","modelId":"legacy"}"""
            }

            assertEquals("legacy", store.loadSession(identity("opencode"), "/repo", "ses-1")?.modelId)
            store.saveSession(identity("opencode"), "/repo", "ses-1", selection("migrated", timestamp = 2_000))

            assertEquals("migrated", store.loadSession(identity("opencode"), "/repo", "ses-1")?.modelId)
            assertNull(dataStore.data.first()[legacyKey])
        }

    @Test
    fun retentionEvictsOldestAndClearAllRemovesBothScopes() =
        runTest {
            val store = store(backgroundScope)
            val identity = identity("opencode")
            repeat(501) { index ->
                store.saveSession(identity, "/repo", "ses-$index", selection("model-$index", timestamp = index.toLong()))
            }
            store.saveNewSessionPreference(identity, "/repo", selection("preferred", timestamp = 1_000))

            assertNull(store.loadSession(identity, "/repo", "ses-0"))
            assertNotNull(store.loadSession(identity, "/repo", "ses-500"))
            store.clearAll()
            assertNull(store.loadSession(identity, "/repo", "ses-500"))
            assertNull(store.loadNewSessionPreference(identity, "/repo"))
        }

    @Test
    fun clearConnection_removesOnlyThatConnection() =
        runTest {
            val store = store(backgroundScope)
            val alice = identity("alice")
            val bob = identity("bob")
            val selection = selection("gpt-4", timestamp = 2_000)
            store.saveSession(alice, "/repo", "ses-1", selection)
            store.saveNewSessionPreference(alice, "/repo", selection)
            store.saveSession(bob, "/repo", "ses-2", selection)

            store.clearConnection(alice)

            assertNull(store.loadSession(alice, "/repo", "ses-1"))
            assertNull(store.loadNewSessionPreference(alice, "/repo"))
            assertEquals("gpt-4", store.loadSession(bob, "/repo", "ses-2")?.modelId)
        }

    @Test
    fun clearConnection_keepsLegacyEntriesOfAServerUnderAPathOfTheSameHost() =
        runTest {
            val dataStore = dataStore(backgroundScope)
            val store = DataStoreSessionModelSelectionStore(dataStore, Json { ignoreUnknownKeys = true })
            val removedKey = stringPreferencesKey("http://127.0.0.1:4096/|/repo|ses-1")
            val keptKey = stringPreferencesKey("http://127.0.0.1:4096/opencode/|/repo|ses-2")
            dataStore.edit {
                it[removedKey] = """{"providerId":"openai","modelId":"removed"}"""
                it[keptKey] = """{"providerId":"openai","modelId":"kept"}"""
            }

            store.clearConnection(identity("opencode"))

            val prefs = dataStore.data.first()
            assertNull(prefs[removedKey])
            assertNotNull(prefs[keptKey])
        }

    private fun store(scope: CoroutineScope) = DataStoreSessionModelSelectionStore(dataStore(scope), Json { ignoreUnknownKeys = true })

    private fun dataStore(scope: CoroutineScope) =
        PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { Files.createTempDirectory("model-selection-store").resolve("preferences.preferences_pb").toFile() },
        )

    private fun identity(username: String) = ModelSelectionConnectionIdentity("http://127.0.0.1:4096", username)

    private fun selection(
        modelId: String,
        timestamp: Long,
    ) = SessionModelSelection("openai", modelId, savedAtEpochMs = timestamp)
}
