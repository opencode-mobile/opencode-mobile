package dev.opencode.mobile.ui.main

import dev.opencode.mobile.domain.live.SessionAncestry
import dev.opencode.mobile.domain.live.SessionListSnapshot
import dev.opencode.mobile.domain.live.SessionLookupFake
import dev.opencode.mobile.domain.session.Session
import dev.opencode.mobile.domain.session.SessionActivity
import dev.opencode.mobile.domain.session.SessionActivityEntry
import dev.opencode.mobile.domain.session.SessionActivitySnapshot
import dev.opencode.mobile.domain.session.SessionActivitySource
import dev.opencode.mobile.domain.session.SessionDomainAdapter
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

/** Home rows from a paged window plus the busy set (#981). */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeSessionRowsTest {
    private val dispatcher = StandardTestDispatcher()

    @Test
    fun busyChildOfALoadedRootMarksTheRoot() =
        runTest(dispatcher) {
            val ancestry = ancestry(SessionLookupFake(emptyMap()))
            // An older v1 server still lists the child; it seeds the link.
            val window = listOf(session("root", updated = 2L), session("child", parent = "root", updated = 3L))
            ancestry.record(window)

            val rows = build(ancestry, SessionListSnapshot(window), busy("child"))

            assertEquals(listOf("root"), rows.summaries.map { it.id })
            assertEquals("Working", rows.summaries.single().statusLabel)
            assertTrue(rows.unresolvedBusyIds.isEmpty())
        }

    @Test
    fun busyGrandchildWithUnknownParentIsResolvedThenMarksItsRoot() =
        runTest(dispatcher) {
            val lookup =
                SessionLookupFake(
                    mapOf(
                        "grandchild" to session("grandchild", parent = "child"),
                        "child" to session("child", parent = "root-b"),
                    ),
                )
            val ancestry = ancestry(lookup)
            val window = listOf(session("root-a", updated = 2L), session("root-b", updated = 1L))
            ancestry.record(window)
            val snapshot = SessionListSnapshot(window)

            val before = build(ancestry, snapshot, busy("grandchild"))
            assertEquals(listOf("grandchild"), before.unresolvedBusyIds)
            assertTrue(before.summaries.all { it.statusLabel == null })

            ancestry.requestRoots(before.unresolvedBusyIds, "/repo")
            advanceUntilIdle()
            val after = build(ancestry, snapshot, busy("grandchild"))

            assertEquals(listOf("grandchild", "child"), lookup.fetched)
            assertEquals(mapOf("root-a" to null, "root-b" to "Working"), after.summaries.associate { it.id to it.statusLabel })
            assertTrue(after.unresolvedBusyIds.isEmpty())
        }

    @Test
    fun busyRootOutsideTheWindowIsShownUntilItsFamilyIsIdle() =
        runTest(dispatcher) {
            val lookup =
                SessionLookupFake(
                    mapOf(
                        "agent" to session("agent", parent = "old-root"),
                        "old-root" to session("old-root", updated = 1L),
                    ),
                )
            val ancestry = ancestry(lookup)
            val window = listOf(session("new", updated = 50L))
            ancestry.record(window)
            val snapshot = SessionListSnapshot(window, hasMore = true)

            ancestry.requestRoots(build(ancestry, snapshot, busy("agent")).unresolvedBusyIds, "/repo")
            advanceUntilIdle()
            val running = build(ancestry, snapshot, busy("agent"))
            assertEquals(listOf("new", "old-root"), running.summaries.map { it.id })
            assertEquals("Working", running.summaries.last().statusLabel)

            val idle = build(ancestry, snapshot, SessionActivitySnapshot(seeded = true))
            assertEquals(listOf("new"), idle.summaries.map { it.id })
        }

    @Test
    fun busyRootOfAnotherListOrArchivedStaysOff() =
        runTest(dispatcher) {
            val ancestry = ancestry(SessionLookupFake(emptyMap()))
            ancestry.record(
                listOf(
                    session("elsewhere", directory = "/other"),
                    session("other-project", directory = "/repo-wt", projectId = "other"),
                    session("same-project", directory = "/repo-wt", projectId = "proj"),
                    session("archived", archived = true),
                ),
            )
            val statuses = busy("elsewhere", "other-project", "same-project", "archived")

            val directoryList = build(ancestry, SessionListSnapshot(emptyList()), statuses)
            assertTrue(directoryList.summaries.isEmpty())

            // v2 project list: every worktree of the project, nothing of another project.
            val projectList = build(ancestry, SessionListSnapshot(emptyList(), projectId = "proj"), statuses)
            assertEquals(listOf("same-project"), projectList.summaries.map { it.id })
        }

    @Test
    fun largeListWithManyBusySessionsRollsUpCorrectly() =
        runTest(dispatcher) {
            val ancestry = ancestry(SessionLookupFake(emptyMap()))
            val roots = (0 until 5_000).map { session("root-$it", updated = it.toLong()) }
            val children = (0 until 5_000).map { session("child-$it", parent = "root-$it", updated = it.toLong()) }
            val window = roots + children
            ancestry.record(window)
            val busyIds = (0 until 50).map { "child-${it * 100}" }

            val rows = build(ancestry, SessionListSnapshot(window), busy(*busyIds.toTypedArray()))

            assertEquals(5_000, rows.summaries.size)
            val working =
                rows.summaries
                    .filter { it.statusLabel == "Working" }
                    .map { it.id }
                    .toSet()
            assertEquals((0 until 50).map { "root-${it * 100}" }.toSet(), working)
            assertTrue(rows.unresolvedBusyIds.isEmpty())
            assertNull(rows.summaries.firstOrNull { it.id.startsWith("child-") })
        }

    private fun build(
        ancestry: SessionAncestry,
        snapshot: SessionListSnapshot,
        activity: SessionActivitySnapshot,
    ) = HomeSessionRows.build(
        directory = "/repo",
        snapshot = snapshot,
        activity = activity,
        readyIds = emptySet(),
        serverReadState = false,
        adapter = SessionDomainAdapter(),
        rootOf = ancestry::rootOf,
        rootSession = ancestry::rootSession,
    )

    private fun ancestry(lookup: SessionLookupFake) =
        SessionAncestry(
            sessionRepository = lookup.repository,
            scope = CoroutineScope(SupervisorJob() + dispatcher),
            ioDispatcher = dispatcher,
        )

    private fun busy(vararg ids: String) =
        SessionActivitySnapshot(
            entries =
                ids.associateWith {
                    SessionActivityEntry(activity = SessionActivity.Running, source = SessionActivitySource.Server, observedAtMs = 1L)
                },
            seeded = true,
        )

    private fun session(
        id: String,
        parent: String? = null,
        updated: Long = 1L,
        directory: String = "/repo",
        projectId: String? = null,
        archived: Boolean = false,
    ) = Session(
        id = id,
        title = id,
        parentId = parent,
        directory = directory,
        projectId = projectId,
        updatedAtEpochMs = updated,
        archivedAtEpochMs = if (archived) 5L else null,
    )
}
