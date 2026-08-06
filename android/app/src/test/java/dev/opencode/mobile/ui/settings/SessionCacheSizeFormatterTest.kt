package dev.opencode.mobile.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionCacheSizeFormatterTest {
    @Test
    fun emptyForZeroOrNegative() {
        assertEquals("Empty", SessionCacheSizeFormatter.format(0))
        assertEquals("Empty", SessionCacheSizeFormatter.format(-1))
    }

    @Test
    fun formatsBytesAndKilobytes() {
        assertEquals("512 B", SessionCacheSizeFormatter.format(512))
        assertEquals("1 KB", SessionCacheSizeFormatter.format(1024))
        assertEquals("2 KB", SessionCacheSizeFormatter.format(2048))
    }

    @Test
    fun formatsMegabytesAndGigabytes() {
        assertEquals("1.5 MB", SessionCacheSizeFormatter.format((1.5 * 1024 * 1024).toLong()))
        assertEquals("1.0 GB", SessionCacheSizeFormatter.format(1024L * 1024L * 1024L))
    }
}
