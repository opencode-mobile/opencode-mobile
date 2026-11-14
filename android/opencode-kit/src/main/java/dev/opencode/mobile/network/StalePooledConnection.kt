package dev.opencode.mobile.network

import java.io.EOFException
import kotlin.coroutines.cancellation.CancellationException

/**
 * A tunnel idle-closes a pooled HTTP/1.1 socket. The next call then fails in a
 * few milliseconds while reading the status line, with zero response bytes.
 *
 * OkHttp 4.12 treats that as a non-retryable "other" failure on a single-route
 * host once the body has been written, and Ktor's streamed bodies are one-shot,
 * so the engine will not open a fresh connection. The server never answered, so
 * replaying the call is safe. [MAX_RETRIES] matches OkHttp's default idle pool
 * so one send can evict every stale socket and still reach a new connection.
 */
internal object StalePooledConnection {
    const val MAX_RETRIES = 5

    fun isRetryable(throwable: Throwable): Boolean {
        if (throwable is CancellationException) return false
        val seen = HashSet<Throwable>()
        var current: Throwable? = throwable
        while (current != null && seen.add(current)) {
            if (current is EOFException && current.message.isZeroByteStatusLineEof()) return true
            current = current.cause
        }
        return false
    }
}

private fun String?.isZeroByteStatusLineEof(): Boolean = this != null && contains("\\n not found: limit=0")
