package dev.opencode.mobile.domain.connection

import dev.opencode.mobile.network.KtorClientFactory
import dev.opencode.mobile.network.ServerConnection
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.encodeURLPath
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException

fun interface PairingCodeRedeeming {
    suspend fun redeem(
        baseUrl: String,
        code: String,
    ): String
}

sealed class PairingCodeRedeemException : Exception() {
    class Expired : PairingCodeRedeemException()

    class Unreachable(
        override val cause: Throwable? = null,
    ) : PairingCodeRedeemException()
}

class PairingCodeRedeemService(
    private val json: Json,
    private val clientFactory: (ServerConnection) -> HttpClient = { connection ->
        KtorClientFactory.create(
            connection,
            json,
            contentNegotiation = false,
            probe = true,
            allowRedirects = false,
        )
    },
) : PairingCodeRedeeming {
    @Serializable
    private data class RedeemedToken(
        val token: String? = null,
    )

    override suspend fun redeem(
        baseUrl: String,
        code: String,
    ): String {
        val origin = ConnectionProfileIdentity.normalizeBaseUrl(baseUrl).trimEnd('/')
        val connection =
            ServerConnection(
                baseUrl = "$origin/",
                username = ConnectionProfileIdentity.normalizeUsername(""),
                password = null,
            )
        val client = clientFactory(connection)
        try {
            val response =
                client.get("$origin/auth/connect/${code.encodeURLPath()}") {
                    // text/html makes this route set a cookie and redirect instead of returning the token.
                    headers[HttpHeaders.Accept] = "application/json"
                }
            when (response.status.value) {
                401, 403 -> throw PairingCodeRedeemException.Expired()
                200 -> {
                    val token =
                        runCatching { json.decodeFromString<RedeemedToken>(response.bodyAsText()).token }
                            .getOrNull()
                    if (token.isNullOrEmpty()) {
                        throw PairingCodeRedeemException.Unreachable(
                            IllegalStateException("Pairing response did not include a token"),
                        )
                    }
                    return token
                }
                else ->
                    throw PairingCodeRedeemException.Unreachable(
                        IllegalStateException("Pairing redeem failed with HTTP ${response.status.value}"),
                    )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: PairingCodeRedeemException) {
            throw error
        } catch (error: Throwable) {
            throw PairingCodeRedeemException.Unreachable(error)
        } finally {
            client.close()
        }
    }
}
