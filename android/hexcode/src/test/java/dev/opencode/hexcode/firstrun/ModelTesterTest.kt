package dev.opencode.hexcode.firstrun

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
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
            val result = ModelTester(json, { client }).test(ModelChoice("anthropic", "claude-opus-5-5", "high"))
            assertEquals("/api/experimental/generate", path)
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
            assertEquals(fail(ModelTestFailure.Unreachable), ModelTester(json, { client }).test(ModelChoice("a", "b")))
        }

    private fun fail(reason: ModelTestFailure) = ModelTestState.Failed(reason)
}
