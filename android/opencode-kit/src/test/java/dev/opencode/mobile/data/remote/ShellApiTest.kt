package dev.opencode.mobile.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/** `GET /api/shell`, with a body captured live on v2.2.5. */
class ShellApiTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    @Test
    fun shellsAreListedForTheFolderWithTheirSession() =
        runTest {
            val captured = mutableListOf<HttpRequestData>()
            val api =
                api { request ->
                    captured += request
                    respond(
                        """{"location":{"directory":"/tmp/p"},"data":[{"id":"sh_1","status":"running","command":"bun dev",
                        "cwd":"/tmp/p","metadata":{"sessionID":"ses_1"},"time":{"started":1793986674864}}]}""",
                        HttpStatusCode.OK,
                        jsonHeaders,
                    )
                }

            val shells = api.listV2("/tmp/p")

            assertEquals("/tmp/p", captured.single().url.parameters["location[directory]"])
            val shell = shells.single()
            assertEquals("bun dev", shell.command)
            assertEquals("/tmp/p", shell.cwd)
            assertEquals(
                "ses_1",
                shell.metadata
                    ?.get("sessionID")
                    ?.jsonPrimitive
                    ?.content,
            )
            assertEquals(1793986674864.0, shell.time?.started)
        }

    private fun api(handler: MockRequestHandler): ShellApi {
        val json = Json { ignoreUnknownKeys = true }
        val client =
            HttpClient(MockEngine(handler)) {
                install(ContentNegotiation) { json(json) }
            }
        return ShellApi(client, json)
    }
}
