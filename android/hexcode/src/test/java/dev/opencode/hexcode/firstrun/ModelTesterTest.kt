package dev.opencode.hexcode.firstrun

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelTesterTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    @Test
    fun postsTheOneLinePromptWithModelAndLevel() =
        runTest {
            var path = ""
            var method = HttpMethod.Get
            var body = ""
            val engine =
                MockEngine { request ->
                    path = request.url.encodedPath
                    method = request.method
                    body = String(request.body.toByteArray())
                    respond("""{"data":{"text":"OK"}}""", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
                }
            val client = HttpClient(engine) { defaultRequest { url("http://192.168.1.2:4096/") } }
            val result = ModelTester(json, { client }).test(ModelChoice("anthropic", "claude-opus-5-5", "high"), "/Users/example/project")
            assertEquals("/api/experimental/generate/text", path)
            assertEquals(HttpMethod.Post, method)
            assertEquals(
                """{"prompt":"Reply with OK","model":{"id":"claude-opus-5-5","providerID":"anthropic","variant":"high"}}""",
                body,
            )
            assertEquals(true, result is ModelTestState.Passed)
        }

    @Test
    fun defaultLevelSendsNoVariant() {
        val body = json.encodeToString(ModelTester.Body.serializer(), ModelTester.body(ModelChoice("openai", "gpt-5")))
        assertEquals("""{"prompt":"Reply with OK","model":{"id":"gpt-5","providerID":"openai"}}""", body)
    }

    @Test
    fun aReplyWithTextPasses() {
        assertEquals(ModelTestState.Passed(1.25), ModelTester.result(200, """{"data":{"text":"OK"}}""", 1.25))
    }

    @Test
    fun anEmptyReplyFails() {
        assertEquals(fail(ModelTestFailure.EmptyReply), ModelTester.result(200, """{"data":{"text":"  "}}""", 1.0))
        assertEquals(fail(ModelTestFailure.EmptyReply), ModelTester.result(200, "not json", 1.0))
    }

    @Test
    fun theServersOwnMessageNamesTheCause() {
        assertEquals(
            fail(ModelTestFailure.Server("Google isn’t set up: no API key")),
            ModelTester.result(400, """{"name":"ProviderInitError","message":"Google isn’t set up: no API key\nat line 2"}""", 1.0),
        )
        assertEquals(
            fail(ModelTestFailure.Server("Model not found")),
            ModelTester.result(404, """{"data":{"message":"Model not found"}}""", 1.0),
        )
    }

    @Test
    fun aRejectedPhoneIsSignedOut() {
        assertEquals(fail(ModelTestFailure.SignedOut), ModelTester.result(401, "", 1.0))
    }

    @Test
    fun otherwiseTheStatusIsShown() {
        assertEquals(fail(ModelTestFailure.HttpStatus(503)), ModelTester.result(503, "<html>busy</html>", 1.0))
    }

    @Test
    fun aTransportFailureIsUnreachable() =
        runTest {
            val client = HttpClient(MockEngine { throw java.io.IOException("refused") })
            assertEquals(fail(ModelTestFailure.Unreachable), ModelTester(json, { client }).test(ModelChoice("a", "b"), null))
        }

    private val freeLimit =
        """{"_tag":"ProviderRequestError","providerID":"opencode","reason":"rate_limit",""" +
            """"message":"Free usage limit reached. Free models work in OpenCode (https://opencode.ai)","status":429}"""

    /** A server whose stateless route is over the free limit; [generate] answers the session route. */
    private fun limitedServer(
        calls: MutableList<String>,
        generate: () -> Pair<HttpStatusCode, String> = { HttpStatusCode.OK to """{"data":{"text":"OK"}}""" },
        create: () -> Pair<HttpStatusCode, String> = { HttpStatusCode.OK to """{"data":{"id":"ses_test"}}""" },
    ) = HttpClient(
        MockEngine { request ->
            val body = String(request.body.toByteArray())
            calls += "${request.method.value} ${request.url.encodedPath} $body".trim()
            val (status, text) =
                when (request.url.encodedPath) {
                    "/api/experimental/generate/text" -> HttpStatusCode.BadGateway to freeLimit
                    "/api/session" -> create()
                    "/api/session/ses_test/generate" -> generate()
                    else -> HttpStatusCode.NoContent to ""
                }
            respond(text, status, headersOf("Content-Type", "application/json"))
        },
    ) { defaultRequest { url("http://192.168.1.2:4096/") } }

    // Real time: the session test has a timeout, and the mock server answers on its own thread.
    @Test
    fun theFreeLimitFallsBackToAThrowawaySession() =
        runBlocking {
            val calls = mutableListOf<String>()
            val client = limitedServer(calls)
            val result = ModelTester(json, { client }).test(ModelChoice("opencode", "big-pickle", "high"), "/Users/example/project")
            assertEquals(true, result is ModelTestState.Passed)
            assertEquals(
                listOf(
                    "POST /api/experimental/generate",
                    """POST /api/session {"title":"Hex Code model test",""" +
                        """"model":{"id":"big-pickle","providerID":"opencode","variant":"high"},""" +
                        """"location":{"directory":"/Users/example/project"}}""",
                    """POST /api/session/ses_test/generate {"prompt":"Reply with OK"}""",
                    "DELETE /api/session/ses_test",
                ),
                calls.map { if (it.startsWith("POST /api/experimental")) "POST /api/experimental/generate" else it },
            )
        }

    // Real time: the session test has a timeout, and the mock server answers on its own thread.
    @Test
    fun theThrowawaySessionIsDeletedWhenTheSessionTestFails() =
        runBlocking {
            val calls = mutableListOf<String>()
            val client = limitedServer(calls, generate = { HttpStatusCode.BadRequest to """{"message":"Model not found"}""" })
            val result = ModelTester(json, { client }).test(ModelChoice("opencode", "big-pickle"), null)
            assertEquals(fail(ModelTestFailure.Server("Model not found")), result)
            assertEquals("DELETE /api/session/ses_test", calls.last())
        }

    // Real time: the session test has a timeout, and the mock server answers on its own thread.
    @Test
    fun theThrowawaySessionIsDeletedAfterATimeout() =
        runBlocking {
            val calls = mutableListOf<String>()
            val client =
                limitedServer(calls, generate = {
                    Thread.sleep(300)
                    HttpStatusCode.OK to """{"data":{"text":"OK"}}"""
                })
            val result = ModelTester(json, { client }, sessionTimeoutMs = 100).test(ModelChoice("opencode", "big-pickle"), null)
            assertEquals(fail(ModelTestFailure.Unreachable), result)
            assertEquals("DELETE /api/session/ses_test", calls.last())
        }

    @Test
    fun otherErrorsDoNotTryASession() =
        runTest {
            val calls = mutableListOf<String>()
            val client =
                HttpClient(
                    MockEngine { request ->
                        calls += request.url.encodedPath
                        respond(
                            """{"message":"Google isn’t set up"}""",
                            HttpStatusCode.BadRequest,
                            headersOf("Content-Type", "application/json"),
                        )
                    },
                ) { defaultRequest { url("http://192.168.1.2:4096/") } }
            assertEquals(
                fail(ModelTestFailure.Server("Google isn’t set up")),
                ModelTester(json, {
                    client
                }).test(ModelChoice("google", "gemini"), null),
            )
            assertEquals(listOf("/api/experimental/generate/text"), calls)
        }

    @Test
    fun recognisesTheFreeUsageLimit() {
        assertEquals(true, ModelTester.isFreeUsageLimit(502, freeLimit))
        assertEquals(false, ModelTester.isFreeUsageLimit(502, """{"reason":"rate_limit","message":"Too many requests"}"""))
        assertEquals(false, ModelTester.isFreeUsageLimit(200, freeLimit))
    }

    private fun fail(reason: ModelTestFailure) = ModelTestState.Failed(reason)
}
