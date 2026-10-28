package dev.opencode.mobile.domain.textmate

import dev.opencode.mobile.markdown.HighlightRequest
import dev.opencode.mobile.markdown.HighlightResult
import dev.opencode.mobile.markdown.MarkdownDiagnostic
import dev.opencode.mobile.markdown.MarkdownDiagnosticKind
import dev.opencode.mobile.markdown.MemoryPressure
import dev.opencode.mobile.markdown.OpenCodeTextMateHighlightingEngine
import dev.opencode.mobile.markdown.TextMateHighlightingEngine
import dev.opencode.mobile.markdown.TextMateParseOutcome
import dev.opencode.mobile.markdown.TextMateRequestScheduler
import dev.opencode.mobile.markdown.TextMateSchedulerSnapshot
import dev.opencode.mobile.markdown.emptyResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.coroutines.cancellation.CancellationException

/**
 * Android's asynchronous platform-to-KMP adapter. The dispatcher keeps TextMate
 * off the UI executor. The shared [TextMateRequestScheduler] owns queue policy;
 * this adapter drives at most one active highlight job.
 *
 * Only highlight jobs and cache trims run on [ownerDispatcher]. Admits take [owner]
 * on the caller's thread, so a newer revision can cancel the job that is running.
 */
class AndroidTextMateHighlightingEngine(
    private val delegateFactory: () -> TextMateHighlightingEngine,
    private val ownerDispatcher: CoroutineDispatcher,
    private val scheduler: TextMateRequestScheduler = TextMateRequestScheduler(),
) : TextMateHighlightingEngine {
    // Guards scheduler, waiters, and pumping; never held while a job runs.
    private val owner = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + ownerDispatcher)
    private val waiters = mutableMapOf<Long, CompletableDeferred<HighlightResult>>()
    private var delegate: TextMateHighlightingEngine? = null
    private var pumping = false

    constructor(
        delegate: TextMateHighlightingEngine,
        ownerDispatcher: CoroutineDispatcher,
        scheduler: TextMateRequestScheduler = TextMateRequestScheduler(),
    ) : this(
        delegateFactory = { delegate },
        ownerDispatcher = ownerDispatcher,
        scheduler = scheduler,
    )

    override suspend fun highlight(request: HighlightRequest): HighlightResult {
        val deferred =
            owner.withLock {
                val admit = scheduler.admit(request)
                for (completion in admit.completed) {
                    waiters.remove(completion.ticketId)?.complete(completion.result)
                }
                val ticketId = admit.ticketId
                if (ticketId == null) {
                    val rejected =
                        admit.completed
                            .lastOrNull {
                                it.request.key == request.key && it.request.revision == request.revision
                            }?.result
                            ?: admit.completed.last().result
                    return@withLock CompletableDeferred(rejected)
                }
                val waiter = CompletableDeferred<HighlightResult>()
                waiters[ticketId] = waiter
                ensurePumpLocked()
                waiter
            }
        return deferred.await()
    }

    override suspend fun trim(level: MemoryPressure) {
        owner.withLock {
            for (completion in scheduler.trim(level)) {
                waiters.remove(completion.ticketId)?.complete(completion.result)
            }
        }
        // Never build the engine just to trim it; a foreground Normal arrives on every app start.
        withContext(ownerDispatcher) {
            delegate?.trim(level)
        }
    }

    suspend fun setLowPowerOrThermal(disabled: Boolean) {
        owner.withLock {
            scheduler.setLowPowerOrThermal(disabled)
        }
    }

    override fun schedulerSnapshot(): TextMateSchedulerSnapshot = scheduler.snapshot()

    private fun ensurePumpLocked() {
        if (pumping) return
        pumping = true
        scope.launch {
            while (true) {
                val job =
                    owner.withLock {
                        scheduler.pickNext().also { next -> if (next == null) pumping = false }
                    } ?: break
                val raw =
                    try {
                        when (val active = ownedDelegate()) {
                            is OpenCodeTextMateHighlightingEngine ->
                                active.highlightNow(job.request, shouldCancel = job.cancelRequested)
                            else -> active.highlight(job.request)
                        }
                    } catch (cancelled: CancellationException) {
                        // Only the pump's own cancellation ends the loop. One thrown by the
                        // delegate (a timeout inside it) cancels this job; finishing it below
                        // clears the active slot so later requests still run.
                        currentCoroutineContext().ensureActive()
                        emptyResult(
                            request = job.request,
                            outcome = TextMateParseOutcome.Cancelled,
                            queueWaitNanoseconds = job.queueWaitNanoseconds,
                            schedulingReason = null,
                        )
                    } catch (error: Throwable) {
                        emptyResult(
                            request = job.request,
                            outcome = TextMateParseOutcome.Failed,
                            queueWaitNanoseconds = job.queueWaitNanoseconds,
                            schedulingReason = null,
                        ).copy(
                            diagnostics =
                                listOf(
                                    MarkdownDiagnostic(
                                        kind = MarkdownDiagnosticKind.HighlighterFallback,
                                        message = error.message ?: "TextMate highlighting failed.",
                                    ),
                                ),
                            observation =
                                emptyResult(
                                    request = job.request,
                                    outcome = TextMateParseOutcome.Failed,
                                    queueWaitNanoseconds = job.queueWaitNanoseconds,
                                    schedulingReason = null,
                                ).observation.copy(
                                    diagnosticKinds = listOf(MarkdownDiagnosticKind.HighlighterFallback),
                                ),
                        )
                    }
                owner.withLock {
                    for (completion in scheduler.finishActive(job.ticketId, raw)) {
                        waiters.remove(completion.ticketId)?.complete(completion.result)
                    }
                }
                // Let a queued cache trim run on the owner dispatcher between jobs.
                yield()
            }
        }
    }

    private fun ownedDelegate(): TextMateHighlightingEngine = delegate ?: delegateFactory().also { created -> delegate = created }
}
