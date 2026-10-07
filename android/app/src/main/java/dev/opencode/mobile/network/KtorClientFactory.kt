package dev.opencode.mobile.network

import dev.opencode.mobile.domain.diagnostic.DiagnosticLog
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogArea
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
import kotlinx.serialization.json.Json
import okhttp3.Dispatcher
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

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
        probe: Boolean = false,
        allowRedirects: Boolean = true,
    ): HttpClient {
        HttpEngineShutdownGuard.install()
        return HttpClient(OkHttp) {
            followRedirects = allowRedirects
            engine {
                config {
                    // Keep connect fail-fast. Read/call stay open so Ktor HttpTimeout (and
                    // per-request overrides) can bound normal calls without OkHttp's 10s default
                    // cutting long ops like POST …/summarize mid-compaction.
                    connectTimeout(if (probe) 3 else if (sse) 5 else 10, TimeUnit.SECONDS)
                    readTimeout(0, TimeUnit.MILLISECONDS)
                    writeTimeout(if (probe) 60 else 0, TimeUnit.SECONDS)
                    callTimeout(0, TimeUnit.MILLISECONDS)
                    dispatcher(nonAbortingDispatcher())
                }
            }
            install(HttpTimeout) {
                connectTimeoutMillis = if (probe) 3_000 else if (sse) 5_000 else 10_000
                if (sse) {
                    requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                    socketTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                } else if (probe) {
                    requestTimeoutMillis = 5_000
                    socketTimeoutMillis = 5_000
                } else {
                    // Default REST ceiling; long endpoints override per request.
                    requestTimeoutMillis = 60_000
                    socketTimeoutMillis = 60_000
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
    }

    /**
     * OkHttp's default AbortPolicy turns [HttpClient.close] into
     * [java.util.concurrent.RejectedExecutionException] on the next enqueue.
     * Drop those tasks so a rebuild cannot kill the process.
     */
    private fun nonAbortingDispatcher(): Dispatcher {
        val executor =
            ThreadPoolExecutor(
                0,
                Int.MAX_VALUE,
                60L,
                TimeUnit.SECONDS,
                SynchronousQueue(),
                { runnable -> Thread(runnable, "OkHttp Dispatcher").apply { isDaemon = true } },
                ThreadPoolExecutor.DiscardPolicy(),
            )
        return Dispatcher(executor)
    }

    private object AndroidApiLogger : Logger {
        override fun log(message: String) {
            DiagnosticLog.debug(DiagnosticLogArea.Connection, message)
        }
    }
}
