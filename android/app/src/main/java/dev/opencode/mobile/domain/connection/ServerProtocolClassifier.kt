package dev.opencode.mobile.domain.connection

import dev.opencode.mobile.data.remote.dto.HealthDto

/**
 * Pure protocol classification from health payloads (desktop-compatible).
 *
 * Returns null when neither response is usable — callers treat that as
 * unreachable for connection probe instead of defaulting to v2.
 */
object ServerProtocolClassifier {
    fun classify(
        globalHealth: HealthDto?,
        apiHealth: HealthDto?,
    ): ServerProtocol? {
        if (globalHealth?.healthy == true) return ServerProtocol.V1
        if (apiHealth?.pid != null) return ServerProtocol.V2
        if (apiHealth?.healthy == true) return ServerProtocol.V1
        return null
    }
}
