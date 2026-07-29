package dev.opencode.mobile.markdown

internal class RecordingOpenCodeTextMateMetricsSink : OpenCodeTextMateMetricsSink {
    val events: MutableList<TextMateMetricEvent> = mutableListOf()

    override fun record(event: TextMateMetricEvent) {
        events += event
    }

    fun totalMilliseconds(stage: TextMateMetricStage): Double =
        events
            .asSequence()
            .filter { event -> event.stage == stage }
            .sumOf { event -> event.durationNanoseconds }
            .toDouble() / 1_000_000.0

    fun maximumMilliseconds(stage: TextMateMetricStage): Double =
        events
            .asSequence()
            .filter { event -> event.stage == stage }
            .maxOfOrNull { event -> event.durationNanoseconds }
            ?.toDouble()
            ?.div(1_000_000.0)
            ?: 0.0
}
