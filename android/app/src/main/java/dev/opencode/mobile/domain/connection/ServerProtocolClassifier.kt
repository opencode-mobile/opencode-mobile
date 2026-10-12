package dev.opencode.mobile.domain.connection

import dev.opencode.mobile.data.remote.dto.HealthDto

/**
 * Pure protocol classification from health payloads (desktop-compatible).
 *
 * Returns null when no response is usable — callers treat that as
 * unreachable for connection probe instead of defaulting to v2.
 */
object ServerProtocolClassifier {
    fun classify(
        globalHealth: HealthDto?,
        apiInfo: HealthDto? = null,
        apiHealth: HealthDto? = null,
    ): ServerProtocol? {
        if (globalHealth?.healthy == true) return ServerProtocol.V1
        if (v2Health(apiInfo, apiHealth) != null) return ServerProtocol.V2
        return null
    }

    /**
     * v2 payload used for the unhealthy check. Prefers a usable `/api/info`
     * body, then a usable `/api/health` body, then either unhealthy body.
     */
    fun v2Health(
        apiInfo: HealthDto?,
        apiHealth: HealthDto?,
    ): HealthDto? {
        val candidates = listOfNotNull(apiInfo, apiHealth)
        return candidates.firstOrNull { it.pid != null && it.healthy != false }
            ?: candidates.firstOrNull { it.pid != null }
    }
}
