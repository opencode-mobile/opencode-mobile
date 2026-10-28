package dev.opencode.mobile.domain.command

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerShellCommandTest {
    @Test
    fun leadingBang_isShellModeAndRunsTheRest() {
        assertTrue(ComposerShellCommand.isShellMode("!git status"))
        assertEquals("git status", ComposerShellCommand.command("!git status"))
    }

    @Test
    fun bareBang_isShellModeWithNoCommand() {
        assertTrue(ComposerShellCommand.isShellMode("!"))
        assertNull(ComposerShellCommand.command("!"))
    }

    @Test
    fun bangFollowedOnlyByWhitespace_hasNoCommand() {
        assertTrue(ComposerShellCommand.isShellMode("!  \n "))
        assertNull(ComposerShellCommand.command("!  \n "))
    }

    @Test
    fun spaceAfterBang_isTrimmedFromTheCommand() {
        assertEquals("ls -la", ComposerShellCommand.command("!  ls -la  "))
    }

    @Test
    fun leadingWhitespaceBeforeBang_staysAPrompt() {
        assertFalse(ComposerShellCommand.isShellMode(" !ls"))
        assertFalse(ComposerShellCommand.isShellMode("\n!ls"))
        assertNull(ComposerShellCommand.command(" !ls"))
    }

    @Test
    fun bangLaterInTheText_staysAPrompt() {
        assertFalse(ComposerShellCommand.isShellMode("run !ls"))
        assertNull(ComposerShellCommand.command("Hello!"))
    }

    @Test
    fun emptyDraft_isNotShellMode() {
        assertFalse(ComposerShellCommand.isShellMode(""))
        assertNull(ComposerShellCommand.command(""))
    }

    @Test
    fun multiLineCommand_keepsInnerNewlines() {
        assertEquals("cd app\nls", ComposerShellCommand.command("!cd app\nls\n"))
    }

    @Test
    fun doubleBang_runsTheSecondBangAsTheCommand() {
        assertEquals("!", ComposerShellCommand.command("!!"))
    }

    @Test
    fun exitShellMode_dropsOnlyThePrefix() {
        assertEquals("git status", ComposerShellCommand.exitShellMode("!git status"))
        assertEquals("", ComposerShellCommand.exitShellMode("!"))
        assertEquals("plain", ComposerShellCommand.exitShellMode("plain"))
    }
}
