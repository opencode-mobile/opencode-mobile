package dev.opencode.mobile.domain.t3

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class T3HttpResponse(
    val code: Int,
    val body: String,
)

interface T3Http {
    suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): T3HttpResponse

    suspend fun post(
        url: String,
        body: String,
        contentType: String,
        headers: Map<String, String> = emptyMap(),
    ): T3HttpResponse

    /**
     * Signed asset URLs are fetched without the environment bearer.
     * Fakes inherit a null result; only [OkHttpT3Http] downloads bytes.
     */
    suspend fun getBytes(
        url: String,
        headers: Map<String, String> = emptyMap(),
        maxBytes: Int = MAX_ASSET_BYTES,
    ): ByteArray? = null

    companion object {
        const val MAX_ASSET_BYTES = T3Assets.MAX_IMAGE_BYTES
    }
}

@Singleton
class OkHttpT3Http
    @Inject
    constructor() : T3Http {
        private val client =
            OkHttpClient
                .Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build()

        override suspend fun get(
            url: String,
            headers: Map<String, String>,
        ): T3HttpResponse = execute(Request.Builder().url(url).applyHeaders(headers).get().build())

        override suspend fun post(
            url: String,
            body: String,
            contentType: String,
            headers: Map<String, String>,
        ): T3HttpResponse {
            val requestBody = body.toRequestBody(contentType.toMediaType())
            return execute(
                Request
                    .Builder()
                    .url(url)
                    .applyHeaders(headers)
                    .post(requestBody)
                    .build(),
            )
        }

        override suspend fun getBytes(
            url: String,
            headers: Map<String, String>,
            maxBytes: Int,
        ): ByteArray? =
            withContext(Dispatchers.IO) {
                val request = Request.Builder().url(url).applyHeaders(headers).get().build()
                runCatching {
                    client.newCall(request).execute().use { response ->
                        if (response.code !in 200..299) return@use null
                        val body = response.body ?: return@use null
                        val contentLength = body.contentLength()
                        if (contentLength > maxBytes) return@use null
                        body.byteStream().use { input ->
                            val output = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(8 * 1024)
                            var total = 0
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                total += read
                                if (total > maxBytes) return@use null
                                output.write(buffer, 0, read)
                            }
                            output.toByteArray()
                        }
                    }
                }.getOrNull()
            }

        private suspend fun execute(request: Request): T3HttpResponse =
            withContext(Dispatchers.IO) {
                try {
                    client.newCall(request).execute().use { response ->
                        T3HttpResponse(code = response.code, body = response.body?.string().orEmpty())
                    }
                } catch (error: IOException) {
                    // OkHttp's text ("failed to connect to …") is for the log, not the snackbar.
                    throw T3TransportException(error)
                }
            }

        private fun Request.Builder.applyHeaders(headers: Map<String, String>): Request.Builder {
            headers.forEach { (name, value) -> header(name, value) }
            return this
        }
    }
