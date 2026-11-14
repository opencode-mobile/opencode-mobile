package dev.opencode.mobile.data.remote

/**
 * Terminal result of one SSE HTTP attempt.
 *
 * Opened (HTTP 2xx headers) is a mid-attempt phase in the lease loop, not a
 * return value here. Live requires `server.connected` after [Opened].
 */
sealed interface SseAttemptOutcome {
    data object Ended : SseAttemptOutcome

    data class Http(
        val statusCode: Int,
    ) : SseAttemptOutcome

    data class Transport(
        val cause: Throwable?,
    ) : SseAttemptOutcome

    data object Cancelled : SseAttemptOutcome
}
