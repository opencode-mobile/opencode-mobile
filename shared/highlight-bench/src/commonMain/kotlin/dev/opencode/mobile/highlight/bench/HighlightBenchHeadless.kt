package dev.opencode.mobile.highlight.bench

/**
 * A benchmark run started from a launch argument, so both apps run the same plan unattended:
 * `xcrun simctl launch <device> <bundle> -labRun quick` or
 * `adb shell am start -n <package>/<activity> --es labRun quick`. The app reads the `labRun`
 * value ([ARGUMENT_NAME]), calls [run] off the main thread, writes [HighlightBenchHeadlessResult.markdown]
 * and [HighlightBenchHeadlessResult.json] to its storage, and exits.
 *
 * The value is a selection followed by optional `:`-separated options, with no spaces, so it
 * passes through `adb shell` and `simctl` without quoting:
 *
 * - selection: a [HighlightBenchTier] (`quick`, `basic`, `full`), or comma-separated data set ids
 *   from [HighlightBenchCatalog] (each runs its default case, see [HighlightBenchPlan.cases]).
 *   `basic` compares javascript and the 17 Basic languages; for their 128K, 1 MB, or worst cases,
 *   list the ids, for example `typical/rust/128k,typical/rust/1mb,worst/rust-unclosed-raw-string-1mb`.
 * - `engines=<id>,<id>`: engines to run, in that order; the first is the comparison baseline.
 *   Default: every engine the app passes, in its order.
 * - `limitMs=<n>`: per-call limit (default 10000). `graceMs=<n>`: cancel grace (default 2000).
 * - `whileStuck=skip|run`: [HighlightBenchStuckPolicy] (default skip).
 *
 * Examples: `quick`, `basic:engines=textmate,highlight-core`, `full:engines=textmate`, `typical/kotlin/16k,worst/binary-200k:engines=textmate,semantic-fallback:limitMs=5000`.
 */
object HighlightBenchHeadless {
    const val ARGUMENT_NAME: String = "labRun"

    /** Parses a `labRun` value; throws [IllegalArgumentException] naming what is wrong. */
    @Throws(IllegalArgumentException::class)
    fun parse(value: String): HighlightBenchHeadlessRequest {
        val parts = value.trim().split(':')
        val selection = parts.first()
        require(selection.isNotEmpty()) { "labRun needs a selection: quick, basic, full, or data set ids" }
        val tier = HighlightBenchTier.entries.firstOrNull { it.name.lowercase() == selection }
        val dataSetIds = if (tier == null) selection.split(',') else emptyList()
        dataSetIds.forEach { id -> require(HighlightBenchCatalog.dataSet(id) != null) { "Unknown data set: $id" } }
        var engineIds = emptyList<String>()
        var settings = HighlightBenchSettings.standard
        parts.drop(1).forEach { option ->
            val key = option.substringBefore('=')
            val optionValue = option.substringAfter('=', missingDelimiterValue = "")
            require(optionValue.isNotEmpty()) { "Option needs a value: $option" }
            when (key) {
                "engines" -> engineIds = optionValue.split(',')
                "limitMs" -> settings = settings.copy(callLimitMs = positive(key, optionValue))
                "graceMs" -> settings = settings.copy(cancelGraceMs = positive(key, optionValue))
                "whileStuck" ->
                    settings =
                        settings.copy(
                            whileCallStuck =
                                when (optionValue) {
                                    "skip" -> HighlightBenchStuckPolicy.SkipIsolatedCases
                                    "run" -> HighlightBenchStuckPolicy.RunAnyway
                                    else -> throw IllegalArgumentException("whileStuck must be skip or run: $optionValue")
                                },
                        )
                else -> throw IllegalArgumentException("Unknown option: $key")
            }
        }
        return HighlightBenchHeadlessRequest(selection, tier, dataSetIds, engineIds, settings)
    }

    /**
     * Parses [value], runs it on the matching [available] engines through [callGuard], and
     * returns the report. Blocks for the whole run; call it off the main thread. Throws
     * [IllegalArgumentException] before running anything if [value] is invalid or names an
     * engine that is not available.
     */
    @Throws(IllegalArgumentException::class)
    fun run(
        value: String,
        available: List<HighlightBenchEngine>,
        callGuard: HighlightBenchCallGuard,
        environment: List<String>,
        onProgress: (HighlightBenchProgress) -> Unit,
    ): HighlightBenchHeadlessResult {
        val request = parse(value)
        val engines = request.engines(available)
        val report = HighlightBenchRun(engines, request.cases(), request.settings, callGuard).run(onProgress)
        val title = "Highlighting Lab run: ${request.selection} (${engines.joinToString { it.displayName }})"
        return HighlightBenchHeadlessResult(request, report, report.markdown(title, environment), report.json())
    }

    private fun positive(
        key: String,
        value: String,
    ): Long = value.toLongOrNull()?.takeIf { it > 0 } ?: throw IllegalArgumentException("$key must be a positive number: $value")
}

class HighlightBenchHeadlessRequest internal constructor(
    /** The selection as given: "quick", "basic", "full", or the data set ids. */
    val selection: String,
    /** Null when the selection lists data sets. */
    val tier: HighlightBenchTier?,
    val dataSetIds: List<String>,
    /** Empty means every available engine. */
    val engineIds: List<String>,
    val settings: HighlightBenchSettings,
) {
    fun cases(): List<HighlightBenchCase> =
        tier?.let(HighlightBenchPlan::cases)
            ?: HighlightBenchPlan.cases(dataSetIds.map { HighlightBenchCatalog.dataSet(it) ?: throw IllegalArgumentException("Unknown data set: $it") })

    /** The requested engines from [available], in request order. */
    @Throws(IllegalArgumentException::class)
    fun engines(available: List<HighlightBenchEngine>): List<HighlightBenchEngine> {
        require(available.isNotEmpty()) { "No engines available" }
        if (engineIds.isEmpty()) return available
        return engineIds.map { id ->
            available.firstOrNull { it.id == id }
                ?: throw IllegalArgumentException("Unknown engine: $id (available: ${available.joinToString { it.id }})")
        }
    }
}

class HighlightBenchHeadlessResult(
    val request: HighlightBenchHeadlessRequest,
    val report: HighlightBenchReport,
    val markdown: String,
    /** [HighlightBenchReport.json]: one row per case and engine. */
    val json: String,
)
