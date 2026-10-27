package dev.opencode.mobile.highlight.bench

import kotlin.math.abs
import kotlin.math.roundToLong

/** The machine-readable summary behind [HighlightBenchReport.json]. */
internal object HighlightBenchJson {
    fun render(report: HighlightBenchReport): String =
        buildString {
            append("{\"engines\":[")
            report.engines.forEachIndexed { index, engine ->
                if (index > 0) append(',')
                append("{\"id\":").append(string(engine.id)).append(",\"displayName\":").append(string(engine.displayName)).append('}')
            }
            append("],\"settings\":{\"callLimitMs\":").append(report.settings.callLimitMs)
            append(",\"cancelGraceMs\":").append(report.settings.cancelGraceMs)
            append(",\"whileCallStuck\":").append(string(report.settings.whileCallStuck.name))
            append("},\"complete\":").append(report.isComplete)
            append(",\"rows\":[")
            report.results.forEachIndexed { index, result ->
                if (index > 0) append(',')
                row(result)
            }
            append("]}")
        }

    private fun StringBuilder.row(result: HighlightBenchCaseResult) {
        val measurement = result.measurement
        val characters =
            when (measurement) {
                is HighlightBenchMeasurement.ColdStart -> measurement.characters
                is HighlightBenchMeasurement.Throughput -> measurement.characters
                is HighlightBenchMeasurement.Timed -> measurement.characters
                else -> null
            }
        val charsPerMs =
            when (measurement) {
                is HighlightBenchMeasurement.Throughput -> measurement.charsPerMs
                is HighlightBenchMeasurement.Timed -> measurement.charsPerMs
                else -> null
            }
        val callStatus = (measurement as? HighlightBenchMeasurement.Timed)?.call?.status ?: result.failedCall?.status
        append("{\"case\":").append(string(result.case.id))
        append(",\"section\":").append(string(result.case.section.name))
        append(",\"dataSet\":").append(string(result.case.dataSet.id))
        append(",\"language\":").append(string(result.case.language))
        append(",\"engine\":").append(string(result.engineId))
        append(",\"status\":").append(string(result.status.name))
        append(",\"headlineMs\":").append(number(measurement?.headlineMs))
        append(",\"headlineLabel\":").append(measurement?.headlineLabel?.let(::string) ?: "null")
        append(",\"characters\":").append(characters?.toString() ?: "null")
        append(",\"charsPerMs\":").append(number(charsPerMs))
        append(",\"callStatus\":").append(callStatus?.name?.let(::string) ?: "null")
        append(",\"ignoredCancel\":").append(result.status == HighlightBenchCaseStatus.IgnoredCancel)
        append(",\"slowed\":").append(result.slowedByStuckCall)
        append(",\"note\":").append(result.note?.let(::string) ?: "null")
        append('}')
    }

    /** At most three decimals, never exponent notation; null for missing or non-finite values. */
    fun number(value: Double?): String {
        if (value == null || !value.isFinite()) return "null"
        val thousandths = (value * 1_000).roundToLong()
        val sign = if (thousandths < 0) "-" else ""
        val whole = abs(thousandths) / 1_000
        val fraction = abs(thousandths) % 1_000
        return if (fraction == 0L) "$sign$whole" else "$sign$whole." + fraction.toString().padStart(3, '0').trimEnd('0')
    }

    fun string(value: String): String =
        buildString {
            append('"')
            value.forEach { char ->
                when {
                    char == '"' -> append("\\\"")
                    char == '\\' -> append("\\\\")
                    char == '\n' -> append("\\n")
                    char == '\r' -> append("\\r")
                    char == '\t' -> append("\\t")
                    char < ' ' -> append("\\u").append(char.code.toString(16).padStart(4, '0'))
                    else -> append(char)
                }
            }
            append('"')
        }
}
