package dev.opencode.mobile.lab

import dev.opencode.mobile.highlight.bench.HighlightBenchCall
import dev.opencode.mobile.highlight.bench.HighlightBenchCallGuard
import dev.opencode.mobile.highlight.bench.HighlightBenchCallStatus
import dev.opencode.mobile.highlight.bench.HighlightBenchCancelSignal
import dev.opencode.mobile.highlight.bench.HighlightBenchEngine
import dev.opencode.mobile.highlight.bench.HighlightBenchHighlighter
import dev.opencode.mobile.highlight.bench.HighlightBenchOutput
import dev.opencode.mobile.highlight.bench.HighlightBenchProbe
import dev.opencode.mobile.highlight.bench.HighlightBenchSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runInterruptible

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
 * A [probe] never holds up its caller: cancelling the calling coroutine stops the wait at once.
 * The engine call itself cannot be stopped from here, so it runs on until it returns (its cancel
 * signal turns true at the call limit); meanwhile [busyInBackground] lists it.
 */
class LabEngines(
    val engines: List<HighlightBenchEngine>,
    private val newCallGuard: () -> HighlightBenchCallGuard,
    /** Generating text, highlighting, and benchmark runs; never the main thread. */
    val dispatcher: CoroutineDispatcher,
    val settings: HighlightBenchSettings = HighlightBenchSettings.standard,
) {
    private val idle = mutableMapOf<String, HighlightBenchHighlighter>()
    private val background = MutableStateFlow<List<String>>(emptyList())

    /** Display names of engine calls the Lab stopped waiting for that have not returned yet. */
    val busyInBackground: StateFlow<List<String>> = background.asStateFlow()

    val options: List<LabEngineOption>
        get() = engines.map { LabEngineOption(it.id, it.displayName) }

    fun engine(id: String): HighlightBenchEngine? = engines.firstOrNull { it.id == id }

    fun callGuard(): HighlightBenchCallGuard = newCallGuard()

    /**
     * One timed call with spans through the call guard, off the main thread. Cancelling the caller
     * interrupts the wait and throws [kotlinx.coroutines.CancellationException] right away; a call
     * the guard gives up on returns as [HighlightBenchCallStatus.Abandoned]. Either way the engine
     * call goes on in the background until it returns.
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
        try {
            val result = runInterruptible(dispatcher) { HighlightBenchProbe.highlight(call, language, source, settings, newCallGuard()) }
            call.leaveIfRunning()
            return LabProbe(result, cold = reused == null)
        } catch (cancelled: CancellationException) {
            call.leaveIfRunning()
            throw cancelled
        }
    }

    /**
     * Wraps one engine call: once it returns, its highlighter is idle again (and warm). If the
     * caller left while it ran, or before the guard's thread got to it, it is listed in
     * [busyInBackground] from its start until it returns. A call that never starts is not listed.
     */
    private inner class TrackedCall(
        private val engineId: String,
        private val engineName: String,
        private val highlighter: HighlightBenchHighlighter,
    ) : HighlightBenchHighlighter {
        private var started = false
        private var finished = false
        private var callerLeft = false
        private var listed = false

        override fun highlight(
            language: String,
            source: String,
            cancel: HighlightBenchCancelSignal,
            collectSpans: Boolean,
        ): HighlightBenchOutput {
            synchronized(this) {
                started = true
                if (callerLeft) list()
            }
            try {
                return highlighter.highlight(language, source, cancel, collectSpans)
            } finally {
                synchronized(this) {
                    finished = true
                    if (listed) background.update { it - engineName }
                }
                synchronized(idle) { idle.putIfAbsent(engineId, highlighter) }
            }
        }

        /** The caller stopped waiting, with a result or cancelled. */
        fun leaveIfRunning() {
            synchronized(this) {
                callerLeft = true
                if (started && !finished) list()
            }
        }

        private fun list() {
            if (!listed) {
                listed = true
                background.update { it + engineName }
            }
        }
    }
}
