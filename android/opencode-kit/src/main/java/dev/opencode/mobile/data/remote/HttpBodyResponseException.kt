package dev.opencode.mobile.data.remote

import io.ktor.client.plugins.ResponseException
import io.ktor.client.statement.HttpResponse

/**
 * A non-2xx response whose body was read, so callers can surface the server's
 * own error `message` instead of Ktor's request description.
 */
class HttpBodyResponseException(
    response: HttpResponse,
    val bodyText: String,
) : ResponseException(response, bodyText)
