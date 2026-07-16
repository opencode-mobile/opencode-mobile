package dev.opencode.mobile.domain.session

import dev.opencode.mobile.data.remote.dto.SessionDto
import dev.opencode.mobile.data.remote.dto.SessionTimeDto
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
}
