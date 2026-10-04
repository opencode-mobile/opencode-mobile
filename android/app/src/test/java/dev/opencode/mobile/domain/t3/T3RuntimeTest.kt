package dev.opencode.mobile.domain.t3

import dev.opencode.mobile.domain.message.MessageContentPreparer
import dev.opencode.mobile.domain.message.MessageListUpdate
import dev.opencode.mobile.markdown.OpenCodeSyntaxHighlighter
import dev.opencode.mobile.markdown.OpenCodeTextMateHighlightingEngine
import dev.opencode.mobile.network.ActiveConnectionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class T3RuntimeTest {
    /** Background-scope work only runs through runCurrent; cover one backoff/debounce window too. */
    private fun TestScope.settle() {
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
    }

    private class Harness(
        scope: TestScope,
        profile: FakeProfileStore = FakeProfileStore(t3Profile),
    ) {
        val store = profile
        val http = FakeT3Http()
        val socket = FakeT3Socket()
        val events = RecordingEventStream()
        lateinit var manager: ActiveConnectionManager
        lateinit var runtime: T3Runtime
        private val testScope = scope

        suspend fun build(): Harness {
            manager = activeManager(store)
            runtime =
                T3Runtime(
                    api = T3Api(http),
                    socket = socket,
                    activeConnectionManager = manager,
                    eventStream = events,
                    scope = testScope.backgroundScope,
                    shellRefreshDebounceMs = 10,
                )
            return this
        }
    }

    @Test
    fun reconnectResubscribesObservedThreadFromLastSequence() =
        runTest {
            val h = Harness(this).build()
            h.http.threadJson["thr-1"] = threadSnapshotJson(sequence = 5, "m1")

            h.runtime.loadThreadMessages("thr-1")
            settle()

            assertEquals(1, h.socket.connectCalls)
            val firstShell = h.socket.lastSubscribe(T3RpcCodec.SUBSCRIBE_SHELL)
            assertNull("no shell cached yet: ask for a snapshot", firstShell.afterSequence)
            val firstThread = h.socket.lastSubscribe(T3RpcCodec.SUBSCRIBE_THREAD)
            assertEquals("thr-1", firstThread.threadId)
            assertEquals(5L, firstThread.afterSequence)

            // A live event advances the resume cursor past the REST snapshot.
            h.socket.chunk(firstThread.id, threadEventItem(7, messageUpdatedEvent("m2", "reply")))
            settle()
            assertEquals(listOf("m1", "m2"), h.runtime.cachedMessages("thr-1").map { it.id })

            h.socket.drop()
            settle()
            h.runtime.refreshShell()
            settle()

            assertEquals(2, h.socket.connectCalls)
            assertEquals(2, h.socket.subscribes(T3RpcCodec.SUBSCRIBE_SHELL).size)
            val resubscribed = h.socket.subscribes(T3RpcCodec.SUBSCRIBE_THREAD)
            assertEquals(2, resubscribed.size)
            assertEquals("thr-1", resubscribed.last().threadId)
            assertEquals(7L, resubscribed.last().afterSequence)

            // Chunks on the old subscription id are ignored; the new id is live.
            h.socket.chunk(firstThread.id, threadEventItem(8, messageUpdatedEvent("stale", "old socket")))
            h.socket.chunk(resubscribed.last().id, threadEventItem(8, messageUpdatedEvent("m3", "new socket")))
            settle()
            assertEquals(listOf("m1", "m2", "m3"), h.runtime.cachedMessages("thr-1").map { it.id })
        }

    @Test
    fun shellStreamPublishesSyntheticHomeEvents() =
        runTest {
            val h = Harness(this).build()
            h.http.shellJson = shellJson(threads = listOf(threadJson("A")), sequence = 3)

            h.runtime.refreshShell()
            settle()
            val shellSub = h.socket.lastSubscribe(T3RpcCodec.SUBSCRIBE_SHELL)
            assertEquals(3L, shellSub.afterSequence)
            assertTrue("REST refresh publishes no lifecycle events", h.events.published.isEmpty())

            h.socket.chunk(shellSub.id, threadUpdatedItem(4, threadJson("B", activeRunId = "run-1")))
            settle()
            assertEquals(listOf("session.created:B", "session.status:B"), h.events.types())
            assertEquals("running", h.events.published.last().rawProperties?.get("type")?.toString()?.trim('"'))

            h.events.published.clear()
            h.socket.chunk(shellSub.id, threadUpdatedItem(5, threadJson("A", pendingRequestId = "req-9")))
            settle()
            assertEquals(listOf("session.updated:A", "permission.asked:A"), h.events.types())

            h.events.published.clear()
            h.socket.chunk(shellSub.id, threadUpdatedItem(2, threadJson("A", title = "stale")))
            settle()
            assertTrue("stale sequence is ignored", h.events.published.isEmpty())
            assertEquals("Thread A", h.runtime.shell.value!!.threads.first { it.id == "A" }.title)

            h.socket.chunk(shellSub.id, threadRemovedItem(6, "B"))
            settle()
            assertEquals(listOf("session.deleted:B", "session.status:B"), h.events.types())
            assertEquals(listOf("A"), h.runtime.shell.value!!.threads.map { it.id })
            assertEquals(6L, h.runtime.shell.value!!.snapshotSequence)
        }

    @Test
    fun stopOnProfileSwitchDisconnectsAndClearsCaches() =
        runTest {
            val h = Harness(this).build()
            h.http.shellJson = shellJson(threads = listOf(threadJson("A")), sequence = 3)
            h.http.threadJson["A"] = threadSnapshotJson(sequence = 3, "m1")

            h.runtime.start()
            settle()
            assertEquals(1, h.socket.connectCalls)
            h.runtime.loadThreadMessages("A")
            settle()
            assertEquals(1, h.runtime.cachedMessages("A").size)

            // Simulate the coordinator: stop, swap the active profile to OpenCode, start.
            h.runtime.stop()
            h.store.profile = opencodeProfile
            h.manager.reloadFromStore()
            h.runtime.start()
            settle()

            assertEquals(1, h.socket.disconnectCalls)
            assertEquals(T3SocketState.Disconnected, h.socket.state.value)
            assertEquals(1, h.socket.connectCalls)
            assertNull(h.runtime.shell.value)
            assertTrue(h.runtime.cachedMessages("A").isEmpty())
        }

    @Test
    fun foregroundStartReconnectsAfterDrop() =
        runTest {
            val h = Harness(this).build()
            h.runtime.start()
            settle()
            assertEquals(1, h.socket.connectCalls)

            h.socket.drop()
            settle()
            assertEquals("reconnects with backoff while live", 2, h.socket.connectCalls)
            assertEquals(2, h.socket.subscribes(T3RpcCodec.SUBSCRIBE_SHELL).size)

            h.runtime.stop()
            h.socket.drop()
            settle()
            assertEquals("no reconnect once stopped", 2, h.socket.connectCalls)
        }

    @Test
    fun subscribeFailureSurfacesAsLoadFailed() =
        runTest {
            val h = Harness(this).build()
            h.http.threadJson["gone"] = threadSnapshotJson(sequence = 1)
            val failures = mutableListOf<T3ThreadFailure>()
            backgroundScope.launch { h.runtime.threadFailures.toList(failures) }

            h.runtime.loadThreadMessages("gone")
            settle()
            val sub = h.socket.lastSubscribe(T3RpcCodec.SUBSCRIBE_THREAD)
            h.socket.exitFailure(sub.id, "ThreadNotFound")
            settle()

            assertEquals(listOf("gone"), failures.map { it.threadId })
            assertTrue(failures.single().error.message!!.contains("ThreadNotFound"))

            val preparer =
                MessageContentPreparer(OpenCodeTextMateHighlightingEngine(OpenCodeSyntaxHighlighter.default)).apply {
                    computationDispatcher = Dispatchers.Unconfined
                }
            val repository = T3MessageRepository(h.runtime, preparer)
            backgroundScope.launch {
                val update = repository.observeMessages("gone", null).first { it is MessageListUpdate.LoadFailed }
                failures += T3ThreadFailure("flow", (update as MessageListUpdate.LoadFailed).error)
            }
            settle()
            h.socket.exitFailure(h.socket.lastSubscribe(T3RpcCodec.SUBSCRIBE_THREAD).id, "ThreadNotFound")
            settle()
            assertEquals("the repository flow surfaced it as LoadFailed", "flow", failures.last().threadId)
        }

    @Test
    fun threadEventsCoalesceIntoOneShellRefresh() =
        runTest {
            val h = Harness(this).build()
            h.http.threadJson["A"] = threadSnapshotJson(sequence = 1)
            h.runtime.loadThreadMessages("A")
            settle()
            val sub = h.socket.lastSubscribe(T3RpcCodec.SUBSCRIBE_THREAD)
            val before = h.http.shellRequests

            repeat(5) { i ->
                h.socket.chunk(
                    sub.id,
                    threadEventItem(
                        (10 + i).toLong(),
                        buildJsonObject { put("type", "run.updated") },
                    ),
                )
            }
            settle()
            assertEquals(1, h.http.shellRequests - before)
        }
}
