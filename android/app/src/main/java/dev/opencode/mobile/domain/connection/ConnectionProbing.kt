package dev.opencode.mobile.domain.connection

import dev.opencode.mobile.network.ServerConnection

fun interface ConnectionProbing {
    suspend fun probe(connection: ServerConnection): ServerProtocol

    /** [probe] plus the reported server version. Probes that only know the protocol omit it. */
    suspend fun probeServer(connection: ServerConnection): DetectedServer = DetectedServer(probe(connection))
}
