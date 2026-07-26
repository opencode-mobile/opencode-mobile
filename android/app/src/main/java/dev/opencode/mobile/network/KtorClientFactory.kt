package dev.opencode.mobile.network

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BasicAuthCredentials
import io.ktor.client.plugins.auth.providers.basic
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json

object KtorClientFactory {
    fun create(
        connection: ServerConnection,
        json: Json,
        contentNegotiation: Boolean = true,
        /**
         * SSE streams stay open for long quiet gaps (heartbeats can be sparse). OkHttp's default
         * 10s read timeout kills `/global/event` and forces a reconnect/fullReload loop — match
         * iOS infinite request/resource timeouts for the SSE client only.
         */
        sse: Boolean = false,
    ): HttpClient =
        HttpClient(OkHttp) {
            if (sse) {
                engine {
                    config {
                        // 0 = infinite. Keep a normal connect timeout so bad hosts still fail fast.
                        connectTimeout(10, TimeUnit.SECONDS)
                        readTimeout(0, TimeUnit.MILLISECONDS)
                        writeTimeout(0, TimeUnit.MILLISECONDS)
                        callTimeout(0, TimeUnit.MILLISECONDS)
                    }
                }
                // Disable Ktor's request/socket ceilings so they cannot reintroduce a 10s kill
                // after OkHttp timeouts are cleared. Connect still fails fast via OkHttp above.
                install(HttpTimeout) {
                    requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                    socketTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                    connectTimeoutMillis = 10_000
                }
            }
            if (contentNegotiation) {
                install(ContentNegotiation) { json(json) }
            }
            install(Logging) {
                logger = AndroidApiLogger
                level = LogLevel.INFO
                sanitizeHeader { header -> header == HttpHeaders.Authorization }
            }
            defaultRequest { url(connection.apiBaseUrl) }
            val password = connection.password
            if (!password.isNullOrEmpty()) {
                install(Auth) {
                    basic {
                        credentials {
                            BasicAuthCredentials(username = connection.username, password = password)
                        }
                        sendWithoutRequest { true }
                    }
                }
            }
        }

    private object AndroidApiLogger : Logger {
        override fun log(message: String) {
            runCatching { Log.i(TAG, message) }
        }
    }

    private const val TAG = "OpencodeApi"
}
