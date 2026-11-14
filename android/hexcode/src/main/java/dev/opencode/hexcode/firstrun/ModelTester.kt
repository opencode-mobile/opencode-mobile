package dev.opencode.hexcode.firstrun

import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLPath
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.coroutines.cancellation.CancellationException

fun interface ModelTesting {
    /** Tests [choice] for the project in [directory]. */
    suspend fun test(
        choice: ModelChoice,
        directory: String?,
    ): ModelTestState
}

/**
 * Sends the one-line test prompt through OpenCode's stateless generate route, so a model that cannot answer (no API
 * key, provider off) is caught before the first real prompt.
 *
 * That route is turned away by OpenCode's free tier ("Free usage limit reached") even when sessions with the same free
 * model work. Only then the test runs again through a throwaway session: one titled "Hex Code model test" in the
 * project, with the model and level, asked through the session's own generate route, and deleted afterwards, also on
 * failure or after [sessionTimeoutMs].
 */
class ModelTester(
    private val json: Json,
    private val client: () -> HttpClient,
    private val clock: () -> Long = System::nanoTime,
    private val sessionTimeoutMs: Long = SESSION_TIMEOUT_MS,
) : ModelTesting {
    @Serializable
    data class Body(
        val prompt: String,
        val model: Model,
    ) {
        @Serializable
        data class Model(
            val id: String,
            val providerID: String,
            val variant: String? = null,
        )
    }

    @Serializable
    data class SessionBody(
        val title: String,
        val model: Body.Model,
        val location: Location? = null,
    ) {
        @Serializable
        data class Location(
            val directory: String,
        )
    }

    @Serializable
    data class PromptBody(
        val prompt: String,
    )

    override suspend fun test(
        choice: ModelChoice,
        directory: String?,
    ): ModelTestState {
        val started = clock()
        return try {
            val response =
                client().post("api/experimental/generate/text") {
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(Body.serializer(), body(choice)))
                }
            val text = response.bodyAsText()
            if (isFreeUsageLimit(response.status.value, text)) return testInSession(choice, directory, started)
            result(response.status.value, text, seconds = elapsed(started))
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            ModelTestState.Failed(ModelTestFailure.Unreachable)
        }
    }

    private suspend fun testInSession(
        choice: ModelChoice,
        directory: String?,
        started: Long,
    ): ModelTestState {
        var session: String? = null
        try {
            return withTimeout(sessionTimeoutMs) {
                val created =
                    client().post("api/session") {
                        contentType(ContentType.Application.Json)
                        setBody(json.encodeToString(SessionBody.serializer(), sessionBody(choice, directory)))
                    }
                val createdBody = created.bodyAsText()
                val id = sessionId(createdBody)
                if (created.status.value !in 200..299 || id == null) {
                    return@withTimeout result(created.status.value, createdBody, seconds = elapsed(started))
                }
                session = id
                val reply =
                    client().post("api/session/${id.encodeURLPath()}/generate") {
                        contentType(ContentType.Application.Json)
                        setBody(json.encodeToString(PromptBody.serializer(), PromptBody(PROMPT)))
                    }
                result(reply.status.value, reply.bodyAsText(), seconds = elapsed(started))
            }
        } catch (_: TimeoutCancellationException) {
            return ModelTestState.Failed(ModelTestFailure.Unreachable)
        } finally {
            session?.let { id ->
                withContext(NonCancellable) { runCatching { client().delete("api/session/${id.encodeURLPath()}") } }
            }
        }
    }

    private fun elapsed(started: Long) = (clock() - started) / 1e9

    companion object {
        const val PROMPT = "Reply with OK"
        const val SESSION_TITLE = "Hex Code model test"
        const val SESSION_TIMEOUT_MS = 30_000L

        fun body(choice: ModelChoice) = Body(PROMPT, model(choice))

        fun sessionBody(
            choice: ModelChoice,
            directory: String?,
        ) = SessionBody(SESSION_TITLE, model(choice), directory?.let { SessionBody.Location(it) })

        private fun model(choice: ModelChoice) = Body.Model(id = choice.modelId, providerID = choice.providerId, variant = choice.variant)

        private val lenient = Json { ignoreUnknownKeys = true }

        /** The free tier turned the stateless route away: a rate limit whose message names the free usage limit. */
        fun isFreeUsageLimit(
            status: Int,
            body: String,
        ): Boolean {
            if (status in 200..299) return false
            val root = runCatching { lenient.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return false
            val message = root.string("message").orEmpty()
            return root.string("reason") == "rate_limit" && message.contains("free usage limit", ignoreCase = true)
        }

        /** The new session's id, from a reply that may or may not wrap it in `data`. */
        fun sessionId(body: String): String? {
            val root = runCatching { lenient.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
            return ((root["data"] as? JsonObject) ?: root).string("id")
        }

        /**
         * 200 with any text passes: the model answered. Anything else fails with the server's own message when it sent
         * one, since that names the cause (for example a missing API key).
         */
        fun result(
            status: Int,
            body: String,
            seconds: Double,
        ): ModelTestState {
            val root = runCatching { lenient.parseToJsonElement(body) as? JsonObject }.getOrNull()
            if (status in 200..299) {
                val text = (root?.get("data") as? JsonObject)?.string("text")
                if (text.isNullOrBlank()) return ModelTestState.Failed(ModelTestFailure.EmptyReply)
                return ModelTestState.Passed(seconds)
            }
            val message = (root?.string("message") ?: (root?.get("data") as? JsonObject)?.string("message"))?.let(::firstLine)
            if (!message.isNullOrEmpty()) return ModelTestState.Failed(ModelTestFailure.Server(message))
            if (status == 401 || status == 403) return ModelTestState.Failed(ModelTestFailure.SignedOut)
            return ModelTestState.Failed(ModelTestFailure.HttpStatus(status))
        }

        private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

        private fun firstLine(text: String): String {
            val line =
                text
                    .lineSequence()
                    .firstOrNull()
                    ?.trim()
                    .orEmpty()
            return if (line.length > 200) line.take(200) + "…" else line
        }
    }
}
