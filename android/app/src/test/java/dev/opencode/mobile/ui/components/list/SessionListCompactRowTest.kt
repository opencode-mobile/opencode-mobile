package dev.opencode.mobile.ui.components

import dev.opencode.mobile.theme.LetterAccent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionListCompactRowTest {
    @Test
    fun idle_hasNoStatus() {
        assertNull(session(SessionListStatus.Idle).compactStatus())
        assertNull(session(SessionListStatus.Idle, stale = true).compactStatus())
    }

    @Test
    fun otherStatuses_keepTodaysLabelsAndTones() {
        val expected =
            mapOf(
                SessionListStatus.Running to ("Running" to OpenCodeListItemStatusTone.Interactive),
                SessionListStatus.Retrying to ("Retrying · 2" to OpenCodeListItemStatusTone.Warning),
                SessionListStatus.Ready to ("Ready" to OpenCodeListItemStatusTone.Success),
                SessionListStatus.Done to ("Done" to OpenCodeListItemStatusTone.Success),
                SessionListStatus.Failed to ("Failed" to OpenCodeListItemStatusTone.Error),
                SessionListStatus.Stopped to ("Stopped" to OpenCodeListItemStatusTone.Warning),
            )
        expected.forEach { (status, labelAndTone) ->
            val compact = session(status).compactStatus()
            assertEquals(status.name, labelAndTone.first, compact?.text)
            assertEquals(status.name, labelAndTone.second, compact?.tone)
        }
    }

    @Test
    fun compactPath_projectThenWorktree() {
        assertEquals(
            OpenCodeListItemPath("opencode-mobile", "794-readable-tool-results"),
            session(projectName = "opencode-mobile", compactWorktree = "794-readable-tool-results").compactPath(),
        )
        assertEquals(OpenCodeListItemPath("opencode-mobile"), session(projectName = "opencode-mobile").compactPath())
    }

    @Test
    fun compactPath_withoutProjectName_usesFolder() {
        assertEquals(OpenCodeListItemPath("notes"), session(subtitle = "/Users/demo/notes/").compactPath())
    }

    private fun session(
        status: SessionListStatus = SessionListStatus.Idle,
        stale: Boolean = false,
        projectName: String? = null,
        compactWorktree: String? = null,
        subtitle: String = "/Users/demo/opencode-mobile",
    ) = SessionListSession(
        id = "s",
        title = "T",
        worktree = "prj",
        letter = 'O',
        letterAccent = LetterAccent.Red,
        subtitle = subtitle,
        status = status,
        caption = "4m ago",
        retryAttempt = 2,
        statusStale = stale,
        projectName = projectName,
        compactWorktree = compactWorktree,
    )
}
