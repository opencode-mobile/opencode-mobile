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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * Android's asynchronous platform-to-KMP adapter. The dispatcher keeps TextMate
 * off the UI executor. The shared [TextMateRequestScheduler] owns queue policy;
 * this adapter drives at most one active highlight job.
 */
class AndroidTextMateHighlightingEngine(
    private val delegateFactory: () -> TextMateHighlightingEngine,
    private val ownerDispatcher: CoroutineDispatcher,
    private val scheduler: TextMateRequestScheduler = TextMateRequestScheduler(),
) : TextMateHighlightingEngine {
    private val owner = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + ownerDispatcher)
    private val waiters = mutableMapOf<Long, CompletableDeferred<HighlightResult>>()
    private var delegate: TextMateHighlightingEngine? = null
    private var pumpJob: Job? = null

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
            withContext(ownerDispatcher) {
                owner.withLock {
                    val admit = scheduler.admit(request)
                    for (completion in admit.completed) {
                        waiters.remove(completion.ticketId)?.complete(completion.result)
                    }
                    val ticketId = admit.ticketId
                    if (ticketId == null) {
                        val rejected =
                            admit.completed.lastOrNull {
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
            }
        return deferred.await()
    }

    override suspend fun trim(level: MemoryPressure) {
        withContext(ownerDispatcher) {
            owner.withLock {
                for (completion in scheduler.trim(level)) {
                    waiters.remove(completion.ticketId)?.complete(completion.result)
                }
                ownedDelegate().trim(level)
            }
        }
    }

    suspend fun setLowPowerOrThermal(disabled: Boolean) {
        withContext(ownerDispatcher) {
            owner.withLock {
                scheduler.setLowPowerOrThermal(disabled)
            }
        }
    }

    override fun schedulerSnapshot(): TextMateSchedulerSnapshot = scheduler.snapshot()

    private fun ensurePumpLocked() {
        if (pumpJob?.isActive == true) return
        pumpJob =
            scope.launch {
                while (true) {
                    val job =
                        owner.withLock {
                            scheduler.pickNext()
                        } ?: break
                    val raw =
                        try {
                            when (val active = ownedDelegate()) {
                                is OpenCodeTextMateHighlightingEngine ->
                                    active.highlightNow(job.request, shouldCancel = job.cancelRequested)
                                else -> active.highlight(job.request)
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
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
                }
            }
    }

    private fun ownedDelegate(): TextMateHighlightingEngine =
        delegate ?: delegateFactory().also { created -> delegate = created }
}
