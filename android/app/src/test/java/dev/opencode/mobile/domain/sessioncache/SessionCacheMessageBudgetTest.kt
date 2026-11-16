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
import org.junit.Assert.assertNull
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
            SessionCacheCodec.encodeMessages(messages, json, budgetBytes = 1_000_000, onMessageEncoded = { pieces += it })

        // Every string built is one message's JSON, never one of the whole 8M-character transcript.
        assertTrue(pieces.max() < 250_000)
        assertTrue("payload ${payload.length} over budget", payload.length < 1_000_200)
        assertTrue(total > 7 * payload.length)
        // Only the messages it kept were encoded, and at most one more to learn it did not fit.
        val decoded = SessionCacheCodec.decodeMessages(payload, json)!!
        assertTrue(pieces.size - decoded.size in 0..1)
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
    fun encodeMessages_countsTheBudgetInUtf8Bytes() {
        // Three bytes a character in UTF-8, the unit SQLite stores and the cursor window reads.
        val messages = transcript(count = 10, outputChars = 100_000, fill = "\u6587")
        val payload = SessionCacheCodec.encodeMessages(messages, json, budgetBytes = 1_000_000)
        val bytes = payload.toByteArray(Charsets.UTF_8).size
        assertTrue("payload $bytes bytes over budget", bytes < 1_000_200)
        val decoded = SessionCacheCodec.decodeMessages(payload, json)!!
        assertEquals(3, decoded.size)
        assertEquals(messages.takeLast(3), decoded)
    }

    @Test
    fun encodeMessages_doesNotEncodeAMessageWhoseTextAloneIsOverTheBudget() {
        // One huge tool output must not build its JSON string just to learn it does not fit.
        val messages = transcript(count = 3, outputChars = 50_000)
        val pieces = mutableListOf<Int>()
        val payload = SessionCacheCodec.encodeMessages(messages, json, budgetBytes = 40_000, onMessageEncoded = { pieces += it })
        assertEquals(emptyList<Int>(), pieces)
        assertEquals(emptyList<Message>(), SessionCacheCodec.decodeMessages(payload, json))
    }

    @Test
    fun encodeMessages_doesNotEncodeAMessageWhoseToolInputAloneIsOverTheBudget() {
        // A large write carries its content in the input, not the output.
        val write =
            Message(
                id = "msg-write",
                role = MessageRole.Assistant,
                text = "",
                parts =
                    listOf(
                        MessagePart.Tool(
                            toolName = "write",
                            status = ToolStatus.Completed,
                            id = "prt-write",
                            input =
                                buildJsonObject {
                                    put("filePath", JsonPrimitive("src/Big.kt"))
                                    put("content", JsonPrimitive("x".repeat(50_000)))
                                },
                        ),
                    ),
            )
        val pieces = mutableListOf<Int>()
        val payload = SessionCacheCodec.encodeMessages(listOf(write), json, budgetBytes = 40_000, onMessageEncoded = { pieces += it })
        assertEquals(emptyList<Int>(), pieces)
        assertEquals(emptyList<Message>(), SessionCacheCodec.decodeMessages(payload, json))
    }

    @Test
    fun encodeMessages_keepsNoMessagesWhenTheNewestIsOverTheBudget() {
        // The floor is under the budget, the encoded JSON over it: still not kept.
        val messages = transcript(count = 3, outputChars = 50_000)
        val payload = SessionCacheCodec.encodeMessages(messages, json, budgetBytes = 50_050)
        assertEquals(emptyList<Message>(), SessionCacheCodec.decodeMessages(payload, json))
    }

    @Test
    fun seed_readsATimelineThatKeptNoMessagesAsAMiss() =
        runTest {
            val cache =
                DefaultSessionCache(
                    store = InMemorySessionCacheStore(),
                    connectionIdProvider = { "profile-a" },
                    scope = backgroundScope,
                    json = json,
                    messageEncoder = { messages, json, hasThumbnail ->
                        SessionCacheCodec.encodeMessages(messages, json, hasThumbnail, budgetBytes = 10)
                    },
                )
            val handle = cache.openMessages("s1", "/repo")
            handle.note(transcript(count = 2, outputChars = 1_000))
            testScheduler.runCurrent()
            handle.flush()
            assertNull(cache.openMessages("s1", "/repo").seed())
        }

    @Test
    fun seed_readsAnErrorFromTheLoadOrDecodeAsAMiss() =
        runTest {
            // #1033: a legacy row too big to decode is a cache miss, so the live load still runs.
            for (failure in listOf(OutOfMemoryError("test: decode"), StackOverflowError("test: decode"), IllegalStateException("test: load"))) {
                val inner = InMemorySessionCacheStore()
                val store =
                    object : SessionCacheStore by inner {
                        override suspend fun load(key: SessionCacheKey): SessionCacheRecord? = throw failure
                    }
                val cache =
                    DefaultSessionCache(
                        store = store,
                        connectionIdProvider = { "profile-a" },
                        scope = backgroundScope,
                        json = json,
                    )
                assertNull(cache.openMessages("s1", "/repo").seed())
            }
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
                        SessionCacheCodec.encodeMessages(messages, json, hasThumbnail, budgetBytes = budget)
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
        fill: String? = null,
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
                            output =
                                fill?.repeat(outputChars)
                                    ?: "line $index\n".repeat(outputChars / 8).take(outputChars),
                        ),
                    ),
            )
        }
}
