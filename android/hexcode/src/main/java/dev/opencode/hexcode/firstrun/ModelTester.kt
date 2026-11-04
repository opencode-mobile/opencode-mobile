package dev.opencode.hexcode.firstrun

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.coroutines.cancellation.CancellationException

fun interface ModelTesting {
    suspend fun test(choice: ModelChoice): ModelTestState
}

/**
 * Sends the one-line test prompt through OpenCode's stateless generate route, so a model that cannot answer (no API
 * key, provider off) is caught before the first real prompt.
 */
class ModelTester(
    private val json: Json,
    private val client: () -> HttpClient,
    private val clock: () -> Long = System::nanoTime,
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

    override suspend fun test(choice: ModelChoice): ModelTestState {
        val started = clock()
        return try {
            val response =
                client().post("api/experimental/generate") {
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(Body.serializer(), body(choice)))
                }
            result(response.status.value, response.bodyAsText(), seconds = (clock() - started) / 1e9)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            ModelTestState.Failed(ModelTestFailure.Unreachable)
        }
    }

    companion object {
        const val PROMPT = "Reply with OK"

        fun body(choice: ModelChoice) =
            Body(PROMPT, Body.Model(id = choice.modelId, providerID = choice.providerId, variant = choice.variant))

        private val lenient = Json { ignoreUnknownKeys = true }

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
