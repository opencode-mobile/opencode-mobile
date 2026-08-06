package dev.opencode.mobile.domain.sessioncache

import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessagePart
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.message.ToolStatus
import dev.opencode.mobile.domain.session.Session
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultSessionCacheTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            explicitNulls = false
        }

    @Test
    fun seedAndRememberSessionsRoundTrip() =
        runTest {
            val store = InMemorySessionCacheStore()
            val cache =
                DefaultSessionCache(
                    store = store,
                    connectionIdProvider = { "http://localhost:4096/|opencode" },
                    scope = backgroundScope,
                    json = json,
                    clock = { 1_000L },
                )
            val sessions = listOf(Session(id = "s1", title = "One"))
            cache.rememberSessions("/repo", sessions)
            assertEquals(sessions, cache.seedSessions("/repo"))
        }

    @Test
    fun seedSessionsNoopsWithoutConnection() =
        runTest {
            val cache =
                DefaultSessionCache(
                    store = InMemorySessionCacheStore(),
                    connectionIdProvider = { null },
                    scope = backgroundScope,
                    json = json,
                )
            assertNull(cache.seedSessions("/repo"))
        }

    @Test
    fun messageHandleSeedsAfterFlush() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = TestScope(dispatcher)
            val cache =
                DefaultSessionCache(
                    store = InMemorySessionCacheStore(),
                    connectionIdProvider = { "http://localhost:4096/|opencode" },
                    scope = scope,
                    json = json,
                    clock = { 2_000L },
                    debounceMs = 3_000L,
                )
            val handle = cache.openMessages("s1", "/repo")
            val messages =
                listOf(
                    Message(
                        id = "m1",
                        role = MessageRole.Assistant,
                        text = "hello",
                        parts =
                            listOf(
                                MessagePart.Text(text = "hello"),
                                MessagePart.Tool(
                                    toolName = "bash",
                                    status = ToolStatus.Completed,
                                    title = "done",
                                ),
                            ),
                    ),
                )
            handle.note(messages)
            scope.runCurrent()
            scope.advanceTimeBy(2_999)
            scope.runCurrent()
            assertNull(handle.seed())
            scope.advanceTimeBy(1)
            scope.runCurrent()
            assertEquals(messages, handle.seed())
        }

    @Test
    fun flushWritesImmediately() =
        runTest {
            val cache =
                DefaultSessionCache(
                    store = InMemorySessionCacheStore(),
                    connectionIdProvider = { "http://localhost:4096/|opencode" },
                    scope = backgroundScope,
                    json = json,
                    debounceMs = 60_000L,
                )
            val handle = cache.openMessages("s1", "/repo")
            val messages = listOf(Message(id = "m1", role = MessageRole.User, text = "hi"))
            handle.note(messages)
            testScheduler.runCurrent()
            handle.flush()
            assertEquals(messages, handle.seed())
        }

    @Test
    fun purgeWorktreeRemovesRows() =
        runTest {
            val cache =
                DefaultSessionCache(
                    store = InMemorySessionCacheStore(),
                    connectionIdProvider = { "http://localhost:4096/|opencode" },
                    scope = backgroundScope,
                    json = json,
                )
            cache.rememberSessions("/repo", listOf(Session(id = "s1", title = "One")))
            cache.purgeWorktree("http://localhost:4096", "/repo")
            assertNull(cache.seedSessions("/repo"))
            assertEquals(0L, cache.estimatedSizeBytes())
        }

    @Test
    fun codecRoundTripsCurrentMessageParts() {
        val messages =
            listOf(
                Message(
                    id = "m1",
                    role = MessageRole.User,
                    text = "file",
                    parts =
                        listOf(
                            MessagePart.File(
                                id = "f1",
                                filename = "a.png",
                                mime = "image/png",
                                kind = dev.opencode.mobile.domain.message.AttachmentKind.Image,
                                dataUrl = "data:image/png;base64,abc",
                            ),
                        ),
                ),
            )
        val encoded = SessionCacheCodec.encodeMessages(messages, json)
        val decoded = SessionCacheCodec.decodeMessages(encoded, json)
        assertEquals(messages, decoded)
        assertTrue(encoded.contains("\"schemaVersion\":1"))
    }
}
