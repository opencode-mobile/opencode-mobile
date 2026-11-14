package dev.opencode.mobile.network

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentType
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Encodes [@Serializable] bodies without Kotlin reflection, which is unavailable on Android.
 */
inline fun <reified T> HttpRequestBuilder.setJsonBody(
    json: Json,
    body: T,
) {
    contentType(ContentType.Application.Json)
    setBody(json.encodeToString(body))
}

fun HttpRequestBuilder.setJsonFileBody(file: File) {
    contentType(ContentType.Application.Json)
    setBody(
        object : OutgoingContent.WriteChannelContent() {
            override val contentType: ContentType = ContentType.Application.Json
            override val contentLength: Long = file.length()

            override suspend fun writeTo(channel: ByteWriteChannel) {
                file.inputStream().use { input ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        channel.writeFully(buffer, 0, read)
                    }
                }
            }
        },
    )
}
