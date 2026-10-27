package dev.opencode.mobile.lab

import dev.opencode.mobile.highlight.bench.HighlightBenchCall
import dev.opencode.mobile.highlight.bench.HighlightBenchCallGuard
import dev.opencode.mobile.highlight.bench.HighlightBenchCallStatus
import dev.opencode.mobile.highlight.bench.HighlightBenchEngine
import dev.opencode.mobile.highlight.bench.HighlightBenchHighlighter
import dev.opencode.mobile.highlight.bench.HighlightBenchProbe
import dev.opencode.mobile.highlight.bench.HighlightBenchSettings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
 * The engines the Lab's screens use, and where their work runs. Viewer and compare calls share
 * one warm highlighter per engine and run one at a time; a benchmark run makes its own.
 */
class LabEngines(
    val engines: List<HighlightBenchEngine>,
    private val newCallGuard: () -> HighlightBenchCallGuard,
    /** Generating text, highlighting, and benchmark runs; never the main thread. */
    val dispatcher: CoroutineDispatcher,
    val settings: HighlightBenchSettings = HighlightBenchSettings.standard,
) {
    private val mutex = Mutex()
    private val warm = mutableMapOf<String, HighlightBenchHighlighter>()

    val options: List<LabEngineOption>
        get() = engines.map { LabEngineOption(it.id, it.displayName) }

    fun engine(id: String): HighlightBenchEngine? = engines.firstOrNull { it.id == id }

    fun callGuard(): HighlightBenchCallGuard = newCallGuard()

    /** One timed call with spans through the call guard, after any other Lab call finishes. */
    suspend fun probe(
        engineId: String,
        language: String,
        source: String,
    ): LabProbe =
        mutex.withLock {
            withContext(dispatcher) {
                val engine = requireNotNull(engine(engineId)) { "Unknown engine: $engineId" }
                val cold = engineId !in warm
                val highlighter = warm.getOrPut(engineId) { engine.newHighlighter() }
                val call = HighlightBenchProbe.highlight(highlighter, language, source, settings, newCallGuard())
                // A call the guard gave up on may still be running on that instance.
                if (call.status == HighlightBenchCallStatus.Abandoned || call.status == HighlightBenchCallStatus.ForceStopped) {
                    warm.remove(engineId)
                }
                LabProbe(call, cold)
            }
        }
}
