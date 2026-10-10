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
import org.junit.Assert.assertNull
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
