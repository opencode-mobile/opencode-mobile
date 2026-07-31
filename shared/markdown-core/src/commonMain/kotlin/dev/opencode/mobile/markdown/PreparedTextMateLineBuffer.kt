package dev.opencode.mobile.markdown

/**
 * Job-local prepared physical-line (or boundary-augmented) search buffer.
 * Built once per line tokenization and reused for every candidate regex search.
 */
class PreparedTextMateLineBuffer private constructor(
    val source: String,
    val indexMap: Utf8IndexMap,
) {
    val bytes: ByteArray
        get() = indexMap.bytes

    val length: Int
        get() = source.length

    companion object {
        fun prepare(
            source: String,
            metricsSink: OpenCodeTextMateMetricsSink = NoOpOpenCodeTextMateMetricsSink,
        ): PreparedTextMateLineBuffer {
            if (metricsSink.isEnabled) {
                metricsSink.record(
                    TextMateMetricEvent(
                        stage = TextMateMetricStage.PreparedLineBuffer,
                        outcome = TextMateMetricOutcome.Success,
                    ),
                )
                metricsSink.record(
                    TextMateMetricEvent(
                        stage = TextMateMetricStage.Utf8IndexMap,
                        outcome = TextMateMetricOutcome.Success,
                    ),
                )
            }
            return PreparedTextMateLineBuffer(
                source = source,
                indexMap = Utf8IndexMap(source),
            )
        }
    }
}
