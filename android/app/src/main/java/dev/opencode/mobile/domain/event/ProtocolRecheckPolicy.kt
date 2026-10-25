package dev.opencode.mobile.domain.event

import dev.opencode.mobile.data.remote.SseAttemptOutcome
import dev.opencode.mobile.domain.connection.ProtocolRedetection
import dev.opencode.mobile.domain.connection.ServerProtocol

internal sealed interface ProtocolRecheckAction {
    /** The server now speaks [protocol]; reopen the event streams on its paths. */
    data class Retarget(
        val protocol: ServerProtocol,
    ) : ProtocolRecheckAction

    /** The saved protocol still answers; the streams' own retries recover. */
    data object KeepProtocol : ProtocolRecheckAction

    /** No protocol answers; show the server as offline until one does. */
    data object MarkUnreachable : ProtocolRecheckAction

    data object None : ProtocolRecheckAction
}

/**
 * When foreground SSE re-runs protocol detection, so a server upgraded (or downgraded) between
 * opencode v1 and v2 at the same address is followed instead of retried on the old paths forever.
 */
internal object ProtocolRecheckPolicy {
    /** At most one re-check per window; foreground and network-path wakes reopen it. */
    const val MIN_INTERVAL_MS = 15_000L

    /**
     * A failed attempt that hints the server speaks the other protocol: an HTTP error other than a
     * rejected credential (opencode v2 answers the v1 event paths with 500), or a 2xx that never
     * sent `server.connected` (an older v1 serves its web app on `/api/event`). A transport failure
     * before any response is an outage, not a protocol change.
     */
    fun suggestsProtocolChange(
        outcome: SseAttemptOutcome,
        httpOpened: Boolean,
        serverConnected: Boolean,
    ): Boolean {
        if (serverConnected) return false
        return when (outcome) {
            is SseAttemptOutcome.Http -> outcome.statusCode != 401 && outcome.statusCode != 403
            is SseAttemptOutcome.Transport,
            SseAttemptOutcome.Ended,
            -> httpOpened
            SseAttemptOutcome.Cancelled -> false
        }
    }

    fun isDue(
        nowMs: Long,
        lastRecheckMs: Long?,
        minIntervalMs: Long = MIN_INTERVAL_MS,
    ): Boolean = lastRecheckMs == null || nowMs - lastRecheckMs >= minIntervalMs

    fun action(
        result: ProtocolRedetection,
        applied: ServerProtocol,
    ): ProtocolRecheckAction =
        when (result) {
            is ProtocolRedetection.Detected ->
                if (result.protocol == applied) {
                    ProtocolRecheckAction.KeepProtocol
                } else {
                    ProtocolRecheckAction.Retarget(result.protocol)
                }
            ProtocolRedetection.Unreachable -> ProtocolRecheckAction.MarkUnreachable
            ProtocolRedetection.Skipped -> ProtocolRecheckAction.None
        }
}
