package dev.opencode.mobile.domain.sessioncache

import dev.opencode.mobile.domain.home.ResolvedHomePath
import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessagePart
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.message.NoticeModelRef
import dev.opencode.mobile.domain.message.TimelineNotice
import dev.opencode.mobile.domain.message.ToolStatus
import dev.opencode.mobile.domain.session.Session
import dev.opencode.mobile.domain.session.SessionActivity
import dev.opencode.mobile.domain.session.SessionIdleOutcome
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
    fun projectResolutionRoundTripsPerConnection() =
        runTest {
            val connectionId = "http://localhost:4096/|opencode"
            val cache =
                DefaultSessionCache(
                    store = InMemorySessionCacheStore(),
                    connectionIdProvider = { connectionId },
                    scope = backgroundScope,
                    json = json,
                )
            val resolution = ResolvedHomePath(projectId = "prj_repo", canonicalWorktree = "/repo", name = "Repo", isRepository = true)
            cache.rememberProjectResolution("/repo", resolution)

            assertEquals(resolution, cache.seedProjectResolution("/repo"))
            assertEquals(resolution, cache.seedProjectResolution("/repo", connectionId))
            assertNull(cache.seedProjectResolution("/repo", "other"))

            cache.purgeWorktree("http://localhost:4096/", "/repo")
            assertNull(cache.seedProjectResolution("/repo"))
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
    fun messageHandleRoundTripsTimelineNotices() =
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
            val messages =
                listOf(
                    TimelineNotice.Moved("/repo/b", previousDirectory = "/repo"),
                    TimelineNotice.AgentChanged("plan", previous = "build"),
                    TimelineNotice.ModelChanged(NoticeModelRef("openai", "gpt-5", "high"), NoticeModelRef("openai", "gpt-5")),
                    TimelineNotice.InstructionsUpdated(listOf("AGENTS.md", "CLAUDE.md")),
                    TimelineNotice.Restart,
                ).mapIndexed { index, notice ->
                    Message(
                        id = "msg_$index",
                        role = MessageRole.Notice,
                        text = "",
                        parts = listOf(MessagePart.Notice(id = "msg_$index", notice = notice)),
                        sentAtEpochMs = index.toLong(),
                    )
                }
            handle.note(messages)
            testScheduler.runCurrent()
            handle.flush()
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
    fun purgeConnectionKeepsServersThatOnlyShareAUrlPrefix() =
        runTest {
            val store = InMemorySessionCacheStore()

            fun cacheFor(connectionId: String) =
                DefaultSessionCache(
                    store = store,
                    connectionIdProvider = { connectionId },
                    scope = backgroundScope,
                    json = json,
                )
            val removed = cacheFor("http://localhost:4096/|opencode")
            val removedNoSlash = cacheFor("http://localhost:4096|other")
            val otherPort = cacheFor("http://localhost:40960/|opencode")
            val otherHost = cacheFor("http://localhost:4096.example/|opencode")
            listOf(removed, removedNoSlash, otherPort, otherHost).forEach {
                it.rememberSessions("/repo", listOf(Session(id = "s1", title = "One")))
            }

            removed.purgeConnection(profileId = "", serverBaseUrl = "http://localhost:4096/")

            assertNull(removed.seedSessions("/repo"))
            assertNull(removedNoSlash.seedSessions("/repo"))
            assertEquals(1, otherPort.seedSessions("/repo")?.size)
            assertEquals(1, otherHost.seedSessions("/repo")?.size)
        }

    @Test
    fun purgeWorktreeRemovesRowsKeyedByProfileId() =
        runTest {
            val cache =
                DefaultSessionCache(
                    store = InMemorySessionCacheStore(),
                    connectionIdProvider = { "profile-abc" },
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
                                dataUrl = "",
                            ),
                        ),
                ),
            )
        val encoded = SessionCacheCodec.encodeMessages(messages, json)
        val decoded = SessionCacheCodec.decodeMessages(encoded, json)
        assertEquals(messages, decoded)
        assertTrue(encoded.contains("\"schemaVersion\":1"))
        assertTrue(!encoded.contains("data:image/png"))
    }

    @Test
    fun codecRoundTripsServerReadState() {
        val sessions =
            listOf(
                Session(
                    id = "ses-unread",
                    title = "Unread",
                    idleAtEpochMs = 30L,
                    viewedAtEpochMs = 20L,
                    idleOutcome = SessionIdleOutcome.Failed,
                ),
                Session(id = "ses-plain", title = "Plain"),
            )

        val decoded = SessionCacheCodec.decodeSessions(SessionCacheCodec.encodeSessions(sessions, json), json)

        assertEquals(sessions, decoded)
    }

    @Test
    fun codecDecodesPayloadWrittenBeforeReadStateExisted() {
        val legacy =
            """{"schemaVersion":${SessionCacheCodec.SCHEMA_VERSION},"sessions":{"sessions":[{"id":"ses-1","title":"Old","updatedAtEpochMs":5}]}}"""

        val decoded = SessionCacheCodec.decodeSessions(legacy, json)?.single()

        assertEquals("ses-1", decoded?.id)
        assertNull(decoded?.idleAtEpochMs)
        assertNull(decoded?.viewedAtEpochMs)
        assertNull(decoded?.idleOutcome)
    }

    @Test
    fun codecRoundTripsCompletedAtEpochMs() {
        val messages =
            listOf(
                Message(
                    id = "m1",
                    role = MessageRole.Assistant,
                    text = "done",
                    sentAtEpochMs = 1_000L,
                    completedAtEpochMs = 2_000L,
                ),
            )
        val encoded = SessionCacheCodec.encodeMessages(messages, json)
        val decoded = SessionCacheCodec.decodeMessages(encoded, json)
        assertEquals(messages, decoded)
        assertEquals(2_000L, decoded?.single()?.completedAtEpochMs)

        val legacy =
            SessionCacheCodec.encodeMessages(
                listOf(Message(id = "m2", role = MessageRole.User, text = "hi", sentAtEpochMs = 1_000L)),
                json,
            )
        assertTrue(!legacy.contains("completedAtEpochMs"))
        val decodedLegacy = SessionCacheCodec.decodeMessages(legacy, json)
        assertEquals(null, decodedLegacy?.single()?.completedAtEpochMs)
    }

    @Test
    fun activityRoundTripPerDirectory() =
        runTest {
            val cache = activityCache(clock = { 1_000L })
            val entries =
                listOf(
                    SessionActivityCacheEntry("s1", SessionActivity.Running, 1_000L),
                    SessionActivityCacheEntry("s2", SessionActivity.Retrying(attempt = 2), 1_000L),
                )
            cache.rememberActivity("/repo", entries)
            testScheduler.runCurrent()
            advanceTimeBy(1_000)
            testScheduler.runCurrent()
            assertEquals(entries, cache.seedActivity("/repo"))
            assertNull(cache.seedActivity("/other"))
        }

    @Test
    fun activitySeedDropsEntriesOlderThanSixHours() =
        runTest {
            var now = 0L
            val cache = activityCache(clock = { now })
            cache.rememberActivity(
                "/repo",
                listOf(
                    SessionActivityCacheEntry("fresh", SessionActivity.Running, 0L),
                    SessionActivityCacheEntry("stale", SessionActivity.Running, 0L),
                ),
            )
            testScheduler.runCurrent()
            advanceTimeBy(1_000)
            testScheduler.runCurrent()
            now = 6L * 60L * 60L * 1_000L + 1L
            val seeded = cache.seedActivity("/repo")
            assertNull(seeded)
        }

    @Test
    fun activitySeedKeepsEntriesInsideSixHours() =
        runTest {
            var now = 1_000L
            val cache = activityCache(clock = { now })
            cache.rememberActivity(
                "/repo",
                listOf(SessionActivityCacheEntry("s1", SessionActivity.Running, 1_000L)),
            )
            testScheduler.runCurrent()
            advanceTimeBy(1_000)
            testScheduler.runCurrent()
            now = 1_000L + 6L * 60L * 60L * 1_000L
            assertEquals(
                listOf(SessionActivityCacheEntry("s1", SessionActivity.Running, 1_000L)),
                cache.seedActivity("/repo"),
            )
        }

    @Test
    fun activityClearAllAndPurgeDropPersistedRows() =
        runTest {
            val cache = activityCache(clock = { 1_000L })
            cache.rememberActivity(
                "/repo",
                listOf(SessionActivityCacheEntry("s1", SessionActivity.Running, 1_000L)),
            )
            testScheduler.runCurrent()
            advanceTimeBy(1_000)
            testScheduler.runCurrent()
            cache.purgeWorktree("http://localhost:4096", "/repo")
            assertNull(cache.seedActivity("/repo"))

            cache.rememberActivity(
                "/repo",
                listOf(SessionActivityCacheEntry("s1", SessionActivity.Running, 1_000L)),
            )
            testScheduler.runCurrent()
            advanceTimeBy(1_000)
            testScheduler.runCurrent()
            cache.clearAll()
            assertNull(cache.seedActivity("/repo"))
        }

    @Test
    fun activityDebounceSkipsUnchanged() =
        runTest {
            val store = CountingSessionCacheStore()
            val cache =
                DefaultSessionCache(
                    store = store,
                    connectionIdProvider = { "profile-1" },
                    scope = backgroundScope,
                    json = json,
                    clock = { 1_000L },
                    activityDebounceMs = 1_000L,
                )
            val entries = listOf(SessionActivityCacheEntry("s1", SessionActivity.Running, 1_000L))
            cache.rememberActivity("/repo", entries)
            testScheduler.runCurrent()
            advanceTimeBy(999)
            testScheduler.runCurrent()
            assertEquals(0, store.commits)
            advanceTimeBy(1)
            testScheduler.runCurrent()
            assertEquals(1, store.commits)
            cache.rememberActivity("/repo", entries)
            testScheduler.runCurrent()
            advanceTimeBy(1_000)
            testScheduler.runCurrent()
            assertEquals(1, store.commits)
        }

    @Test
    fun purgeConnectionCancelsAPendingActivityFlush() =
        runTest {
            val store = CountingSessionCacheStore()
            val cache =
                DefaultSessionCache(
                    store = store,
                    connectionIdProvider = { "profile-1" },
                    scope = backgroundScope,
                    json = json,
                    clock = { 1_000L },
                    activityDebounceMs = 1_000L,
                )
            cache.rememberActivity("/repo", listOf(SessionActivityCacheEntry("s1", SessionActivity.Running, 1_000L)))
            testScheduler.runCurrent()
            cache.purgeConnection(profileId = "profile-1", serverBaseUrl = "http://localhost:4096/")
            advanceTimeBy(2_000)
            testScheduler.runCurrent()
            assertEquals(0, store.commits)
            assertNull(cache.seedActivity("/repo"))
        }

    private fun TestScope.activityCache(clock: () -> Long) =
        DefaultSessionCache(
            store = InMemorySessionCacheStore(),
            connectionIdProvider = { "profile-1" },
            scope = backgroundScope,
            json = json,
            clock = clock,
            activityDebounceMs = 1_000L,
        )
}

private class CountingSessionCacheStore : SessionCacheStore {
    var commits = 0
    private val inner = InMemorySessionCacheStore()

    override suspend fun load(key: SessionCacheKey) = inner.load(key)

    override suspend fun commit(
        key: SessionCacheKey,
        value: SessionCacheValue,
        meta: SessionCacheCommitMeta,
    ) {
        commits += 1
        inner.commit(key, value, meta)
    }

    override suspend fun retain(
        key: SessionCacheKey,
        reason: SessionCacheRetainReason,
        atEpochMs: Long,
    ) = inner.retain(key, reason, atEpochMs)

    override suspend fun evict(selector: SessionCacheEvictSelector) = inner.evict(selector)

    override suspend fun report(selector: SessionCacheEvictSelector) = inner.report(selector)
}
