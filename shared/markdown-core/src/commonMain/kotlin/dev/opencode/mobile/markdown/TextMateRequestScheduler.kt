package dev.opencode.mobile.markdown

import kotlin.concurrent.Volatile
import kotlin.time.TimeSource

/**
 * Pure TextMate request queue policy. Platforms own serial execution and drive
 * [pickNext] / [finishActive]; this type never touches regex, grammar, or UI.
 * Not thread-safe: callers serialize access, but a job's [TextMateScheduledJob.cancelRequested]
 * may be polled from the executing thread while another thread admits.
 */
class TextMateRequestScheduler(
    private val limits: TextMateSchedulerLimits = TextMateSchedulerLimits(),
    private val clock: () -> Long = MonotonicMillisClock,
) {
    private var nextTicketId = 1L
    private val pending = ArrayDeque<QueuedTicket>()
    private var active: QueuedTicket? = null
    private var backgroundDisabledByTrim = false
    private var backgroundDisabledByPower = false
    private var historyAdmissionDisabled = false

    // Platforms rarely say when pressure is over, so trim restrictions lapse on their own.
    private var trimRecoversAtMs: Long? = null
    private var interactiveCredit = limits.deficitInteractive
    private var visibleCredit = limits.deficitVisible
    private var backgroundCredit = limits.deficitBackground

    private val backgroundDisabled: Boolean
        get() = backgroundDisabledByTrim || backgroundDisabledByPower || historyAdmissionDisabled

    private var pendingInteractiveHwm = 0
    private var pendingHistoryHwm = 0
    private var pendingCharsHwm = 0
    private var supersessionCount = 0
    private var queuePressureCount = 0
    private var deadlinePriorityCount = 0
    private var trimDropCount = 0

    fun snapshot(): TextMateSchedulerSnapshot {
        // Read-only: some platforms read snapshots without serializing with admits.
        val trimActive = trimRecoversAtMs?.let { clock() < it } ?: false
        return TextMateSchedulerSnapshot(
            pendingInteractive = pending.count { it.request.intent == HighlightIntent.InteractiveFinal },
            pendingHistory = pending.count { it.request.intent != HighlightIntent.InteractiveFinal },
            pendingCharacters = pending.sumOf { it.request.source.length },
            active = active != null,
            activeCancelRequested = active?.cancelRequested == true,
            backgroundDisabled = backgroundDisabledByPower || (trimActive && backgroundDisabled),
            historyAdmissionDisabled = trimActive && historyAdmissionDisabled,
            pendingInteractiveHwm = pendingInteractiveHwm,
            pendingHistoryHwm = pendingHistoryHwm,
            pendingCharsHwm = pendingCharsHwm,
            supersessionCount = supersessionCount,
            queuePressureCount = queuePressureCount,
            deadlinePriorityCount = deadlinePriorityCount,
            trimDropCount = trimDropCount,
        )
    }

    fun admit(request: HighlightRequest): TextMateAdmitResult {
        val nowMs = clock()
        recoverFromTrimIfDue(nowMs)
        val completed = mutableListOf<TextMateTicketCompletion>()

        pending.removeAll { ticket ->
            if (ticket.request.key == request.key && ticket.request.revision.value <= request.revision.value) {
                completed += ticket.completion(TextMateParseOutcome.Cancelled, TextMateSchedulingReason.Supersession, nowMs)
                supersessionCount += 1
                true
            } else {
                false
            }
        }

        active?.let { running ->
            if (running.request.key == request.key && running.request.revision.value < request.revision.value) {
                running.cancelRequested = true
                running.cancelRequestedAtMs = running.cancelRequestedAtMs ?: nowMs
                supersessionCount += 1
            }
        }

        if (historyAdmissionDisabled && request.intent != HighlightIntent.InteractiveFinal) {
            return TextMateAdmitResult(
                ticketId = null,
                completed =
                    completed +
                        immediateCompletion(
                            request = request,
                            outcome = TextMateParseOutcome.BudgetExceeded,
                            reason = TextMateSchedulingReason.Trim,
                            nowMs = nowMs,
                        ),
            )
        }
        if (backgroundDisabled && request.intent == HighlightIntent.BackgroundHistory) {
            return TextMateAdmitResult(
                ticketId = null,
                completed =
                    completed +
                        immediateCompletion(
                            request = request,
                            outcome = TextMateParseOutcome.BudgetExceeded,
                            reason = TextMateSchedulingReason.Trim,
                            nowMs = nowMs,
                        ),
            )
        }

        val ticket =
            QueuedTicket(
                id = nextTicketId++,
                request = request,
                enqueuedAtMs = nowMs,
            )
        pending.addLast(ticket)
        completed += enforceLimits(nowMs)
        if (completed.any { it.ticketId == ticket.id }) {
            recordHighWater()
            return TextMateAdmitResult(ticketId = null, completed = completed)
        }
        recordHighWater()
        return TextMateAdmitResult(ticketId = ticket.id, completed = completed)
    }

    fun isIdle(): Boolean = active == null && pending.isEmpty()

    fun pickNext(): TextMateScheduledJob? {
        if (active != null) return null
        val nowMs = clock()
        recoverFromTrimIfDue(nowMs)
        val selected = selectPending(nowMs) ?: return null
        pending.remove(selected)
        selected.startedAtMs = nowMs
        active = selected
        if (selected.request.intent == HighlightIntent.InteractiveFinal &&
            nowMs - selected.enqueuedAtMs >= limits.interactiveFinalAtRiskMs
        ) {
            deadlinePriorityCount += 1
        }
        consumeDeficit(selected.request.intent)
        return TextMateScheduledJob(
            ticketId = selected.id,
            request = selected.request,
            queueWaitNanoseconds = (nowMs - selected.enqueuedAtMs).saturatingMsToNs(),
            cancelRequested = { selected.cancelRequested },
        )
    }

    fun finishActive(
        ticketId: Long,
        rawResult: HighlightResult,
    ): List<TextMateTicketCompletion> {
        val running = active ?: return emptyList()
        if (running.id != ticketId) return emptyList()
        active = null
        val nowMs = clock()
        val cancelStopNs =
            running.cancelRequestedAtMs?.let { requestedAt ->
                (nowMs - requestedAt).saturatingMsToNs()
            }
        val outcome =
            if (running.cancelRequested) {
                TextMateParseOutcome.Cancelled
            } else {
                rawResult.outcome
            }
        val reason =
            when {
                running.cancelRequested -> TextMateSchedulingReason.Supersession
                outcome == TextMateParseOutcome.BudgetExceeded -> TextMateSchedulingReason.SearchBudget
                else -> null
            }
        return listOf(
            TextMateTicketCompletion(
                ticketId = running.id,
                request = running.request,
                result =
                    rawResult.copy(
                        outcome = outcome,
                        observation =
                            rawResult.observation.copy(
                                outcome = outcome,
                                queueWaitNanoseconds = (running.startedAtMs - running.enqueuedAtMs).saturatingMsToNs(),
                                cancelRequestedToStopNanoseconds = cancelStopNs,
                                schedulingReason = reason?.name,
                            ),
                    ),
                reason = reason,
            ),
        )
    }

    /**
     * Moderate and Critical restrictions last until [MemoryPressure.Normal] or until
     * [TextMateSchedulerLimits.trimRecoveryMs] passes without another trim, whichever comes first.
     */
    fun trim(level: MemoryPressure): List<TextMateTicketCompletion> {
        val nowMs = clock()
        recoverFromTrimIfDue(nowMs)
        val completed = mutableListOf<TextMateTicketCompletion>()
        when (level) {
            MemoryPressure.Moderate -> {
                backgroundDisabledByTrim = true
                trimRecoversAtMs = nowMs + limits.trimRecoveryMs
                completed += dropPending(nowMs, intents = setOf(HighlightIntent.BackgroundHistory), TextMateSchedulingReason.Trim)
            }
            MemoryPressure.Critical -> {
                backgroundDisabledByTrim = true
                historyAdmissionDisabled = true
                trimRecoversAtMs = nowMs + limits.trimRecoveryMs
                completed +=
                    dropPending(
                        nowMs,
                        intents = setOf(HighlightIntent.BackgroundHistory, HighlightIntent.VisibleHistory),
                        TextMateSchedulingReason.Trim,
                    )
                active?.let { running ->
                    if (running.request.intent != HighlightIntent.InteractiveFinal) {
                        running.cancelRequested = true
                        running.cancelRequestedAtMs = running.cancelRequestedAtMs ?: nowMs
                    }
                }
            }
            MemoryPressure.Normal -> clearTrimRestrictions()
        }
        trimDropCount += completed.size
        recordHighWater()
        return completed
    }

    private fun recoverFromTrimIfDue(nowMs: Long) {
        val recoversAt = trimRecoversAtMs ?: return
        if (nowMs >= recoversAt) clearTrimRestrictions()
    }

    private fun clearTrimRestrictions() {
        backgroundDisabledByTrim = false
        historyAdmissionDisabled = false
        trimRecoversAtMs = null
    }

    /**
     * Low-power / thermal hint. Disabling speculative background is independent of
     * [trim] Moderate/Critical flags.
     */
    fun setLowPowerOrThermal(disabled: Boolean) {
        backgroundDisabledByPower = disabled
    }

    private fun enforceLimits(nowMs: Long): List<TextMateTicketCompletion> {
        val completed = mutableListOf<TextMateTicketCompletion>()
        while (overCapacity()) {
            val victim =
                pending.firstOrNull { it.request.intent == HighlightIntent.BackgroundHistory }
                    ?: pending.firstOrNull { it.request.intent == HighlightIntent.VisibleHistory }
                    ?: pending.firstOrNull { it.request.intent == HighlightIntent.InteractiveFinal }
                    ?: break
            pending.remove(victim)
            completed += victim.completion(TextMateParseOutcome.BudgetExceeded, TextMateSchedulingReason.QueuePressure, nowMs)
            queuePressureCount += 1
        }
        return completed
    }

    private fun dropPending(
        nowMs: Long,
        intents: Set<HighlightIntent>,
        reason: TextMateSchedulingReason,
    ): List<TextMateTicketCompletion> {
        val completed = mutableListOf<TextMateTicketCompletion>()
        pending.removeAll { ticket ->
            if (ticket.request.intent in intents) {
                completed += ticket.completion(TextMateParseOutcome.BudgetExceeded, reason, nowMs)
                true
            } else {
                false
            }
        }
        return completed
    }

    private fun overCapacity(): Boolean {
        val interactive = pending.count { it.request.intent == HighlightIntent.InteractiveFinal }
        val history = pending.count { it.request.intent != HighlightIntent.InteractiveFinal }
        val chars = pending.sumOf { it.request.source.length }
        return interactive > limits.maxPendingInteractive ||
            history > limits.maxPendingHistory ||
            chars > limits.maxQueuedSourceChars
    }

    private fun selectPending(nowMs: Long): QueuedTicket? {
        if (pending.isEmpty()) return null
        val interactiveAtRisk =
            pending.any { ticket ->
                ticket.request.intent == HighlightIntent.InteractiveFinal &&
                    nowMs - ticket.enqueuedAtMs >= limits.interactiveFinalAtRiskMs
            }
        if (interactiveAtRisk) {
            return pending
                .filter { it.request.intent == HighlightIntent.InteractiveFinal }
                .minByOrNull { it.enqueuedAtMs }
        }

        refillDeficitIfNeeded()
        val candidates =
            buildList {
                if (interactiveCredit > 0) {
                    pending.firstOrNull { it.request.intent == HighlightIntent.InteractiveFinal }?.let(::add)
                }
                if (visibleCredit > 0) {
                    pending
                        .firstOrNull {
                            it.request.intent == HighlightIntent.VisibleHistory &&
                                nowMs - it.enqueuedAtMs >= limits.visibleHistoryPromoteAfterMs
                        }?.let(::add)
                        ?: pending.firstOrNull { it.request.intent == HighlightIntent.VisibleHistory }?.let(::add)
                }
                if (backgroundCredit > 0 && !backgroundDisabled) {
                    pending.firstOrNull { it.request.intent == HighlightIntent.BackgroundHistory }?.let(::add)
                }
            }
        if (candidates.isNotEmpty()) {
            return candidates.minByOrNull { intentRank(it.request.intent) }
                ?: candidates.first()
        }
        return pending.minWithOrNull(compareBy({ intentRank(it.request.intent) }, { it.enqueuedAtMs }))
    }

    private fun refillDeficitIfNeeded() {
        if (interactiveCredit <= 0 && visibleCredit <= 0 && backgroundCredit <= 0) {
            interactiveCredit = limits.deficitInteractive
            visibleCredit = limits.deficitVisible
            backgroundCredit = limits.deficitBackground
        }
    }

    private fun consumeDeficit(intent: HighlightIntent) {
        when (intent) {
            HighlightIntent.InteractiveFinal -> interactiveCredit = (interactiveCredit - 1).coerceAtLeast(0)
            HighlightIntent.VisibleHistory -> visibleCredit = (visibleCredit - 1).coerceAtLeast(0)
            HighlightIntent.BackgroundHistory -> backgroundCredit = (backgroundCredit - 1).coerceAtLeast(0)
        }
    }

    private fun intentRank(intent: HighlightIntent): Int =
        when (intent) {
            HighlightIntent.InteractiveFinal -> 0
            HighlightIntent.VisibleHistory -> 1
            HighlightIntent.BackgroundHistory -> 2
        }

    private fun recordHighWater() {
        val interactive = pending.count { it.request.intent == HighlightIntent.InteractiveFinal }
        val history = pending.count { it.request.intent != HighlightIntent.InteractiveFinal }
        val chars = pending.sumOf { it.request.source.length }
        pendingInteractiveHwm = maxOf(pendingInteractiveHwm, interactive)
        pendingHistoryHwm = maxOf(pendingHistoryHwm, history)
        pendingCharsHwm = maxOf(pendingCharsHwm, chars)
    }

    private fun QueuedTicket.completion(
        outcome: TextMateParseOutcome,
        reason: TextMateSchedulingReason,
        nowMs: Long,
    ): TextMateTicketCompletion =
        TextMateTicketCompletion(
            ticketId = id,
            request = request,
            result =
                emptyResult(
                    request = request,
                    outcome = outcome,
                    queueWaitNanoseconds = (nowMs - enqueuedAtMs).saturatingMsToNs(),
                    schedulingReason = reason.name,
                ),
            reason = reason,
        )

    private fun immediateCompletion(
        request: HighlightRequest,
        outcome: TextMateParseOutcome,
        reason: TextMateSchedulingReason,
        nowMs: Long,
    ): TextMateTicketCompletion =
        TextMateTicketCompletion(
            ticketId = nextTicketId++,
            request = request,
            result =
                emptyResult(
                    request = request,
                    outcome = outcome,
                    queueWaitNanoseconds = 0,
                    schedulingReason = reason.name,
                ),
            reason = reason,
        )

    private class QueuedTicket(
        val id: Long,
        val request: HighlightRequest,
        val enqueuedAtMs: Long,
        var startedAtMs: Long = enqueuedAtMs,
    ) {
        @Volatile
        var cancelRequested: Boolean = false
        var cancelRequestedAtMs: Long? = null
    }
}

data class TextMateSchedulerLimits(
    val maxActiveJobs: Int = 1,
    val maxPendingInteractive: Int = 8,
    val maxPendingHistory: Int = 24,
    val maxQueuedSourceChars: Int = 256_000,
    val interactiveFinalAtRiskMs: Long = 8,
    val interactiveFinalDeadlineMs: Long = 16,
    val visibleHistoryPromoteAfterMs: Long = 250,
    val deficitInteractive: Int = 8,
    val deficitVisible: Int = 2,
    val deficitBackground: Int = 1,
    val trimRecoveryMs: Long = 30_000,
)

enum class TextMateSchedulingReason {
    Supersession,
    QueuePressure,
    Deadline,
    SearchBudget,
    Trim,
}

data class TextMateAdmitResult(
    val ticketId: Long?,
    val completed: List<TextMateTicketCompletion>,
)

data class TextMateScheduledJob(
    val ticketId: Long,
    val request: HighlightRequest,
    val queueWaitNanoseconds: Long,
    val cancelRequested: () -> Boolean,
)

data class TextMateTicketCompletion(
    val ticketId: Long,
    val request: HighlightRequest,
    val result: HighlightResult,
    val reason: TextMateSchedulingReason?,
)

data class TextMateSchedulerSnapshot(
    val pendingInteractive: Int,
    val pendingHistory: Int,
    val pendingCharacters: Int,
    val active: Boolean,
    val activeCancelRequested: Boolean,
    val backgroundDisabled: Boolean,
    val historyAdmissionDisabled: Boolean,
    val pendingInteractiveHwm: Int,
    val pendingHistoryHwm: Int,
    val pendingCharsHwm: Int,
    val supersessionCount: Int,
    val queuePressureCount: Int,
    val deadlinePriorityCount: Int,
    val trimDropCount: Int,
)

private val MonotonicClockOrigin = TimeSource.Monotonic.markNow()

// Milliseconds since one fixed origin; a fresh mark per call would always read 0.
private val MonotonicMillisClock: () -> Long = {
    MonotonicClockOrigin.elapsedNow().inWholeMilliseconds
}

fun emptyResult(
    request: HighlightRequest,
    outcome: TextMateParseOutcome,
    queueWaitNanoseconds: Long,
    schedulingReason: String?,
    cancelRequestedToStopNanoseconds: Long? = null,
): HighlightResult =
    HighlightResult(
        key = request.key,
        revision = request.revision,
        source = request.source,
        canonicalLanguage = null,
        policyEpoch = request.policyEpoch,
        spans = emptyList(),
        outcome = outcome,
        diagnostics = emptyList(),
        observation =
            TextMateParseObservation(
                requestedLanguage = request.language,
                canonicalLanguage = null,
                outcome = outcome,
                messageCharacters = request.messageCharacters,
                fenceCharacters = request.source.length,
                fenceLines = if (request.source.isEmpty()) 0 else request.source.count { it == '\n' } + 1,
                fenceIndex = request.fenceIndex,
                fenceCount = request.fenceCount,
                spanCount = 0,
                backend = null,
                totalNanoseconds = 0,
                stages = emptyList(),
                diagnosticKinds = emptyList(),
                queueWaitNanoseconds = queueWaitNanoseconds,
                cancelRequestedToStopNanoseconds = cancelRequestedToStopNanoseconds,
                schedulingReason = schedulingReason,
            ),
    )

private fun Long.saturatingMsToNs(): Long =
    if (this <= 0L) {
        0L
    } else if (this > Long.MAX_VALUE / 1_000_000L) {
        Long.MAX_VALUE
    } else {
        this * 1_000_000L
    }
