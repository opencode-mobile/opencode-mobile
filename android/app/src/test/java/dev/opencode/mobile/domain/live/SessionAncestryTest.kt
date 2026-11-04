package dev.opencode.mobile.domain.live

import dev.opencode.mobile.domain.session.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionAncestryTest {
    private val dispatcher = StandardTestDispatcher()

    @Test
    fun recordedWindowResolvesRootsAndChildrenWithoutFetching() =
        runTest(dispatcher) {
            val lookup = SessionLookupFake(emptyMap())
            val ancestry = ancestry(lookup)

            ancestry.record(listOf(session("root"), session("child", "root"), session("grandchild", "child")))

            assertEquals("root", ancestry.rootOf("root"))
            assertEquals("root", ancestry.rootOf("grandchild"))
            assertNull(ancestry.rootOf("unknown"))
            assertEquals("root", ancestry.rootSession("root")?.id)
            assertTrue(lookup.fetched.isEmpty())
        }

    @Test
    fun unknownParentsAreFetchedUpToAKnownRoot() =
        runTest(dispatcher) {
            val lookup =
                SessionLookupFake(
                    mapOf(
                        "grandchild" to session("grandchild", "child"),
                        "child" to session("child", "root"),
                    ),
                )
            val ancestry = ancestry(lookup)
            ancestry.record(listOf(session("root")))
            val changesBefore = ancestry.changes.value

            assertEquals(mapOf("grandchild" to "root"), ancestry.resolveRoots(listOf("grandchild"), "/repo"))
            assertEquals(listOf("grandchild", "child"), lookup.fetched)
            assertEquals("root", ancestry.rootOf("child"))
            assertTrue(ancestry.changes.value > changesBefore)

            // Memoized: no second fetch.
            ancestry.resolveRoots(listOf("grandchild"), "/repo")
            assertEquals(2, lookup.fetched.size)
        }

    @Test
    fun fetchedRootIsStoredForHome() =
        runTest(dispatcher) {
            val lookup = SessionLookupFake(mapOf("old-root" to session("old-root"), "agent" to session("agent", "old-root")))
            val ancestry = ancestry(lookup)

            ancestry.requestRoots(listOf("agent"), "/repo")
            advanceUntilIdle()

            assertEquals("old-root", ancestry.rootOf("agent"))
            assertEquals("old-root", ancestry.rootSession("old-root")?.id)
        }

    @Test
    fun failedLookupIsRetriedOnlyAfterAMinute() =
        runTest(dispatcher) {
            var now = 0L
            val lookup = SessionLookupFake(emptyMap(), failing = setOf("lost"))
            val ancestry = ancestry(lookup) { now }

            assertTrue(ancestry.resolveRoots(listOf("lost"), "/repo").isEmpty())
            now = 30_000L
            ancestry.resolveRoots(listOf("lost"), "/repo")
            assertEquals(listOf("lost"), lookup.fetched)

            now = 61_000L
            ancestry.resolveRoots(listOf("lost"), "/repo")
            assertEquals(listOf("lost", "lost"), lookup.fetched)
        }

    @Test
    fun walkStopsAfterMaxDepth() =
        runTest(dispatcher) {
            // s0 ← s1 ← … ← s12: twelve levels, deeper than the limit.
            val chain = (0..12).associate { index -> "s$index" to session("s$index", if (index == 0) null else "s${index - 1}") }
            val lookup = SessionLookupFake(chain)
            val ancestry = ancestry(lookup)

            assertNull(ancestry.resolveRoots(listOf("s12"), "/repo")["s12"])
            assertTrue(lookup.fetched.size <= SessionAncestry.MAX_DEPTH + 1)
        }

    @Test
    fun archivedSessionDoesNotRollUpAndClearForgetsEverything() =
        runTest(dispatcher) {
            val ancestry = ancestry(SessionLookupFake(emptyMap()))
            ancestry.record(listOf(session("root"), session("child", "root").copy(archivedAtEpochMs = 5L)))

            assertNull(ancestry.rootOf("child"))

            ancestry.clear()
            assertNull(ancestry.rootOf("root"))
            assertNull(ancestry.rootSession("root"))
        }

    private fun ancestry(
        lookup: SessionLookupFake,
        nowMs: () -> Long = { 0L },
    ) = SessionAncestry(lookup.repository, CoroutineScope(SupervisorJob() + dispatcher), ioDispatcher = dispatcher, nowMs = nowMs)

    private fun session(
        id: String,
        parentId: String? = null,
    ) = Session(id = id, title = id, parentId = parentId, directory = "/repo", updatedAtEpochMs = 1L)
}
