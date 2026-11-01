package dev.opencode.mobile.domain.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionFamilyActivityTest {
    @Test
    fun runningChildMarksIdleRootAndDoesNotCrossFamilies() {
        val sessions =
            listOf(
                session("root"),
                session("child", "root"),
                session("other"),
                session("other-child", "other"),
            )
        val snapshot = busy("child" to SessionActivity.Running, "other-child" to SessionActivity.Running)

        assertEquals("child", snapshot.homeActivitySessionId("root", sessions))
        assertEquals("other-child", snapshot.homeActivitySessionId("other", sessions))
    }

    @Test
    fun nestedChildRollsUpToRoot() {
        val sessions =
            listOf(
                session("root"),
                session("child", "root"),
                session("grandchild", "child"),
            )

        assertEquals(
            "grandchild",
            busy("grandchild" to SessionActivity.Running).homeActivitySessionId("root", sessions),
        )
    }

    @Test
    fun keepsOwnLabelWhenRootIsBusiest() {
        val sessions = listOf(session("root"), session("child", "root"))
        val snapshot =
            busy(
                "root" to SessionActivity.Retrying(attempt = 2),
                "child" to SessionActivity.Retrying(attempt = 1),
            )

        assertEquals("root", snapshot.homeActivitySessionId("root", sessions))
    }

    @Test
    fun runningChildOutranksRetryingRoot() {
        val sessions = listOf(session("root"), session("child", "root"))
        val snapshot =
            busy(
                "root" to SessionActivity.Retrying(attempt = 2),
                "child" to SessionActivity.Running,
            )

        assertEquals("child", snapshot.homeActivitySessionId("root", sessions))
    }

    @Test
    fun ignoresArchivedChildAndUnseededStatus() {
        val sessions =
            listOf(
                session("root"),
                session("child", "root").copy(archivedAtEpochMs = 10L),
            )
        val unseeded =
            SessionActivitySnapshot(
                entries =
                    mapOf(
                        "child" to
                            SessionActivityEntry(
                                activity = SessionActivity.Running,
                                source = SessionActivitySource.Server,
                                observedAtMs = 1L,
                            ),
                    ),
                seeded = false,
            )

        assertNull(unseeded.homeActivitySessionId("root", sessions))
        assertNull(busy("child" to SessionActivity.Running).homeActivitySessionId("root", sessions))
    }

    private fun session(
        id: String,
        parentId: String? = null,
    ) = Session(id = id, title = id, parentId = parentId, updatedAtEpochMs = 1L)

    private fun busy(vararg activities: Pair<String, SessionActivity>) =
        SessionActivitySnapshot(
            entries =
                activities.associate { (id, activity) ->
                    id to
                        SessionActivityEntry(
                            activity = activity,
                            source = SessionActivitySource.Server,
                            observedAtMs = 1L,
                        )
                },
            seeded = true,
        )
}
