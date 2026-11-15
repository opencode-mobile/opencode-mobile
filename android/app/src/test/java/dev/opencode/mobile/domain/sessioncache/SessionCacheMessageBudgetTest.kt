package dev.opencode.mobile.domain.sessioncache

import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessagePart
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.message.ToolStatus
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** #1033: a message timeline flush is encoded per message and bounded, and never swallows an Error. */
class SessionCacheMessageBudgetTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            explicitNulls = false
        }

    @Test
    fun encodeMessages_buildsOneMessageAtATimeAndKeepsTheNewestThatFit() {
        val messages = transcript(count = 40, outputChars = 200_000)
        val total = messages.sumOf { it.parts.sumOf { part -> (part as? MessagePart.Tool)?.output?.length ?: 0 } }
        val pieces = mutableListOf<Int>()

        val payload =
            SessionCacheCodec.encodeMessages(messages, json, budgetChars = 1_000_000, onMessageEncoded = { pieces += it })

        // Every string built is one message's JSON, never one of the whole 8M-character transcript.
        assertTrue(pieces.max() < 250_000)
        assertTrue("payload ${payload.length} over budget", payload.length < 1_000_200)
        assertTrue(total > 7 * payload.length)
        // Only the messages it kept were encoded, and one more to learn it did not fit.
        val decoded = SessionCacheCodec.decodeMessages(payload, json)!!
        assertEquals(pieces.size - 1, decoded.size)
        // The newest messages, whole: nothing inside a kept message is cut.
        assertEquals(messages.takeLast(decoded.size), decoded)
    }

    @Test
    fun encodeMessages_underTheBudget_matchesOneWholeEncode() {
        val messages = transcript(count = 3, outputChars = 100)
        val payload = SessionCacheCodec.encodeMessages(messages, json)
        val whole =
            json.encodeToString(
                CachedEnvelope.serializer(),
                CachedEnvelope(
                    schemaVersion = SessionCacheCodec.SCHEMA_VERSION,
                    messages = CachedMessageListPayload(messages.map { message -> decodeOne(message) }),
                ),
            )
        assertEquals(whole, payload)
        assertEquals(messages, SessionCacheCodec.decodeMessages(payload, json))
    }

    @Test
    fun encodeMessages_keepsTheNewestMessageEvenWhenItAloneIsOverTheBudget() {
        val messages = transcript(count = 3, outputChars = 50_000)
        val decoded = SessionCacheCodec.decodeMessages(SessionCacheCodec.encodeMessages(messages, json, budgetChars = 10), json)
        assertEquals(listOf(messages.last()), decoded)
    }

    @Test
    fun flush_commitsAPayloadWithinTheBudget() =
        runTest {
            val store = InMemorySessionCacheStore()
            val budget = 300_000
            val cache =
                DefaultSessionCache(
                    store = store,
                    connectionIdProvider = { "profile-a" },
                    scope = backgroundScope,
                    json = json,
                    messageEncoder = { messages, json, hasThumbnail ->
                        SessionCacheCodec.encodeMessages(messages, json, hasThumbnail, budgetChars = budget)
                    },
                )
            val messages = transcript(count = 20, outputChars = 100_000)
            val handle = cache.openMessages("s1", "/repo")
            handle.note(messages)
            testScheduler.runCurrent()
            handle.flush()

            val seeded = cache.openMessages("s1", "/repo").seed()!!
            assertTrue(seeded.size in 1 until messages.size)
            assertEquals(messages.takeLast(seeded.size), seeded)
        }

    @Test
    fun flush_doesNotSwallowAnOutOfMemoryErrorFromTheEncode() =
        runTest {
            val oom = OutOfMemoryError("test: encode")
            val cache =
                DefaultSessionCache(
                    store = InMemorySessionCacheStore(),
                    connectionIdProvider = { "profile-a" },
                    scope = backgroundScope,
                    json = json,
                    messageEncoder = { _, _, _ -> throw oom },
                )
            val handle = cache.openMessages("s1", "/repo")
            handle.note(transcript(count = 1, outputChars = 10))
            testScheduler.runCurrent()
            try {
                handle.flush()
                fail("the OutOfMemoryError was swallowed")
            } catch (error: OutOfMemoryError) {
                assertSame(oom, error)
            }
        }

    @Test
    fun flush_stillLogsAndSkipsAnEncodeException() =
        runTest {
            val store = InMemorySessionCacheStore()
            val cache =
                DefaultSessionCache(
                    store = store,
                    connectionIdProvider = { "profile-a" },
                    scope = backgroundScope,
                    json = json,
                    messageEncoder = { _, _, _ -> throw IllegalStateException("test: encode") },
                )
            val handle = cache.openMessages("s1", "/repo")
            handle.note(transcript(count = 1, outputChars = 10))
            testScheduler.runCurrent()
            handle.flush()
            assertEquals(null, cache.openMessages("s1", "/repo").seed())
        }

    private fun decodeOne(message: Message): CachedMessagePayload {
        val single = SessionCacheCodec.encodeMessages(listOf(message), json)
        return json
            .decodeFromString(CachedEnvelope.serializer(), single)
            .messages!!
            .messages
            .single()
    }

    private fun transcript(
        count: Int,
        outputChars: Int,
    ): List<Message> =
        (1..count).map { index ->
            Message(
                id = "msg-$index",
                role = MessageRole.Assistant,
                text = "",
                parts =
                    listOf(
                        MessagePart.Text("Step $index", id = "txt-$index"),
                        MessagePart.Tool(
                            toolName = "write",
                            status = ToolStatus.Completed,
                            id = "prt-$index",
                            input = buildJsonObject { put("filePath", JsonPrimitive("src/File$index.kt")) },
                            output = "line $index\n".repeat(outputChars / 8).take(outputChars),
                        ),
                    ),
            )
        }
}
