package dev.opencode.mobile.ui.sessionchat

import org.junit.Assert.assertEquals
import org.junit.Test

class PresentationBlockIdTest {
    @Test
    fun direct_prefixesPartId() {
        assertEquals("p:prt-1", PresentationBlockId.direct("prt-1"))
    }

    @Test
    fun mergedText_singlePartUsesDirectEncoding() {
        assertEquals("p:prt-1", PresentationBlockId.mergedText(listOf("prt-1")))
    }

    @Test
    fun mergedText_joinsOrderedPartIds() {
        val sep = "\u001f"
        assertEquals(
            "t:prt-1${sep}prt-2${sep}prt-3",
            PresentationBlockId.mergedText(listOf("prt-1", "prt-2", "prt-3")),
        )
    }

    @Test
    fun toolGroup_joinsOrderedMemberIds() {
        val sep = "\u001f"
        assertEquals(
            "g:prt-a${sep}prt-b",
            PresentationBlockId.toolGroup(listOf("prt-a", "prt-b")),
        )
    }

    @Test
    fun messageFallback_prefixesMessageId() {
        assertEquals("m:msg-1", PresentationBlockId.messageFallback("msg-1"))
    }
}
