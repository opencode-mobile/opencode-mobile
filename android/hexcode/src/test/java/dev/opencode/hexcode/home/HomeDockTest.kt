package dev.opencode.hexcode.home

import org.junit.Assert.assertEquals
import org.junit.Test

/** app-plan.md, Dictation › Locked take: the dock either side of the talk button. */
class HomeDockTest {
    private val attachAndStop = HomeDock(DockButton.Attach, DockButton.Stop)

    @Test
    fun idleAndADraftShowAttachAndStop() {
        // A draft waiting is the idle talk mode with chunks: the dock is the same.
        assertEquals(attachAndStop, HomeDock.of(TalkMode.Idle))
    }

    @Test
    fun holdingKeepsAttachAndStop() {
        assertEquals(attachAndStop, HomeDock.of(TalkMode.Holding))
    }

    @Test
    fun aLockedTakeSwapsInDropAndDone() {
        assertEquals(HomeDock(DockButton.DropTake, DockButton.Done), HomeDock.of(TalkMode.Locked))
    }
}
