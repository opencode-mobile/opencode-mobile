package dev.opencode.mobile.domain.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalWsFrameParserTest {
    @Test
    fun parseText_isOutput() {
        assertEquals(TerminalWsFrame.Output("hello"), TerminalWsFrameParser.parseText("hello"))
    }

    @Test
    fun parseBinary_controlCursor_isCursor() {
        val bytes = byteArrayOf(0) + """{"cursor":12}""".encodeToByteArray()

        assertEquals(TerminalWsFrame.Cursor("12"), TerminalWsFrameParser.parseBinary(bytes))
    }

    @Test
    fun parseBinary_otherPayload_isIgnored() {
        assertEquals(TerminalWsFrame.Ignored, TerminalWsFrameParser.parseBinary(byteArrayOf(1, 2, 3)))
        assertEquals(TerminalWsFrame.Ignored, TerminalWsFrameParser.parseBinary(byteArrayOf()))
        val negative = byteArrayOf(0) + """{"cursor":-1}""".encodeToByteArray()
        assertEquals(TerminalWsFrame.Ignored, TerminalWsFrameParser.parseBinary(negative))
    }
}
