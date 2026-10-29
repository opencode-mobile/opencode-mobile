package dev.opencode.mobile.highlight.bench

/** How one engine call ended, judged against its limit and grace. */
enum class HighlightBenchCallStatus {
    /** Returned before the limit. */
    Completed,

    /** Passed the limit and returned within the grace: the engine honored its cancel signal. */
    CancelHonored,

    /** Returned, but later than the grace: the engine ignored its cancel signal and ran on. */
    CancelIgnored,

    /** Never returned within the grace; the guard left it running in the background. */
    Abandoned,

    /** Never returned within the grace; the guard killed its thread. */
    ForceStopped,

    /** The engine threw. */
    Threw,
    ;

    val ignoredCancel: Boolean
        get() = this == CancelIgnored || this == Abandoned || this == ForceStopped
}

/** One measured engine call. Times are wall-clock milliseconds. */
class HighlightBenchCall(
    val status: HighlightBenchCallStatus,
    /** Until the call returned, or until the guard gave up on it. */
    val elapsedMs: Double,
    /** The engine's outcome name; null when it threw or never returned. */
    val outcome: String?,
    val spanCount: Int,
    val highlighted: Boolean,
    /** How long past the limit the call returned or was given up on; null within the limit. */
    val pastLimitMs: Double?,
    val error: String?,
    /** Present only for calls that collected spans (see [HighlightBenchProbe]). */
    val spans: List<HighlightBenchSpan>?,
)

/** How a case ended, for a Lab summary. The worst call decides it. */
enum class HighlightBenchCaseStatus {
    Completed,

    /** A call ran past its limit but stopped when cancelled. */
    OverLimit,

    /** A call ignored its cancel signal: returned late, was left running, or was force-stopped. */
    IgnoredCancel,

    /** The engine threw, a cold start did not highlight, or setting up the case failed. */
    Failed,

    /** Not run: the run was stopped, or an earlier abandoned call was still running. */
    Skipped,
}

/** Typed numbers behind a row; the matching table cells are [HighlightBenchCaseResult.cells]. */
sealed class HighlightBenchMeasurement {
    /** The time two engines are compared on; null when the call did not complete. */
    abstract val headlineMs: Double?

    /** What [headlineMs] is, for example "p50 ms". */
    abstract val headlineLabel: String

    class ColdStart(
        val characters: Int,
        val firstCall: HighlightBenchCall,
        /** Null when the first call did not complete. */
        val secondCall: HighlightBenchCall?,
    ) : HighlightBenchMeasurement() {
        override val headlineMs: Double?
            get() = firstCall.elapsedMs.takeIf { firstCall.status == HighlightBenchCallStatus.Completed }
        override val headlineLabel: String
            get() = "first call ms"
    }

    class Throughput(
        val characters: Int,
        val runs: Int,
        val medianMs: Double,
        val charsPerMs: Double,
        val spansPerThousandChars: Double,
    ) : HighlightBenchMeasurement() {
        override val headlineMs: Double
            get() = medianMs
        override val headlineLabel: String
            get() = "median ms"
    }

    class ChatFences(
        val fenceCount: Int,
        val p50Ms: Double,
        val p95Ms: Double,
        val maxMs: Double,
        /** Fences that took longer than one 60 Hz frame (16 ms). */
        val overFrameBudget: Int,
        val fixedSize: Int,
        val fixedP50Ms: Double,
        val fixedP95Ms: Double,
    ) : HighlightBenchMeasurement() {
        override val headlineMs: Double
            get() = p50Ms
        override val headlineLabel: String
            get() = "p50 ms"
    }

    /** One call on a fresh instance: 1 MB files, worst cases, custom inputs. */
    class Timed(
        val characters: Int,
        val lines: Int,
        val longestLine: Int,
        val call: HighlightBenchCall,
    ) : HighlightBenchMeasurement() {
        override val headlineMs: Double?
            get() = call.elapsedMs.takeIf { call.status == HighlightBenchCallStatus.Completed }
        override val headlineLabel: String
            get() = "ms"

        /** Null unless the call completed. */
        val charsPerMs: Double?
            get() = headlineMs?.let { characters / it }
    }
}

class HighlightBenchCaseResult(
    val case: HighlightBenchCase,
    val engineId: String,
    val engineName: String,
    val status: HighlightBenchCaseStatus,
    /** Null when the case was skipped or failed before measuring anything. */
    val measurement: HighlightBenchMeasurement?,
    /** The call that ended the case early (past its limit, threw, or ignored cancel), if any. */
    val failedCall: HighlightBenchCall?,
    /** The row for this case's section table, matching [HighlightBenchReport.headers] (with an Engine column when a run has several). */
    val cells: List<String>,
    /** An abandoned call was still running in the background, so times may be slowed. */
    val slowedByStuckCall: Boolean,
    val note: String?,
)

/**
 * Sent before each case and engine ([result] is null) and after it. Arrives on the thread that
 * runs the benchmark; [report] is a snapshot that stays valid after the run moves on.
 */
class HighlightBenchProgress(
    val case: HighlightBenchCase,
    val engineId: String,
    val engineName: String,
    /** Zero-based step: cases times engines, engines inner. */
    val index: Int,
    val total: Int,
    val result: HighlightBenchCaseResult?,
    val report: HighlightBenchReport,
)

data class HighlightBenchEngineLabel(
    val id: String,
    val displayName: String,
)

/**
 * The same case on a baseline engine (a run's first) and a candidate. [timeRatio] is candidate
 * time over baseline time on the measurement's headline: 0.1 means the candidate took a tenth.
 */
class HighlightBenchComparison(
    val case: HighlightBenchCase,
    val baseline: HighlightBenchCaseResult,
    val candidate: HighlightBenchCaseResult,
) {
    val baselineMs: Double?
        get() = baseline.measurement?.headlineMs
    val candidateMs: Double?
        get() = candidate.measurement?.headlineMs

    /** Null when either side has no completed headline time. */
    val timeRatio: Double?
        get() {
            val base = baselineMs ?: return null
            val other = candidateMs ?: return null
            return if (base > 0) other / base else null
        }
}

/** Results so far, in case order then engine order, plus how to show them as tables or Markdown. */
class HighlightBenchReport internal constructor(
    val engines: List<HighlightBenchEngineLabel>,
    val settings: HighlightBenchSettings,
    val cases: List<HighlightBenchCase>,
    val results: List<HighlightBenchCaseResult>,
) {
    val isComplete: Boolean
        get() = results.size == cases.size * engines.size

    /** Sections present in this run, in report order. */
    val sections: List<HighlightBenchSection>
        get() = HighlightBenchSection.entries.filter { section -> cases.any { it.section == section } }

    fun sectionTitle(section: HighlightBenchSection): String = HighlightBenchMarkdown.sectionTitle(this, section)

    fun headers(section: HighlightBenchSection): List<String> = HighlightBenchMarkdown.headers(this, section)

    fun results(section: HighlightBenchSection): List<HighlightBenchCaseResult> = results.filter { it.case.section == section }

    fun result(
        caseId: String,
        engineId: String,
    ): HighlightBenchCaseResult? = results.firstOrNull { it.case.id == caseId && it.engineId == engineId }

    /** Each finished case on the first engine against each other engine, in case order. */
    fun comparisons(): List<HighlightBenchComparison> {
        val baseline = engines.firstOrNull() ?: return emptyList()
        return cases.flatMap { case ->
            val base = result(case.id, baseline.id) ?: return@flatMap emptyList()
            engines.drop(1).mapNotNull { engine -> result(case.id, engine.id)?.let { HighlightBenchComparison(case, base, it) } }
        }
    }

    /**
     * The report as Markdown: [title], one bullet per [environment] line (device, OS, build),
     * a table per section, and a comparison table when the run had several engines. Valid for a
     * partial report too.
     */
    fun markdown(
        title: String,
        environment: List<String>,
    ): String = HighlightBenchMarkdown.render(this, title, environment)

    /**
     * A machine-readable summary: `engines`, `settings`, `complete`, and `rows`, one per case and
     * engine, with `case`, `section`, `dataSet`, `language`, `engine`, `status`, `headlineMs`,
     * `headlineLabel`, `characters`, `charsPerMs`, `callStatus`, `ignoredCancel`, `slowed`, and `note`
     * (missing values are null).
     */
    fun json(): String = HighlightBenchJson.render(this)
}
