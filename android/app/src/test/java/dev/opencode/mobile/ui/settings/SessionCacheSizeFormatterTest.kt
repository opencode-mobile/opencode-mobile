package dev.opencode.mobile.ui.settings

import dev.opencode.mobile.R
import dev.opencode.mobile.ui.text.UiText
import dev.opencode.mobile.ui.text.english
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionCacheSizeFormatterTest {
    @Test
    fun emptyForZeroOrNegative() {
        assertEquals(UiText.resource(R.string.settings_session_cache_empty), SessionCacheSizeFormatter.format(0))
        assertEquals("Empty", SessionCacheSizeFormatter.format(0).english())
        assertEquals("Empty", SessionCacheSizeFormatter.format(-1).english())
    }

    @Test
    fun formatsBytesAndKilobytes() {
        assertEquals("512 B", SessionCacheSizeFormatter.format(512).english())
        assertEquals("1 KB", SessionCacheSizeFormatter.format(1024).english())
        assertEquals("2 KB", SessionCacheSizeFormatter.format(2048).english())
    }

    @Test
    fun formatsMegabytesAndGigabytes() {
        assertEquals("1.5 MB", SessionCacheSizeFormatter.format((1.5 * 1024 * 1024).toLong()).english())
        assertEquals("1.0 GB", SessionCacheSizeFormatter.format(1024L * 1024L * 1024L).english())
    }
}
