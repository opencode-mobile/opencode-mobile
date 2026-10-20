package dev.opencode.mobile.data.local

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.opencode.mobile.domain.agent.AgentMention
import dev.opencode.mobile.domain.agent.FileMention
import dev.opencode.mobile.domain.composer.ComposerDraft
import dev.opencode.mobile.domain.model.ModelSelectionConnectionIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class DataStoreComposerDraftStoreTest {
    @Test
    fun saveAndLoad_isIsolatedByConnectionWorktreeAndSession() =
        runTest {
            val store = store(backgroundScope)
            val alice = identity("alice")
            val bob = identity("bob")
            val mention = AgentMention(id = "m1", name = "explore", start = 0, end = 8)

            store.save(alice, "/repo", "ses-1", draft("hello @explore", listOf(mention), timestamp = 2_000))
            store.save(alice, "/repo", "ses-2", draft("other session", timestamp = 2_000))
            store.save(alice, "/other", "ses-1", draft("other worktree", timestamp = 2_000))
            store.save(bob, "/repo", "ses-1", draft("other user", timestamp = 2_000))

            val loaded = store.load(alice, "/repo", "ses-1")
            assertEquals("hello @explore", loaded?.text)
            assertEquals(listOf(mention), loaded?.agentMentions)
            assertEquals("other session", store.load(alice, "/repo", "ses-2")?.text)
            assertEquals("other worktree", store.load(alice, "/other", "ses-1")?.text)
            assertEquals("other user", store.load(bob, "/repo", "ses-1")?.text)
        }

    @Test
    fun deleteAndDeleteSessionEntries_removeMatchingDrafts() =
        runTest {
            val store = store(backgroundScope)
            val alice = identity("alice")
            store.save(alice, "/repo", "ses-1", draft("one", timestamp = 1_000))
            store.save(alice, "/other", "ses-1", draft("two", timestamp = 1_000))
            store.save(alice, "/repo", "ses-2", draft("keep", timestamp = 1_000))

            store.delete(alice, "/repo", "ses-2")
            assertNull(store.load(alice, "/repo", "ses-2"))

            store.deleteSessionEntries(alice, "ses-1")
            assertNull(store.load(alice, "/repo", "ses-1"))
            assertNull(store.load(alice, "/other", "ses-1"))
        }

    @Test
    fun olderWriteCannotReplaceNewerDraft() =
        runTest {
            val store = store(backgroundScope)
            val identity = identity("opencode")

            store.save(identity, "/repo", "ses-1", draft("new", timestamp = 2_000))
            store.save(identity, "/repo", "ses-1", draft("old", timestamp = 1_000))

            assertEquals("new", store.load(identity, "/repo", "ses-1")?.text)
        }

    @Test
    fun retentionEvictsOldestAndClearAllRemovesDrafts() =
        runTest {
            val store = store(backgroundScope)
            val identity = identity("opencode")
            repeat(501) { index ->
                store.save(identity, "/repo", "ses-$index", draft("draft-$index", timestamp = index.toLong()))
            }

            assertNull(store.load(identity, "/repo", "ses-0"))
            assertNotNull(store.load(identity, "/repo", "ses-500"))
            store.clearAll()
            assertNull(store.load(identity, "/repo", "ses-500"))
        }

    @Test
    fun trailingSlashAndBlankUsernameNormalizeToSameKey() =
        runTest {
            val store = store(backgroundScope)
            store.save(
                ModelSelectionConnectionIdentity("http://127.0.0.1:4096", ""),
                "/repo",
                "ses-1",
                draft("normalized", timestamp = 1_000),
            )

            val loaded =
                store.load(
                    ModelSelectionConnectionIdentity("http://127.0.0.1:4096/", "opencode"),
                    "/repo",
                    "ses-1",
                )
            assertEquals("normalized", loaded?.text)
            assertTrue(loaded?.savedAtEpochMs != null)
        }

    @Test
    fun saveAndLoad_roundTripsFileMentions() =
        runTest {
            val store = store(backgroundScope)
            val file = FileMention(id = "f1", path = "src/App.kt", start = 4, end = 15)
            store.save(
                identity("alice"),
                "/repo",
                "ses-1",
                ComposerDraft(text = "see @src/App.kt", fileMentions = listOf(file), savedAtEpochMs = 2_000),
            )
            assertEquals(listOf(file), store.load(identity("alice"), "/repo", "ses-1")?.fileMentions)
        }

    @Test
    fun clearConnection_removesOnlyThatConnection() =
        runTest {
            val store = store(backgroundScope)
            val alice = identity("alice")
            val bob = identity("bob")
            store.save(alice, "/repo", "ses-1", draft("a", timestamp = 2_000))
            store.save(bob, "/repo", "ses-2", draft("b", timestamp = 2_000))

            store.clearConnection(alice)

            assertNull(store.load(alice, "/repo", "ses-1"))
            assertEquals("b", store.load(bob, "/repo", "ses-2")?.text)
        }

    private fun store(scope: CoroutineScope) = DataStoreComposerDraftStore(dataStore(scope), Json { ignoreUnknownKeys = true })

    private fun dataStore(scope: CoroutineScope) =
        PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { Files.createTempDirectory("composer-draft-store").resolve("preferences.preferences_pb").toFile() },
        )

    private fun identity(username: String) = ModelSelectionConnectionIdentity("http://127.0.0.1:4096", username)

    private fun draft(
        text: String,
        mentions: List<AgentMention> = emptyList(),
        timestamp: Long,
    ) = ComposerDraft(text = text, agentMentions = mentions, savedAtEpochMs = timestamp)
}
