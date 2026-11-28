package dev.opencode.mobile.domain.session

import dev.opencode.mobile.R
import dev.opencode.mobile.domain.text.UiText
import dev.opencode.mobile.ui.text.english
import org.junit.Assert.assertEquals
import org.junit.Test

class OptimisticSessionCreateTest {
    @Test
    fun displayTitleKeysOnlyThePlaceholder() {
        val placeholder = OptimisticSessionCreate.displayTitle(OptimisticSessionCreate.PLACEHOLDER_TITLE)

        assertEquals(UiText.resource(R.string.session_new_title), placeholder)
        assertEquals("New Session", placeholder.english())
        assertEquals(UiText.verbatim("Fix the login bug"), OptimisticSessionCreate.displayTitle("Fix the login bug"))
    }
}
