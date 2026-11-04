package dev.opencode.mobile.domain.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Home's busy-set roll-up (#981): the best busy entry per root, found from the busy ids only. */
class SessionFamilyActivityTest {
    @Test
    fun runningChildMarksIdleRootAndDoesNotCrossFamilies() {
        val roots = rootsOf(session("root"), session("child", "root"), session("other"), session("other-child", "other"))
        val snapshot = busy("child" to SessionActivity.Running, "other-child" to SessionActivity.Running)

        assertEquals(mapOf("root" to "child", "other" to "other-child"), snapshot.homeActivityByRoot(roots))
    }

    @Test
    fun nestedChildRollsUpToRoot() {
        val roots = rootsOf(session("root"), session("child", "root"), session("grandchild", "child"))

        assertEquals(mapOf("root" to "grandchild"), busy("grandchild" to SessionActivity.Running).homeActivityByRoot(roots))
    }

    @Test
    fun keepsOwnLabelWhenRootIsBusiest() {
        val roots = rootsOf(session("root"), session("child", "root"))
        val childFirst =
            busy(
                "child" to SessionActivity.Retrying(attempt = 1),
                "root" to SessionActivity.Retrying(attempt = 2),
            )
        val rootFirst =
            busy(
                "root" to SessionActivity.Retrying(attempt = 2),
                "child" to SessionActivity.Retrying(attempt = 1),
            )

        assertEquals(mapOf("root" to "root"), childFirst.homeActivityByRoot(roots))
        assertEquals(mapOf("root" to "root"), rootFirst.homeActivityByRoot(roots))
    }

    @Test
    fun runningChildOutranksRetryingRoot() {
        val roots = rootsOf(session("root"), session("child", "root"))
        val snapshot =
            busy(
                "root" to SessionActivity.Retrying(attempt = 2),
                "child" to SessionActivity.Running,
            )

        assertEquals(mapOf("root" to "child"), snapshot.homeActivityByRoot(roots))
    }

    @Test
    fun freshEntryOutranksCachedOne() {
        val roots = rootsOf(session("root"), session("child", "root"))
        val snapshot =
            SessionActivitySnapshot(
                entries =
                    mapOf(
                        "root" to entry(SessionActivity.Running, SessionActivitySource.Cached),
                        "child" to entry(SessionActivity.Running, SessionActivitySource.Server),
                    ),
                seeded = true,
            )

        assertEquals(mapOf("root" to "child"), snapshot.homeActivityByRoot(roots))
    }

    @Test
    fun skipsUnknownParentsAndUnseededStatus() {
        val roots = rootsOf(session("root"))
        val unseeded =
            SessionActivitySnapshot(
                entries = mapOf("root" to entry(SessionActivity.Running, SessionActivitySource.Server)),
                seeded = false,
            )

        assertTrue(unseeded.homeActivityByRoot(roots).isEmpty())
        assertTrue(unseeded.busySessionIds().isEmpty())
        val orphan = busy("lost-child" to SessionActivity.Running)
        assertTrue(orphan.homeActivityByRoot(roots).isEmpty())
        assertEquals(listOf("lost-child"), orphan.busySessionIds())
    }

    @Test
    fun idleEntriesAreNotBusy() {
        val roots = rootsOf(session("root"))
        val snapshot = busy("root" to SessionActivity.Idle)

        assertTrue(snapshot.homeActivityByRoot(roots).isEmpty())
        assertTrue(snapshot.busySessionIds().isEmpty())
    }

    private fun rootsOf(vararg sessions: Session): (String) -> String? {
        val parents = sessions.associate { it.id to it.parentId }
        return { id ->
            var current: String? = id
            while (current != null && parents.containsKey(current) && parents[current] != null) current = parents[current]
            current?.takeIf { parents.containsKey(it) }
        }
    }

    private fun session(
        id: String,
        parentId: String? = null,
    ) = Session(id = id, title = id, parentId = parentId, updatedAtEpochMs = 1L)

    private fun entry(
        activity: SessionActivity,
        source: SessionActivitySource,
    ) = SessionActivityEntry(activity = activity, source = source, observedAtMs = 1L)

    private fun busy(vararg activities: Pair<String, SessionActivity>) =
        SessionActivitySnapshot(
            entries = activities.associate { (id, activity) -> id to entry(activity, SessionActivitySource.Server) },
            seeded = true,
        )
}
