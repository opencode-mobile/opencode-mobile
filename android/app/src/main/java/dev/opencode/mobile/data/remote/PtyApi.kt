package dev.opencode.mobile.data.remote

import dev.opencode.mobile.data.remote.dto.CreatePtyRequestDto
import dev.opencode.mobile.data.remote.dto.PtyDto
import dev.opencode.mobile.data.remote.dto.UpdatePtyRequestDto
import dev.opencode.mobile.data.remote.dto.decodeDataEnvelope
import dev.opencode.mobile.network.OpencodeHttpClient
import dev.opencode.mobile.network.applyDirectory
import dev.opencode.mobile.network.applyLocationDirectory
import dev.opencode.mobile.network.setJsonBody
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

class PtyApi private constructor(
    private val httpClient: ClientProvider,
    private val json: Json,
) {
    constructor(httpClient: OpencodeHttpClient, json: Json) : this(ClientProvider(httpClient::get), json)

    internal constructor(httpClient: HttpClient, json: Json) : this(ClientProvider { httpClient }, json)

    suspend fun list(directory: String? = null): List<PtyDto> =
        httpClient
            .get()
            .get("pty") {
                applyDirectory(directory)
            }.bodyOnSuccess()

    suspend fun listV2(directory: String? = null): List<PtyDto> {
        val element =
            httpClient
                .get()
                .get("api/pty") {
                    applyLocationDirectory(directory)
                }.jsonOnSuccess()
        return json.decodeDataEnvelope(element)
    }

    suspend fun create(
        body: CreatePtyRequestDto,
        directory: String,
    ): PtyDto =
        httpClient
            .get()
            .post("pty") {
                applyDirectory(directory)
                setJsonBody(json, body)
            }.bodyOnSuccess()

    suspend fun createV2(
        body: CreatePtyRequestDto,
        directory: String,
    ): PtyDto {
        val element =
            httpClient
                .get()
                .post("api/pty") {
                    applyLocationDirectory(directory)
                    setJsonBody(json, body)
                }.jsonOnSuccess()
        return json.decodeDataEnvelope(element)
    }

    suspend fun get(
        id: String,
        directory: String,
    ): PtyDto =
        httpClient
            .get()
            .get("pty/$id") {
                applyDirectory(directory)
            }.bodyOnSuccess()

    suspend fun getV2(
        id: String,
        directory: String,
    ): PtyDto {
        val element =
            httpClient
                .get()
                .get("api/pty/$id") {
                    applyLocationDirectory(directory)
                }.jsonOnSuccess()
        return json.decodeDataEnvelope(element)
    }

    suspend fun update(
        id: String,
        body: UpdatePtyRequestDto,
        directory: String,
    ): PtyDto =
        httpClient
            .get()
            .put("pty/$id") {
                applyDirectory(directory)
                setJsonBody(json, body)
            }.bodyOnSuccess()

    suspend fun updateV2(
        id: String,
        body: UpdatePtyRequestDto,
        directory: String,
    ): PtyDto {
        val element =
            httpClient
                .get()
                .put("api/pty/$id") {
                    applyLocationDirectory(directory)
                    setJsonBody(json, body)
                }.jsonOnSuccess()
        return json.decodeDataEnvelope(element)
    }

    suspend fun delete(
        id: String,
        directory: String,
    ) {
        httpClient
            .get()
            .delete("pty/$id") {
                applyDirectory(directory)
            }.ensureSuccess()
    }

    suspend fun deleteV2(
        id: String,
        directory: String,
    ) {
        httpClient
            .get()
            .delete("api/pty/$id") {
                applyLocationDirectory(directory)
            }.ensureSuccess()
    }

    private suspend inline fun <reified T> HttpResponse.bodyOnSuccess(): T {
        if (!status.isSuccess()) {
            throw ResponseException(this, "HTTP ${status.value}: ${status.description}")
        }
        return body()
    }

    private suspend fun HttpResponse.jsonOnSuccess(): JsonElement {
        if (!status.isSuccess()) {
            throw ResponseException(this, "HTTP ${status.value}: ${status.description}")
        }
        return body()
    }

    private suspend fun HttpResponse.ensureSuccess() {
        if (!status.isSuccess()) {
            throw ResponseException(this, "HTTP ${status.value}: ${status.description}")
        }
        bodyAsText()
    }

    private fun interface ClientProvider {
        fun get(): HttpClient
    }
}
