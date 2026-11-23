package dev.opencode.mobile.network

import io.ktor.client.HttpClient
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OpencodeHttpClient
    internal constructor(
        private val activeConnectionManager: ActiveConnectionManager,
        private val clientFactory: (ServerConnection) -> HttpClient,
    ) {
        @Inject
        constructor(
            json: Json,
            activeConnectionManager: ActiveConnectionManager,
        ) : this(activeConnectionManager, { connection -> KtorClientFactory.create(connection, json) })

        @Volatile
        private var bound: BoundClient = bindClient(activeConnectionManager.current(), clientFactory)

        @Synchronized
        fun rebuild() {
            bound.client.close()
            bound = bindClient(activeConnectionManager.current(), clientFactory)
        }

        /** Moves to the bound connection, leaving a client that already serves it (and its requests) alone. */
        @Synchronized
        fun rebind() {
            swapIfNeeded(activeConnectionManager.current())
        }

        /**
         * The client of the bound connection, never another's. Inside a connection pin it is the
         * pinned connection's client, and a changed connection throws [ConnectionChangedException].
         */
        fun get(): HttpClient {
            val pinned = activeConnectionManager.pinnedConnection()
            val wanted = pinned ?: activeConnectionManager.current()
            val current = bound
            if (current.key == clientKey(wanted)) return current.client
            return synchronized(this) {
                val live = activeConnectionManager.current()
                if (pinned != null && clientKey(live) != clientKey(pinned)) throw ConnectionChangedException()
                swapIfNeeded(live)
            }
        }

        private fun swapIfNeeded(connection: ServerConnection?): HttpClient {
            val current = bound
            if (current.key == clientKey(connection)) return current.client
            val next = bindClient(connection, clientFactory)
            bound = next
            current.client.close()
            return next.client
        }
    }

/** A client and the connection it was built for. */
private class BoundClient(
    val key: ClientKey?,
    val client: HttpClient,
)

/** What a client carries: its address and credentials. A protocol update keeps the client. */
private data class ClientKey(
    val baseUrl: String,
    val username: String,
    val password: String?,
)

private fun clientKey(connection: ServerConnection?): ClientKey? = connection?.let { ClientKey(it.baseUrl, it.username, it.password) }

private fun bindClient(
    connection: ServerConnection?,
    factory: (ServerConnection) -> HttpClient,
): BoundClient =
    BoundClient(
        key = clientKey(connection),
        client = factory(connection ?: UNBOUND_CONNECTION),
    )

/** Fails fast: nothing listens here, so a request with no bound server cannot reach one. */
private val UNBOUND_CONNECTION = ServerConnection(baseUrl = "http://127.0.0.1:1/", password = null)

@Singleton
class OpencodeSseHttpClient
    internal constructor(
        private val activeConnectionManager: ActiveConnectionManager,
        private val clientFactory: (ServerConnection) -> HttpClient,
    ) {
        @Inject
        constructor(
            json: Json,
            activeConnectionManager: ActiveConnectionManager,
        ) : this(
            activeConnectionManager,
            { connection ->
                KtorClientFactory.create(
                    connection = connection,
                    json = json,
                    contentNegotiation = false,
                    sse = true,
                )
            },
        )

        @Volatile
        private var bound: BoundClient = bindClient(activeConnectionManager.current(), clientFactory)
        private val dials = mutableListOf<Dial>()
        private var retiredBefore: Long = 0L

        @Synchronized
        fun rebuild() {
            val previous = bound
            val open = dials.toList()
            dials.clear()
            bound = bindClient(activeConnectionManager.current(), clientFactory)
            previous.client.close()
            open.forEach { it.client.close() }
        }

        /** Moves to the bound connection, leaving a client or dial that already serves it open. */
        @Synchronized
        fun rebind() {
            val connection = activeConnectionManager.current()
            val live = clientKey(connection)
            val stale = dials.filter { it.key != live }
            dials.removeAll(stale)
            val previous = bound
            if (previous.key != live) {
                bound = bindClient(connection, clientFactory)
                previous.client.close()
            }
            stale.forEach { it.client.close() }
        }

        @Synchronized
        fun openDial(token: Long): HttpClient {
            val connection = activeConnectionManager.current()
            val client = clientFactory(connection ?: UNBOUND_CONNECTION)
            if (token < retiredBefore) {
                client.close()
                return client
            }
            dials.add(Dial(token, clientKey(connection), client))
            return client
        }

        @Synchronized
        fun closeDial(client: HttpClient) {
            dials.removeAll { it.client === client }
            client.close()
        }

        @Synchronized
        fun retire(olderThan: Long) {
            retiredBefore = maxOf(retiredBefore, olderThan)
            val stale = dials.filter { it.token < olderThan }
            dials.removeAll { it.token < olderThan }
            stale.forEach { it.client.close() }
        }

        /** The client of the bound connection, never another's. */
        fun get(): HttpClient {
            val connection = activeConnectionManager.current()
            val current = bound
            if (current.key == clientKey(connection)) return current.client
            return synchronized(this) {
                val live = activeConnectionManager.current()
                val latest = bound
                if (latest.key == clientKey(live)) {
                    latest.client
                } else {
                    bound = bindClient(live, clientFactory)
                    latest.client.close()
                    bound.client
                }
            }
        }

        private data class Dial(
            val token: Long,
            val key: ClientKey?,
            val client: HttpClient,
        )
    }
