package dev.opencode.mobile.domain.connection

import dev.opencode.mobile.network.ServerConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerProtocolMatrixTest {
    @Test
    fun capabilitiesAndLastTurnAvailabilityMatchProtocol() {
        val rows =
            listOf(
                Row(
                    ServerProtocol.V1,
                    supportsSessionMessageDiff = true,
                    supportsSessionArchive = true,
                    supportsFollowUpDelivery = false,
                ),
                Row(
                    ServerProtocol.V2,
                    supportsSessionMessageDiff = false,
                    supportsSessionArchive = false,
                    supportsFollowUpDelivery = true,
                ),
            )
        for (row in rows) {
            assertEquals(
                row.supportsSessionMessageDiff,
                ServerCapabilities.forProtocol(row.protocol).supportsSessionMessageDiff,
            )
            assertEquals(
                row.supportsSessionArchive,
                ServerCapabilities.forProtocol(row.protocol).supportsSessionArchive,
            )
            assertEquals(
                row.supportsFollowUpDelivery,
                ServerCapabilities.forProtocol(row.protocol).supportsFollowUpDelivery,
            )
        }
    }

    @Test
    fun t3CapabilitiesIgnoreTheV1ProtocolFallback() {
        val t3 = ServerCapabilities.forEngine(ConnectionEngine.T3, ServerProtocol.V1)
        assertFalse(t3.supportsSessionMessageDiff)
        assertTrue(t3.supportsSessionArchive)
        assertFalse(t3.supportsFollowUpDelivery)
        assertFalse(t3.supportsTerminal)
        assertFalse(t3.supportsRevert)
        assertFalse(t3.supportsMentions)
        assertFalse(t3.supportsSlashCommands)
        assertFalse(t3.supportsDocumentAttachments)
        assertEquals(
            ServerCapabilities.t3,
            ServerConnection(baseUrl = "http://t3.test", engine = ConnectionEngine.T3).capabilities,
        )
        assertTrue(ServerCapabilities.forProtocol(ServerProtocol.V1).supportsTerminal)
        assertTrue(ServerCapabilities.forProtocol(ServerProtocol.V2).supportsDocumentAttachments)
        assertTrue(ServerCapabilities.forEngine(ConnectionEngine.OPENCODE, null).supportsMentions)
    }

    private data class Row(
        val protocol: ServerProtocol,
        val supportsSessionMessageDiff: Boolean,
        val supportsSessionArchive: Boolean,
        val supportsFollowUpDelivery: Boolean,
    )
}
