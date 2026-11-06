package dev.opencode.hexcode.home

import org.junit.Assert.assertEquals
import org.junit.Test

/** app-plan.md, Formatting: code stays whole on a line, as the reference render does. */
class SummaryLookTest {
    @Test
    fun codeGetsAWordJoinerAfterEachPunctuationMark() {
        assertEquals("origin/\u2060ios-\u2060pwa-\u2060shell", unbroken("origin/ios-pwa-shell"))
        assertEquals("Info.\u2060plist", unbroken("Info.plist"))
    }

    @Test
    fun spacesInCodeStillBreak() {
        assertEquals("bun test auth", unbroken("bun test auth"))
    }
}
