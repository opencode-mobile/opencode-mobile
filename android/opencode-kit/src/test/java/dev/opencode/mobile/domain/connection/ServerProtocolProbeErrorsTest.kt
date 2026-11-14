package dev.opencode.mobile.domain.connection

import dev.opencode.mobile.data.remote.dto.HealthDto
import dev.opencode.mobile.domain.project.ServerProbeError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerProtocolProbeErrorsTest {
    @Test
    fun preferKeepsAuthOverLaterNotFound() {
        val preferred =
            ServerProtocolProbeErrors.prefer(
                previous = ServerProbeError.Http(401, "unauthorized"),
                next = ServerProbeError.Http(404, "missing"),
            )
        assertTrue(preferred is ServerProbeError.Http)
        assertEquals(401, (preferred as ServerProbeError.Http).statusCode)
    }

    @Test
    fun preferPromotesAuthOverEarlierNotFound() {
        val preferred =
            ServerProtocolProbeErrors.prefer(
                previous = ServerProbeError.Http(404, "missing"),
                next = ServerProbeError.Http(401, "unauthorized"),
            )
        assertEquals(401, (preferred as ServerProbeError.Http).statusCode)
    }

    @Test
    fun unresolvedUsesUnhealthyBodyWhenFetchesDidNotThrow() {
        val error =
            ServerProtocolProbeErrors.unresolved(
                globalHealth = HealthDto(healthy = false, version = "9.9.9"),
                fetchError = null,
            )
        assertTrue(error is ServerProbeError.Unhealthy)
        assertEquals("9.9.9", (error as ServerProbeError.Unhealthy).version)
    }

    @Test
    fun preferDoesNotPromoteNotFoundOverDecodeMiss() {
        val preferred =
            ServerProtocolProbeErrors.prefer(
                previous = ServerProbeError.Transport(IllegalStateException("non-json")),
                next = ServerProbeError.Http(404, "Not Found"),
            )
        assertTrue(preferred is ServerProbeError.Transport)
    }
}
