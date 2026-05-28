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
            return KtorClientFactory.create(resolved, json, contentNegotiation = false)
        }
    }
