package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.message.MessageStopReason
import dev.opencode.mobile.domain.message.PromptSendProgress
import dev.opencode.mobile.domain.session.SessionActivity

/**
 * The lifecycle of the permanent turn pill. It mounts while this session's status is not idle, a
 * large prompt body is on the wire, or a permission or question waits. When the turn goes idle
 * it keeps the last active label for [TURN_SETTLE_MS], so a short idle gap never shows Done;
 * then Done holds and fades. A stopped turn, or one that ended while disconnected, leaves
 * quietly, and a failed turn hands the pill to its failure at once.
 */
object WorkingTurnIndicator {
    const val IDLE_LABEL = "Idle"
    const val TURN_SETTLE_MS = 500L
    const val TURN_DONE_HOLD_MS = 3_000L
    const val TURN_FADE_MS = 240L

    /**
     * How long after a reconnect an idle status still counts as "ended while disconnected".
     * The status refresh that follows a reconnect lands inside this window.
     */
    const val RECONNECT_CONFIRM_MS = 5_000L

    data class Chrome(
        val label: String?,
        val fading: Boolean,
    ) {
        companion object {
            val Unmounted = Chrome(label = null, fading = false)
        }
    }

    /**
     * The turn went idle at [startedAtMs] showing [label]. [quietFadeAtMs] is when a quiet
     * ending started to fade; null while the turn is on its way to Done.
     */
    data class Hold(
        val startedAtMs: Long,
        val label: String,
        val quietFadeAtMs: Long? = null,
    )

    data class Resolution(
        val chrome: Chrome,
        val hold: Hold?,
        val wakeAfterMs: Long? = null,
    )

    /** How the turn that just went idle ended. */
    enum class Ending {
        /** Finished normally: settle, then Done. */
        Completed,

        /** Stopped by the user, or ended while disconnected: settle, then leave with no Done. */
        Quiet,

        /** Failed: the failure state replaces the pill immediately. */
        Failed,
    }

    fun showsIdleMark(label: String): Boolean = label == IDLE_LABEL

    /**
     * The active label, highest priority first: Retrying or Compacting, Sending, Working. A
     * pending request keeps the turn active even if the status is momentarily null.
     */
    fun labelForActiveTurn(
        statusLabel: String?,
        isCompacting: Boolean = false,
        isRetrying: Boolean = false,
        hasPendingRequest: Boolean = false,
        sendProgressPercent: Int? = null,
    ): String? {
        if (isCompacting) return SessionCompactChrome.STATUS_LABEL
        if (isRetrying && statusLabel != null) return statusLabel
        if (sendProgressPercent != null) {
            return PromptSendProgress.label(sendProgressPercent)
        }
        return statusLabel ?: SessionActivity.Running.chatStatusLabel().takeIf { hasPendingRequest }
    }

    /**
     * Whether the user stopped the last turn: its newest assistant reply carries the server's
     * interrupted stop reason, with no sent prompt after it.
     */
    fun lastTurnInterrupted(messages: List<Message>): Boolean {
        for (message in messages.asReversed()) {
            when (message.role) {
                MessageRole.Assistant -> return message.stopReason == MessageStopReason.Interrupted
                MessageRole.User -> if (!message.isWaitingForPickup) return false
                else -> Unit
            }
        }
        return false
    }

    /**
     * The next pill chrome. [connectionLost] keeps the last state while the status cannot be
     * trusted. [hideImmediately] is an unbound session.
     */
    fun resolveFooter(
        activeLabel: String?,
        ending: Ending,
        connectionLost: Boolean,
        hideImmediately: Boolean,
        previous: Chrome,
        hold: Hold?,
        nowMs: Long,
    ): Resolution {
        if (hideImmediately) return Resolution(Chrome.Unmounted, hold = null)
        if (activeLabel != null) {
            return Resolution(Chrome(label = activeLabel, fading = false), hold = null)
        }
        if (hold == null && previous.label == null) return Resolution(Chrome.Unmounted, hold = null)
        if (ending == Ending.Failed) return Resolution(Chrome.Unmounted, hold = null)
        if (hold == null && connectionLost) return Resolution(previous, hold = null)
        val started = hold ?: Hold(startedAtMs = nowMs, label = previous.label.orEmpty())
        val settleEndsAt = started.startedAtMs + TURN_SETTLE_MS
        if (nowMs < settleEndsAt) {
            return Resolution(Chrome(started.label, fading = false), started, wakeAfterMs = settleEndsAt - nowMs)
        }
        val doneFadesAt = settleEndsAt + TURN_DONE_HOLD_MS
        val next =
            if (ending == Ending.Quiet && started.quietFadeAtMs == null && nowMs < doneFadesAt) {
                started.copy(quietFadeAtMs = nowMs)
            } else {
                started
            }
        val quietFadeAt = next.quietFadeAtMs
        if (quietFadeAt != null) {
            val remainingMs = quietFadeAt + TURN_FADE_MS - nowMs
            if (remainingMs <= 0) return Resolution(Chrome.Unmounted, hold = null)
            // A stop reason that arrives after Done showed fades Done, not the older label.
            val label = if (previous.label == IDLE_LABEL) IDLE_LABEL else next.label
            return Resolution(Chrome(label, fading = true), next, wakeAfterMs = remainingMs)
        }
        if (nowMs < doneFadesAt) {
            return Resolution(Chrome(IDLE_LABEL, fading = false), next, wakeAfterMs = doneFadesAt - nowMs)
        }
        val fadeEndsAt = doneFadesAt + TURN_FADE_MS
        if (nowMs < fadeEndsAt) {
            return Resolution(Chrome(IDLE_LABEL, fading = true), next, wakeAfterMs = fadeEndsAt - nowMs)
        }
        return Resolution(Chrome.Unmounted, hold = null)
    }
}
