package dev.opencode.mobile.ui.components

import dev.opencode.mobile.theme.LetterAccent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SessionListDividerTest {
    private val sessions = listOf("a", "b", "c", "d").map(::session)

    private fun dividers(selected: String?): List<Boolean> = sessions.indices.map { sessionListShowsDivider(it, sessions, selected) }

    @Test
    fun noSelection_keepsInnerDividersAndNoneAfterLastRow() {
        assertEquals(listOf(true, true, true, false), dividers(null))
    }

    @Test
    fun selectionOnFirstRow_hidesDividerBelow() {
        assertEquals(listOf(false, true, true, false), dividers("a"))
    }

    @Test
    fun selectionOnMiddleRow_hidesDividersAboveAndBelow() {
        assertEquals(listOf(false, false, true, false), dividers("b"))
    }

    @Test
    fun selectionOnLastRow_hidesDividerAbove() {
        assertEquals(listOf(true, true, false, false), dividers("d"))
    }

    @Test
    fun selectionOutsideSection_changesNothing() {
        assertEquals(listOf(true, true, true, false), dividers("zzz"))
    }

    @Test
    fun singleRowSection_hasNoDivider() {
        val single = sessions.take(1)
        assertFalse(sessionListShowsDivider(0, single, null))
        assertFalse(sessionListShowsDivider(0, single, "a"))
    }

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
