package dev.opencode.mobile.ui.components

import dev.opencode.mobile.domain.home.HomeSessionLadderKind
import dev.opencode.mobile.domain.home.HomeSessionLadderSection
import dev.opencode.mobile.theme.LetterAccent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionListItemIndexTest {
    private val sections =
        listOf(
            section("recent", HomeSessionLadderKind.Recent, "a", "b"),
            section("today", HomeSessionLadderKind.Day, "c"),
            section("week", HomeSessionLadderKind.Week, "d", "e"),
        )

    @Test
    fun countsOneHeaderItemPerSectionBeforeItsRows() {
        assertEquals(1, sessionListItemIndex(sections, "a"))
        assertEquals(2, sessionListItemIndex(sections, "b"))
        assertEquals(4, sessionListItemIndex(sections, "c"))
        assertEquals(6, sessionListItemIndex(sections, "d"))
        assertEquals(7, sessionListItemIndex(sections, "e"))
    }

    @Test
    fun missingSessionHasNoIndex() {
        assertNull(sessionListItemIndex(sections, "zzz"))
        assertNull(sessionListItemIndex(emptyList(), "a"))
    }

    private fun section(
        id: String,
        kind: HomeSessionLadderKind,
        vararg ids: String,
    ): HomeSessionLadderSection<SessionListSession> =
        HomeSessionLadderSection(
            id = id,
            kind = kind,
            title = id,
            items = ids.map(::session),
        )

    private fun session(id: String): SessionListSession =
        SessionListSession(
            id = id,
            title = "Session $id",
            worktree = "/w",
            letter = 'W',
            letterAccent = LetterAccent.Violet,
            subtitle = "/w",
            status = SessionListStatus.Idle,
            caption = "",
        )
}
