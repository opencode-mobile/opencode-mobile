package dev.opencode.mobile.lab

import dev.opencode.mobile.highlight.bench.HighlightBenchCall
import dev.opencode.mobile.highlight.bench.HighlightBenchCallGuard
import dev.opencode.mobile.highlight.bench.HighlightBenchCallStatus
import dev.opencode.mobile.highlight.bench.HighlightBenchCancelSignal
import dev.opencode.mobile.highlight.bench.HighlightBenchCanceller
import dev.opencode.mobile.highlight.bench.HighlightBenchEngine
import dev.opencode.mobile.highlight.bench.HighlightBenchHighlighter
import dev.opencode.mobile.highlight.bench.HighlightBenchOutput
import dev.opencode.mobile.highlight.bench.HighlightBenchProbe
import dev.opencode.mobile.highlight.bench.HighlightBenchSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.TimeSource

/** An engine as a picker option. */
data class LabEngineOption(
    val id: String,
    val name: String,
)

/** One viewer call: the probe's result and whether it paid the engine's first-call cost. */
class LabProbe(
    val call: HighlightBenchCall,
    val cold: Boolean,
)

/**
 * The engines the Lab's screens use, and where their work runs. Viewer and compare calls reuse an
 * idle warm highlighter per engine; a benchmark run makes its own.
 *
 * A [probe] never holds up its caller: cancelling the calling coroutine stops the wait at once
 * and fires the call's [HighlightBenchCanceller], so an engine that checks its cancel signal
 * returns within moments. One that ignores it is given up on by the call guard after the cancel
 * grace and runs on until it returns; only then is it listed in [busyInBackground].
 */
class LabEngines(
    val engines: List<HighlightBenchEngine>,
    private val newCallGuard: () -> HighlightBenchCallGuard,
    /** Generating text, highlighting, and benchmark runs; never the main thread. */
    val dispatcher: CoroutineDispatcher,
    val settings: HighlightBenchSettings = HighlightBenchSettings.standard,
    /** Told how long each cancelled engine call took to return after its cancel, by engine name. */
    private val onCancelledCallReturned: (engineName: String, afterCancelMs: Long) -> Unit = { _, _ -> },
) {
    private val idle = mutableMapOf<String, HighlightBenchHighlighter>()
    private val background = MutableStateFlow<List<String>>(emptyList())

    /** Probe waits outlive a cancelled caller, to tell an engine that stops from one that runs on. */
    private val guardScope = CoroutineScope(SupervisorJob() + dispatcher)

    /** Display names of engine calls the call guard gave up on that have not returned yet. */
    val busyInBackground: StateFlow<List<String>> = background.asStateFlow()

    val options: List<LabEngineOption>
        get() = engines.map { LabEngineOption(it.id, it.displayName) }

    fun engine(id: String): HighlightBenchEngine? = engines.firstOrNull { it.id == id }

    fun callGuard(): HighlightBenchCallGuard = newCallGuard()

    /**
     * One timed call with spans through the call guard, off the main thread. Cancelling the caller
     * throws [kotlinx.coroutines.CancellationException] right away and cancels the engine call; a
     * call the guard gives up on returns as [HighlightBenchCallStatus.Abandoned] and is listed in
     * [busyInBackground] until it returns.
     */
    suspend fun probe(
        engineId: String,
        language: String,
        source: String,
    ): LabProbe {
        val engine = requireNotNull(engine(engineId)) { "Unknown engine: $engineId" }
        val reused = synchronized(idle) { idle.remove(engineId) }
        val highlighter = reused ?: engine.newHighlighter()
        val call = TrackedCall(engineId, engine.displayName, highlighter)
        val canceller = HighlightBenchCanceller()
        val guarded =
            guardScope.async {
                HighlightBenchProbe.highlight(call, language, source, settings, newCallGuard(), canceller).also { result ->
                    if (result.status == HighlightBenchCallStatus.Abandoned) call.listUntilReturned()
                }
            }
        try {
            return LabProbe(guarded.await(), cold = reused == null)
        } catch (cancelled: CancellationException) {
            call.cancelledAt = TimeSource.Monotonic.markNow()
            canceller.cancel()
            throw cancelled
        }
    }

    /**
     * Wraps one engine call: once it returns, its highlighter is idle again (and warm), and it
     * leaves [busyInBackground] if the guard had given up on it.
     */
    private inner class TrackedCall(
        private val engineId: String,
        private val engineName: String,
        private val highlighter: HighlightBenchHighlighter,
    ) : HighlightBenchHighlighter {
        private var finished = false
        private var listed = false

        @Volatile
        var cancelledAt: TimeSource.Monotonic.ValueTimeMark? = null

        override fun highlight(
            language: String,
            source: String,
            cancel: HighlightBenchCancelSignal,
            collectSpans: Boolean,
        ): HighlightBenchOutput {
            try {
                return highlighter.highlight(language, source, cancel, collectSpans)
            } finally {
                synchronized(this) {
                    finished = true
                    if (listed) background.update { it - engineName }
                }
                synchronized(idle) { idle.putIfAbsent(engineId, highlighter) }
                cancelledAt?.let { onCancelledCallReturned(engineName, it.elapsedNow().inWholeMilliseconds) }
            }
        }

        /** The guard gave up on the call: list it unless it returned meanwhile. */
        fun listUntilReturned() {
            synchronized(this) {
                if (!finished && !listed) {
                    listed = true
                    background.update { it + engineName }
                }
            }
        }
    }
}
