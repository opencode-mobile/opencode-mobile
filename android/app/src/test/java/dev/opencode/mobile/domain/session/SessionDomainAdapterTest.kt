package dev.opencode.mobile.domain.session

import dev.opencode.mobile.data.remote.dto.ApiSessionActiveDto
import dev.opencode.mobile.data.remote.dto.ApiSessionRetryActionDto
import dev.opencode.mobile.data.remote.dto.ApiSessionRetryErrorDto
import dev.opencode.mobile.data.remote.dto.SessionDto
import dev.opencode.mobile.data.remote.dto.SessionTimeDto
import dev.opencode.mobile.data.remote.dto.V2SessionDto
import dev.opencode.mobile.data.remote.dto.V2SessionForkDto
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionDomainAdapterTest {
    private val adapter = SessionDomainAdapter()

    @Test
    fun mapsArchivedAtEpochMsFromSessionTime() {
        val session =
            adapter.toSession(
                SessionDto(
                    id = "ses-1",
                    title = "Title",
                    time =
                        SessionTimeDto(
                            created = 1L,
                            updated = 2L,
                            archived = 3L,
                        ),
                ),
            )

        assertEquals(3L, session.archivedAtEpochMs)
        assertEquals(1L, session.createdAtEpochMs)
        assertEquals(2L, session.updatedAtEpochMs)
    }

    @Test
    fun treatsMissingArchivedTimeAsActive() {
        val session =
            adapter.toSession(
                SessionDto(
                    id = "ses-1",
                    title = "Title",
                    time = SessionTimeDto(created = 1L, updated = 2L),
                ),
            )

        assertNull(session.archivedAtEpochMs)
    }

    @Test
    fun activeSessionsExcludesArchived() {
        val active = Session(id = "a", title = "Active")
        val archived = Session(id = "b", title = "Archived", archivedAtEpochMs = 10L)

        assertEquals(listOf(active), listOf(active, archived).activeSessions())
    }

    @Test
    fun rootSessionsExcludesChildSessions() {
        val root = Session(id = "root", title = "Root")
        val child = Session(id = "child", title = "Child", parentId = "root")

        assertEquals(listOf(root), listOf(root, child).rootSessions())
    }

    @Test
    fun v2ForkIsTopLevelSession() {
        val fork =
            adapter.toSession(
                V2SessionDto(id = "ses_fork", title = "Fork", fork = V2SessionForkDto(sessionId = "ses_src")),
            )

        assertNull(fork.parentId)
        assertEquals(listOf(fork), listOf(fork).rootSessions())
    }

    @Test
    fun v2ParentIdStillMarksChildSession() {
        val child = adapter.toSession(V2SessionDto(id = "ses_child", title = "Child", parentId = "ses_1"))

        assertEquals("ses_1", child.parentId)
        assertEquals(emptyList<Session>(), listOf(child).rootSessions())
    }

    @Test
    fun serverUnreadNeedsIdleNewerThanViewed() {
        assertFalse(Session(id = "a", title = "Never ran").isServerUnread)
        assertTrue(Session(id = "a", title = "Never viewed", idleAtEpochMs = 10L).isServerUnread)
        assertTrue(Session(id = "a", title = "Ran again", idleAtEpochMs = 20L, viewedAtEpochMs = 10L).isServerUnread)
        assertFalse(Session(id = "a", title = "Seen", idleAtEpochMs = 10L, viewedAtEpochMs = 10L).isServerUnread)
        assertFalse(Session(id = "a", title = "Seen later", idleAtEpochMs = 10L, viewedAtEpochMs = 30L).isServerUnread)
        assertFalse(Session(id = "a", title = "Viewed only", viewedAtEpochMs = 30L).isServerUnread)
    }

    @Test
    fun withViewedOnlyMovesWatermarkForward() {
        val unread = Session(id = "a", title = "A", idleAtEpochMs = 20L, viewedAtEpochMs = 10L)

        assertEquals(20L, unread.withViewed(20L).viewedAtEpochMs)
        assertEquals(15L, unread.withViewed(15L).viewedAtEpochMs)
        assertEquals(10L, unread.withViewed(5L).viewedAtEpochMs)
        assertEquals(7L, Session(id = "b", title = "B").withViewed(7L).viewedAtEpochMs)
    }

    @Test
    fun outcomeWireValuesMapCaseInsensitivelyAndUnknownIsNull() {
        assertEquals(SessionIdleOutcome.Succeeded, SessionIdleOutcome.fromWire("succeeded"))
        assertEquals(SessionIdleOutcome.Failed, SessionIdleOutcome.fromWire("FAILED"))
        assertEquals(SessionIdleOutcome.Interrupted, SessionIdleOutcome.fromWire("interrupted"))
        assertNull(SessionIdleOutcome.fromWire("cancelled"))
        assertNull(SessionIdleOutcome.fromWire(null))
    }

    @Test
    fun v2DtoMapsReadStateAndV1DtoIgnoresIt() {
        val time = SessionTimeDto(created = 1L, updated = 2L, idle = 30L, viewed = 20L)
        val v2 = adapter.toSession(V2SessionDto(id = "ses_v2", title = "V2", time = time, outcome = "interrupted"))
        val v1 = adapter.toSession(SessionDto(id = "ses_v1", title = "V1", time = time))

        assertEquals(30L, v2.idleAtEpochMs)
        assertEquals(20L, v2.viewedAtEpochMs)
        assertEquals(SessionIdleOutcome.Interrupted, v2.idleOutcome)
        assertNull(v1.idleAtEpochMs)
        assertNull(v1.viewedAtEpochMs)
        assertNull(v1.idleOutcome)
    }

    @Test
    fun activeRetryKeepsCountdownAndAction() {
        val status =
            adapter.toSessionStatusesFromActive(
                mapOf(
                    "ses-1" to
                        ApiSessionActiveDto(
                            type = "retry",
                            attempt = 2,
                            message = "Slow down",
                            next = 9_000L,
                            error = ApiSessionRetryErrorDto(type = "provider.rate-limit"),
                            action = ApiSessionRetryActionDto(label = "Upgrade", link = "https://example.com"),
                        ),
                ),
            ).single()

        assertEquals("retry", status.raw["type"])
        assertEquals("2", status.raw["attempt"])
        assertEquals("9000", status.raw["next"])
        assertEquals("provider.rate-limit", status.raw["errorType"])
        assertEquals("Upgrade", status.raw["actionLabel"])
        val card = status.toSessionActivity().let { (it as SessionActivity.Retrying).retryCard() }
        assertEquals("Slow down", card?.message)
        assertEquals(9_000L, card?.nextAtMs)
        assertEquals("Upgrade", card?.actionLabel)
    }

    @Test
    fun statusObjectFlattensNestedActionInsteadOfDroppingIt() {
        val status =
            adapter.toSessionStatus(
                "ses-1",
                buildJsonObject {
                    put("type", "retry")
                    put("attempt", 1)
                    putJsonObject("action") {
                        put("label", "Upgrade")
                        put("link", "https://example.com")
                    }
                    putJsonObject("error") {
                        put("type", "provider.quota")
                    }
                },
            )

        assertEquals("Upgrade", status.raw["actionLabel"])
        assertEquals("https://example.com", status.raw["actionLink"])
        assertEquals("provider.quota", status.raw["errorType"])
        assertEquals("1", status.raw["attempt"])
    }
}
