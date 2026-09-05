package dev.opencode.mobile.ui.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TerminalWebPageTest {
    @Test
    fun androidHostPinsViewportAndKeepsImeTextareaOnscreen() {
        val html = terminalWebIndexHtml()
        assertTrue(html.contains("html.android"))
        assertTrue(html.contains("position: fixed"))
        assertTrue(html.contains("left: 0 !important"))
        assertTrue(html.contains("if (window.AndroidTerminal) document.documentElement.classList.add(\"android\")"))
        assertTrue(html.contains("window.visualViewport && !window.AndroidTerminal"))
    }

    @Test
    fun parseTerminalBackgroundColorReadsRgbHex() {
        assertEquals(0xFF1A1A1A.toInt(), parseTerminalBackgroundColor("#1A1A1A"))
        assertEquals(0xFF000000.toInt(), parseTerminalBackgroundColor("not-a-color"))
    }

    private fun terminalWebIndexHtml(): String {
        val candidates =
            listOf(
                File("src/main/assets/terminal/index.html"),
                File("android/app/src/main/assets/terminal/index.html"),
            )
        val file = candidates.firstOrNull { it.isFile } ?: error("terminal index.html not found")
        return file.readText()
    }
}
