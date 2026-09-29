package dev.opencode.mobile.network

import dev.opencode.mobile.domain.connection.ConnectionProfile
import dev.opencode.mobile.domain.connection.ConnectionProfileStore
import io.ktor.client.HttpClient
import kotlinx.coroutines.Job
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class OpencodeSseHttpClientTest {
    @Test
    fun concurrentDialsOnSameGenerationSurviveRetireOfThatGeneration() {
        val json = Json { ignoreUnknownKeys = true }
        val sse = OpencodeSseHttpClient(json, ActiveConnectionManager(FakeProfileStore()))
        val first = sse.openDial(2)
        val second = sse.openDial(2)
        try {
            assertNotSame(first, second)
            assertTrue(first.isOpen())
            assertTrue(second.isOpen())

            sse.retire(olderThan = 2)
            assertTrue(first.isOpen())
            assertTrue(second.isOpen())

            sse.retire(olderThan = 3)
            assertFalse(first.isOpen())
            assertFalse(second.isOpen())

            val newer = sse.openDial(3)
            try {
                assertTrue(newer.isOpen())
                sse.retire(olderThan = 2)
                assertTrue(newer.isOpen())
            } finally {
                sse.closeDial(newer)
            }
        } finally {
            sse.closeDial(first)
            sse.closeDial(second)
        }
    }

    @Test
    fun closeDialDoesNotCloseSiblingOnSameGeneration() {
        val json = Json { ignoreUnknownKeys = true }
        val sse = OpencodeSseHttpClient(json, ActiveConnectionManager(FakeProfileStore()))
        val first = sse.openDial(2)
        val second = sse.openDial(2)
        try {
            sse.closeDial(first)
            assertFalse(first.isOpen())
            assertTrue(second.isOpen())
        } finally {
            sse.closeDial(second)
        }
    }

    private fun HttpClient.isOpen(): Boolean = coroutineContext[Job]?.isActive == true

    private class FakeProfileStore : ConnectionProfileStore {
        override suspend fun activeProfile(): ConnectionProfile? = null

        override suspend fun saveProfile(profile: ConnectionProfile) = Unit

        override suspend fun addedWorktrees(serverBaseUrl: String): List<String> = emptyList()

        override suspend fun addWorktree(
            serverBaseUrl: String,
            worktree: String,
        ) = Unit

        override suspend fun removeWorktree(
            serverBaseUrl: String,
            worktree: String,
        ) = Unit

        override suspend fun setAddedWorktrees(
            serverBaseUrl: String,
            worktrees: List<String>,
        ) = Unit

        override suspend fun clearAll() = Unit
    }
}
