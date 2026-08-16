package dev.opencode.mobile.domain.connection

import org.junit.Assert.assertEquals
import org.junit.Test

class ServerProtocolMatrixTest {
    @Test
    fun capabilitiesAndLastTurnAvailabilityMatchProtocol() {
        val rows =
            listOf(
                Row(ServerProtocol.V1, supportsSessionMessageDiff = true),
                Row(ServerProtocol.V2, supportsSessionMessageDiff = false),
            )
        for (row in rows) {
            assertEquals(
                row.supportsSessionMessageDiff,
                ServerCapabilities.forProtocol(row.protocol).supportsSessionMessageDiff,
            )
        }
    }

    private data class Row(
        val protocol: ServerProtocol,
        val supportsSessionMessageDiff: Boolean,
    )
}
