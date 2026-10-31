package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.ui.components.OpenCodeActionPillVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HiddenRequestPillTest {
    @Test
    fun collapsedQuestionInChildSession_keepsItsPillUnderTheLockout() {
        // A hidden question no longer claims the composer, so a child session falls back to its lockout.
        val kind = SessionChatComposerKind.resolve(isChildSession = true, hasPermission = false, hasQuestion = false)
        assertEquals(SessionChatComposerKind.ChildLockout, kind)
        assertEquals(
            OpenCodeActionPillVariant.AnswerQuestion,
            hiddenRequestPillVariant(permissionDockHidden = false, questionDockHidden = true),
        )
    }

    @Test
    fun collapsedPermission_showsReviewPermission() {
        assertEquals(
            OpenCodeActionPillVariant.ReviewPermission,
            hiddenRequestPillVariant(permissionDockHidden = true, questionDockHidden = false),
        )
    }

    @Test
    fun nothingHidden_showsNoPill() {
        assertNull(hiddenRequestPillVariant(permissionDockHidden = false, questionDockHidden = false))
    }
}
