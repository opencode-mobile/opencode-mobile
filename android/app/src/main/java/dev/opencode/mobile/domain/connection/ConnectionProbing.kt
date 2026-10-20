package dev.opencode.mobile.domain.connection

import dev.opencode.mobile.network.ServerConnection

fun interface ConnectionProbing {
    suspend fun probe(connection: ServerConnection): ServerProtocol

    /** [probe] plus the reported server version. Probes that only know the protocol omit it. */
    suspend fun probeServer(connection: ServerConnection): DetectedServer = DetectedServer(probe(connection))

    /**
     * Whether a v2 server can archive sessions (opencode 2.1.0+). Throws when the
     * server's API contract could not be read, so the caller can retry later.
     */
    suspend fun probeV2SessionArchive(connection: ServerConnection): Boolean = false
}
