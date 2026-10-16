package dev.opencode.mobile.ui.main

import dev.opencode.mobile.domain.session.SessionSummary
import dev.opencode.mobile.theme.LetterAccent
import org.junit.Assert.assertEquals
import org.junit.Test

class CrossProjectSessionListSessionsTest {
    @Test
    fun sameIdInTwoProjects_keepsNewerRowFromLaterProject() {
        val sessions =
            listOf(
                section(worktree = "/earlier", session(id = "ses", title = "older", updatedAtEpochMs = 10L)),
                section(worktree = "/later", session(id = "ses", title = "newer", updatedAtEpochMs = 20L)),
            ).toCrossProjectSessionListSessions()

        assertEquals(listOf("ses"), sessions.map { it.id })
        assertEquals("newer", sessions.single().title)
        assertEquals("/later", sessions.single().worktree)
        assertEquals(20L, sessions.single().updatedAtEpochMs)
    }

    @Test
    fun sameId_equalTimestamps_keepsEarlierProject() {
        val sessions =
            listOf(
                section(worktree = "/earlier", session(id = "ses", title = "first", updatedAtEpochMs = 10L)),
                section(worktree = "/later", session(id = "ses", title = "second", updatedAtEpochMs = 10L)),
            ).toCrossProjectSessionListSessions()

        assertEquals(listOf("ses"), sessions.map { it.id })
        assertEquals("first", sessions.single().title)
        assertEquals("/earlier", sessions.single().worktree)
    }

    @Test
    fun sameId_nullTimestampLosesToRealTimestamp() {
        val realThenNull =
            listOf(
                section(worktree = "/real", session(id = "ses", title = "dated", updatedAtEpochMs = 50L)),
                section(worktree = "/missing", session(id = "ses", title = "undated", updatedAtEpochMs = null)),
            ).toCrossProjectSessionListSessions()
        val nullThenReal =
            listOf(
                section(worktree = "/missing", session(id = "ses", title = "undated", updatedAtEpochMs = null)),
                section(worktree = "/real", session(id = "ses", title = "dated", updatedAtEpochMs = 50L)),
            ).toCrossProjectSessionListSessions()

        assertEquals("/real", realThenNull.single().worktree)
        assertEquals(50L, realThenNull.single().updatedAtEpochMs)
        assertEquals("/real", nullThenReal.single().worktree)
        assertEquals(50L, nullThenReal.single().updatedAtEpochMs)
    }

    @Test
    fun distinctIds_stayNewestFirst() {
        val sessions =
            listOf(
                section(
                    worktree = "/a",
                    session(id = "old", title = "Old", updatedAtEpochMs = 100L),
                    session(id = "tie-early", title = "Early", updatedAtEpochMs = 50L),
                ),
                section(
                    worktree = "/b",
                    session(id = "tie-late", title = "Late", updatedAtEpochMs = 50L),
                    session(id = "newest", title = "Newest", updatedAtEpochMs = 200L),
                ),
            ).toCrossProjectSessionListSessions()

        assertEquals(listOf("newest", "old", "tie-early", "tie-late"), sessions.map { it.id })
    }

    @Test
    fun sameIdTwiceInOneProject_keepsNewerRow() {
        val newerLast =
            listOf(
                section(
                    worktree = "/a",
                    session(id = "ses", title = "older", updatedAtEpochMs = 10L),
                    session(id = "ses", title = "newer", updatedAtEpochMs = 30L),
                ),
            ).toCrossProjectSessionListSessions()
        val newerFirst =
            listOf(
                section(
                    worktree = "/a",
                    session(id = "ses", title = "newer", updatedAtEpochMs = 30L),
                    session(id = "ses", title = "older", updatedAtEpochMs = 10L),
                ),
            ).toCrossProjectSessionListSessions()

        assertEquals("newer", newerLast.single().title)
        assertEquals(30L, newerLast.single().updatedAtEpochMs)
        assertEquals("newer", newerFirst.single().title)
        assertEquals(30L, newerFirst.single().updatedAtEpochMs)
    }

    @Test
    fun worktreeSession_staysInAddedProjectButOpensItsOwnDirectory() {
        val sessions =
            listOf(
                section(
                    worktree = "/repo",
                    session(
                        id = "ses_worktree",
                        title = "Add project sheet prototype",
                        updatedAtEpochMs = 20L,
                        directory = "/worktrees/add-project-browse",
                    ),
                ),
            ).toCrossProjectSessionListSessions()

        assertEquals("/repo", sessions.single().worktree)
        assertEquals("/worktrees/add-project-browse", sessions.single().directory)
    }

    @Test
    fun projectCounts_followTheWinningWorktree() {
        val sections =
            listOf(
                section(
                    worktree = "/earlier",
                    session(id = "shared", title = "older", updatedAtEpochMs = 10L),
                    session(id = "local", title = "stays", updatedAtEpochMs = 5L),
                ),
                section(worktree = "/later", session(id = "shared", title = "newer", updatedAtEpochMs = 20L)),
                section(worktree = "/empty-after-dedupe", session(id = "shared", title = "oldest", updatedAtEpochMs = 1L)),
            )
        val counts = sections.toHomeChromeProjects().associate { it.worktree to it.sessionCount }

        assertEquals(1, counts["/earlier"])
        assertEquals(1, counts["/later"])
        assertEquals(0, counts["/empty-after-dedupe"])
    }

    private fun section(
        worktree: String,
        vararg sessions: SessionSummary,
    ): ProjectSectionUi =
        ProjectSectionUi(
            worktree = worktree,
            displayName = worktree,
            pathSubtitle = worktree,
            letter = 'P',
            letterAccent = LetterAccent.Violet,
            allSessions = sessions.toList(),
        )

    private fun session(
        id: String,
        title: String,
        updatedAtEpochMs: Long?,
        directory: String? = null,
    ): SessionSummary =
        SessionSummary(
            id = id,
            title = title,
            updatedAtEpochMs = updatedAtEpochMs,
            directory = directory,
        )
}
