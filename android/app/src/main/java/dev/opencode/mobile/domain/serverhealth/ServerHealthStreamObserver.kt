package dev.opencode.mobile.domain.serverhealth

import dev.opencode.mobile.data.remote.SseAttemptOutcome
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.coroutines.TimeoutCancellationException
import java.net.SocketTimeoutException

/** Told why a foreground SSE attempt failed, so Server Health can break drops down by cause. */
fun interface ServerHealthStreamObserver {
    fun onStreamFailure(category: ServerHealthFailureCategory)

    companion object {
        val None = ServerHealthStreamObserver { }
    }
}

object ServerHealthFailureClassifier {
    /** Null for [SseAttemptOutcome.Cancelled]: the app stopped the stream, the server did not fail. */
    fun classify(outcome: SseAttemptOutcome): ServerHealthFailureCategory? =
        when (outcome) {
            SseAttemptOutcome.Cancelled -> null
            SseAttemptOutcome.Ended -> ServerHealthFailureCategory.Server
            is SseAttemptOutcome.Http -> classifyStatus(outcome.statusCode)
            is SseAttemptOutcome.Transport -> classifyTransport(outcome.cause)
        }

    fun classifyStatus(statusCode: Int): ServerHealthFailureCategory =
        when (statusCode) {
            401, 403 -> ServerHealthFailureCategory.Auth
            in 500..599 -> ServerHealthFailureCategory.Server
            else -> ServerHealthFailureCategory.Other
        }

    fun classifyTransport(cause: Throwable?): ServerHealthFailureCategory =
        if (isTimeout(cause)) ServerHealthFailureCategory.Timeout else ServerHealthFailureCategory.Network

    fun isTimeout(cause: Throwable?): Boolean {
        var current = cause
        var depth = 0
        while (current != null && depth < 8) {
            if (current is HttpRequestTimeoutException ||
                current is ConnectTimeoutException ||
                current is SocketTimeoutException ||
                current is TimeoutCancellationException ||
                current.javaClass.simpleName.contains("Timeout")
            ) {
                return true
            }
            current = current.cause
            depth += 1
        }
        return false
    }
}
