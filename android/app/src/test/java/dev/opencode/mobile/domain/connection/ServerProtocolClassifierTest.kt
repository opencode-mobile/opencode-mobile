package dev.opencode.mobile.domain.connection

import dev.opencode.mobile.data.remote.dto.HealthDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerProtocolClassifierTest {
    @Test
    fun prefersLegacyHealthWhenBothGenerationsExist() {
        val protocol =
            ServerProtocolClassifier.classify(
                globalHealth = HealthDto(healthy = true, version = "1.18.4"),
                apiHealth = HealthDto(healthy = true, version = "2.0.0", pid = 123),
            )
        assertEquals(ServerProtocol.V1, protocol)
    }

    @Test
    fun recognizesV2HealthByProcessIdentifier() {
        val protocol =
            ServerProtocolClassifier.classify(
                globalHealth = null,
                apiHealth = HealthDto(healthy = true, version = "2.0.0", pid = 123),
            )
        assertEquals(ServerProtocol.V2, protocol)
    }

    @Test
    fun recognizesV2WhenPidPresentAndHealthyMissing() {
        assertEquals(
            ServerProtocol.V2,
            ServerProtocolClassifier.classify(
                globalHealth = null,
                apiHealth = HealthDto(healthy = null, version = "2.0.0", pid = 123),
            ),
        )
        assertEquals(
            ServerProtocol.V2,
            ServerProtocolClassifier.classify(
                globalHealth = null,
                apiHealth = null,
                apiInfo = HealthDto(healthy = null, version = "2.0.7", pid = 1481731),
            ),
        )
    }

    @Test
    fun recognizesTransitionalV1ApiHealthResponse() {
        val protocol =
            ServerProtocolClassifier.classify(
                globalHealth = null,
                apiHealth = HealthDto(healthy = true),
            )
        assertEquals(ServerProtocol.V1, protocol)
    }

    @Test
    fun returnsNullWhenNeitherHealthEndpointIsUsable() {
        assertNull(
            ServerProtocolClassifier.classify(
                globalHealth = null,
                apiHealth = null,
            ),
        )
        assertNull(
            ServerProtocolClassifier.classify(
                globalHealth = HealthDto(healthy = false),
                apiHealth = HealthDto(healthy = false),
            ),
        )
    }

    @Test
    fun capabilitiesHideSessionMessageDiffOnV2() {
        assertEquals(
            true,
            ServerCapabilities.forProtocol(ServerProtocol.V1).supportsSessionMessageDiff,
        )
        assertEquals(
            false,
            ServerCapabilities.forProtocol(ServerProtocol.V2).supportsSessionMessageDiff,
        )
        assertEquals(
            true,
            ServerCapabilities.forProtocol(ServerProtocol.V1).supportsSessionArchive,
        )
        assertEquals(
            false,
            ServerCapabilities.forProtocol(ServerProtocol.V2).supportsSessionArchive,
        )
    }
}
