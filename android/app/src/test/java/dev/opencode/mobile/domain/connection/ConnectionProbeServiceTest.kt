package dev.opencode.mobile.domain.connection

import dev.opencode.mobile.domain.project.ProjectDomainAdapter
import dev.opencode.mobile.domain.project.ServerProbeError
import dev.opencode.mobile.network.ServerConnection
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
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
import java.util.Collections

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
            val paths = threadSafePaths()
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
            assertEquals(setOf("/global/health", "/api/info", "/api/health"), paths.toSet())
        }

    @Test
    fun probe_detectsV2FromApiInfoPidWhenGlobalMissing() =
        runTest {
            val paths = threadSafePaths()
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
                        "/api/info" ->
                            respond(
                                content = """{"healthy":true,"version":"2.0.0","pid":123}""",
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        else -> jsonNotFound()
                    }
                }

            val protocol = service.probe(connection)

            assertEquals(ServerProtocol.V2, protocol)
            assertEquals(setOf("/global/health", "/api/info", "/api/health"), paths.toSet())
        }

    @Test
    fun probe_prefersV1WhenBothHealthEndpointsExist() =
        runTest {
            val paths = threadSafePaths()
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
                        "/api/info" ->
                            respond(
                                content = """{"healthy":true,"version":"2.0.0","pid":123}""",
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        else -> jsonNotFound()
                    }
                }

            assertEquals(ServerProtocol.V1, service.probe(connection))
            assertEquals(setOf("/global/health", "/api/info", "/api/health"), paths.toSet())
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

    @Test
    fun probe_prefersAuthFailureOverLaterNotFound() =
        runTest {
            val service =
                service { request ->
                    when (request.url.encodedPath) {
                        "/global/health" ->
                            respond(
                                content = """{"error":"unauthorized"}""",
                                status = HttpStatusCode.Unauthorized,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        "/api/info" -> jsonNotFound()
                        else -> jsonNotFound()
                    }
                }

            try {
                service.probe(connection)
                fail("expected ServerProbeError.Http 401")
            } catch (error: ServerProbeError.Http) {
                assertEquals(401, error.statusCode)
            }
        }

    @Test
    fun probe_reportsUnhealthyWhenBodiesArePresentButUnusable() =
        runTest {
            val service =
                service { request ->
                    when (request.url.encodedPath) {
                        "/global/health" ->
                            respond(
                                content = """{"healthy":false,"version":"1.0.0"}""",
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        "/api/info" -> jsonNotFound()
                        else -> jsonNotFound()
                    }
                }

            try {
                service.probe(connection)
                fail("expected ServerProbeError.Unhealthy")
            } catch (error: ServerProbeError.Unhealthy) {
                assertEquals("1.0.0", error.version)
            }
        }

    @Test
    fun probe_detectsV2FromOpenCode207ApiInfoWhenHealthPathsMiss() =
        runTest {
            val paths = threadSafePaths()
            val service =
                service { request ->
                    paths += request.url.encodedPath
                    when (request.url.encodedPath) {
                        "/global/health" -> spaHtml()
                        "/api/info" ->
                            jsonOk(
                                """{"version":"2.0.7","pid":1481731,"urls":["http://192.168.1.60:4096"]}""",
                            )
                        else -> jsonNotFound()
                    }
                }

            assertEquals(ServerProtocol.V2, service.probe(connection))
            assertEquals(setOf("/global/health", "/api/info", "/api/health"), paths.toSet())
        }

    @Test
    fun probe_detectsV2FromApiHealthWhenInfoIsMissing() =
        runTest {
            val paths = threadSafePaths()
            val service =
                service { request ->
                    paths += request.url.encodedPath
                    when (request.url.encodedPath) {
                        "/global/health" -> spaHtml()
                        "/api/info" -> jsonNotFound()
                        "/api/health" ->
                            jsonOk("""{"healthy":true,"version":"2.0.1","pid":2605231}""")
                        else -> jsonNotFound()
                    }
                }

            assertEquals(ServerProtocol.V2, service.probe(connection))
            assertEquals(setOf("/global/health", "/api/info", "/api/health"), paths.toSet())
        }

    @Test
    fun probe_prefersUsableApiHealthOverUnhealthyApiInfo() =
        runTest {
            val service =
                service { request ->
                    when (request.url.encodedPath) {
                        "/global/health" -> jsonNotFound()
                        "/api/info" ->
                            jsonOk("""{"healthy":false,"version":"2.0.0","pid":1}""")
                        "/api/health" ->
                            jsonOk("""{"healthy":true,"version":"2.0.1","pid":2}""")
                        else -> jsonNotFound()
                    }
                }

            assertEquals(ServerProtocol.V2, service.probe(connection))
        }

    @Test
    fun probe_reportsUnhealthyWhenOnlyApiHealthPidIsUnhealthy() =
        runTest {
            val service =
                service { request ->
                    when (request.url.encodedPath) {
                        "/api/health" ->
                            jsonOk("""{"healthy":false,"version":"2.0.1","pid":12}""")
                        else -> jsonNotFound()
                    }
                }

            try {
                service.probe(connection)
                fail("expected ServerProbeError.Unhealthy")
            } catch (error: ServerProbeError.Unhealthy) {
                assertEquals("2.0.1", error.version)
            }
        }

    @Test
    fun probe_detectsV1FromGlobalHealthEvenIfApiInfoIsHtml() =
        runTest {
            val service =
                service { request ->
                    when (request.url.encodedPath) {
                        "/global/health" ->
                            jsonOk("""{"healthy":true,"version":"1.18.31"}""")
                        "/api/info" -> spaHtml()
                        else -> jsonNotFound()
                    }
                }

            assertEquals(ServerProtocol.V1, service.probe(connection))
        }

    @Test
    fun probe_htmlAndNotFoundDoNotSurfaceNotFound() =
        runTest {
            val service =
                service { request ->
                    when (request.url.encodedPath) {
                        "/global/health" -> spaHtml()
                        "/api/info" -> jsonNotFound()
                        else -> jsonNotFound()
                    }
                }

            try {
                service.probe(connection)
                fail("expected ServerProbeError")
            } catch (error: ServerProbeError) {
                assertTrue(error is ServerProbeError.Http || error is ServerProbeError.Transport)
                assertEquals("Unable to reach the server", error.userMessage())
            }
        }

    @Test
    fun probe_authFailureOnHealthPathsIsNotSwallowedByApiInfo() =
        runTest {
            val service =
                service { request ->
                    when (request.url.encodedPath) {
                        "/global/health" ->
                            respond(
                                content = """{"error":"unauthorized"}""",
                                status = HttpStatusCode.Unauthorized,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        "/api/info" -> jsonNotFound()
                        else -> jsonNotFound()
                    }
                }

            try {
                service.probe(connection)
                fail("expected ServerProbeError.Http 401")
            } catch (error: ServerProbeError.Http) {
                assertEquals(401, error.statusCode)
                assertEquals("Authentication failed", error.userMessage())
            }
        }

    @Test
    fun userMessage_nonAuthHttpDoesNotSurfaceNotFound() {
        assertEquals(
            "Unable to reach the server",
            ServerProbeError.Http(404, "Not Found").userMessage(),
        )
        assertEquals(
            "Authentication failed",
            ServerProbeError.Http(401, "Unauthorized").userMessage(),
        )
    }

    @Test
    fun probeV2Features_readsOpenApiContract() =
        runTest {
            val paths = threadSafePaths()
            val service =
                service { request ->
                    paths += request.url.encodedPath
                    jsonOk(openApi(V2_1_PATCH_BODY))
                }

            assertTrue(service.probeV2Features(connection).sessionArchive)
            assertEquals(listOf("/openapi.json"), paths)
        }

    @Test
    fun probeV2Features_olderServerIsUnsupported() =
        runTest {
            val service = service { _ -> jsonOk(openApi(V2_0_PATCH_BODY)) }

            assertEquals(V2ServerFeatures(), service.probeV2Features(connection))
        }

    @Test
    fun probeV2Features_throwsWhenContractIsMissing() =
        runTest {
            val service = service { _ -> jsonNotFound() }

            try {
                service.probeV2Features(connection)
                fail("expected a probe error")
            } catch (error: ServerProbeError.Http) {
                assertEquals(404, error.statusCode)
            }
        }

    private fun openApi(patchBody: String): String =
        """{"paths":{"/api/session/{sessionID}":{"patch":{"requestBody":{"content":{"application/json":{"schema":$patchBody}}}}}}}"""

    private fun MockRequestHandleScope.jsonOk(content: String) =
        respond(
            content = content,
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )

    private fun MockRequestHandleScope.jsonNotFound() =
        respond(
            content = "{}",
            status = HttpStatusCode.NotFound,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )

    private fun MockRequestHandleScope.spaHtml() =
        respond(
            content = "<!doctype html><html><head><title>OpenCode</title></head><body></body></html>",
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "text/html"),
        )

    @Test
    fun probeServer_keepsHealthVersionForEachProtocol() =
        runTest {
            val v1 =
                service { request ->
                    when (request.url.encodedPath) {
                        "/global/health" ->
                            respond(
                                content = """{"healthy":true,"version":"1.18.4"}""",
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        else -> jsonNotFound()
                    }
                }
            val v2 =
                service { request ->
                    when (request.url.encodedPath) {
                        "/api/info" ->
                            respond(
                                content = """{"healthy":true,"version":"2.0.0","pid":123}""",
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        else -> jsonNotFound()
                    }
                }

            assertEquals(DetectedServer(ServerProtocol.V1, "1.18.4"), v1.probeServer(connection))
            assertEquals(DetectedServer(ServerProtocol.V2, "2.0.0"), v2.probeServer(connection))
        }

    // The probe requests its health endpoints concurrently and MockEngine answers on IO threads,
    // so the handler records paths from several threads at once.
    private fun threadSafePaths(): MutableList<String> = Collections.synchronizedList(mutableListOf())

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
