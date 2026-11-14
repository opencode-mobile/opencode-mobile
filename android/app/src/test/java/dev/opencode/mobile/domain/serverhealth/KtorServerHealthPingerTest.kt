package dev.opencode.mobile.domain.serverhealth

import dev.opencode.mobile.data.remote.SseAttemptOutcome
import dev.opencode.mobile.domain.connection.ServerProtocol
import dev.opencode.mobile.network.ServerConnection
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class KtorServerHealthPingerTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val connection = ServerConnection(baseUrl = "http://192.168.1.2:4096", password = "test-password")
    private val requestedPaths = mutableListOf<String>()

    private fun pinger(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): KtorServerHealthPinger =
        KtorServerHealthPinger(json) { server ->
            HttpClient(
                MockEngine { request ->
                    requestedPaths += request.url.encodedPath
                    handler(request)
                },
            ) {
                install(ContentNegotiation) { json(json) }
                defaultRequest { url(server.apiBaseUrl) }
            }
        }

    private fun MockRequestHandleScope.jsonBody(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) = respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun ping(
        protocol: ServerProtocol = ServerProtocol.V1,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): ServerHealthPingResult = runBlocking { pinger(handler).ping(connection, protocol) }

    @Test
    fun healthyResponse_isASuccessWithPidAndVersion() {
        val result = ping { jsonBody("""{"healthy":true,"version":"1.2.3","pid":4321}""") }

        val success = result as ServerHealthPingResult.Success
        assertEquals(4321, success.pid)
        assertEquals("1.2.3", success.version)
        assertEquals(listOf("/global/health"), requestedPaths)
    }

    @Test
    fun v2_pingsApiInfo() {
        ping(ServerProtocol.V2) { jsonBody("""{"healthy":true}""") }

        assertEquals(listOf("/api/info"), requestedPaths)
    }

    @Test
    fun statusCodes_areClassified() {
        assertEquals(failure(ServerHealthFailureCategory.Auth), ping { jsonBody("{}", HttpStatusCode.Unauthorized) })
        assertEquals(failure(ServerHealthFailureCategory.Auth), ping { jsonBody("{}", HttpStatusCode.Forbidden) })
        assertEquals(failure(ServerHealthFailureCategory.Server), ping { jsonBody("{}", HttpStatusCode.ServiceUnavailable) })
        assertEquals(failure(ServerHealthFailureCategory.Other), ping { jsonBody("{}", HttpStatusCode.NotFound) })
    }

    @Test
    fun unhealthyBody_isAServerFailure() {
        assertEquals(failure(ServerHealthFailureCategory.Server), ping { jsonBody("""{"healthy":false}""") })
    }

    @Test
    fun undecodableBody_isOther() {
        assertEquals(failure(ServerHealthFailureCategory.Other), ping { jsonBody("not json") })
    }

    @Test
    fun transportErrors_areNetworkOrTimeout() {
        assertEquals(failure(ServerHealthFailureCategory.Network), ping { throw IOException("connection refused") })
        assertEquals(failure(ServerHealthFailureCategory.Timeout), ping { throw SocketTimeoutException("read timed out") })
    }

    @Test
    fun sseOutcomes_areClassified() {
        assertNull(ServerHealthFailureClassifier.classify(SseAttemptOutcome.Cancelled))
        assertEquals(ServerHealthFailureCategory.Server, ServerHealthFailureClassifier.classify(SseAttemptOutcome.Ended))
        assertEquals(ServerHealthFailureCategory.Auth, ServerHealthFailureClassifier.classify(SseAttemptOutcome.Http(401)))
        assertEquals(ServerHealthFailureCategory.Server, ServerHealthFailureClassifier.classify(SseAttemptOutcome.Http(502)))
        assertEquals(ServerHealthFailureCategory.Other, ServerHealthFailureClassifier.classify(SseAttemptOutcome.Http(404)))
        assertEquals(
            ServerHealthFailureCategory.Timeout,
            ServerHealthFailureClassifier.classify(SseAttemptOutcome.Transport(IOException("wrapped", SocketTimeoutException()))),
        )
        assertEquals(ServerHealthFailureCategory.Network, ServerHealthFailureClassifier.classify(SseAttemptOutcome.Transport(null)))
    }

    private fun failure(category: ServerHealthFailureCategory) = ServerHealthPingResult.Failure(category)
}
