package dev.opencode.mobile.domain.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionTitleSearchTest {
    @Test
    fun matchRange_isTheFirstCaseInsensitiveOccurrenceOfTheTrimmedQuery() {
        assertEquals(9 until 15, SessionTitleSearch.matchRange("Refactor search search", " SEARCH "))
        assertEquals(0 until 3, SessionTitleSearch.matchRange("Fix login", "fix"))
    }

    @Test
    fun matchRange_isNullWithoutAMatchOrForABlankQuery() {
        assertNull(SessionTitleSearch.matchRange("Fix login", "storm"))
        assertNull(SessionTitleSearch.matchRange("Fix login", "   "))
        assertFalse(SessionTitleSearch.matches("Fix login", ""))
        assertTrue(SessionTitleSearch.matches("Fix login", "LOG"))
    }

    @Test
    fun filter_keepsActiveRootSessionsWhoseTitleMatches() {
        val sessions =
            listOf(
                Session(id = "root", title = "Invoice export"),
                Session(id = "child", title = "Invoice child", parentId = "root"),
                Session(id = "archived", title = "Old invoice", archivedAtEpochMs = 5),
                Session(id = "other", title = "Refactor auth"),
            )

        assertEquals(listOf("root"), SessionTitleSearch.filter(sessions, "invoice").map { it.id })
    }
}
