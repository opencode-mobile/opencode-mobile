package dev.opencode.mobile.domain.connection

import dev.opencode.mobile.network.ServerConnection

fun interface ConnectionProbing {
    suspend fun probe(connection: ServerConnection): ServerProtocol
}
