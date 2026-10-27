package dev.opencode.mobile.lab

import dev.opencode.mobile.highlight.bench.HighlightBenchCallStatus
import dev.opencode.mobile.highlight.bench.HighlightBenchRange
import dev.opencode.mobile.highlight.bench.HighlightBenchSpan
import dev.opencode.mobile.markdown.CodeSpan
import dev.opencode.mobile.markdown.CodeTokenRole
import dev.opencode.mobile.ui.components.OpenCodeCodeLine
import dev.opencode.mobile.ui.components.OpenCodeCodeLines
import dev.opencode.mobile.ui.components.OpenCodeCodeMark

/** The timing strip under a highlighted data set. */
data class LabTiming(
    val timeToSpansMs: Double,
    /** Null when the call did not complete. */
    val charsPerMs: Double?,
    val outcome: String,
    val spanCount: Int,
    val status: HighlightBenchCallStatus,
    /** The engine's first call in this process: grammar loading and regex compilation included. */
    val cold: Boolean,
) {
    val timeLabel: String
        get() = LabFormat.ms(timeToSpansMs) + if (cold) " (cold)" else ""

    val rateLabel: String
        get() = charsPerMs?.let(LabFormat::rate) ?: "—"

    val spansLabel: String
        get() = "${LabFormat.count(spanCount)} spans"

    /** The outcome, plus how the call ended when it did not simply complete. */
    val outcomeLabel: String
        get() = if (status == HighlightBenchCallStatus.Completed) outcome else "$outcome · $status"

    companion object {
        fun of(
            probe: LabProbe,
            characters: Int,
        ): LabTiming {
            val call = probe.call
            val completed = call.status == HighlightBenchCallStatus.Completed
            return LabTiming(
                timeToSpansMs = call.elapsedMs,
                charsPerMs = if (completed && call.elapsedMs > 0) characters / call.elapsedMs else null,
                outcome = call.outcome ?: call.error ?: "No result",
                spanCount = call.spanCount,
                status = call.status,
                cold = probe.cold,
            )
        }
    }
}

private val rolesByName = CodeTokenRole.entries.associateBy { it.name }

/** Engine role names back to design-system roles; unknown roles stay plain. */
internal fun List<HighlightBenchSpan>.toCodeSpans(): List<CodeSpan> =
    mapNotNull { span -> rolesByName[span.role]?.let { CodeSpan(span.start, span.end, it) } }

/**
 * [source] split into code file lines with [spans], and [marks] (whole-source ranges) on the
 * lines they cross. Marks go through the same split as spans, so tabs and cut lines move them too.
 */
internal fun labCodeLines(
    source: String,
    spans: List<CodeSpan>,
    marks: List<HighlightBenchRange> = emptyList(),
): List<OpenCodeCodeLine> {
    val lines = OpenCodeCodeLines.split(source, spans)
    if (marks.isEmpty()) return lines
    val markedLines = OpenCodeCodeLines.split(source, marks.map { CodeSpan(it.start, it.end, CodeTokenRole.Plain) })
    return lines.zip(markedLines) { line, marked ->
        if (marked.spans.isEmpty()) line else line.copy(marks = marked.spans.map { OpenCodeCodeMark(it.start, it.end) })
    }
}
