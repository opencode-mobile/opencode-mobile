package dev.opencode.mobile.highlight.bench

import kotlin.time.Duration.Companion.milliseconds

/** What a run does with isolated cases while a call it gave up on is still running. */
enum class HighlightBenchStuckPolicy {
    /** Skip them: a stuck call keeps a core busy and would slow every later heavy case. */
    SkipIsolatedCases,

    /** Run them anyway and mark their rows as possibly slowed. */
    RunAnyway,
}

data class HighlightBenchSettings(
    /** Each call's deadline; its cancel signal turns true when it passes. */
    val callLimitMs: Long = 10_000,
    /** How long a cancelled call gets to return before it counts as ignoring cancellation. */
    val cancelGraceMs: Long = 2_000,
    val whileCallStuck: HighlightBenchStuckPolicy = HighlightBenchStuckPolicy.SkipIsolatedCases,
) {
    companion object {
        val standard: HighlightBenchSettings = HighlightBenchSettings()
    }
}

/**
 * One benchmark run of [cases] on each of [engines], executed once by [run] on the caller's
 * thread. Call it off the main thread: a full tier takes minutes per engine. [stop] may be called
 * from any thread.
 *
 * Each case runs on every engine before the next case starts, so paired results share
 * conditions. Every engine call gets a cancel signal that turns true at its deadline. Light cases
 * call the engine's shared warm instance inline. Isolated cases (1 MB files, worst cases, custom
 * inputs) make one call on a fresh instance through [callGuard], which can give up on a call that
 * ignores its signal; such a call becomes an [HighlightBenchCaseStatus.IgnoredCancel] row, and
 * [HighlightBenchSettings.whileCallStuck] decides what happens to the isolated cases after it.
 */
class HighlightBenchRun(
    val engines: List<HighlightBenchEngine>,
    val cases: List<HighlightBenchCase>,
    val settings: HighlightBenchSettings,
    private val callGuard: HighlightBenchCallGuard,
) {
    constructor(
        engines: List<HighlightBenchEngine>,
        tier: HighlightBenchTier,
        settings: HighlightBenchSettings,
        callGuard: HighlightBenchCallGuard,
    ) : this(engines, HighlightBenchPlan.cases(tier), settings, callGuard)

    init {
        require(engines.isNotEmpty()) { "A run needs at least one engine." }
        require(engines.map { it.id }.toSet().size == engines.size) { "Engine ids must be unique." }
    }

    private val stopFlag = HighlightBenchStopFlag()
    private val caller = HighlightBenchCaller(settings, stopFlag)
    private val labels = engines.map { HighlightBenchEngineLabel(it.id, it.displayName) }
    private val results = mutableListOf<HighlightBenchCaseResult>()
    private val abandoned = mutableListOf<HighlightBenchTask>()
    private val coldHighlighters = mutableMapOf<String, HighlightBenchHighlighter>()
    private val warmHighlighters = mutableMapOf<String, HighlightBenchHighlighter>()
    private var started = false

    val isStopped: Boolean
        get() = stopFlag.isStopped

    /** Cancels the call in progress and skips the remaining cases; [run] then returns. */
    fun stop() {
        stopFlag.isStopped = true
    }

    /** Runs every case on every engine, reporting before and after each, and returns the final report. */
    fun run(onProgress: (HighlightBenchProgress) -> Unit): HighlightBenchReport {
        check(!started) { "A HighlightBenchRun runs once; create a new one." }
        started = true
        val total = cases.size * engines.size
        cases.forEachIndexed { caseIndex, case ->
            engines.forEachIndexed { engineIndex, engine ->
                val index = caseIndex * engines.size + engineIndex
                onProgress(HighlightBenchProgress(case, engine.id, engine.displayName, index, total, null, snapshot()))
                val result = runCase(case, engine)
                results += result
                onProgress(HighlightBenchProgress(case, engine.id, engine.displayName, index, total, result, snapshot()))
            }
        }
        return snapshot()
    }

    /** Calls given up on that have not returned yet; each keeps a core busy. */
    private val stuckCalls: Int
        get() = abandoned.count { !it.isFinished }

    private fun snapshot() = HighlightBenchReport(labels, settings, cases, results.toList())

    private fun runCase(
        case: HighlightBenchCase,
        engine: HighlightBenchEngine,
    ): HighlightBenchCaseResult {
        if (stopFlag.isStopped) return result(case, engine, skipped("Run stopped"), slowed = false)
        val stuck = stuckCalls
        if (case.isolated && stuck > 0 && settings.whileCallStuck == HighlightBenchStuckPolicy.SkipIsolatedCases) {
            return result(case, engine, skipped("Skipped: $stuck abandoned call(s) still running"), slowed = false)
        }
        val measured =
            try {
                measure(case, engine)
            } catch (failure: Throwable) {
                Measured(HighlightBenchCaseStatus.Failed, null, null, "Failed: ${failure.describe()}")
            }
        if (stopFlag.isStopped) return result(case, engine, skipped("Run stopped"), slowed = false)
        return result(case, engine, measured, slowed = stuck > 0)
    }

    private fun result(
        case: HighlightBenchCase,
        engine: HighlightBenchEngine,
        measured: Measured,
        slowed: Boolean,
    ) = HighlightBenchCaseResult(
        case = case,
        engineId = engine.id,
        engineName = engine.displayName,
        status = measured.status,
        measurement = measured.measurement,
        failedCall = measured.failedCall,
        cells = HighlightBenchMarkdown.cells(case, engine.displayName.takeIf { engines.size > 1 }, measured, settings, slowed),
        slowedByStuckCall = slowed,
        note = measured.note,
    )

    private fun skipped(reason: String) = Measured(HighlightBenchCaseStatus.Skipped, null, null, reason)

    private fun measure(
        case: HighlightBenchCase,
        engine: HighlightBenchEngine,
    ): Measured =
        when (val spec = case.spec) {
            CaseSpec.ColdStart -> coldStart(case.dataSet, engine)
            is CaseSpec.Throughput -> throughput(case.dataSet, spec, engine)
            is CaseSpec.ChatFences -> chatFences(case.dataSet, spec, engine)
            CaseSpec.Timed -> timed(case.dataSet, engine)
        }

    /** First call per language on one fresh instance per engine, shared by the cold-start cases. */
    private fun coldStart(
        dataSet: HighlightBenchDataSet,
        engine: HighlightBenchEngine,
    ): Measured {
        val highlighter = coldHighlighters.getOrPut(engine.id) { engine.newHighlighter() }
        val source = dataSet.generate()
        val first = caller.inline(highlighter, dataSet.language, source)
        if (first.status != HighlightBenchCallStatus.Completed) {
            return ended(first, HighlightBenchMeasurement.ColdStart(source.length, first, null))
        }
        val second = caller.inline(highlighter, dataSet.language, source)
        val measurement = HighlightBenchMeasurement.ColdStart(source.length, first, second)
        if (!first.highlighted) {
            return Measured(HighlightBenchCaseStatus.Failed, measurement, first, "Did not highlight: ${first.outcome}")
        }
        if (second.status != HighlightBenchCallStatus.Completed) return ended(second, measurement)
        return Measured(HighlightBenchCaseStatus.Completed, measurement, null, null)
    }

    private fun throughput(
        dataSet: HighlightBenchDataSet,
        spec: CaseSpec.Throughput,
        engine: HighlightBenchEngine,
    ): Measured {
        val highlighter = warm(engine)
        val source = dataSet.generate()
        var spansPerThousand = 0.0
        val samples =
            List(spec.runs) {
                val call = caller.inline(highlighter, dataSet.language, source)
                if (call.status != HighlightBenchCallStatus.Completed) return ended(call, null)
                spansPerThousand = call.spanCount * 1_000.0 / source.length
                call.elapsedMs
            }
        val median = percentile(samples, 50)
        val measurement = HighlightBenchMeasurement.Throughput(source.length, spec.runs, median, source.length / median, spansPerThousand)
        return Measured(HighlightBenchCaseStatus.Completed, measurement, null, null)
    }

    private fun chatFences(
        dataSet: HighlightBenchDataSet,
        spec: CaseSpec.ChatFences,
        engine: HighlightBenchEngine,
    ): Measured {
        val highlighter = warm(engine)
        val samples =
            dataSet.pieces().map { fence ->
                val call = caller.inline(highlighter, dataSet.language, fence)
                if (call.status != HighlightBenchCallStatus.Completed) return ended(call, null)
                call.elapsedMs
            }
        val fixed = HighlightBenchCorpus.code(dataSet.language, spec.fixedSize)
        val fixedSamples =
            List(spec.fixedRuns) {
                val call = caller.inline(highlighter, dataSet.language, fixed)
                if (call.status != HighlightBenchCallStatus.Completed) return ended(call, null)
                call.elapsedMs
            }
        val measurement =
            HighlightBenchMeasurement.ChatFences(
                fenceCount = samples.size,
                p50Ms = percentile(samples, 50),
                p95Ms = percentile(samples, 95),
                maxMs = samples.max(),
                overFrameBudget = samples.count { it > FRAME_BUDGET_MS },
                fixedSize = fixed.length,
                fixedP50Ms = percentile(fixedSamples, 50),
                fixedP95Ms = percentile(fixedSamples, 95),
            )
        return Measured(HighlightBenchCaseStatus.Completed, measurement, null, null)
    }

    /** One call on a fresh, warmed instance, through the platform's call guard. */
    private fun timed(
        dataSet: HighlightBenchDataSet,
        engine: HighlightBenchEngine,
    ): Measured {
        val language = dataSet.language
        val source = dataSet.generate()
        val highlighter = engine.newHighlighter()
        val warmup = if (language in HighlightBenchCorpus.languages) HighlightBenchCorpus.code(language, 8_000) else "const a = 1;\n"
        caller.inline(highlighter, language, warmup)
        val task = caller.task(highlighter, language, source, collectSpans = false)
        val exit = callGuard.execute(task)
        if (exit == HighlightBenchGuardExit.Abandoned) abandoned += task
        val call = caller.call(task, exit)
        var lines = 1
        var longestLine = 0
        var lineStart = 0
        source.forEachIndexed { index, char ->
            if (char == '\n') {
                lines += 1
                longestLine = maxOf(longestLine, index - lineStart)
                lineStart = index + 1
            }
        }
        longestLine = maxOf(longestLine, source.length - lineStart)
        val measurement = HighlightBenchMeasurement.Timed(source.length, lines, longestLine, call)
        return if (call.status == HighlightBenchCallStatus.Completed) {
            Measured(HighlightBenchCaseStatus.Completed, measurement, null, null)
        } else {
            ended(call, measurement)
        }
    }

    private fun warm(engine: HighlightBenchEngine): HighlightBenchHighlighter =
        warmHighlighters[engine.id] ?: engine.newHighlighter().also { highlighter ->
            HighlightBenchCorpus.languages.forEach { language ->
                caller.inline(highlighter, language, HighlightBenchCorpus.code(language, 8_000))
            }
            warmHighlighters[engine.id] = highlighter
        }

    private fun ended(
        call: HighlightBenchCall,
        measurement: HighlightBenchMeasurement?,
    ): Measured {
        val status =
            when {
                call.status == HighlightBenchCallStatus.Threw -> HighlightBenchCaseStatus.Failed
                call.status.ignoredCancel -> HighlightBenchCaseStatus.IgnoredCancel
                call.status == HighlightBenchCallStatus.CancelHonored -> HighlightBenchCaseStatus.OverLimit
                else -> HighlightBenchCaseStatus.Completed
            }
        return Measured(status, measurement, call, call.error?.let { "Threw: $it" })
    }

    private companion object {
        const val FRAME_BUDGET_MS = 16.0

        fun percentile(
            samples: List<Double>,
            percent: Int,
        ): Double {
            val sorted = samples.sorted()
            return sorted[((percent / 100.0) * sorted.size).toInt().coerceAtMost(sorted.size - 1)]
        }
    }
}

/**
 * One engine call outside a run, for viewing a data set highlighted with its timing. The result
 * carries [HighlightBenchCall.spans] when the call completed or was cancelled in time.
 */
object HighlightBenchProbe {
    /**
     * Highlights [source] with [highlighter] (keep one per engine to measure warm calls, or take
     * a fresh one to measure a cold call) through [callGuard], blocking until it returns or the
     * guard gives up. Call it off the main thread.
     */
    fun highlight(
        highlighter: HighlightBenchHighlighter,
        language: String,
        source: String,
        settings: HighlightBenchSettings,
        callGuard: HighlightBenchCallGuard,
    ): HighlightBenchCall {
        val caller = HighlightBenchCaller(settings, HighlightBenchStopFlag())
        val task = caller.task(highlighter, language, source, collectSpans = true)
        return caller.call(task, callGuard.execute(task))
    }
}

/** Makes engine calls and judges them against the settings' limit and grace. */
internal class HighlightBenchCaller(
    private val settings: HighlightBenchSettings,
    private val stop: HighlightBenchStopFlag,
) {
    private val limit = settings.callLimitMs.milliseconds
    private val grace = settings.cancelGraceMs.milliseconds

    fun task(
        highlighter: HighlightBenchHighlighter,
        language: String,
        source: String,
        collectSpans: Boolean,
    ) = HighlightBenchTask(highlighter, language, source, collectSpans, limit, grace, stop)

    fun inline(
        highlighter: HighlightBenchHighlighter,
        language: String,
        source: String,
    ): HighlightBenchCall {
        val task = task(highlighter, language, source, collectSpans = false)
        task.execute()
        return call(task, HighlightBenchGuardExit.Finished)
    }

    fun call(
        task: HighlightBenchTask,
        exit: HighlightBenchGuardExit,
    ): HighlightBenchCall {
        val limitMs = settings.callLimitMs.toDouble()
        if (exit != HighlightBenchGuardExit.Finished) {
            return HighlightBenchCall(
                status = if (exit == HighlightBenchGuardExit.ForceStopped) HighlightBenchCallStatus.ForceStopped else HighlightBenchCallStatus.Abandoned,
                elapsedMs = task.giveUpAfterMs.toDouble(),
                outcome = null,
                spanCount = 0,
                highlighted = false,
                pastLimitMs = settings.cancelGraceMs.toDouble(),
                error = null,
                spans = null,
            )
        }
        val elapsedMs = task.elapsed.inWholeNanoseconds / 1_000_000.0
        val error = task.error
        val output = task.output
        val status =
            when {
                error != null || output == null -> HighlightBenchCallStatus.Threw
                elapsedMs <= limitMs -> HighlightBenchCallStatus.Completed
                elapsedMs <= limitMs + settings.cancelGraceMs -> HighlightBenchCallStatus.CancelHonored
                else -> HighlightBenchCallStatus.CancelIgnored
            }
        return HighlightBenchCall(
            status = status,
            elapsedMs = elapsedMs,
            outcome = output?.outcome,
            spanCount = output?.spanCount ?: 0,
            highlighted = output?.highlighted ?: false,
            pastLimitMs = (elapsedMs - limitMs).takeIf { it > 0 },
            error = error?.describe(),
            spans = output?.spans,
        )
    }
}

internal class Measured(
    val status: HighlightBenchCaseStatus,
    val measurement: HighlightBenchMeasurement?,
    val failedCall: HighlightBenchCall?,
    val note: String?,
)

internal fun Throwable.describe(): String = this::class.simpleName + (message?.let { ": $it" } ?: "")
