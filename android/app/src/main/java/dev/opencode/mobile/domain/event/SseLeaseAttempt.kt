package dev.opencode.mobile.domain.event

import dev.opencode.mobile.data.remote.SseAttemptOutcome

internal enum class SseFirstFrameResult {
    Connected,
    Rejected,
    Subsequent,
}

/**
 * Per-attempt first-frame machine. HTTP 2xx is Opened; the lease is not live
 * until the first data frame is `server.connected`.
 */
internal class SseLeaseAttempt {
    var httpOpened: Boolean = false
        private set
    var serverConnected: Boolean = false
        private set
    private var firstFrameHandled: Boolean = false

    fun onHttpStatus(statusCode: Int): Boolean {
        val opened = statusCode in 200..299
        if (opened) {
            httpOpened = true
        }
        return opened
    }

    fun onFirstDataEvents(events: List<OpencodeEvent>): SseFirstFrameResult {
        if (firstFrameHandled) return SseFirstFrameResult.Subsequent
        firstFrameHandled = true
        return if (events.firstOrNull()?.type == "server.connected") {
            serverConnected = true
            SseFirstFrameResult.Connected
        } else {
            SseFirstFrameResult.Rejected
        }
    }

    fun terminalOutcome(outcome: SseAttemptOutcome): SseAttemptOutcome {
        if (serverConnected) return outcome
        return when (outcome) {
            is SseAttemptOutcome.Http,
            SseAttemptOutcome.Cancelled,
            -> outcome
            SseAttemptOutcome.Ended,
            is SseAttemptOutcome.Transport,
            ->
                SseAttemptOutcome.Transport(
                    (outcome as? SseAttemptOutcome.Transport)?.cause,
                )
        }
    }
}
