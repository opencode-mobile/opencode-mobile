package dev.opencode.mobile.domain.connection

import org.junit.Assert.assertEquals
import org.junit.Test

class ServerProtocolDisplayTest {
    @Test
    fun v1Labels() {
        assertEquals("v1", ServerProtocol.V1.shortLabel)
        assertEquals("API v1", ServerProtocol.V1.apiCaption)
        assertEquals("Server reachable · API v1", ServerProtocol.V1.reachableMessage)
    }

    @Test
    fun v2Labels() {
        assertEquals("v2", ServerProtocol.V2.shortLabel)
        assertEquals("API v2", ServerProtocol.V2.apiCaption)
        assertEquals("Server reachable · API v2", ServerProtocol.V2.reachableMessage)
    }
}
