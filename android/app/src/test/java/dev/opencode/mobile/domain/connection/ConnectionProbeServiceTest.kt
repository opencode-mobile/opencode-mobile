package dev.opencode.mobile.domain.connection

import dev.opencode.mobile.domain.project.ProjectDomainAdapter
import dev.opencode.mobile.domain.project.ServerProbeError
import dev.opencode.mobile.network.ServerConnection
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ConnectionProbeServiceTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val connection =
        ServerConnection(
            baseUrl = "http://127.0.0.1:4096/",
            username = "opencode",
            password = "secret",
        )

    @Test
    fun probe_detectsV1FromGlobalHealthAndSkipsApiHealth() =
        runTest {
            val paths = mutableListOf<String>()
            val service =
                service { request ->
                    paths += request.url.encodedPath
                    respond(
                        content = """{"healthy":true,"version":"1.18.4"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val protocol = service.probe(connection)

            assertEquals(ServerProtocol.V1, protocol)
            assertEquals(listOf("/global/health"), paths)
        }

    @Test
    fun probe_detectsV2FromApiHealthPidWhenGlobalMissing() =
        runTest {
            val paths = mutableListOf<String>()
            val service =
                service { request ->
                    paths += request.url.encodedPath
                    when (request.url.encodedPath) {
                        "/global/health" ->
                            respond(
                                content = """{}""",
                                status = HttpStatusCode.NotFound,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        "/api/health" ->
                            respond(
                                content = """{"healthy":true,"version":"2.0.0","pid":123}""",
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        else -> error("unexpected ${request.url}")
                    }
                }

            val protocol = service.probe(connection)

            assertEquals(ServerProtocol.V2, protocol)
            assertEquals(listOf("/global/health", "/api/health"), paths)
        }

    @Test
    fun probe_detectsTransitionalV1FromApiHealthWithoutPid() =
        runTest {
            val service =
                service { request ->
                    when (request.url.encodedPath) {
                        "/global/health" ->
                            respond(
                                content = """{}""",
                                status = HttpStatusCode.NotFound,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        "/api/health" ->
                            respond(
                                content = """{"healthy":true}""",
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        else -> error("unexpected ${request.url}")
                    }
                }

            assertEquals(ServerProtocol.V1, service.probe(connection))
        }

    @Test
    fun probe_prefersV1WhenBothHealthEndpointsExist() =
        runTest {
            val paths = mutableListOf<String>()
            val service =
                service { request ->
                    paths += request.url.encodedPath
                    when (request.url.encodedPath) {
                        "/global/health" ->
                            respond(
                                content = """{"healthy":true,"version":"1.18.4"}""",
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        "/api/health" ->
                            respond(
                                content = """{"healthy":true,"version":"2.0.0","pid":123}""",
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        else -> error("unexpected ${request.url}")
                    }
                }

            assertEquals(ServerProtocol.V1, service.probe(connection))
            assertEquals(listOf("/global/health"), paths)
        }

    @Test
    fun probe_failsWhenNeitherHealthEndpointIsUsable() =
        runTest {
            val service =
                service {
                    respond(
                        content = """{}""",
                        status = HttpStatusCode.NotFound,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            try {
                service.probe(connection)
                fail("expected ServerProbeError")
            } catch (error: ServerProbeError) {
                assertTrue(error is ServerProbeError.Http || error is ServerProbeError.Transport)
            }
        }

    @Test
    fun probe_mapsUnauthorizedAsHttpError() =
        runTest {
            val service =
                service {
                    respond(
                        content = """{"error":"unauthorized"}""",
                        status = HttpStatusCode.Unauthorized,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            try {
                service.probe(connection)
                fail("expected ServerProbeError.Http")
            } catch (error: ServerProbeError.Http) {
                assertEquals(401, error.statusCode)
            }
        }

    private fun service(handler: MockRequestHandler) =
        ConnectionProbeService(
            json = json,
            adapter = ProjectDomainAdapter(),
            clientFactory = {
                HttpClient(MockEngine(handler)) {
                    install(ContentNegotiation) { json(json) }
                }
            },
        )
}
