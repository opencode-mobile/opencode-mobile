package dev.opencode.mobile.domain.session

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SideQuestionStoreTest {
    private class Server {
        val requests = mutableListOf<Pair<String, String>>()
        val replies = mutableListOf<CompletableDeferred<String>>()

        suspend fun ask(
            sessionId: String,
            question: String,
        ): String {
            requests += sessionId to question
            val reply = CompletableDeferred<String>()
            replies += reply
            return reply.await()
        }
    }

    /** Runs on the test scheduler, so `advanceUntilIdle` drives the store's requests. */
    private fun TestScope.storeScope(): CoroutineScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))

    private fun store(
        server: Server,
        scope: CoroutineScope,
    ): SideQuestionStore {
        var next = 0
        return SideQuestionStore(
            request = server::ask,
            scope = scope,
            newId = { "q${++next}" },
        )
    }

    @Test
    fun askRunsOneRequestAndAnswersIntoTheSession() =
        runTest(StandardTestDispatcher()) {
            val server = Server()
            val scope = storeScope()
            val store = store(server, scope)
            val events = mutableListOf<SideQuestionEvent>()
            scope.launch { store.events.toList(events) }

            val id = store.ask("ses-1", "  What changed? ")
            advanceUntilIdle()

            assertEquals(listOf("ses-1" to "What changed?"), server.requests)
            assertEquals(SideQuestionEntry.Status.Pending, store.entry(id!!)?.status)

            server.replies.single().complete("Two files.")
            advanceUntilIdle()

            assertEquals("Two files.", store.entries("ses-1").single().answer)
            assertTrue(store.entries("ses-2").isEmpty())
            assertEquals(listOf<SideQuestionEvent>(SideQuestionEvent.Answered("ses-1", id)), events)
            scope.cancel()
        }

    @Test
    fun blankQuestionIsNotAsked() =
        runTest(StandardTestDispatcher()) {
            val server = Server()
            val scope = storeScope()
            val store = store(server, scope)

            assertNull(store.ask("ses-1", "  \n"))
            advanceUntilIdle()

            assertTrue(server.requests.isEmpty())
            assertTrue(store.entries("ses-1").isEmpty())
            scope.cancel()
        }

    @Test
    fun failureStaysRetryableAndRetryAsksAgain() =
        runTest(StandardTestDispatcher()) {
            val server = Server()
            val scope = storeScope()
            val store = store(server, scope)
            val events = mutableListOf<SideQuestionEvent>()
            scope.launch { store.events.toList(events) }

            val id = store.ask("ses-1", "Why?")!!
            advanceUntilIdle()
            server.replies.single().completeExceptionally(SessionError.Http(503, "busy"))
            advanceUntilIdle()

            assertEquals(SideQuestionEntry.Status.Failed, store.entry(id)?.status)
            assertEquals(listOf<SideQuestionEvent>(SideQuestionEvent.Failed("ses-1", id, "Why?", retryable = true)), events)

            store.retry(id)
            advanceUntilIdle()
            assertEquals(SideQuestionEntry.Status.Pending, store.entry(id)?.status)
            server.replies.last().complete("Because.")
            advanceUntilIdle()

            assertEquals(2, server.requests.size)
            assertEquals("Because.", store.entry(id)?.answer)
            scope.cancel()
        }

    @Test
    fun notFoundIsNotRetryableAndDropsTheEntry() =
        runTest(StandardTestDispatcher()) {
            val server = Server()
            val scope = storeScope()
            val store = store(server, scope)
            val events = mutableListOf<SideQuestionEvent>()
            scope.launch { store.events.toList(events) }

            val id = store.ask("ses-1", "Why?")!!
            advanceUntilIdle()
            server.replies.single().completeExceptionally(SessionError.Http(404, "Not Found"))
            advanceUntilIdle()

            assertNull(store.entry(id))
            assertEquals(listOf<SideQuestionEvent>(SideQuestionEvent.Failed("ses-1", id, "Why?", retryable = false)), events)
            scope.cancel()
        }

    @Test
    fun removeAndClearDropRunningRequests() =
        runTest(StandardTestDispatcher()) {
            val server = Server()
            val scope = storeScope()
            val store = store(server, scope)
            val events = mutableListOf<SideQuestionEvent>()
            scope.launch { store.events.toList(events) }

            val removed = store.ask("ses-1", "One")!!
            store.ask("ses-2", "Two")
            advanceUntilIdle()

            store.remove(removed)
            assertNull(store.entry(removed))
            assertEquals(1, store.entries("ses-2").size)

            store.clear()
            server.replies.forEach { it.complete("late") }
            advanceUntilIdle()

            assertTrue(store.entries("ses-1").isEmpty())
            assertTrue(store.entries("ses-2").isEmpty())
            assertTrue(events.isEmpty())
            scope.cancel()
        }
}
