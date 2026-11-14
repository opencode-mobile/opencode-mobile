package dev.opencode.mobile.domain.connection

import org.junit.Assert.assertEquals
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
                    supportsSessionViewed = false,
                ),
                Row(
                    ServerProtocol.V2,
                    supportsSessionMessageDiff = false,
                    supportsSessionArchive = false,
                    supportsFollowUpDelivery = true,
                    supportsSessionViewed = true,
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
            assertEquals(
                row.supportsSessionViewed,
                ServerCapabilities.forProtocol(row.protocol).supportsSessionViewed,
            )
        }
    }

    private data class Row(
        val protocol: ServerProtocol,
        val supportsSessionMessageDiff: Boolean,
        val supportsSessionArchive: Boolean,
        val supportsFollowUpDelivery: Boolean,
        val supportsSessionViewed: Boolean,
    )
}
