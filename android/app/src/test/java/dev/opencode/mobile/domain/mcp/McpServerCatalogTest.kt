package dev.opencode.mobile.domain.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class McpServerCatalogTest {
    @Test
    fun summary_empty_hasNoWorstStatus() {
        val summary = McpServerCatalog.summary(emptyList())
        assertEquals(0, summary.total)
        assertNull(summary.worst)
    }

    @Test
    fun summary_failedOutranksNeedsSignInAndCountsAttention() {
        val summary =
            McpServerCatalog.summary(
                listOf(
                    McpServer("a", McpServerStatus.Connected),
                    McpServer("b", McpServerStatus.NeedsSignIn),
                    McpServer("c", McpServerStatus.Failed, "boom"),
                    McpServer("d", McpServerStatus.Disabled),
                ),
            )
        assertEquals(McpServerStatus.Failed, summary.worst)
        assertEquals(2, summary.attention)
        assertEquals(1, summary.connected)
        assertEquals("1 of 4 connected", summary.headline)
    }

    @Test
    fun summary_allConnected_isHealthy() {
        val summary =
            McpServerCatalog.summary(
                listOf(McpServer("a", McpServerStatus.Connected), McpServer("b", McpServerStatus.Connecting)),
            )
        assertEquals(McpServerStatus.Connecting, summary.worst)
        assertEquals(0, summary.attention)
    }
}
