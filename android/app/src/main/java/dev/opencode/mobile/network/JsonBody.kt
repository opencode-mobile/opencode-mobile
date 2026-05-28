package dev.opencode.mobile.network

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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
