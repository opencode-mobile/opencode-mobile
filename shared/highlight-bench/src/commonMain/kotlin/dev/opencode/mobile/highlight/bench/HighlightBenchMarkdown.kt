package dev.opencode.mobile.highlight.bench

import kotlin.math.abs
import kotlin.math.roundToLong

/** Table cells and the Markdown report, in the format of the #872 host baseline. */
internal object HighlightBenchMarkdown {
    private const val SLOWED_MARK = " †"
    private const val SLOWED_NOTE =
        "† An abandoned call was still running in the background during this case, so its times may be slowed."

    fun render(
        report: HighlightBenchReport,
        title: String,
        environment: List<String>,
    ): String =
        buildString {
            appendLine("# $title")
            appendLine()
            environment.forEach { appendLine("- $it") }
            appendLine("- Engines: ${report.engines.joinToString { it.displayName }}.")
            appendLine("- Times are wall-clock milliseconds; \"warm\" means after the engine's first calls on an instance.")
            report.sections.forEach { section ->
                val rows = report.results(section)
                if (rows.isEmpty()) return@forEach
                appendLine().appendLine("## ${sectionTitle(report, section)}").appendLine()
                if (section.isTimed()) appendLine(limitNote(report.settings)).appendLine()
                table(headers(report, section), rows.map { it.cells })
                if (rows.any { it.slowedByStuckCall }) appendLine().appendLine(SLOWED_NOTE)
                val notes = rows.filter { it.note != null }
                if (notes.isNotEmpty()) {
                    appendLine()
                    notes.forEach { appendLine("- ${it.case.title}${engineSuffix(report, it)}: ${it.note}") }
                }
            }
            val comparisons = report.comparisons()
            if (comparisons.isNotEmpty()) {
                appendLine().appendLine("## Comparison (candidate time / baseline time; below 1 is faster)").appendLine()
                table(
                    listOf("Case", "Measure", "Baseline", "ms", "Candidate", "ms", "Ratio"),
                    comparisons.map { comparison ->
                        listOf(
                            "${sectionTitleShort(comparison.case.section)}: ${comparison.case.title}",
                            comparison.baseline.measurement?.headlineLabel ?: comparison.candidate.measurement?.headlineLabel ?: "-",
                            comparison.baseline.engineName,
                            comparison.baselineMs?.let(::ms) ?: comparison.baseline.status.name,
                            comparison.candidate.engineName,
                            comparison.candidateMs?.let(::ms) ?: comparison.candidate.status.name,
                            comparison.timeRatio?.let(::ratio) ?: "-",
                        )
                    },
                )
            }
        }

    private fun StringBuilder.table(
        headers: List<String>,
        rows: List<List<String>>,
    ) {
        appendLine(headers.joinToString(" | ", "| ", " |"))
        appendLine(headers.joinToString(" | ", "| ", " |") { "---" })
        rows.forEach { appendLine(it.joinToString(" | ", "| ", " |")) }
    }

    private fun engineSuffix(
        report: HighlightBenchReport,
        result: HighlightBenchCaseResult,
    ) = if (report.engines.size > 1) " (${result.engineName})" else ""

    fun sectionTitle(
        report: HighlightBenchReport,
        section: HighlightBenchSection,
    ): String =
        when (section) {
            HighlightBenchSection.ColdStart -> {
                val size = report.cases.firstOrNull { it.section == section }?.dataSet?.sizeClass?.label ?: ""
                "Cold start (fresh highlighter, first $size fence per language)"
            }
            HighlightBenchSection.Throughput -> "Warm throughput (median of runs, characters per ms)"
            HighlightBenchSection.ChatFences ->
                "Chat fences (${HighlightBenchCatalog.CHAT_FENCE_COUNT} fences per language, sizes 80..8,000, median ~900 characters)"
            HighlightBenchSection.LargeFiles -> "1 MB files, engine called directly (what a whole-file pass costs)"
            HighlightBenchSection.WorstCases -> "Worst-case inputs (fresh warmed highlighter each)"
            HighlightBenchSection.Custom -> "Custom inputs (fresh warmed highlighter each)"
        }

    private fun sectionTitleShort(section: HighlightBenchSection): String =
        when (section) {
            HighlightBenchSection.ColdStart -> "Cold start"
            HighlightBenchSection.Throughput -> "Throughput"
            HighlightBenchSection.ChatFences -> "Chat fences"
            HighlightBenchSection.LargeFiles -> "1 MB file"
            HighlightBenchSection.WorstCases -> "Worst case"
            HighlightBenchSection.Custom -> "Custom"
        }

    fun headers(
        report: HighlightBenchReport,
        section: HighlightBenchSection,
    ): List<String> = withEngine(baseHeaders(section), if (report.engines.size > 1) "Engine" else null)

    private fun baseHeaders(section: HighlightBenchSection): List<String> =
        when (section) {
            HighlightBenchSection.ColdStart -> listOf("Language", "First call ms", "Second call ms", "Outcome")
            HighlightBenchSection.Throughput -> listOf("Data set", "Characters", "Runs", "Median ms", "chars/ms", "Spans per 1K chars")
            HighlightBenchSection.ChatFences ->
                listOf("Language", "p50 ms", "p95 ms", "Max ms", "Fences over 16 ms", "2,000-char p50 ms", "2,000-char p95 ms")
            HighlightBenchSection.LargeFiles -> listOf("Language", "Characters", "Lines", "ms", "chars/ms", "Outcome", "Spans", "After cancel")
            HighlightBenchSection.WorstCases, HighlightBenchSection.Custom ->
                listOf("Case", "Language", "Characters", "Longest line", "ms", "Outcome", "Spans", "After cancel")
        }

    /** The engine column goes second, after the row label. */
    private fun withEngine(
        cells: List<String>,
        engine: String?,
    ): List<String> = if (engine == null) cells else listOf(cells.first(), engine) + cells.drop(1)

    fun cells(
        case: HighlightBenchCase,
        engineName: String?,
        measured: Measured,
        settings: HighlightBenchSettings,
        slowed: Boolean,
    ): List<String> = withEngine(baseCells(case, if (slowed) case.title + SLOWED_MARK else case.title, measured, settings), engineName)

    private fun baseCells(
        case: HighlightBenchCase,
        label: String,
        measured: Measured,
        settings: HighlightBenchSettings,
    ): List<String> =
        when (val measurement = measured.measurement) {
            is HighlightBenchMeasurement.ColdStart ->
                listOf(
                    label,
                    msLabel(measurement.firstCall, settings),
                    measurement.secondCall?.let { msLabel(it, settings) } ?: "-",
                    outcomeLabel(measurement.firstCall),
                )
            is HighlightBenchMeasurement.Throughput ->
                listOf(
                    label,
                    grouped(measurement.characters),
                    measurement.runs.toString(),
                    ms(measurement.medianMs),
                    whole(measurement.charsPerMs),
                    whole(measurement.spansPerThousandChars),
                )
            is HighlightBenchMeasurement.ChatFences ->
                listOf(
                    label,
                    ms(measurement.p50Ms),
                    ms(measurement.p95Ms),
                    ms(measurement.maxMs),
                    "${measurement.overFrameBudget} / ${measurement.fenceCount}",
                    ms(measurement.fixedP50Ms),
                    ms(measurement.fixedP95Ms),
                )
            is HighlightBenchMeasurement.Timed -> {
                val call = measurement.call
                val timing = msLabel(call, settings)
                val tail = listOf(outcomeLabel(call), grouped(call.spanCount), afterCancelLabel(call))
                if (case.section == HighlightBenchSection.LargeFiles) {
                    listOf(label, grouped(measurement.characters), grouped(measurement.lines), timing, measurement.charsPerMs?.let(::whole) ?: "-") + tail
                } else {
                    listOf(label, case.language, grouped(measurement.characters), grouped(measurement.longestLine), timing) + tail
                }
            }
            null -> {
                val failed = measured.failedCall
                placeholder(case, label, failed?.let { "${outcomeLabel(it)} (${msLabel(it, settings)} ms)" } ?: measured.status.name)
            }
        }

    /** A row with no measurement: the status in the Outcome column (or the second), the rest "-". */
    private fun placeholder(
        case: HighlightBenchCase,
        label: String,
        text: String,
    ): List<String> {
        val headers = baseHeaders(case.section)
        val cells = MutableList(headers.size) { "-" }
        cells[0] = label
        if (headers[1] == "Language") cells[1] = case.language
        cells[headers.indexOf("Outcome").takeIf { it >= 0 } ?: 1] = text
        return cells
    }

    private fun HighlightBenchSection.isTimed() =
        this == HighlightBenchSection.LargeFiles || this == HighlightBenchSection.WorstCases || this == HighlightBenchSection.Custom

    private fun limitNote(settings: HighlightBenchSettings) =
        "Each run stops at ${duration(settings.callLimitMs)} (the whole-file budget is 500 ms). \"After cancel\" says whether the " +
            "engine honored the cancel flag within ${duration(settings.cancelGraceMs)}; a call that ignored it was left running " +
            "or, on a JVM host, force-stopped."

    /** Time label: past-the-limit calls show the limit, since their real end is unknown or cancelled. */
    private fun msLabel(
        call: HighlightBenchCall,
        settings: HighlightBenchSettings,
    ): String =
        when (call.status) {
            HighlightBenchCallStatus.CancelHonored,
            HighlightBenchCallStatus.Abandoned,
            HighlightBenchCallStatus.ForceStopped,
            -> "> ${ms(settings.callLimitMs.toDouble())}"
            else -> ms(call.elapsedMs)
        }

    private fun outcomeLabel(call: HighlightBenchCall): String =
        when (call.status) {
            HighlightBenchCallStatus.Abandoned, HighlightBenchCallStatus.ForceStopped -> "Ignored cancel"
            HighlightBenchCallStatus.Threw -> "threw"
            else -> call.outcome ?: "-"
        }

    private fun afterCancelLabel(call: HighlightBenchCall): String =
        when (call.status) {
            HighlightBenchCallStatus.Completed, HighlightBenchCallStatus.Threw -> "-"
            HighlightBenchCallStatus.CancelHonored -> "stopped in ${ms(call.pastLimitMs ?: 0.0)} ms"
            HighlightBenchCallStatus.CancelIgnored -> "ignored, returned ${ms(call.pastLimitMs ?: 0.0)} ms later"
            HighlightBenchCallStatus.Abandoned -> "ignored, left running"
            HighlightBenchCallStatus.ForceStopped -> "ignored, force-stopped"
        }

    private fun duration(millis: Long): String = if (millis % 1_000 == 0L) "${millis / 1_000} s" else "${grouped(millis.toInt())} ms"

    /** One decimal, like `"%.1f"`. */
    fun ms(value: Double): String {
        val tenths = (value * 10).roundToLong()
        val sign = if (tenths < 0) "-" else ""
        return "$sign${abs(tenths) / 10}.${abs(tenths) % 10}"
    }

    /** Ratios: three decimals below 1 (a much faster candidate stays readable), two above. */
    private fun ratio(value: Double): String {
        val digits = if (value < 1) 3 else 2
        var scale = 1L
        repeat(digits) { scale *= 10 }
        val scaled = (value * scale).roundToLong()
        return "${scaled / scale}.${(scaled % scale).toString().padStart(digits, '0')}"
    }

    /** No decimals, like `"%.0f"`. */
    fun whole(value: Double): String = if (value.isFinite()) value.roundToLong().toString() else "-"

    /** Thousands separators, like `"%,d"`. */
    fun grouped(value: Int): String {
        val digits = abs(value.toLong()).toString()
        val groups = digits.reversed().chunked(3).joinToString(",").reversed()
        return if (value < 0) "-$groups" else groups
    }
}
