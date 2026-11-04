package dev.opencode.mobile.network

import io.ktor.client.HttpClient
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OpencodeHttpClient
    @Inject
    constructor(
        private val json: Json,
        private val activeConnectionManager: ActiveConnectionManager,
    ) {
        @Volatile
        private var delegate: HttpClient = createClient(activeConnectionManager.current())

        @Synchronized
        fun rebuild() {
            delegate.close()
            delegate = createClient(activeConnectionManager.current())
        }

        fun get(): HttpClient = delegate

        private fun createClient(connection: ServerConnection?): HttpClient {
            val resolved =
                connection
                    ?: ServerConnection(
                        baseUrl = "http://127.0.0.1:1/",
                        password = null,
                    )
            return KtorClientFactory.create(resolved, json)
        }
    }

@Singleton
class OpencodeSseHttpClient
    @Inject
    constructor(
        private val json: Json,
        private val activeConnectionManager: ActiveConnectionManager,
    ) {
        @Volatile
        private var delegate: HttpClient = createClient(activeConnectionManager.current())
        private val dials = mutableListOf<Dial>()
        private var retiredBefore: Long = 0L

        @Synchronized
        fun rebuild() {
            val previous = delegate
            val open = dials.toList()
            dials.clear()
            delegate = createClient(activeConnectionManager.current())
            previous.close()
            open.forEach { it.client.close() }
        }

        @Synchronized
        fun openDial(token: Long): HttpClient {
            val client = createClient(activeConnectionManager.current())
            if (token < retiredBefore) {
                client.close()
                return client
            }
            dials.add(Dial(token, client))
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

        fun get(): HttpClient = delegate

        private fun createClient(connection: ServerConnection?): HttpClient {
            val resolved =
                connection
                    ?: ServerConnection(
                        baseUrl = "http://127.0.0.1:1/",
                        password = null,
                    )
            return KtorClientFactory.create(
                connection = resolved,
                json = json,
                contentNegotiation = false,
                sse = true,
            )
        }

        private data class Dial(
            val token: Long,
            val client: HttpClient,
        )
    }
