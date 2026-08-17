package dev.opencode.mobile.domain.connection

import org.junit.Assert.assertEquals
import org.junit.Test

class ServerProtocolMatrixTest {
    @Test
    fun capabilitiesAndLastTurnAvailabilityMatchProtocol() {
        val rows =
            listOf(
                Row(ServerProtocol.V1, supportsSessionMessageDiff = true, supportsSessionArchive = true),
                Row(ServerProtocol.V2, supportsSessionMessageDiff = false, supportsSessionArchive = false),
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
        }
    }

    private data class Row(
        val protocol: ServerProtocol,
        val supportsSessionMessageDiff: Boolean,
        val supportsSessionArchive: Boolean,
    )
}
