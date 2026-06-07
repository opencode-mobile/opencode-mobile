package dev.opencode.mobile.network

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
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

object KtorClientFactory {
    fun create(
        connection: ServerConnection,
        json: Json,
        contentNegotiation: Boolean = true,
    ): HttpClient =
        HttpClient(OkHttp) {
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
