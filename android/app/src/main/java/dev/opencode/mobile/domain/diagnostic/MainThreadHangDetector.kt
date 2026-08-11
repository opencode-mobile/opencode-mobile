package dev.opencode.mobile.domain.diagnostic

/**
 * Pure hang state machine for unit tests and the runtime watchdog.
 * Tracks one outstanding main-thread ping and emits detect / recover events.
 */
class MainThreadHangDetector {
    sealed class Event {
        data class HangDetected(
            val overdueMilliseconds: Long,
        ) : Event()

        data class HangRecovered(
            val durationMilliseconds: Long,
        ) : Event()
    }

    var isHung: Boolean = false
        private set

    private var pendingSentAtMilliseconds: Long? = null
    private var hangStartedAtMilliseconds: Long? = null

    val hasPendingPing: Boolean
        get() = pendingSentAtMilliseconds != null

    fun notePingSent(atEpochMilliseconds: Long) {
        if (pendingSentAtMilliseconds != null) return
        pendingSentAtMilliseconds = atEpochMilliseconds
    }

    fun notePingAcknowledged(atEpochMilliseconds: Long): Event? {
        pendingSentAtMilliseconds = null
        if (!isHung) return null
        val started = hangStartedAtMilliseconds ?: atEpochMilliseconds
        isHung = false
        hangStartedAtMilliseconds = null
        return Event.HangRecovered(durationMilliseconds = maxOf(0L, atEpochMilliseconds - started))
    }

    fun evaluate(
        nowEpochMilliseconds: Long,
        thresholdMilliseconds: Long,
    ): Event? {
        val sentAt = pendingSentAtMilliseconds ?: return null
        val overdue = nowEpochMilliseconds - sentAt
        if (overdue < thresholdMilliseconds) return null
        if (isHung) return null
        isHung = true
        hangStartedAtMilliseconds = sentAt
        return Event.HangDetected(overdueMilliseconds = overdue)
    }
}
