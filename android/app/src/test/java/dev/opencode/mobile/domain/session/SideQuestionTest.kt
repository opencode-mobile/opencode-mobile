package dev.opencode.mobile.domain.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SideQuestionTest {
    @Test
    fun promptPutsTheTuiInstructionBeforeTheTrimmedQuestion() {
        assertEquals(
            "The user is asking a quick side question about the conversation so far. " +
                "Answer directly and concisely in markdown from what you already know. " +
                "Do not call any tools and do not take any actions.\n\nWhat changed?",
            SideQuestion.prompt("  What changed?\n"),
        )
    }

    @Test
    fun questionInDraftReadsALeadingBtwCommand() {
        assertEquals("why?", SideQuestion.questionInDraft("/btw why?"))
        assertEquals("why?", SideQuestion.questionInDraft("  /btw   why? "))
        assertEquals("line one\nline two", SideQuestion.questionInDraft("/btw line one\nline two"))
        assertEquals("", SideQuestion.questionInDraft("/btw"))
        assertEquals("", SideQuestion.questionInDraft("/btw "))
    }

    @Test
    fun questionInDraftIgnoresOtherText() {
        assertNull(SideQuestion.questionInDraft("/btwx why"))
        assertNull(SideQuestion.questionInDraft("ask /btw why"))
        assertNull(SideQuestion.questionInDraft("/build"))
        assertNull(SideQuestion.questionInDraft(""))
    }

    @Test
    fun draftAfterCommandKeepsTheRestAsTyped() {
        assertEquals("", SideQuestion.draftAfterCommand("/btw "))
        assertEquals("Why ", SideQuestion.draftAfterCommand(" /btw  Why "))
        assertNull(SideQuestion.draftAfterCommand("/btw"))
        assertNull(SideQuestion.draftAfterCommand("Why /btw "))
    }

    @Test
    fun entersModeOnlyOnceWhitespaceFollowsTheCommand() {
        assertFalse(SideQuestion.entersMode("/bt"))
        assertFalse(SideQuestion.entersMode("/btw"))
        assertTrue(SideQuestion.entersMode("/btw "))
        assertTrue(SideQuestion.entersMode("/btw what changed?"))
        assertFalse(SideQuestion.entersMode("/btwx "))
        assertFalse(SideQuestion.entersMode("hi /btw "))
    }
}
