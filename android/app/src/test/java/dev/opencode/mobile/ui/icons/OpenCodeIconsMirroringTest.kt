package dev.opencode.mobile.ui.icons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class OpenCodeIconsMirroringTest {
    @Test
    fun directionalIcons_autoMirrorInRightToLeft() {
        val mirrored =
            listOf(
                OpenCodeIcons.ArrowBack,
                OpenCodeIcons.ArrowForward,
                OpenCodeIcons.ChevronLeft,
                OpenCodeIcons.ChevronRight,
                OpenCodeIcons.Sidebar,
                OpenCodeIcons.SubdirectoryArrowRight,
                OpenCodeIcons.Undo,
                OpenCodeIcons.Redo,
            )
        assertEquals(List(mirrored.size) { true }, mirrored.map { it.autoMirror })
    }

    @Test
    fun verticalMediaAndSymmetricIcons_doNotMirror() {
        listOf(
            OpenCodeIcons.KeyboardArrowDown,
            OpenCodeIcons.ArrowUpward,
            OpenCodeIcons.ArrowDownward,
            OpenCodeIcons.Play,
            OpenCodeIcons.SkipNext,
            OpenCodeIcons.Restart,
            OpenCodeIcons.Close,
        ).forEach { assertFalse(it.name, it.autoMirror) }
    }
}
